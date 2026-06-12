package com.intranet.service;

import com.intranet.dto.*;
import com.intranet.entity.InternalProject;
import com.intranet.entity.TimeSheet;
import com.intranet.entity.TimeSheetOnHolidaysType;
import com.intranet.entity.TimeSheetReview;
import com.intranet.entity.WeekInfo;
import com.intranet.repository.InternalProjectRepo;
import com.intranet.repository.TimeSheetOnHolidayTypeRepo;
import com.intranet.repository.TimeSheetOnHolidaysRepo;
import com.intranet.repository.TimeSheetRepo;
import com.intranet.repository.WeekInfoRepo;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;



@Service
@RequiredArgsConstructor
public class WeeklySummaryService {

    private final TimeSheetRepo timeSheetRepo;
    private final WeekInfoRepo weekInfoRepo;
    private final InternalProjectRepo internalProjectRepo;
    private final TimeSheetOnHolidayTypeRepo timeSheetOnHolidayTypeRepo;

    private HttpEntity<Void> buildEntityWithAuth() {
    ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    if (attrs == null) {
        return (HttpEntity<Void>) HttpEntity.EMPTY;
    }

    HttpServletRequest request = attrs.getRequest();
    String authHeader = request.getHeader("Authorization");

    HttpHeaders headers = new HttpHeaders();
    if (authHeader != null && !authHeader.isBlank()) {
        headers.set("Authorization", authHeader);
    }

    return new HttpEntity<>(headers);
    }

    @Value("${pms.api.base-url}")
    private String pmsBaseUrl;
    @Value("${ums.api.base-url}")
    private String umsBaseUrl;

     private final RestTemplate restTemplate = new RestTemplate();

    private final TimeSheetOnHolidaysRepo timeSheetOnHolidaysRepository;
    
    @Transactional
    public WeeklySummaryDTO getUserWeeklyTimeSheetHistory(Long userId) {
        LocalDate now = LocalDate.now();
        LocalDate startOfMonth = now.withDayOfMonth(1);
        LocalDate endOfMonth = now.withDayOfMonth(now.lengthOfMonth());

        List<WeekInfo> weeks = weekInfoRepo
                .findByStartDateGreaterThanEqualAndEndDateLessThanEqualOrderByStartDateAsc(startOfMonth, endOfMonth);

        List<Long> weekIds = weeks.stream().map(WeekInfo::getId).collect(Collectors.toList());
        List<TimeSheet> timesheets = timeSheetRepo.findByUserIdAndWeekInfo_IdInOrderByWorkDateAsc(userId, weekIds);

        if (timesheets.isEmpty()) {
            WeeklySummaryDTO empty = new WeeklySummaryDTO();
            empty.setUserId(userId);
            empty.setWeeklySummary(Collections.emptyList());
            return empty;
        }

        // 🔹 Step 1: Call PMS to get all projects
        String projectsUrl = String.format("%s/projects/tms", pmsBaseUrl);
        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                projectsUrl,
                HttpMethod.GET,
                buildEntityWithAuth(),
                new ParameterizedTypeReference<>() {}
        );

        List<Map<String, Object>> projects = Optional.ofNullable(response.getBody()).orElse(Collections.emptyList());
        Map<Long, Map<String, Object>> projectMap = new HashMap<>();
        for (Map<String, Object> p : projects) {
            Long projectId = ((Number) p.get("id")).longValue();
            projectMap.put(projectId, p);
        }

        // 🔹 Step 2: Group by week and build DTOs
        List<WeekSummaryDTO> weeklySummaries = weeks.stream()
        .map(week -> buildWeekSummary(week, timesheets, projectMap))
        .sorted(Comparator.comparing(WeekSummaryDTO::getWeekId).reversed()) // week 5 → 1
        .collect(Collectors.toList());


        WeeklySummaryDTO summary = new WeeklySummaryDTO();
        summary.setUserId(userId);
        summary.setWeeklySummary(weeklySummaries);

