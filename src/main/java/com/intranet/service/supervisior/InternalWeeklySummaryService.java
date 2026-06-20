package com.intranet.service.supervisior;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.intranet.dto.TimeSheetEntrySummaryDTO;
import com.intranet.dto.TimeSheetSummaryDTO;
import com.intranet.dto.WeekSummaryDTO;
import com.intranet.dto.external.ManagerWeeklySummaryDTO;
import com.intranet.entity.InternalProject;
import com.intranet.entity.TimeSheet;
import com.intranet.entity.TimeSheetEntry;
import com.intranet.entity.TimeSheetOnHolidays;
import com.intranet.entity.WeekInfo;
import com.intranet.repository.InternalProjectRepo;
import com.intranet.repository.TimeSheetOnHolidaysRepo;
import com.intranet.repository.TimeSheetRepo;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class InternalWeeklySummaryService {

    private final RestTemplate restTemplate;
    private final TimeSheetRepo timeSheetRepo;
    private final TimeSheetOnHolidaysRepo timeSheetOnHolidaysRepo;
    private final InternalProjectRepo internalProjectRepo;

    @Value("${ums.api.base-url}")
    private String umsBaseUrl;

    @Value("${eos.api.base-url}")
    private String eosBaseUrl;

    public List<ManagerWeeklySummaryDTO> getInternalWeeklySummary(String authHeader,
                                                                  LocalDate startOfMonth,
                                                                  LocalDate endOfMonth) {

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", authHeader);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        Map<Long, Map<String, Object>> userCache = fetchAllUsers(entity);
        List<TimeSheet> allSheets = timeSheetRepo.findAllNonDraft();

        return buildInternalWeeklySummary(allSheets, userCache, startOfMonth, endOfMonth);
    }

    public List<ManagerWeeklySummaryDTO> getInternalWeeklySummaryForReportingManager(String authHeader,
                                                                                     String managerEmpid,
                                                                                     LocalDate startOfMonth,
                                                                                     LocalDate endOfMonth) {

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", authHeader);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Void> getEntity = new HttpEntity<>(headers);

        // STEP 1: EOS — direct reports for this manager
        Set<Long> employeeIds = fetchReportingManagerEmployeeIds(getEntity, managerEmpid);
        if (employeeIds.isEmpty()) return Collections.emptyList();

        // STEP 2: Aggregate against the scoped timesheet set
        Map<Long, Map<String, Object>> userCache = fetchAllUsers(getEntity);
        List<TimeSheet> scopedSheets = timeSheetRepo.findNonDraftByUserIds(employeeIds);

        return buildInternalWeeklySummary(scopedSheets, userCache, startOfMonth, endOfMonth);
    }

    private List<ManagerWeeklySummaryDTO> buildInternalWeeklySummary(List<TimeSheet> sheets,
                                                                     Map<Long, Map<String, Object>> userCache,
                                                                     LocalDate startOfMonth,
                                                                     LocalDate endOfMonth) {

        Map<Long, List<InternalProject>> internalProjectMap =
                internalProjectRepo.findAll().stream()
                        .collect(Collectors.groupingBy(
                                ip -> ip.getProjectId().longValue()
                        ));

        if (internalProjectMap == null || internalProjectMap.isEmpty()) {
            return Collections.emptyList();
        }

        if (sheets == null || sheets.isEmpty()) {
            return Collections.emptyList();
        }

        // Filter month
        List<TimeSheet> monthSheets = sheets.stream()
                .filter(ts -> ts.getWorkDate() != null
                        && !ts.getWorkDate().isBefore(startOfMonth)
                        && !ts.getWorkDate().isAfter(endOfMonth))
                .collect(Collectors.toList());

        // STRICT FILTER — timesheet must contain ONLY internal project entries
        List<TimeSheet> internalSheets = monthSheets.stream()
                .filter(ts -> {
                    if (ts.getEntries() == null || ts.getEntries().isEmpty()) return false;

                    boolean hasInternal = ts.getEntries().stream()
                            .anyMatch(e -> internalProjectMap.containsKey(e.getProjectId()));

                    if (!hasInternal) return false;

                    boolean hasExternal = ts.getEntries().stream()
                            .anyMatch(e -> !internalProjectMap.containsKey(e.getProjectId()));

                    return !hasExternal;
                })
                .collect(Collectors.toList());

        if (internalSheets.isEmpty()) return Collections.emptyList();

        // Group by user and build manager DTOs
        return internalSheets.stream()
                .collect(Collectors.groupingBy(TimeSheet::getUserId))
                .entrySet().stream()
                .map(entry -> {
                    Long userId = entry.getKey();
                    List<TimeSheet> userSheets = entry.getValue();

                    Map<String, Object> userInfo = userCache.get(userId);
                    String fullName = extractName(userInfo);

                    BigDecimal totalBillable = sumBillable(userSheets, internalProjectMap);
                    BigDecimal nonBillable = sumNonBillable(userSheets, internalProjectMap);
                    BigDecimal autoHours = sumAutoGeneratedHours(userSheets);
                    BigDecimal totalWorked = totalBillable.add(nonBillable);

                    List<WeekSummaryDTO> weekSummaries = buildWeekSummaries(userSheets, internalProjectMap);

                    ManagerWeeklySummaryDTO dto = new ManagerWeeklySummaryDTO();
                    dto.setUserId(userId);
                    dto.setUserName(fullName);
                    dto.setBillableHours(totalBillable);
                    dto.setNonBillableHours(nonBillable);
                    dto.setAutogeneratedHours(autoHours);
                    dto.setTotalHours(totalWorked);
                    dto.setWeeklySummary(weekSummaries);

                    return dto;
                })
                .sorted(Comparator.comparing(ManagerWeeklySummaryDTO::getUserName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .collect(Collectors.toList());
    }


    // -------------------------------------------------------------
    // USER FETCH
    // -------------------------------------------------------------
    @SuppressWarnings("unchecked")
    private Map<Long, Map<String, Object>> fetchAllUsers(HttpEntity<Void> entity) {
        try {
            String url = String.format("%s/admin/users?page=1&limit=500", umsBaseUrl);

            ResponseEntity<Map<String, Object>> res =
                    restTemplate.exchange(url, HttpMethod.GET, entity,
                            new ParameterizedTypeReference<Map<String, Object>>() {});

            Map<String, Object> body = res.getBody();
            if (body != null && body.containsKey("users")) {
                List<Map<String, Object>> list = (List<Map<String, Object>>) body.get("users");

                return list.stream().collect(Collectors.toMap(
                        u -> ((Number) u.get("user_id")).longValue(),
                        u -> u
                ));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private Set<Long> fetchReportingManagerEmployeeIds(HttpEntity<Void> entity, String managerEmpid) {
        String url = String.format("%s/hr/reporting-manager/%s/employees", eosBaseUrl, managerEmpid);
        try {
            ResponseEntity<Map<String, Object>> res = restTemplate.exchange(
                    url, HttpMethod.GET, entity,
                    new ParameterizedTypeReference<Map<String, Object>>() {});

            Map<String, Object> body = res.getBody();
            if (body == null || !body.containsKey("employees")) return Collections.emptySet();

            List<Map<String, Object>> employees = (List<Map<String, Object>>) body.get("employees");
            return employees.stream()
                    .map(e -> e.get("employee_id"))
                    .filter(id -> id != null)
                    .map(id -> id instanceof Number
                            ? ((Number) id).longValue()
                            : Long.parseLong(id.toString().trim()))
                    .collect(Collectors.toSet());
        } catch (HttpStatusCodeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "EOS call failed ");
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "EOS call failed");
        }
    }

    // -------------------------------------------------------------
    // REPORTING-MANAGER USERS (for the holiday-exclude dropdown)
    // Returns the manager's direct reports in { id, fullName, email } shape.
    // -------------------------------------------------------------
    public List<Map<String, Object>> getReportingManagerUsers(String authHeader, String managerEmpid) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", authHeader);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        Set<Long> employeeIds = fetchReportingManagerEmployeeIds(entity, managerEmpid);
        if (employeeIds.isEmpty()) return Collections.emptyList();

        Map<Long, Map<String, Object>> userCache = fetchAllUsers(entity);

        return employeeIds.stream()
                .map(id -> {
                    Map<String, Object> info = userCache.get(id);
                    String email = (info != null && info.get("mail") != null)
                            ? info.get("mail").toString()
                            : "";

                    Map<String, Object> dto = new LinkedHashMap<>();
                    dto.put("id", id);
                    dto.put("fullName", extractName(info));
                    dto.put("email", email);
                    return dto;
                })
                .sorted(Comparator.comparing(u -> ((String) u.get("fullName")).toLowerCase()))
                .collect(Collectors.toList());
    }

    private String extractName(Map<String, Object> user) {
        if (user == null) return "Unknown";
        String f = (String) user.getOrDefault("first_name", "");
        String l = (String) user.getOrDefault("last_name", "");
        String name = (f + " " + l).trim();
        return name.isEmpty() ? "Unknown" : name;
    }


    // -------------------------------------------------------------
    // HOURS CALCULATION (only entries that belong to internal projects)
    // -------------------------------------------------------------
    private BigDecimal sumBillable(List<TimeSheet> sheets,
                                   Map<Long, List<InternalProject>> ipMap) {
        return sheets.stream()
                .flatMap(ts -> ts.getEntries().stream())
                .filter(e -> ipMap.containsKey(e.getProjectId()))
                .filter(TimeSheetEntry::isBillable)
                .map(TimeSheetEntry::getHoursWorked)
                .filter(h -> h != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumNonBillable(List<TimeSheet> sheets,
                                      Map<Long, List<InternalProject>> ipMap) {
        return sheets.stream()
                .flatMap(ts -> ts.getEntries().stream())
                .filter(e -> ipMap.containsKey(e.getProjectId()))
                .filter(e -> !e.isBillable())
                .map(TimeSheetEntry::getHoursWorked)
                .filter(h -> h != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumAutoGeneratedHours(List<TimeSheet> sheets) {
        return sheets.stream()
                .filter(ts -> Boolean.TRUE.equals(ts.getAutoGenerated()))
                .map(TimeSheet::getHoursWorked)
                .filter(h -> h != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }


    // -------------------------------------------------------------
    // WEEK SUMMARY BUILDER
    // -------------------------------------------------------------
    private List<WeekSummaryDTO> buildWeekSummaries(List<TimeSheet> sheets,
                                                    Map<Long, List<InternalProject>> ipMap) {

        return sheets.stream()
                .collect(Collectors.groupingBy(ts -> ts.getWeekInfo().getWeekNo()))
                .entrySet().stream()
                .sorted((a, b) -> Integer.compare(a.getKey(), b.getKey()))
                .map(e -> {
                    Integer weekNo = e.getKey();
                    List<TimeSheet> weekSheets = e.getValue();
                    WeekInfo weekInfo = weekSheets.get(0).getWeekInfo();

                    List<TimeSheetSummaryDTO> tsDtos =
                            weekSheets.stream()
                                    .map(ts -> mapTimesheet(ts, ipMap))
                                    .filter(t -> t != null)
                                    .collect(Collectors.toList());

                    BigDecimal totalHours = tsDtos.stream()
                            .map(TimeSheetSummaryDTO::getHoursWorked)
                            .filter(h -> h != null)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    WeekSummaryDTO w = new WeekSummaryDTO();
                    w.setWeekId(weekNo.longValue());
                    w.setStartDate(weekInfo.getStartDate());
                    w.setEndDate(weekInfo.getEndDate());
                    w.setTotalHours(totalHours);
                    w.setTimesheets(tsDtos);

                    // Week Status Logic.
                    // After a partial-reject + resubmit, a week can mix APPROVED days
                    // with SUBMITTED days that still need review — surface that as
                    // PARTIALLY_APPROVED so the admin/RM frontend keeps the action
                    // buttons visible (it would otherwise hide them on plain "APPROVED").
                    Set<String> statuses = tsDtos.stream()
                            .map(TimeSheetSummaryDTO::getStatus)
                            .collect(Collectors.toSet());

                    boolean hasRejected  = statuses.contains("REJECTED");
                    boolean hasApproved  = statuses.contains("APPROVED");
                    boolean hasSubmitted = statuses.contains("SUBMITTED");

                    if (hasRejected) {
                        w.setWeeklyStatus("REJECTED");
                    } else if (hasApproved && hasSubmitted) {
                        w.setWeeklyStatus("PARTIALLY_APPROVED");
                    } else if (hasApproved) {
                        w.setWeeklyStatus("APPROVED");
                    } else {
                        w.setWeeklyStatus("SUBMITTED");
                    }

                    return w;
                })
                .collect(Collectors.toList());
    }


    // -------------------------------------------------------------
    // TIMESHEET MAPPING (with projectName + taskName)
    // -------------------------------------------------------------
    private TimeSheetSummaryDTO mapTimesheet(TimeSheet ts,
                                         Map<Long, List<InternalProject>> ipMap) {

    if (ts.getEntries() == null) return null;

    // -------------------------------------------------
    // CHECK HOLIDAY TIMESHEET FROM TimeSheetOnHolidays
    // -------------------------------------------------
    Boolean isHoliday = timeSheetOnHolidaysRepo.findByTimeSheetId(ts.getId())
            .map(TimeSheetOnHolidays::getIsHoliday)
            .orElse(false);   // default false

    Boolean isAutoGenerated = ts.getAutoGenerated() != null && ts.getAutoGenerated();

    // -------------------------------------------------
    // ENTRIES
    // -------------------------------------------------
    List<TimeSheetEntrySummaryDTO> entries =
            ts.getEntries().stream()
                    .filter(e -> ipMap.containsKey(e.getProjectId()))
                    .map(e -> {
                        List<InternalProject> ipList = ipMap.get(e.getProjectId());
                        if (ipList == null || ipList.isEmpty()) return null;

                        // match by taskId if possible
                        Optional<InternalProject> matched = ipList.stream()
                                .filter(ip -> ip.getTaskId() != null
                                        && ip.getTaskId().equals(e.getTaskId()))
                                .findFirst();

                        InternalProject ip = matched.orElse(ipList.get(0));

                        TimeSheetEntrySummaryDTO dto = new TimeSheetEntrySummaryDTO();
                        dto.setTimesheetEntryid(e.getId());
                        dto.setProjectId(e.getProjectId());
                        dto.setProjectName(ip.getProjectName());
                        dto.setTaskId(e.getTaskId());
                        dto.setTaskName(ip.getTaskName());
                        dto.setDescription(e.getDescription());
                        dto.setWorkLocation(e.getWorkLocation());
                        dto.setFromTime(e.getFromTime());
                        dto.setToTime(e.getToTime());
                        dto.setHoursWorked(e.getHoursWorked());
                        dto.setOtherDescription(e.getOtherDescription());
                        dto.setIsBillable(e.isBillable());
                        return dto;
                    })
                    .filter(d -> d != null)
                    .collect(Collectors.toList());

    // -------------------------------------------------
    // BASE TIMESHEET SUMMARY DTO
    // -------------------------------------------------
    TimeSheetSummaryDTO dto = new TimeSheetSummaryDTO();
    dto.setTimesheetId(ts.getId());
    dto.setWorkDate(ts.getWorkDate());
    dto.setHoursWorked(ts.getHoursWorked());
    dto.setEntries(entries);

    // STATUS
    dto.setStatus(ts.getStatus() != null ? ts.getStatus().name() : "SUBMITTED");

    // -------------------------------------------------
    // SET HOLIDAY FIELDS
    // -------------------------------------------------
    dto.setIsHolidayTimesheet(isHoliday);        // Boolean, never null
    dto.setDefaultHolidayTimesheet(isAutoGenerated);

    return dto;
    }

}
