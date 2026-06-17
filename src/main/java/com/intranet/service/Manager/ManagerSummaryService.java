package com.intranet.service.Manager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.intranet.entity.TimeSheet;
import com.intranet.entity.TimeSheetEntry;
import com.intranet.entity.TimeSheetReview;
import com.intranet.entity.WeeklyTimeSheetReview;
import com.intranet.repository.TimeSheetRepo;
import com.intranet.repository.TimeSheetReviewRepo;
import com.intranet.repository.WeeklyTimeSheetReviewRepo;
import com.intranet.util.PmsProjectUtils;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ManagerSummaryService {

    private final TimeSheetRepo timeSheetRepo;
    private final TimeSheetReviewRepo timeSheetReviewRepo;
    private final WeeklyTimeSheetReviewRepo weeklyReviewRepo;

    @Value("${pms.api.base-url}")
    private String pmsBaseUrl;

    @Value("${ums.api.base-url}")
    private String umsBaseUrl;


    private final RestTemplate restTemplate = new RestTemplate();

    public Map<String, Object> generateManagerSummary(
            Long managerId,
            LocalDate startDate,
            LocalDate endDate,
            String authHeader
    ) {

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", authHeader);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        // ------------------------------
        // 1️⃣ Fetch Manager Projects
        // ------------------------------
        String url = pmsBaseUrl + "/projects/owner";

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                new ParameterizedTypeReference<List<Map<String, Object>>>() {}
        );

        List<Map<String, Object>> projects = response.getBody();
        if (projects == null || projects.isEmpty()) {
            return emptySummary(startDate, endDate);
        }

        // Collect memberIds
        Set<Long> memberIds = PmsProjectUtils.extractMemberIds(projects);

        if (memberIds.isEmpty()) {
            return emptySummary(startDate, endDate);
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

        BigDecimal totalHours = sumHours(entries);
        BigDecimal billableHours = sumBillableHours(entries);

        double billablePercent = BigDecimal.ZERO.equals(totalHours)
                ? 0.0
                : billableHours.divide(totalHours, 2, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)).doubleValue();

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
        // 7️⃣ Build Response
        // ------------------------------
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalHours", totalHours);
        result.put("billableHours", billableHours);
        result.put("billablePercentage", billablePercent);
        result.put("pending", pending.get("count"));
        result.put("pendingUsers", pending.get("users"));
        result.put("dateRange", buildDateRange(startDate, endDate));
        result.put("weeklySummary", weekSummary);
        result.put("missingTimesheets", missing);

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
    private BigDecimal sumHours(List<TimeSheetEntry> entries) {
        BigDecimal sum = BigDecimal.ZERO;
        for (TimeSheetEntry e : entries) {
            sum = sum.add(convert(e));
        }
        return sum;
    }

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
            if (d == DayOfWeek.SUNDAY) continue;

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
    private Map<String, Object> emptySummary(LocalDate s, LocalDate e) {

        Map<String, Object> map = new HashMap<>();
        map.put("totalHours", BigDecimal.ZERO);
        map.put("billableHours", BigDecimal.ZERO);
        map.put("billablePercentage", 0.0);

        map.put("pending", 0);
        map.put("pendingUsers", new ArrayList<>());

        map.put("dateRange", buildDateRange(s, e));
        map.put("weeklySummary", new HashMap<>());
        map.put("missingTimesheets", new ArrayList<>());

        return map;
    }
}