        return summary;
    }

    private WeekSummaryDTO buildWeekSummary(WeekInfo week, List<TimeSheet> allSheets, Map<Long, Map<String, Object>> projectMap) {
        List<TimeSheet> weekSheets = allSheets.stream()
                .filter(ts -> ts.getWeekInfo().getId().equals(week.getId()))
                .collect(Collectors.toList());

        Map<Long, List<InternalProject>> internalProjectMap =
                internalProjectRepo.findAll().stream()
                        .collect(Collectors.groupingBy(
                                ip -> ip.getProjectId().longValue()
                        ));
        
        List<TimeSheetSummaryDTO> sheetDTOs = new ArrayList<>();

        for (TimeSheet ts : weekSheets) {
            List<ActionStatusDTO> actionStatusList = new ArrayList<>();

            boolean isInternal = ts.getEntries().stream()
        .allMatch(e -> internalProjectMap.containsKey(e.getProjectId()));


        if (isInternal && ts.getAutoGenerated()==Boolean.FALSE) {
            // ------------------------------------------
            // INTERNAL PROJECT LOGIC
            // ------------------------------------------

            // 1. Check if review exists
            Optional<TimeSheetReview> reviewOpt =
                    ts.getReviews().stream().findFirst();

            if (!reviewOpt.isPresent()) {
                // CASE A — No review → show default admin pending
                actionStatusList.add(new ActionStatusDTO(
                        9999L,
                        "Timesheet Admin/Reporting Manager",
                        "Pending",
                        null
                ));
            } else {

                TimeSheetReview review = reviewOpt.get();

                String status = review.getStatus().name();
                if(status.equals("SUBMITTED"))
                    status = "Pending";

                // CASE C — Reviewed
                actionStatusList.add(new ActionStatusDTO(
                        review.getManagerId(),
                        fetchUserFullName(review.getManagerId()),
                        status,
                        review.getStatus() == TimeSheetReview.Status.REJECTED ? review.getComments() : null
                ));
            }

        } else {

            ts.getEntries().forEach(entry -> {
                Map<String, Object> project = projectMap.get(entry.getProjectId());
                if (project == null) return;

                Map<String, Object> owner = (Map<String, Object>) project.get("owner");
                if (owner == null) return;

                Long managerId = ((Number) owner.get("id")).longValue();
                String managerName = (String) owner.get("name");

                Optional<TimeSheetReview> reviewOpt = ts.getReviews().stream()
                        .filter(r -> r.getManagerId().equals(managerId))
                        .findFirst();

                String action;

                if (ts.getStatus() == TimeSheet.Status.SUBMITTED ) {
                    // 🔹 If timesheet is just submitted, force action to PENDING
                    action = "Pending";
                } else {
                    // 🔹 Otherwise, get actual review status if exists
                    action = reviewOpt.map(r -> r.getStatus().name()).orElse("Pending");
                }


                // avoid duplicates
                boolean exists = actionStatusList.stream()
                        .anyMatch(a -> a.getApproverId().equals(managerId));
                if (!exists) {
                    String reviewComments = "REJECTED".equalsIgnoreCase(action)
                            ? reviewOpt.map(TimeSheetReview::getComments).orElse(null)
                            : null;
                    actionStatusList.add(new ActionStatusDTO(managerId, managerName, action, reviewComments));
                }
            }
        
            );
        }

            // ✅ Determine overall status
            String overallStatus;
            boolean anyRejected = actionStatusList.stream().anyMatch(a -> "REJECTED".equalsIgnoreCase(a.getStatus()));
            boolean allApproved = !actionStatusList.isEmpty() && actionStatusList.stream().allMatch(a -> "APPROVED".equalsIgnoreCase(a.getStatus()));
            boolean anyApproved = actionStatusList.stream().anyMatch(a -> "APPROVED".equalsIgnoreCase(a.getStatus()));
            // boolean allPending = actionStatusList.stream().allMatch(a -> "PENDING".equalsIgnoreCase(a.getStatus()));

                List<TimeSheetEntrySummaryDTO> entries = ts.getEntries().stream()
                .sorted(Comparator.comparing(
                    e -> e.getFromTime() != null ? e.getFromTime() : LocalDateTime.MIN
                )) // ✅ Sort by start time safely, even if null
                .map(e -> {
                TimeSheetEntrySummaryDTO dto = new TimeSheetEntrySummaryDTO();
                dto.setTimesheetEntryid(e.getId());
                dto.setProjectId(e.getProjectId());
                dto.setTaskId(e.getTaskId());
                dto.setDescription(e.getDescription());
                dto.setWorkLocation(e.getWorkLocation());
                dto.setFromTime(e.getFromTime() != null ? e.getFromTime(): null);
                dto.setToTime(e.getToTime() != null ? e.getToTime(): null);
                dto.setHoursWorked(e.getHoursWorked());
                dto.setOtherDescription(e.getOtherDescription());
                dto.setIsBillable(e.isBillable());
                return dto;
            }).collect(Collectors.toList());

            TimeSheetSummaryDTO tsDTO = new TimeSheetSummaryDTO();
            tsDTO.setTimesheetId(ts.getId());
            tsDTO.setWorkDate(ts.getWorkDate());
            // Recompute from entry fromTime/toTime — bypasses corrupted stored values.
            tsDTO.setHoursWorked(TimeUtil.computeTimeSheetHours(ts));
            tsDTO.setEntries(entries);
            
            // ✅ Mark if this timesheet is an auto-generated 8-hour default holiday
            Optional<TimeSheetOnHolidaysType> holidayOpt = timeSheetOnHolidayTypeRepo.findByTimeSheetId(ts.getId());

            if (holidayOpt.isPresent()) {

            TimeSheetOnHolidaysType holiday = holidayOpt.get();

                    if (holiday.getHolidayType() == TimeSheetOnHolidaysType.HolidayType.GENERAL) {
                        tsDTO.setDefaultHolidayTimesheet(true);
                        tsDTO.setIsLeaveTimesheet(false);
                    }

                    else if (holiday.getHolidayType() == TimeSheetOnHolidaysType.HolidayType.LEAVE) {
                        tsDTO.setIsLeaveTimesheet(true);
                        tsDTO.setDefaultHolidayTimesheet(false);
                    }

                } else {
                    tsDTO.setDefaultHolidayTimesheet(false);
                    tsDTO.setIsLeaveTimesheet(false);
                }

            if (actionStatusList.isEmpty()) {
                overallStatus = "APPROVED";
                actionStatusList.add(new ActionStatusDTO(99L, "Supervisor Mock", "APPROVED", null));
                    }
             else if (anyRejected) {
                        overallStatus = "REJECTED";
                    } else if (allApproved) {
                        overallStatus = "APPROVED";
                    } else if (anyApproved) {
                        overallStatus = "PARTIALLY APPROVED";
                    } else {
                        overallStatus = ts.getStatus().name();
                    }

                    tsDTO.setActionStatus(actionStatusList);
                    tsDTO.setStatus(overallStatus);
                    // 🔹 NEW: mark if it's a holiday timesheet
                    boolean isHoliday = timeSheetOnHolidaysRepository.existsByTimeSheetId(ts.getId());
                    tsDTO.setIsHolidayTimesheet(isHoliday);
                    sheetDTOs.add(tsDTO);
            }

        // ✅ Calculate total hours
        BigDecimal totalHours = TimeUtil.sumHours(
                sheetDTOs.stream().map(TimeSheetSummaryDTO::getHoursWorked).collect(Collectors.toList())
        );

        WeekSummaryDTO weekDTO = new WeekSummaryDTO();
        weekDTO.setWeekId(week.getWeekNo().longValue());
        weekDTO.setStartDate(week.getStartDate());
        weekDTO.setEndDate(week.getEndDate());
        // 🔸 Validation: No timesheets found for this week
            if (weekSheets.isEmpty()) {
                weekDTO.setTimesheets(Collections.emptyList());
                weekDTO.setTotalHours(BigDecimal.ZERO);
                weekDTO.setWeeklyStatus("No Timesheets");
                return weekDTO;
            }
        else {
        weekDTO.setTotalHours(totalHours);
        weekDTO.setTimesheets(sheetDTOs);

        // Derive overall week-level status
        boolean anyRejected = sheetDTOs.stream().anyMatch(ts -> "Rejected".equalsIgnoreCase(ts.getStatus()));
        boolean allApproved = !sheetDTOs.isEmpty() && sheetDTOs.stream().allMatch(ts -> "Approved".equalsIgnoreCase(ts.getStatus()));
        boolean anyApproved = sheetDTOs.stream().anyMatch(ts -> "Partially Approved".equalsIgnoreCase(ts.getStatus()) || "Approved".equalsIgnoreCase(ts.getStatus()));
        
        

        if (anyRejected) weekDTO.setWeeklyStatus("REJECTED");
        else if (allApproved) weekDTO.setWeeklyStatus("APPROVED");
        else if (anyApproved) weekDTO.setWeeklyStatus("PARTIALLY APPROVED");
        else weekDTO.setWeeklyStatus("SUBMITTED");

        return weekDTO;
        }
    }

    @Transactional
    public WeeklySummaryDTO getUserWeeklyTimeSheetHistoryRange(Long userId, LocalDate startDate, LocalDate endDate) {
        LocalDate today = LocalDate.now();

        if (startDate == null || endDate == null) {
            startDate = today.withDayOfMonth(1);
            endDate = today.withDayOfMonth(today.lengthOfMonth());
        }

        LocalDate startOfMonth = startDate;
        LocalDate endOfMonth = endDate;

        // List<WeekInfo> weeks = weekInfoRepo
        //         .findByStartDateGreaterThanEqualAndEndDateLessThanEqualOrderByStartDateAsc(startOfMonth, endOfMonth);

        List<WeekInfo> weeks = weekInfoRepo
        .findByStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateAsc(endDate, startDate);

        List<Long> weekIds = weeks.stream().map(WeekInfo::getId).collect(Collectors.toList());
        List<TimeSheet> timesheets = timeSheetRepo.findByUserIdAndWeekInfo_IdInOrderByWorkDateAsc(userId, weekIds);

        if (timesheets.isEmpty()) {
            WeeklySummaryDTO empty = new WeeklySummaryDTO();
            empty.setUserId(userId);
            empty.setWeeklySummary(Collections.emptyList());
            return empty;
        }

        // 🔹 Step 1: Call PMS to get all projects
        String projectsUrl = String.format("%s/projects/tms", pmsBaseUrl);
        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                projectsUrl,
                HttpMethod.GET,
                buildEntityWithAuth(),
                new ParameterizedTypeReference<>() {}
        );

        List<Map<String, Object>> projects = Optional.ofNullable(response.getBody()).orElse(Collections.emptyList());
        Map<Long, Map<String, Object>> projectMap = new HashMap<>();
        for (Map<String, Object> p : projects) {
            Long projectId = ((Number) p.get("id")).longValue();
            projectMap.put(projectId, p);
        }

        // 🔹 Step 2: Group by week and build DTOs
        List<WeekSummaryDTO> weeklySummaries = weeks.stream()
        .map(week -> buildWeekSummary(week, timesheets, projectMap))
        .sorted(Comparator.comparing(WeekSummaryDTO::getStartDate).reversed()) // current month → past
        .collect(Collectors.toList());


        WeeklySummaryDTO summary = new WeeklySummaryDTO();
        summary.setUserId(userId);
        summary.setWeeklySummary(weeklySummaries);

        return summary;
    }

    private String fetchUserFullName(Long userId) {

    try {
        String url = umsBaseUrl + "/admin/users/" + userId;


          // ✔ Use your existing method
        HttpEntity<Void> entity = buildEntityWithAuth();

        ResponseEntity<Map<String, Object>> response =
                restTemplate.exchange(
                        url,
                        HttpMethod.GET,
                        entity,
                        new ParameterizedTypeReference<>() {}
                );

        Map<String, Object> body = response.getBody();
        if (body == null) return "Unknown User";

        String first = (String) body.getOrDefault("first_name", "");
        String last  = (String) body.getOrDefault("last_name", "");

        String fullName = (first + " " + last).trim();
        return fullName.isEmpty() ? "Unknown User" : fullName;

    } catch (Exception e) {
        return "Unknown User";
    }
    }

}
