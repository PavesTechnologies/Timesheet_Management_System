package com.intranet.service.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.intranet.entity.TimeSheet;
import com.intranet.entity.TimeSheetEntry;
import com.intranet.entity.TimeSheetReview;
import com.intranet.entity.WeeklyTimeSheetReview;
import com.intranet.repository.TimeSheetRepo;
import com.intranet.repository.TimeSheetReviewRepo;
import com.intranet.repository.WeeklyTimeSheetReviewRepo;
import com.intranet.service.HolidayExcludeUsersService;
import com.intranet.service.external.ManagerWeeklySummaryService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ManagerMonthlyReportService {

    @Value("${timesheet.expectedBaseHours}")
     int expectedBaseHours; // 22 working days × 8 hours
    

    private final TimeSheetRepo timeSheetRepo;
    private final TimeSheetReviewRepo timeSheetReviewRepo;
    private final WeeklyTimeSheetReviewRepo weeklyReviewRepo;
    private final ManagerWeeklySummaryService managerWeeklySummaryService;
    private final HolidayExcludeUsersService holidayExcludeUsersService;

    @Value("${pms.api.base-url}")
    private String pmsBaseUrl;

    @Value("${ums.api.base-url}")
    private String umsBaseUrl;


    private final RestTemplate restTemplate = new RestTemplate();

    public Map<String, Object> generateManagerMonthlyReport(
            Long managerId,
            String managerName,
            LocalDate startDate,
            LocalDate endDate,
            Integer month,
            Integer year,
            String authHeader
    ) {

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", authHeader);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        // ------------------------------
        // 1️⃣ Fetch Manager Projects
        // ------------------------------

        String formattedMonth = String.format("%02d", month);
        String url = pmsBaseUrl + "/projects/owner/period?month=" + formattedMonth + "&year=" + year;

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                new ParameterizedTypeReference<List<Map<String, Object>>>() {}
        );

        List<Map<String, Object>> projects = response.getBody();
        if (projects == null || projects.isEmpty()) {
            return emptySummary(startDate, endDate, managerName);
        }
        // Count unique project IDs
        Set<Long> projectIds = projects.stream()
                .map(p -> ((Number) p.get("id")).longValue())
                .collect(Collectors.toSet());

        int uniqueProjectCount = projectIds.size();
        // Collect memberIds
        Set<Long> memberIds = new HashSet<>();
        for (Map<String, Object> p : projects) {
            List<Map<String, Object>> members =
                    (List<Map<String, Object>>) p.get("members");

            if (members != null) {
                for (Map<String, Object> m : members) {
                    Number idNum = (Number) m.get("id");
                    if (idNum != null) {
                        memberIds.add(idNum.longValue());
                    }
                }
            }
        }

        if (memberIds.isEmpty()) {
            return emptySummary(startDate, endDate, managerName);
        }

        // ------------------------------   
        // 2️⃣ Load UMS Users (names + emails)
        // ------------------------------
        Map<Long, String> nameCache = new HashMap<>();
        Map<Long, String> emailCache = new HashMap<>();

        try {
            String umsUrl = umsBaseUrl + "/admin/users?page=1&limit=500";

            ResponseEntity<Map<String, Object>> umsResponse = restTemplate.exchange(
                    umsUrl,
                    HttpMethod.GET,
                    entity,
                    new ParameterizedTypeReference<Map<String, Object>>() {}
            );

            Map<String, Object> body = umsResponse.getBody();
            if (body != null) {
                List<Map<String, Object>> users =
                        (List<Map<String, Object>>) body.get("users");

                if (users != null) {
                    for (Map<String, Object> u : users) {

                        Number idNum = (Number) u.get("user_id");
                        if (idNum == null) continue;

                        Long id = idNum.longValue();
                        String fn = (String) u.getOrDefault("first_name", "");
                        String ln = (String) u.getOrDefault("last_name", "");
                        String email = (String) u.getOrDefault("mail", "unknown@example.com");

                        nameCache.put(id, (fn + " " + ln).trim());
                        emailCache.put(id, email);
                    }
                }
            }

        } catch (Exception ignore) {}

        // Defaults
        for (Long id : memberIds) {
            if (!nameCache.containsKey(id)) nameCache.put(id, "Unknown");
            if (!emailCache.containsKey(id)) emailCache.put(id, "unknown@example.com");
        }

        // ------------------------------
        // 3️⃣ Fetch timesheets
        // ------------------------------
        List<TimeSheet> sheets = timeSheetRepo
                .findByUserIdInAndWorkDateBetween(memberIds, startDate, endDate);

        sheets = sheets.stream()
                .filter(ts -> ts.getStatus() != TimeSheet.Status.DRAFT)
                .collect(Collectors.toList());

        List<TimeSheetEntry> entries =
                sheets.stream().flatMap(s -> s.getEntries().stream()).collect(Collectors.toList());

        
        BigDecimal billableHours = sumBillableHours(entries);
        BigDecimal nonBillableHours = calculateNonBillableHours(entries);
        BigDecimal autoGeneratedHours = calculateAutoGeneratedHours(sheets);
        BigDecimal billable_autogen=nonBillableHours.add(autoGeneratedHours);
        BigDecimal totalHours = billableHours.add(nonBillableHours);

        double billablePercent = BigDecimal.ZERO.equals(totalHours)
                ? 0.0
                : billableHours.divide(totalHours, 2, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)).doubleValue();

        // ✅ Step 1: Get holiday dates for users to exclude
        List<LocalDate> holidayDates = holidayExcludeUsersService.getUserHolidayDates(month);

        // ✅ Step 2: Filter to only include holidays from this year
        holidayDates = holidayDates.stream()
                .filter(date -> date != null && date.getYear() == year)
                .toList();


        int monthlyhrs=calculateExpectedHours(month, year, holidayDates);
        // ------------------------------
        // 4️⃣ Pending Approvals
        // ------------------------------
        Map<String, Object> pending = computePending(sheets, managerId, nameCache);

        // ------------------------------
        // 5️⃣ Missing Timesheets
        // ------------------------------
        List<Map<String, Object>> missing = computeMissing(memberIds, nameCache, emailCache);

        // ------------------------------
        // 6️⃣ Weekly Summary
        // ------------------------------
        Map<String, BigDecimal> weekSummary = weeklySummary(sheets);
        // ------------------------------
        // 8️⃣ Project Breakdown
        List<Map<String, Object>> projectBreakdown =
                generateProjectBreakdown(sheets, projects, nameCache);

        // ------------------------------
        // 9️⃣ Project Hours Breakdown
        // ------------------------------
        Map<String, Object> projectHoursSummary =
        generateProjectHoursBreakdown(projects, entries);

        // Extract project hours list from projectHoursSummary
        List<Map<String, Object>> projectHours =
        (List<Map<String, Object>>) projectHoursSummary.get("projectHours");

        Map<String, Object> contribution =
        calculateProjectContribution(projectHours);

        Map<Long, BigDecimal> memberHours = generateMemberHours(sheets);
        Map<Long, Integer> projectCountMap = generateProjectCountMap(projects);

        Map<String, Object> underutilized =
        generateUnderutilizedInsight(memberHours, nameCache,monthlyhrs);

        Map<String, Object> overworked =
        generateOverworkedInsight(memberHours, nameCache,monthlyhrs);

        Map<String, Object> multiProjectWorkers =
        generateMultiProjectWorkersInsight(projectCountMap, nameCache);
        Map<String, Object> billableContribution =
        generateBillableContribution(entries, nameCache);

        Map<String, Object> nonBillableContribution =
                generateNonBillableContribution(entries, nameCache);

        Map<String, Object> autoContribution =
                generateAutoGeneratedContribution(sheets, nameCache);

        String monthName = java.time.Month.of(month)
        .getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH);

        // ------------------------------
        // 9️ Build Response
        // ------------------------------
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month", startDate.getMonthValue());
        result.put("year", startDate.getYear());
        result.put("monthName", monthName);
        result.put("managerName", managerName);
        result.put("totalHours", totalHours);
        result.put("billableHours", billableHours);
        result.put("nonBillableHours", nonBillableHours);
        result.put("autoGeneratedHours", autoGeneratedHours);
        result.put("totalBillableAndAutoGeneratedHours", billable_autogen);
        result.put("billablePercentage", billablePercent);
        result.put("pending", pending.get("count"));
        result.put("pendingUsers", pending.get("users"));
        result.put("dateRange", buildDateRange(startDate, endDate));
        result.put("expectedHours",monthlyhrs);
        result.put("weeklySummary", weekSummary);
        result.put("missingTimesheets", missing);
        result.put("uniqueProjectCount", uniqueProjectCount);
        result.put("projectBreakdown", projectBreakdown);
        result.put("projectHoursSummary", projectHoursSummary);
        result.put("projectContribution", contribution);
        result.put("underutilizedInsight", underutilized);
        result.put("overworkedInsight", overworked);
        result.put("multiProjectWorkersInsight", multiProjectWorkers);
        result.put("billableContribution", billableContribution);
        result.put("nonBillableContribution", nonBillableContribution);
        result.put("autoGeneratedContribution", autoContribution);
        result.put("userEntriesSummary",managerWeeklySummaryService.getWeeklySubmittedTimesheetsByManagerReport(managerId, authHeader, startDate, endDate,month,year));


        return result;
    }

    private Map<String, Object> buildDateRange(LocalDate s, LocalDate e) {
        Map<String, Object> map = new HashMap<>();
        map.put("startDate", s);
        map.put("endDate", e);
        return map;
    }

    // ------------------------------
    // HOURS CALCULATIONS
    // ------------------------------

    private BigDecimal sumBillableHours(List<TimeSheetEntry> entries) {
        BigDecimal sum = BigDecimal.ZERO;
        for (TimeSheetEntry e : entries) {
            if (e.isBillable()) {
                sum = sum.add(convert(e));
            }
        }
        return sum;
    }

    private BigDecimal convert(TimeSheetEntry e) {
        if (e.getFromTime() == null || e.getToTime() == null) return BigDecimal.ZERO;

        long mins = Duration.between(e.getFromTime(), e.getToTime()).toMinutes();
        return new BigDecimal(String.format("%d.%02d", mins / 60, mins % 60));
    }

    // ------------------------------
    // PENDING APPROVAL LOGIC
    // ------------------------------
    private Map<String, Object> computePending(
            List<TimeSheet> sheets, Long managerId, Map<Long, String> names) {

        List<Map<String, Object>> userList = new ArrayList<>();

        List<TimeSheet> relevant = sheets.stream()
                .filter(t ->
                        "SUBMITTED".equals(t.getStatus().name()) ||
                        "PARTIALLY_APPROVED".equals(t.getStatus().name()))
                .collect(Collectors.toList());

        if (relevant.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("count", 0);
            result.put("users", userList);
            return result;
        }

        Set<Long> weekIds = relevant.stream()
                .map(t -> t.getWeekInfo().getId())
                .collect(Collectors.toSet());

        List<TimeSheetReview> reviews =
                timeSheetReviewRepo.findByManagerIdAndWeekInfo_IdIn(managerId, weekIds);

        Set<String> reviewedKeys = reviews.stream()
                .filter(r ->
                        "APPROVED".equals(r.getStatus().name()) ||
                        "REJECTED".equals(r.getStatus().name()))
                .map(r -> r.getUserId() + "-" + r.getWeekInfo().getId())
                .collect(Collectors.toSet());

        Map<Long, Set<Long>> pending = new HashMap<>();
        for (TimeSheet ts : relevant) {
            String key = ts.getUserId() + "-" + ts.getWeekInfo().getId();
            if (!reviewedKeys.contains(key)) {
                pending.computeIfAbsent(ts.getUserId(), k -> new HashSet<>())
                        .add(ts.getWeekInfo().getId());
            }
        }

        int count = 0;
        for (Map.Entry<Long, Set<Long>> e : pending.entrySet()) {
            Long userId = e.getKey();
            int weeks = e.getValue().size();
            count += weeks;

            Map<String, Object> userMap = new HashMap<>();
            userMap.put("userId", userId);
            userMap.put("userName", names.get(userId));
            userMap.put("pendingWeeks", weeks);
            userList.add(userMap);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("count", count);
        result.put("users", userList);
        return result;
    }

    // ------------------------------
    // MISSING USERS
    // ------------------------------
    private List<Map<String, Object>> computeMissing(
            Set<Long> users,
            Map<Long, String> names,
            Map<Long, String> emails) {

        List<Map<String, Object>> list = new ArrayList<>();

        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate from = today.getDayOfMonth() < 15 ? monthStart : today.minusDays(15);

        Set<Long> active = weeklyReviewRepo
                .findByUserIdInAndWeekInfo_StartDateBetween(users, from, today)
                .stream()
                .map(WeeklyTimeSheetReview::getUserId)
                .collect(Collectors.toSet());

        for (Long id : users) {
            if (!active.contains(id)) {
                Map<String, Object> m = new HashMap<>();
                m.put("userId", id);
                m.put("fullName", names.get(id));
                m.put("email", emails.get(id));
                list.add(m);
            }
        }

        return list;
    }

    // ------------------------------
    // WEEKLY SUMMARY
    // ------------------------------
    private Map<String, BigDecimal> weeklySummary(List<TimeSheet> sheets) {

        Map<String, BigDecimal> result = new LinkedHashMap<>();

        for (DayOfWeek d : DayOfWeek.values()) {
            // if (d == DayOfWeek.SUNDAY) continue;

            long mins = 0;
            for (TimeSheet s : sheets) {
                if (s.getWorkDate().getDayOfWeek() == d) {
                    for (TimeSheetEntry e : s.getEntries()) {
                        if (e.getFromTime() != null && e.getToTime() != null) {
                            mins += Duration.between(e.getFromTime(), e.getToTime()).toMinutes();
                        }
                    }
                }
            }

            result.put(d.name(),
                    new BigDecimal(String.format("%d.%02d", mins / 60, mins % 60)));
        }

        return result;
    }

    // ------------------------------
    // EMPTY SUMMARY
    // ------------------------------
    private Map<String, Object> emptySummary(LocalDate s, LocalDate e, String managerName) {

        Map<String, Object> map = new HashMap<>();
        map.put("month", s.getMonthValue());
        map.put("year", s.getYear());
        map.put("monthName", s.getMonth()
                .getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH));
        map.put("managerName", managerName);
        map.put("totalHours", BigDecimal.ZERO);
        map.put("billableHours", BigDecimal.ZERO);
        map.put("nonBillableHours", BigDecimal.ZERO);
        map.put("autoGeneratedHours", BigDecimal.ZERO);
        map.put("totalBillableAndAutoGeneratedHours", BigDecimal.ZERO);
        map.put("billablePercentage", 0.0);

        map.put("pending", 0);
        map.put("pendingUsers", new ArrayList<>());

        map.put("dateRange", buildDateRange(s, e));
        map.put("expectedHours", 0);
        map.put("weeklySummary", new HashMap<>());
        map.put("missingTimesheets", new ArrayList<>());
        map.put("uniqueProjectCount", 0);
        map.put("projectBreakdown", new ArrayList<>());
        map.put("projectHoursSummary", new HashMap<>());
        map.put("projectContribution", new HashMap<>());
        map.put("underutilizedInsight", new HashMap<>());
        map.put("overworkedInsight", new HashMap<>());
        map.put("multiProjectWorkersInsight", new HashMap<>());
        map.put("billableContribution", new HashMap<>());
        map.put("nonBillableContribution", new HashMap<>());
        map.put("autoGeneratedContribution", new HashMap<>());
        map.put("userEntriesSummary", new ArrayList<>());

        return map;
    }
    private BigDecimal calculateNonBillableHours(List<TimeSheetEntry> entries) {
    BigDecimal total = BigDecimal.ZERO;

    for (TimeSheetEntry entry : entries) {
        if (!entry.isBillable() && entry.getHoursWorked() != null) {
            total = total.add(entry.getHoursWorked());
        }
    }

    return total;
    }

    private BigDecimal calculateAutoGeneratedHours(List<TimeSheet> sheets) {
        BigDecimal total = BigDecimal.ZERO;

        for (TimeSheet sheet : sheets) {
            if (sheet.getAutoGenerated()) {  // or getAutoGenerated() if Boolean
                total = total.add(
                        sheet.getHoursWorked() != null ? sheet.getHoursWorked() : BigDecimal.ZERO
                );
            }
        }

        return total;
    }
        private List<Map<String, Object>> generateProjectBreakdown(
            List<TimeSheet> sheets,
            List<Map<String, Object>> projects,
            Map<Long, String> nameCache
        ) {

            List<Map<String, Object>> breakdownList = new ArrayList<>();

            // Flatten all entries once
            List<TimeSheetEntry> allEntries = sheets.stream()
                    .flatMap(s -> s.getEntries().stream())
                    .collect(Collectors.toList());

            for (Map<String, Object> project : projects) {

                Long projectId = ((Number) project.get("id")).longValue();
                String projectName = (String) project.get("name");

                List<Map<String, Object>> members =
                        (List<Map<String, Object>>) project.getOrDefault("members", new ArrayList<>());

                // ==== PROJECT-LEVEL CALCULATIONS ====

                BigDecimal billable = BigDecimal.ZERO;
                BigDecimal nonBillable = BigDecimal.ZERO;

                for (TimeSheetEntry e : allEntries) {
                    if (e.getProjectId() != null && e.getProjectId().equals(projectId)) {
                        if (e.isBillable()) billable = billable.add(e.getHoursWorked() != null ? e.getHoursWorked() : BigDecimal.ZERO);
                        else nonBillable = nonBillable.add(e.getHoursWorked() != null ? e.getHoursWorked() : BigDecimal.ZERO);
                    }
                }

                BigDecimal total = billable.add(nonBillable);

                double billablePercentage = total.compareTo(BigDecimal.ZERO) == 0
                        ? 0.0
                        : billable.divide(total, 4, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100))
                                .setScale(2, RoundingMode.HALF_UP)
                                .doubleValue();

                // ==== MEMBER CONTRIBUTIONS ====

                List<Map<String, Object>> memberList = new ArrayList<>();

                for (Map<String, Object> m : members) {

                    Long userId = ((Number) m.get("id")).longValue();
                    String userName = nameCache.getOrDefault(userId, (String) m.get("name"));

                    BigDecimal userBill = BigDecimal.ZERO;
                    BigDecimal userNonBill = BigDecimal.ZERO;

                    // Filter entries per user & project
                    for (TimeSheetEntry e : allEntries) {
                        if (e.getProjectId() != null &&
                            e.getProjectId().equals(projectId) &&
                            e.getTimeSheet().getUserId().equals(userId)) {

                            BigDecimal hrs = e.getHoursWorked() != null ? e.getHoursWorked() : BigDecimal.ZERO;

                            if (e.isBillable()) userBill = userBill.add(hrs);
                            else userNonBill = userNonBill.add(hrs);
                        }
                    }

                    BigDecimal userTotal = userBill.add(userNonBill);

                    double userBillPct = userTotal.compareTo(BigDecimal.ZERO) == 0
                            ? 0.0
                            : userBill.divide(userTotal, 4, RoundingMode.HALF_UP)
                                    .multiply(BigDecimal.valueOf(100))
                                    .setScale(2, RoundingMode.HALF_UP)
                                    .doubleValue();

                    double contribution = total.compareTo(BigDecimal.ZERO) == 0
                            ? 0.0
                            : userTotal.divide(total, 4, RoundingMode.HALF_UP)
                                    .multiply(BigDecimal.valueOf(100))
                                    .setScale(2, RoundingMode.HALF_UP)
                                    .doubleValue();

                    Map<String, Object> userMap = new HashMap<>();
                    userMap.put("userId", userId);
                    userMap.put("userName", userName);
                    userMap.put("billableHours", userBill);
                    userMap.put("nonBillableHours", userNonBill);
                    userMap.put("totalHours", userTotal);
                    userMap.put("billablePercentage", userBillPct);
                    userMap.put("contribution", contribution);

                    memberList.add(userMap);
                }

                // BUILD PROJECT MAP
                Map<String, Object> projectMap = new LinkedHashMap<>();
                projectMap.put("projectId", projectId);
                projectMap.put("projectName", projectName);
                projectMap.put("billableHours", billable);
                projectMap.put("nonBillableHours", nonBillable);
                projectMap.put("totalHours", total);
                projectMap.put("billablePercentage", billablePercentage);
                projectMap.put("membersContribution", memberList);

                breakdownList.add(projectMap);
            }

            return breakdownList;
        }
        private Map<String, Object> generateProjectHoursBreakdown(
            List<Map<String, Object>> projects,
            List<TimeSheetEntry> allEntries
    ) {

        List<Map<String, Object>> projectBreakdownList = new ArrayList<>();

        BigDecimal grandTotalHours = BigDecimal.ZERO;
        BigDecimal grandBillable = BigDecimal.ZERO;
        BigDecimal grandNonBillable = BigDecimal.ZERO;
        BigDecimal sumProjectBillablePercentages = BigDecimal.ZERO;

        for (Map<String, Object> project : projects) {

            Long projectId = ((Number) project.get("id")).longValue();
            String projectName = (String) project.get("name");

            // Filter entries for this project
            List<TimeSheetEntry> projectEntries = allEntries.stream()
                    .filter(e -> e.getProjectId() != null && e.getProjectId().equals(projectId))
                    .collect(Collectors.toList());

            // Calculate billable / non-billable
            BigDecimal billable = projectEntries.stream()
                    .filter(TimeSheetEntry::isBillable)
                    .map(e -> e.getHoursWorked() == null ? BigDecimal.ZERO : e.getHoursWorked())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal nonBillable = projectEntries.stream()
                    .filter(e -> !e.isBillable())
                    .map(e -> e.getHoursWorked() == null ? BigDecimal.ZERO : e.getHoursWorked())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal total = billable.add(nonBillable);

            // billable %
            BigDecimal billablePercentage =
                    total.compareTo(BigDecimal.ZERO) == 0
                            ? BigDecimal.ZERO
                            : billable.multiply(BigDecimal.valueOf(100))
                            .divide(total, 2, RoundingMode.HALF_UP);

            sumProjectBillablePercentages = sumProjectBillablePercentages.add(billablePercentage);
            grandTotalHours = grandTotalHours.add(total);
            grandBillable = grandBillable.add(billable);
            grandNonBillable = grandNonBillable.add(nonBillable);

            

            // Project Summary
            Map<String, Object> projectMap = new LinkedHashMap<>();
            projectMap.put("projectId", projectId);
            projectMap.put("projectName", projectName);
            projectMap.put("billableHours", billable);
            projectMap.put("nonBillableHours", nonBillable);
            projectMap.put("totalHours", total);
            projectMap.put("billablePercentage", billablePercentage);

            projectBreakdownList.add(projectMap);
        }

        // average billable percentage
        BigDecimal avgBillablePercentage =
                projects.isEmpty()
                        ? BigDecimal.ZERO
                        : sumProjectBillablePercentages
                        .divide(BigDecimal.valueOf(projects.size()), 2, RoundingMode.HALF_UP);

        // Final response for "project hours"
        Map<String, Object> finalResult = new LinkedHashMap<>();
        finalResult.put("projectHours", projectBreakdownList);
        finalResult.put("totalHours", grandTotalHours);
        finalResult.put("totalBillable", grandBillable);
        finalResult.put("totalNonBillable", grandNonBillable);
        finalResult.put("averageBillablePercentage", avgBillablePercentage);

        return finalResult;
    }
    private Map<String, Object> calculateProjectContribution(List<Map<String, Object>> projectHours) {

    Map<String, Object> result = new LinkedHashMap<>();

    // Step 1: Calculate global total hours
    BigDecimal globalTotal = BigDecimal.ZERO;
    for (Map<String, Object> p : projectHours) {
        BigDecimal hours = (BigDecimal) p.get("totalHours");
        globalTotal = globalTotal.add(hours);
    }

    List<Map<String, Object>> list = new ArrayList<>();

    // Step 2: Calculate contribution for each project
    for (Map<String, Object> p : projectHours) {

        BigDecimal billable = (BigDecimal) p.get("billableHours");
        BigDecimal nonBillable = (BigDecimal) p.get("nonBillableHours");
        BigDecimal total = (BigDecimal) p.get("totalHours");

        BigDecimal contribution = BigDecimal.ZERO;

        if (globalTotal.compareTo(BigDecimal.ZERO) > 0) {
            contribution = total
                    .divide(globalTotal, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("projectId", p.get("projectId"));
        m.put("projectName", p.get("projectName"));
        m.put("billableHours", billable);
        m.put("nonBillableHours", nonBillable);
        m.put("totalHours", total);
        m.put("contribution", contribution);

        list.add(m);
    }

    result.put("projectContribution", list);
    result.put("globalTotalHours", globalTotal);

    return result;
    }
    private Map<Long, BigDecimal> generateMemberHours(List<TimeSheet> sheets) {

    Map<Long, BigDecimal> memberHours = new HashMap<>();

    for (TimeSheet sheet : sheets) {
        Long userId = sheet.getUserId();

        BigDecimal hrs = sheet.getHoursWorked() == null
                ? BigDecimal.ZERO
                : sheet.getHoursWorked();

        memberHours.put(userId,
                memberHours.getOrDefault(userId, BigDecimal.ZERO).add(hrs));
    }

    return memberHours;
    }
    private Map<Long, Integer> generateProjectCountMap(List<Map<String, Object>> projects) {

    Map<Long, Integer> projectCount = new HashMap<>();

    for (Map<String, Object> project : projects) {
        List<Map<String, Object>> members =
                (List<Map<String, Object>>) project.getOrDefault("members", new ArrayList<>());

        for (Map<String, Object> m : members) {
            Long userId = ((Number) m.get("id")).longValue();
            projectCount.put(userId, projectCount.getOrDefault(userId, 0) + 1);
        }
    }

    return projectCount;
    }
    private Map<String, Object> generateUnderutilizedInsight(
        Map<Long, BigDecimal> memberHours,
        Map<Long, String> memberNames,
        int monthlyhrs
    ) {

    List<Map<String, Object>> list = new ArrayList<>();

    // Filter < 176
    List<Map.Entry<Long, BigDecimal>> filtered = memberHours.entrySet()
            .stream()
            .filter(e -> e.getValue().compareTo(BigDecimal.valueOf(monthlyhrs)) < 0)
            .sorted(Map.Entry.comparingByValue()) // lowest first
            .collect(Collectors.toList());

    BigDecimal prev = null;
    int rank = 0;

    for (Map.Entry<Long, BigDecimal> e : filtered) {

        if (prev == null || e.getValue().compareTo(prev) != 0) {
            rank++;
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", e.getKey());
        m.put("userName", memberNames.get(e.getKey()));
        m.put("totalHours", e.getValue());
        m.put("rank", rank);

        list.add(m);

        prev = e.getValue();
    }

    Map<String, Object> result = new HashMap<>();
    result.put("underutilized", list);

    return result;
    }
    private Map<String, Object> generateOverworkedInsight(
        Map<Long, BigDecimal> memberHours,
        Map<Long, String> memberNames,
        int monthlyhrs) {

    List<Map<String, Object>> list = new ArrayList<>();

    // Filter > 176
    List<Map.Entry<Long, BigDecimal>> filtered = memberHours.entrySet()
            .stream()
            .filter(e -> e.getValue().compareTo(BigDecimal.valueOf(monthlyhrs)) > 0)
            .sorted((a, b) -> b.getValue().compareTo(a.getValue())) // highest first
            .collect(Collectors.toList());

    BigDecimal prev = null;
    int rank = 0;

    for (Map.Entry<Long, BigDecimal> e : filtered) {

        if (prev == null || e.getValue().compareTo(prev) != 0) {
            rank++;
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", e.getKey());
        m.put("userName", memberNames.get(e.getKey()));
        m.put("totalHours", e.getValue());
        m.put("rank", rank);

        list.add(m);

        prev = e.getValue();
    }

    Map<String, Object> result = new HashMap<>();
    result.put("overworked", list);

    return result;
    }
    private Map<String, Object> generateMultiProjectWorkersInsight(
        Map<Long, Integer> projectCount,
        Map<Long, String> memberNames) {

    List<Map.Entry<Long, Integer>> sorted = projectCount.entrySet()
            .stream()
            .sorted((a, b) -> b.getValue() - a.getValue()) // highest projects first
            .collect(Collectors.toList());

    List<Map<String, Object>> list = new ArrayList<>();

    Integer prev = null;
    int rank = 0;

    for (Map.Entry<Long, Integer> e : sorted) {

        if (prev == null || !e.getValue().equals(prev)) {
            rank++;
        }

        if (rank > 2) break; // Only top 2 ranks

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", e.getKey());
        m.put("userName", memberNames.get(e.getKey()));
        m.put("projectCount", e.getValue());
        m.put("rank", rank);

        list.add(m);

        prev = e.getValue();
    }

    Map<String, Object> result = new HashMap<>();
    result.put("multiProjectWorkers", list);

    return result;
    }
    private Map<String, Object> generateBillableContribution(
        List<TimeSheetEntry> entries,
        Map<Long, String> nameCache) {

    Map<String, Object> result = new LinkedHashMap<>();

    // Total billable hours
    BigDecimal totalBillable = entries.stream()
            .filter(TimeSheetEntry::isBillable)
            .map(e -> e.getHoursWorked() == null ? BigDecimal.ZERO : e.getHoursWorked())
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    List<Map<String, Object>> members = new ArrayList<>();

    // Group billable hours by user
    Map<Long, BigDecimal> userBill = new HashMap<>();
    for (TimeSheetEntry e : entries) {
        if (e.isBillable()) {
            Long uid = e.getTimeSheet().getUserId();
            BigDecimal hrs = e.getHoursWorked() == null ? BigDecimal.ZERO : e.getHoursWorked();
            userBill.put(uid, userBill.getOrDefault(uid, BigDecimal.ZERO).add(hrs));
        }
    }

    // Build user list (only users with >0 hours)
    for (Map.Entry<Long, BigDecimal> entry : userBill.entrySet()) {
        if (entry.getValue().compareTo(BigDecimal.ZERO) <= 0) continue; // skip zero

        BigDecimal contribution = totalBillable.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : entry.getValue()
                    .divide(totalBillable, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", entry.getKey());
        m.put("userName", nameCache.get(entry.getKey()));
        m.put("billableHours", entry.getValue());
        m.put("contribution", contribution);

        members.add(m);
    }

    result.put("totalBillableHours", totalBillable);
    result.put("members", members);

    return result;
    }
    private Map<String, Object> generateNonBillableContribution(
        List<TimeSheetEntry> entries,
        Map<Long, String> nameCache) {

    Map<String, Object> result = new LinkedHashMap<>();

    // Total non-billable hours
    BigDecimal totalNonBillable = entries.stream()
            .filter(e -> !e.isBillable())
            .map(e -> e.getHoursWorked() == null ? BigDecimal.ZERO : e.getHoursWorked())
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    List<Map<String, Object>> members = new ArrayList<>();
    Map<Long, BigDecimal> userNonBill = new HashMap<>();

    for (TimeSheetEntry e : entries) {
        if (!e.isBillable()) {
            Long uid = e.getTimeSheet().getUserId();
            BigDecimal hrs = e.getHoursWorked() == null ? BigDecimal.ZERO : e.getHoursWorked();
            userNonBill.put(uid, userNonBill.getOrDefault(uid, BigDecimal.ZERO).add(hrs));
        }
    }

    for (Map.Entry<Long, BigDecimal> entry : userNonBill.entrySet()) {
        if (entry.getValue().compareTo(BigDecimal.ZERO) <= 0) continue;

        BigDecimal contribution = totalNonBillable.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : entry.getValue()
                    .divide(totalNonBillable, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", entry.getKey());
        m.put("userName", nameCache.get(entry.getKey()));
        m.put("nonBillableHours", entry.getValue());
        m.put("contribution", contribution);

        members.add(m);
    }

    result.put("totalNonBillableHours", totalNonBillable);
    result.put("members", members);

    return result;
    }
    private Map<String, Object> generateAutoGeneratedContribution(
        List<TimeSheet> sheets,
        Map<Long, String> nameCache) {

    Map<String, Object> result = new LinkedHashMap<>();

    // Total auto-generated hours
    BigDecimal totalAuto = sheets.stream()
            .filter(TimeSheet::getAutoGenerated)
            .map(ts -> ts.getHoursWorked() == null ? BigDecimal.ZERO : ts.getHoursWorked())
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    List<Map<String, Object>> members = new ArrayList<>();
    Map<Long, BigDecimal> userAuto = new HashMap<>();

    for (TimeSheet sheet : sheets) {
        if (sheet.getAutoGenerated()) {
            Long uid = sheet.getUserId();
            BigDecimal hrs = sheet.getHoursWorked() == null ? BigDecimal.ZERO : sheet.getHoursWorked();
            userAuto.put(uid, userAuto.getOrDefault(uid, BigDecimal.ZERO).add(hrs));
        }
    }

    for (Map.Entry<Long, BigDecimal> entry : userAuto.entrySet()) {
        if (entry.getValue().compareTo(BigDecimal.ZERO) <= 0) continue;

        BigDecimal contribution = totalAuto.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : entry.getValue()
                    .divide(totalAuto, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", entry.getKey());
        m.put("userName", nameCache.get(entry.getKey()));
        m.put("autoHours", entry.getValue());
        m.put("contribution", contribution);

        members.add(m);
    }

    result.put("totalAutoHours", totalAuto);
    result.put("members", members);

    return result;
    }
    public int calculateExpectedHours(int month, int year, List<LocalDate> holidays) {

    int weekdayHolidays = 0;

    for (LocalDate d : holidays) {
        switch (d.getDayOfWeek()) {
            case MONDAY:
            case TUESDAY:
            case WEDNESDAY:
            case THURSDAY:
            case FRIDAY:
                weekdayHolidays++;
                break;
            default:
                // skip saturday & sunday
                break;
        }
    }

    return expectedBaseHours - (weekdayHolidays * 8);
 }
}   
