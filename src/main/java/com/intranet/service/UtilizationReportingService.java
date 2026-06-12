package com.intranet.service;

import com.intranet.dto.rms.*;
import com.intranet.entity.TimeSheet;
import com.intranet.entity.TimeSheetEntry;
import com.intranet.repository.TimeSheetRepo;
import com.intranet.repository.TimeSheetEntryRepo;
import com.intranet.repository.InternalProjectRepo;
import com.intranet.service.RMS.RMSTimeSheetService;
import com.intranet.service.DashboardService;
import com.intranet.util.cache.UserDirectoryService;
import com.intranet.dto.UserHoursDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class UtilizationReportingService {

    private final TimeSheetRepo timeSheetRepository;
    private final TimeSheetEntryRepo entryRepository;
    private final InternalProjectRepo internalProjectRepo;
    private final RMSTimeSheetService rmsTimeSheetService;
    private final DashboardService dashboardService;
    private final UserDirectoryService userDirectoryService;

    public UtilizationReportResponseDTO generateUtilizationReport(UtilizationReportRequestDTO request, jakarta.servlet.http.HttpServletRequest httpRequest) {
        log.info("Generating utilization report: type={}, startDate={}, endDate={}", 
                request.getReportType(), request.getStartDate(), request.getEndDate());

        // Validate request
        validateRequest(request);

        // Handle backward compatibility - if request is null, use original logic
        if (httpRequest == null) {
            return generateUtilizationReportOriginal(request);
        }

        String authHeader = httpRequest.getHeader("Authorization");
        
        if (authHeader == null || authHeader.isBlank()) {
            throw new RuntimeException("Authorization header is required");
        }

        try {
            // Get all users from UMS using getUsersWithHours() approach
            List<Map<String, Object>> users = userDirectoryService.fetchAllUsers2(authHeader);
            log.info("DEBUG: Total users fetched from UMS: {}", users.size());
            
            // Extract user IDs
            List<Long> userIds = users.stream()
                    .filter(user -> user.get("id") != null)
                    .map(user -> ((Number) user.get("id")).longValue())
                    .collect(Collectors.toList());
            
            log.info("DEBUG: Users with valid id: {}", userIds.size());

            // Get hours summary for all users
            List<UserHoursDTO> usersHours = dashboardService.getUsersHoursSummary(userIds, request.getStartDate(), request.getEndDate());

            // Create a map of userId -> UserHoursDTO for quick lookup
            Map<Long, UserHoursDTO> hoursMap = usersHours.stream()
                    .collect(Collectors.toMap(UserHoursDTO::getUserId, userHours -> userHours));

            // Create user name and role maps from UMS data
            Map<Long, String> resourceNames = users.stream()
                    .filter(user -> user.get("id") != null)
                    .collect(Collectors.toMap(
                            user -> ((Number) user.get("id")).longValue(),
                            user -> user.get("name") != null ? (String) user.get("name") : "Unknown User"
                    ));

            Map<Long, String> resourceRoles = users.stream()
                    .filter(user -> user.get("id") != null)
                    .collect(Collectors.toMap(
                            user -> ((Number) user.get("id")).longValue(),
                            user -> user.get("designation") != null ? (String) user.get("designation") : "Employee"
                    ));

            // Get filtered timesheet data
            List<TimeSheet> filteredTimeSheets = getFilteredTimeSheets(request);
            List<TimeSheetEntry> filteredEntries = getFilteredEntries(filteredTimeSheets, request);

            // Build response
            UtilizationReportResponseDTO response = UtilizationReportResponseDTO.builder()
                    .startDate(request.getStartDate())
                    .endDate(request.getEndDate())
                    .reportType(request.getReportType())
                    .groupBy(request.getGroupBy())
                    .approvedDataOnly(request.isApprovedOnly())
                    .build();

            // Calculate based on report type
            switch (request.getReportType().toUpperCase()) {
                case "RESOURCE":
                    buildResourceReportWithAllUsers(response, filteredTimeSheets, filteredEntries, request, resourceNames, resourceRoles, hoursMap);
                    break;
                case "PROJECT":
                    buildProjectReport(response, filteredTimeSheets, filteredEntries, request);
                    break;
                case "CLIENT":
                    buildClientReport(response, filteredTimeSheets, filteredEntries, request);
                    break;
                case "ROLE":
                    buildRoleReport(response, filteredTimeSheets, filteredEntries, request);
                    break;
                case "SUMMARY":
                default:
                    buildSummaryReportWithAllUsers(response, filteredTimeSheets, filteredEntries, request, resourceNames, resourceRoles, hoursMap);
                    break;
            }

            // Add trends if requested
            if (request.isIncludeTrends()) {
                response.setTrends(buildTrends(filteredTimeSheets, filteredEntries, request));
            }

            // Add alerts if requested
            if (request.isIncludeAlerts()) {
                response.setAlerts(buildAlerts(response, request));
                response.setPatterns(buildPatterns(response, request));
            }

            return response;
        } catch (Exception e) {
            log.error("Exception occurred in generateUtilizationReport: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to generate utilization report: " + e.getMessage(), e);
        }
    }

    private void validateRequest(UtilizationReportRequestDTO request) {
        if (request.getStartDate() == null || request.getEndDate() == null) {
            throw new IllegalArgumentException("Start date and end date are required");
        }
        if (request.getStartDate().isAfter(request.getEndDate())) {
            throw new IllegalArgumentException("Start date cannot be after end date");
        }
        if (ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) > 365) {
            throw new IllegalArgumentException("Report period cannot exceed 365 days");
        }
    }

    private List<TimeSheet> getFilteredTimeSheets(UtilizationReportRequestDTO request) {
        // Base query for date range
        List<TimeSheet> timeSheets = timeSheetRepository.findByWorkDateBetweenWithWeekInfoAndEntries(
                request.getStartDate(), request.getEndDate());

        // Filter by approval status
        if (request.isApprovedOnly()) {
            timeSheets = timeSheets.stream()
                    .filter(ts -> ts.getStatus() == TimeSheet.Status.APPROVED)
                    .collect(Collectors.toList());
        }

        // Filter by resources
        if (request.getResourceIds() != null && !request.getResourceIds().isEmpty()) {
            timeSheets = timeSheets.stream()
                    .filter(ts -> ts.getUserId() != null && request.getResourceIds().contains(ts.getUserId()))
                    .collect(Collectors.toList());
        }

        return timeSheets;
    }

    private List<TimeSheetEntry> getFilteredEntries(List<TimeSheet> timeSheets, UtilizationReportRequestDTO request) {
        List<TimeSheetEntry> entries = timeSheets.stream()
                .flatMap(ts -> ts.getEntries().stream())
                .collect(Collectors.toList());

        // Filter by projects
        if (request.getProjectIds() != null && !request.getProjectIds().isEmpty()) {
            entries = entries.stream()
                    .filter(entry -> entry.getProjectId() != null && request.getProjectIds().contains(entry.getProjectId()))
                    .collect(Collectors.toList());
        }

        return entries;
    }

    private void buildResourceReport(UtilizationReportResponseDTO response, 
                                   List<TimeSheet> timeSheets, 
                                   List<TimeSheetEntry> entries, 
                                   UtilizationReportRequestDTO request) {
        
        Map<Long, String> resourceNames = fetchResourceNames();
        Map<Long, String> resourceRoles = fetchResourceRoles();
        
        // Group by resource
        Map<Long, List<TimeSheet>> timeSheetsByResource = timeSheets.stream()
                .collect(Collectors.groupingBy(TimeSheet::getUserId));
        
        Map<Long, List<TimeSheetEntry>> entriesByResource = entries.stream()
                .collect(Collectors.groupingBy(entry -> entry.getTimeSheet().getUserId()));

        List<ResourceUtilizationDTO> resourceUtilizations = new ArrayList<>();
        
        for (Map.Entry<Long, List<TimeSheet>> entry : timeSheetsByResource.entrySet()) {
            Long resourceId = entry.getKey();
            if (resourceId == null) continue;
            
            List<TimeSheet> resourceTimeSheets = entry.getValue();
            List<TimeSheetEntry> resourceEntries = entriesByResource.getOrDefault(resourceId, new ArrayList<>());
            
            ResourceUtilizationDTO resourceUtil = calculateResourceUtilization(
                    resourceId, 
                    resourceNames.get(resourceId),
                    resourceRoles.get(resourceId),
                    resourceTimeSheets, 
                    resourceEntries, 
                    request);
            
            resourceUtilizations.add(resourceUtil);
        }
        
        // Sort by utilization percentage descending
        resourceUtilizations.sort((a, b) -> b.getUtilizationPercentage().compareTo(a.getUtilizationPercentage()));
        
        response.setResourceUtilizations(resourceUtilizations);
        response.setTotalResources(resourceUtilizations.size());
        
        // Calculate overall metrics
        calculateOverallMetrics(response, resourceUtilizations);
    }

    private ResourceUtilizationDTO calculateResourceUtilization(Long resourceId, 
                                                             String resourceName,
                                                             String role,
                                                             List<TimeSheet> timeSheets, 
                                                             List<TimeSheetEntry> entries, 
                                                             UtilizationReportRequestDTO request) {
        
        // Calculate hours
        BigDecimal totalHours = calculateTotalHours(timeSheets, entries);
        BigDecimal billableHours = calculateBillableHours(entries);
        BigDecimal nonBillableHours = calculateNonBillableHours(entries);
        BigDecimal internalHours = calculateInternalHours(entries);
        BigDecimal plannedHours = calculatePlannedHours(timeSheets, request.getStartDate(), request.getEndDate());
        
        // Calculate utilization
        BigDecimal utilizationPercentage = calculateUtilizationPercentage(totalHours, plannedHours);
        BigDecimal billableRatio = calculateBillableRatio(billableHours, totalHours);
        
        // Determine utilization band
        String utilizationBand = determineUtilizationBand(utilizationPercentage.doubleValue(), request);
        
        // Calculate trend
        String trendSignal = calculateTrendSignal(timeSheets, entries, request);
        
        // Calculate confidence score
        Integer confidenceScore = calculateConfidenceScore(timeSheets);
        
        // Analyze patterns
        boolean consistentlyOverUtilized = isConsistentlyOverUtilized(timeSheets, entries, request);
        boolean consistentlyUnderUtilized = isConsistentlyUnderUtilized(timeSheets, entries, request);
        Integer consecutiveWeeksOverThreshold = calculateConsecutiveWeeksOverThreshold(timeSheets, entries, request);
        Integer consecutiveWeeksUnderThreshold = calculateConsecutiveWeeksUnderThreshold(timeSheets, entries, request);
        
        // Generate alerts
        List<String> alerts = generateResourceAlerts(utilizationPercentage.doubleValue(), utilizationBand, 
                consistentlyOverUtilized, consistentlyUnderUtilized, request);
        
        return ResourceUtilizationDTO.builder()
                .resourceId(resourceId)
                .resourceName(resourceName != null ? resourceName : "Resource " + resourceId)
                .role(role != null ? role : "Unknown")
                .totalHours(totalHours)
                .billableHours(billableHours)
                .nonBillableHours(nonBillableHours)
                .internalHours(internalHours)
                .plannedHours(plannedHours)
                .utilizationPercentage(utilizationPercentage)
                .billableRatio(billableRatio)
                .utilizationBand(utilizationBand)
                .trendSignal(trendSignal)
                .alerts(alerts)
                .consistentlyOverUtilized(consistentlyOverUtilized)
                .consistentlyUnderUtilized(consistentlyUnderUtilized)
                .consecutiveWeeksOverThreshold(consecutiveWeeksOverThreshold)
                .consecutiveWeeksUnderThreshold(consecutiveWeeksUnderThreshold)
                .confidenceScore(confidenceScore)
                .daysWithApprovedTimesheets(calculateApprovedDays(timeSheets))
                .totalWorkingDays(calculateWorkingDays(request.getStartDate(), request.getEndDate()))
                .build();
    }

    private String determineUtilizationBand(Double utilization, UtilizationReportRequestDTO request) {
        double overThreshold = request.getOverUtilizationThreshold() != null ? request.getOverUtilizationThreshold().doubleValue() : 90.0;
        double underThreshold = request.getUnderUtilizationThreshold() != null ? request.getUnderUtilizationThreshold().doubleValue() : 60.0;
        
        if (utilization >= overThreshold) {
            return "HIGH";
        } else if (utilization >= 80) {
            return "OPTIMAL";
        } else if (utilization >= underThreshold) {
            return "LOW";
        } else {
            return "CRITICAL";
        }
    }

    private BigDecimal calculateTotalHours(List<TimeSheet> timeSheets, List<TimeSheetEntry> entries) {
        BigDecimal entryHours = entries.stream()
                .map(entry -> entry.getHoursWorked() != null ? entry.getHoursWorked() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal autoGeneratedHours = timeSheets.stream()
                .filter(ts -> Boolean.TRUE.equals(ts.getAutoGenerated()))
                .map(ts -> ts.getHoursWorked() != null ? ts.getHoursWorked() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        return entryHours.add(autoGeneratedHours);
    }

    private BigDecimal calculateBillableHours(List<TimeSheetEntry> entries) {
        return entries.stream()
                .filter(TimeSheetEntry::isBillable)
                .map(entry -> entry.getHoursWorked() != null ? entry.getHoursWorked() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal calculateNonBillableHours(List<TimeSheetEntry> entries) {
        Set<Long> internalProjectIds = internalProjectRepo.findAll().stream()
                .map(project -> project.getProjectId() != null ? project.getProjectId().longValue() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        
        return entries.stream()
                .filter(entry -> !entry.isBillable() && 
                        (entry.getProjectId() == null || !internalProjectIds.contains(entry.getProjectId())))
                .map(entry -> entry.getHoursWorked() != null ? entry.getHoursWorked() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal calculateInternalHours(List<TimeSheetEntry> entries) {
        Set<Long> internalProjectIds = internalProjectRepo.findAll().stream()
                .map(project -> project.getProjectId() != null ? project.getProjectId().longValue() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        
        return entries.stream()
                .filter(entry -> entry.getProjectId() != null && internalProjectIds.contains(entry.getProjectId()))
                .map(entry -> entry.getHoursWorked() != null ? entry.getHoursWorked() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal calculatePlannedHours(List<TimeSheet> timeSheets, LocalDate startDate, LocalDate endDate) {
        Set<LocalDate> holidayDates = timeSheets.stream()
                .filter(ts -> Boolean.TRUE.equals(ts.getAutoGenerated()))
                .map(TimeSheet::getWorkDate)
                .collect(Collectors.toSet());
        
        int workingDays = 0;
        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            if (cursor.getDayOfWeek() != DayOfWeek.SATURDAY && 
                cursor.getDayOfWeek() != DayOfWeek.SUNDAY && 
                !holidayDates.contains(cursor)) {
                workingDays++;
            }
            cursor = cursor.plusDays(1);
        }
        
        return BigDecimal.valueOf(workingDays * 8); // 8 hours per working day
    }

    private BigDecimal calculateUtilizationPercentage(BigDecimal actualHours, BigDecimal plannedHours) {
        if (plannedHours.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return actualHours.multiply(BigDecimal.valueOf(100))
                .divide(plannedHours, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateBillableRatio(BigDecimal billableHours, BigDecimal totalHours) {
        if (totalHours.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return billableHours.multiply(BigDecimal.valueOf(100))
                .divide(totalHours, 2, RoundingMode.HALF_UP);
    }

    private String calculateTrendSignal(List<TimeSheet> timeSheets, List<TimeSheetEntry> entries, UtilizationReportRequestDTO request) {
        // Simple trend calculation based on recent vs older periods
        LocalDate midPoint = request.getStartDate().plusDays(
                ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) / 2);
        
        List<TimeSheet> recentTimeSheets = timeSheets.stream()
                .filter(ts -> ts.getWorkDate().isAfter(midPoint))
                .collect(Collectors.toList());
        
        List<TimeSheet> olderTimeSheets = timeSheets.stream()
                .filter(ts -> ts.getWorkDate().isBefore(midPoint) || ts.getWorkDate().equals(midPoint))
                .collect(Collectors.toList());
        
        BigDecimal recentUtil = calculateResourceUtilizationTrend(recentTimeSheets, request);
        BigDecimal olderUtil = calculateResourceUtilizationTrend(olderTimeSheets, request);
        
        if (recentUtil.compareTo(olderUtil) > 5) {
            return "UP";
        } else if (recentUtil.compareTo(olderUtil) < -5) {
            return "DOWN";
        } else {
            return "STABLE";
        }
    }

    private BigDecimal calculateResourceUtilizationTrend(List<TimeSheet> timeSheets, UtilizationReportRequestDTO request) {
        if (timeSheets.isEmpty()) {
            return BigDecimal.ZERO;
        }
        
        BigDecimal totalHours = timeSheets.stream()
                .map(ts -> ts.getHoursWorked() != null ? ts.getHoursWorked() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        LocalDate startDate = timeSheets.stream()
                .map(TimeSheet::getWorkDate)
                .min(LocalDate::compareTo)
                .orElse(request.getStartDate());
        
        LocalDate endDate = timeSheets.stream()
                .map(TimeSheet::getWorkDate)
                .max(LocalDate::compareTo)
                .orElse(request.getEndDate());
        
        BigDecimal plannedHours = calculatePlannedHours(timeSheets, startDate, endDate);
        
        return calculateUtilizationPercentage(totalHours, plannedHours);
    }

    private Integer calculateConfidenceScore(List<TimeSheet> timeSheets) {
        if (timeSheets.isEmpty()) {
            return 0;
        }
        
        long approvedCount = timeSheets.stream()
                .filter(ts -> ts.getStatus() == TimeSheet.Status.APPROVED)
                .count();
        
        return (int) ((approvedCount * 100) / timeSheets.size());
    }

    private boolean isConsistentlyOverUtilized(List<TimeSheet> timeSheets, List<TimeSheetEntry> entries, UtilizationReportRequestDTO request) {
        return calculateConsecutiveWeeksOverThreshold(timeSheets, entries, request) >= 4;
    }

    private boolean isConsistentlyUnderUtilized(List<TimeSheet> timeSheets, List<TimeSheetEntry> entries, UtilizationReportRequestDTO request) {
        return calculateConsecutiveWeeksUnderThreshold(timeSheets, entries, request) >= 4;
    }

    private Integer calculateConsecutiveWeeksOverThreshold(List<TimeSheet> timeSheets, List<TimeSheetEntry> entries, UtilizationReportRequestDTO request) {
        // Group by week and calculate utilization
        Map<LocalDate, List<TimeSheet>> weeklyTimeSheets = timeSheets.stream()
                .collect(Collectors.groupingBy(ts -> ts.getWorkDate().with(DayOfWeek.MONDAY)));
        
        int consecutiveWeeks = 0;
        for (Map.Entry<LocalDate, List<TimeSheet>> entry : weeklyTimeSheets.entrySet()) {
            List<TimeSheetEntry> weekEntries = entries.stream()
                    .filter(e -> weeklyTimeSheets.get(entry.getKey()).stream()
                            .anyMatch(ts -> ts.equals(e.getTimeSheet())))
                    .collect(Collectors.toList());
            
            BigDecimal weekUtil = calculateResourceUtilizationTrend(entry.getValue(), request);
            double overThreshold = request.getOverUtilizationThreshold() != null ? request.getOverUtilizationThreshold().doubleValue() : 90.0;
            if (weekUtil.doubleValue() >= overThreshold) {
                consecutiveWeeks++;
            } else {
                break;
            }
        }
        
        return consecutiveWeeks;
    }

    private Integer calculateConsecutiveWeeksUnderThreshold(List<TimeSheet> timeSheets, List<TimeSheetEntry> entries, UtilizationReportRequestDTO request) {
        // Group by week and calculate utilization
        Map<LocalDate, List<TimeSheet>> weeklyTimeSheets = timeSheets.stream()
                .collect(Collectors.groupingBy(ts -> ts.getWorkDate().with(DayOfWeek.MONDAY)));
        
        int consecutiveWeeks = 0;
        for (Map.Entry<LocalDate, List<TimeSheet>> entry : weeklyTimeSheets.entrySet()) {
            List<TimeSheetEntry> weekEntries = entries.stream()
                    .filter(e -> weeklyTimeSheets.get(entry.getKey()).stream()
                            .anyMatch(ts -> ts.equals(e.getTimeSheet())))
                    .collect(Collectors.toList());
            
            BigDecimal weekUtil = calculateResourceUtilizationTrend(entry.getValue(), request);
            double underThreshold = request.getUnderUtilizationThreshold() != null ? request.getUnderUtilizationThreshold().doubleValue() : 60.0;
            if (weekUtil.doubleValue() <= underThreshold) {
                consecutiveWeeks++;
            } else {
                break;
            }
        }
        
        return consecutiveWeeks;
    }

    private List<String> generateResourceAlerts(Double utilization, String band, boolean consistentlyOver, boolean consistentlyUnder, UtilizationReportRequestDTO request) {
        List<String> alerts = new ArrayList<>();
        
        if (band.equals("CRITICAL")) {
            alerts.add("Critical under-utilization detected");
        }
        
        if (band.equals("HIGH")) {
            alerts.add("High utilization - risk of burnout");
        }
        
        if (consistentlyOver) {
            alerts.add("Sustained over-utilization pattern detected");
        }
        
        if (consistentlyUnder) {
            alerts.add("Sustained under-utilization pattern detected");
        }
        
        return alerts;
    }

    private Integer calculateApprovedDays(List<TimeSheet> timeSheets) {
        return (int) timeSheets.stream()
                .filter(ts -> ts.getStatus() == TimeSheet.Status.APPROVED)
                .count();
    }

    private Integer calculateWorkingDays(LocalDate startDate, LocalDate endDate) {
        int workingDays = 0;
        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            if (cursor.getDayOfWeek() != DayOfWeek.SATURDAY && cursor.getDayOfWeek() != DayOfWeek.SUNDAY) {
                workingDays++;
            }
            cursor = cursor.plusDays(1);
        }
        return workingDays;
    }

    private void calculateOverallMetrics(UtilizationReportResponseDTO response, List<ResourceUtilizationDTO> resourceUtilizations) {
        BigDecimal totalHours = resourceUtilizations.stream()
                .map(ResourceUtilizationDTO::getTotalHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal plannedHours = resourceUtilizations.stream()
                .map(ResourceUtilizationDTO::getPlannedHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal utilizationPercentage = calculateUtilizationPercentage(totalHours, plannedHours);
        
        BigDecimal billableHours = resourceUtilizations.stream()
                .map(ResourceUtilizationDTO::getBillableHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal nonBillableHours = resourceUtilizations.stream()
                .map(ResourceUtilizationDTO::getNonBillableHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal internalHours = resourceUtilizations.stream()
                .map(ResourceUtilizationDTO::getInternalHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        response.setTotalHours(totalHours);
        response.setPlannedHours(plannedHours);
        response.setUtilizationPercentage(utilizationPercentage);
        response.setBillableHours(billableHours);
        response.setNonBillableHours(nonBillableHours);
        response.setInternalHours(internalHours);
    }

    private Map<String, List<PortfolioTrendDTO>> buildTrends(List<TimeSheet> timeSheets, List<TimeSheetEntry> entries, UtilizationReportRequestDTO request) {
        // Reuse existing trend building logic from RMSTimeSheetService
        return rmsTimeSheetService.getSummary(request.getStartDate(), request.getEndDate(), null).getPortfolioTrends();
    }

    private List<UtilizationAlertDTO> buildAlerts(UtilizationReportResponseDTO response, UtilizationReportRequestDTO request) {
        List<UtilizationAlertDTO> alerts = new ArrayList<>();
        
        // Resource-level alerts
        if (response.getResourceUtilizations() != null) {
            for (ResourceUtilizationDTO resource : response.getResourceUtilizations()) {
                alerts.addAll(generateResourceAlerts(resource, request));
            }
        }
        
        return alerts;
    }

    private List<UtilizationAlertDTO> generateResourceAlerts(ResourceUtilizationDTO resource, UtilizationReportRequestDTO request) {
        List<UtilizationAlertDTO> alerts = new ArrayList<>();
        
        // Critical under-utilization
        if (resource.getUtilizationBand().equals("CRITICAL")) {
            alerts.add(UtilizationAlertDTO.builder()
                    .id("resource-critical-low-" + resource.getResourceId())
                    .type("UNDER_UTILIZATION")
                    .severity("CRITICAL")
                    .scope("RESOURCE")
                    .title("Critical Under-Utilization")
                    .message(String.format("%s has critical under-utilization at %.1f%%", 
                            resource.getResourceName(), resource.getUtilizationPercentage()))
                    .recommendation("Review workload allocation and project assignments")
                    .resourceId(resource.getResourceId())
                    .resourceName(resource.getResourceName())
                    .currentValue(resource.getUtilizationPercentage().doubleValue())
                    .thresholdValue(request.getUnderUtilizationThreshold() != null ? request.getUnderUtilizationThreshold().doubleValue() : 60.0)
                    .status("OPEN")
                    .createdDate(LocalDate.now())
                    .build());
        }
        
        // High utilization
        if (resource.getUtilizationBand().equals("HIGH")) {
            alerts.add(UtilizationAlertDTO.builder()
                    .id("resource-high-" + resource.getResourceId())
                    .type("OVER_UTILIZATION")
                    .severity("HIGH")
                    .scope("RESOURCE")
                    .title("High Utilization")
                    .message(String.format("%s has high utilization at %.1f%%", 
                            resource.getResourceName(), resource.getUtilizationPercentage()))
                    .recommendation("Monitor for burnout risk and consider workload redistribution")
                    .resourceId(resource.getResourceId())
                    .resourceName(resource.getResourceName())
                    .currentValue(resource.getUtilizationPercentage().doubleValue())
                    .thresholdValue(request.getOverUtilizationThreshold() != null ? request.getOverUtilizationThreshold().doubleValue() : 90.0)
                    .status("OPEN")
                    .createdDate(LocalDate.now())
                    .build());
        }
        
        // Sustained patterns
        if (resource.isConsistentlyOverUtilized()) {
            alerts.add(UtilizationAlertDTO.builder()
                    .id("resource-sustained-high-" + resource.getResourceId())
                    .type("PATTERN")
                    .severity("HIGH")
                    .scope("RESOURCE")
                    .title("Sustained Over-Utilization")
                    .message(String.format("%s shows sustained over-utilization for %d consecutive weeks", 
                            resource.getResourceName(), resource.getConsecutiveWeeksOverThreshold()))
                    .recommendation("Immediate action required to prevent burnout")
                    .resourceId(resource.getResourceId())
                    .resourceName(resource.getResourceName())
                    .consecutiveWeeks(resource.getConsecutiveWeeksOverThreshold())
                    .status("OPEN")
                    .createdDate(LocalDate.now())
                    .build());
        }
        
        if (resource.isConsistentlyUnderUtilized()) {
            alerts.add(UtilizationAlertDTO.builder()
                    .id("resource-sustained-low-" + resource.getResourceId())
                    .type("PATTERN")
                    .severity("MEDIUM")
                    .scope("RESOURCE")
                    .title("Sustained Under-Utilization")
                    .message(String.format("%s shows sustained under-utilization for %d consecutive weeks", 
                            resource.getResourceName(), resource.getConsecutiveWeeksUnderThreshold()))
                    .recommendation("Review project assignments and capacity planning")
                    .resourceId(resource.getResourceId())
                    .resourceName(resource.getResourceName())
                    .consecutiveWeeks(resource.getConsecutiveWeeksUnderThreshold())
                    .status("OPEN")
                    .createdDate(LocalDate.now())
                    .build());
        }
        
        return alerts;
    }

    private List<UtilizationPatternDTO> buildPatterns(UtilizationReportResponseDTO response, UtilizationReportRequestDTO request) {
        List<UtilizationPatternDTO> patterns = new ArrayList<>();
        
        // Resource-level patterns
        if (response.getResourceUtilizations() != null) {
            for (ResourceUtilizationDTO resource : response.getResourceUtilizations()) {
                patterns.addAll(generateResourcePatterns(resource, request));
            }
        }
        
        return patterns;
    }

    private List<UtilizationPatternDTO> generateResourcePatterns(ResourceUtilizationDTO resource, UtilizationReportRequestDTO request) {
        List<UtilizationPatternDTO> patterns = new ArrayList<>();
        
        if (resource.isConsistentlyOverUtilized()) {
            double overThreshold = request.getOverUtilizationThreshold() != null ? request.getOverUtilizationThreshold().doubleValue() : 90.0;
            patterns.add(UtilizationPatternDTO.builder()
                    .id("pattern-sustained-high-" + resource.getResourceId())
                    .patternType("SUSTAINED_HIGH")
                    .severity("HIGH")
                    .scope("RESOURCE")
                    .title("Sustained High Utilization Pattern")
                    .description(String.format("%s has maintained utilization above %.1f%% for %d consecutive weeks", 
                            resource.getResourceName(), overThreshold, resource.getConsecutiveWeeksOverThreshold()))
                    .impact("High risk of burnout, decreased quality, potential turnover")
                    .recommendation("Immediate workload redistribution and resource planning review")
                    .resourceId(resource.getResourceId())
                    .resourceName(resource.getResourceName())
                    .averageUtilization(resource.getUtilizationPercentage().doubleValue())
                    .durationWeeks(resource.getConsecutiveWeeksOverThreshold())
                    .weeksOverThreshold(resource.getConsecutiveWeeksOverThreshold())
                    .overThreshold(overThreshold)
                    .status("ACTIVE")
                    .detectedDate(LocalDate.now())
                    .lastUpdatedDate(LocalDate.now())
                    .build());
        }
        
        if (resource.isConsistentlyUnderUtilized()) {
            double underThreshold = request.getUnderUtilizationThreshold() != null ? request.getUnderUtilizationThreshold().doubleValue() : 60.0;
            patterns.add(UtilizationPatternDTO.builder()
                    .id("pattern-sustained-low-" + resource.getResourceId())
                    .patternType("SUSTAINED_LOW")
                    .severity("MEDIUM")
                    .scope("RESOURCE")
                    .title("Sustained Low Utilization Pattern")
                    .description(String.format("%s has maintained utilization below %.1f%% for %d consecutive weeks", 
                            resource.getResourceName(), underThreshold, resource.getConsecutiveWeeksUnderThreshold()))
                    .impact("Reduced revenue, inefficient resource allocation")
                    .recommendation("Review project pipeline and capacity utilization")
                    .resourceId(resource.getResourceId())
                    .resourceName(resource.getResourceName())
                    .averageUtilization(resource.getUtilizationPercentage().doubleValue())
                    .durationWeeks(resource.getConsecutiveWeeksUnderThreshold())
                    .weeksUnderThreshold(resource.getConsecutiveWeeksUnderThreshold())
                    .underThreshold(underThreshold)
                    .status("ACTIVE")
                    .detectedDate(LocalDate.now())
                    .lastUpdatedDate(LocalDate.now())
                    .build());
        }
        
        return patterns;
    }

    private void buildProjectReport(UtilizationReportResponseDTO response, List<TimeSheet> timeSheets, List<TimeSheetEntry> entries, UtilizationReportRequestDTO request) {
        // Group entries by project
        Map<Long, List<TimeSheetEntry>> entriesByProject = entries.stream()
                .filter(entry -> entry.getProjectId() != null)
                .collect(Collectors.groupingBy(TimeSheetEntry::getProjectId));

        List<ProjectUtilizationDTO> projectUtilizations = new ArrayList<>();
        
        for (Map.Entry<Long, List<TimeSheetEntry>> entry : entriesByProject.entrySet()) {
            Long projectId = entry.getKey();
            List<TimeSheetEntry> projectEntries = entry.getValue();
            
            // Get timesheets for this project
            List<TimeSheet> projectTimeSheets = projectEntries.stream()
                    .map(TimeSheetEntry::getTimeSheet)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
            
            ProjectUtilizationDTO projectUtil = calculateProjectUtilization(
                    projectId, 
                    projectEntries, 
                    projectTimeSheets, 
                    request);
            
            projectUtilizations.add(projectUtil);
        }
        
        // Sort by utilization percentage descending
        projectUtilizations.sort((a, b) -> b.getUtilizationPercentage().compareTo(a.getUtilizationPercentage()));
        
        response.setProjectUtilizations(projectUtilizations);
        response.setTotalProjects(projectUtilizations.size());
        
        // Calculate overall metrics if not already set
        if (response.getTotalHours() == null) {
            calculateOverallMetricsFromProjects(response, projectUtilizations);
        }
    }

    private void buildClientReport(UtilizationReportResponseDTO response, List<TimeSheet> timeSheets, List<TimeSheetEntry> entries, UtilizationReportRequestDTO request) {
        // Group entries by client (assuming client info comes from project or external service)
        Map<String, List<TimeSheetEntry>> entriesByClient = groupEntriesByClient(entries);
        
        List<ClientUtilizationDTO> clientUtilizations = new ArrayList<>();
        
        for (Map.Entry<String, List<TimeSheetEntry>> entry : entriesByClient.entrySet()) {
            String clientName = entry.getKey();
            List<TimeSheetEntry> clientEntries = entry.getValue();
            
            // Get timesheets for this client
            List<TimeSheet> clientTimeSheets = clientEntries.stream()
                    .map(TimeSheetEntry::getTimeSheet)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
            
            ClientUtilizationDTO clientUtil = calculateClientUtilization(
                    clientName, 
                    clientEntries, 
                    clientTimeSheets, 
                    request);
            
            clientUtilizations.add(clientUtil);
        }
        
        // Sort by utilization percentage descending
        clientUtilizations.sort((a, b) -> b.getUtilizationPercentage().compareTo(a.getUtilizationPercentage()));
        
        response.setClientUtilizations(clientUtilizations);
        response.setTotalClients(clientUtilizations.size());
    }

    private void buildRoleReport(UtilizationReportResponseDTO response, List<TimeSheet> timeSheets, List<TimeSheetEntry> entries, UtilizationReportRequestDTO request) {
        // Get resource roles
        Map<Long, String> resourceRoles = fetchResourceRoles();
        
        // Group entries by role
        Map<String, List<TimeSheetEntry>> entriesByRole = new HashMap<>();
        
        for (TimeSheetEntry entry : entries) {
            if (entry.getTimeSheet() != null && entry.getTimeSheet().getUserId() != null) {
                Long userId = entry.getTimeSheet().getUserId();
                String role = resourceRoles.getOrDefault(userId, "Unknown");
                entriesByRole.computeIfAbsent(role, k -> new ArrayList<>()).add(entry);
            }
        }
        
        List<RoleUtilizationDTO> roleUtilizations = new ArrayList<>();
        
        for (Map.Entry<String, List<TimeSheetEntry>> entry : entriesByRole.entrySet()) {
            String roleName = entry.getKey();
            List<TimeSheetEntry> roleEntries = entry.getValue();
            
            // Get timesheets for this role
            List<TimeSheet> roleTimeSheets = roleEntries.stream()
                    .map(TimeSheetEntry::getTimeSheet)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
            
            RoleUtilizationDTO roleUtil = calculateRoleUtilization(
                    roleName, 
                    roleEntries, 
                    roleTimeSheets, 
                    request);
            
            roleUtilizations.add(roleUtil);
        }
        
        // Sort by utilization percentage descending
        roleUtilizations.sort((a, b) -> b.getUtilizationPercentage().compareTo(a.getUtilizationPercentage()));
        
        response.setRoleUtilizations(roleUtilizations);
        response.setTotalRoles(roleUtilizations.size());
    }

    private void buildSummaryReport(UtilizationReportResponseDTO response, List<TimeSheet> timeSheets, List<TimeSheetEntry> entries, UtilizationReportRequestDTO request) {
        // Build all dimensions for summary report
        buildResourceReport(response, timeSheets, entries, request);
        buildProjectReport(response, timeSheets, entries, request);
        buildClientReport(response, timeSheets, entries, request);
        buildRoleReport(response, timeSheets, entries, request);
    }

    private Map<Long, String> fetchResourceNames() {
        return rmsTimeSheetService.fetchResourceNames();
    }

    private Map<Long, String> fetchResourceRoles() {
        return rmsTimeSheetService.fetchResourceRoles();
    }

    private ProjectUtilizationDTO calculateProjectUtilization(Long projectId, 
                                                           List<TimeSheetEntry> entries, 
                                                           List<TimeSheet> timeSheets, 
                                                           UtilizationReportRequestDTO request) {
        
        // Calculate hours
        BigDecimal totalHours = calculateTotalHours(timeSheets, entries);
        BigDecimal billableHours = calculateBillableHours(entries);
        BigDecimal plannedHours = calculatePlannedHours(timeSheets, request.getStartDate(), request.getEndDate());
        
        // Calculate utilization
        BigDecimal utilizationPercentage = calculateUtilizationPercentage(totalHours, plannedHours);
        BigDecimal billableRatio = calculateBillableRatio(billableHours, totalHours);
        
        // Determine utilization band
        String utilizationBand = determineUtilizationBand(utilizationPercentage.doubleValue(), request);
        
        // Calculate trend
        String trendSignal = calculateTrendSignal(timeSheets, entries, request);
        
        // Calculate confidence score
        Integer confidenceScore = calculateConfidenceScore(timeSheets);
        
        // Calculate resource metrics
        Set<Long> uniqueResources = timeSheets.stream()
                .map(TimeSheet::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        
        BigDecimal averageHoursPerResource = uniqueResources.isEmpty() ? BigDecimal.ZERO :
                totalHours.divide(BigDecimal.valueOf(uniqueResources.size()), 2, RoundingMode.HALF_UP);
        
        // Analyze patterns
        boolean consistentlyOverUtilized = isConsistentlyOverUtilized(timeSheets, entries, request);
        boolean consistentlyUnderUtilized = isConsistentlyUnderUtilized(timeSheets, entries, request);
        Integer consecutiveWeeksOverThreshold = calculateConsecutiveWeeksOverThreshold(timeSheets, entries, request);
        Integer consecutiveWeeksUnderThreshold = calculateConsecutiveWeeksUnderThreshold(timeSheets, entries, request);
        
        // Generate alerts
        List<String> alerts = generateProjectAlerts(utilizationPercentage.doubleValue(), utilizationBand, 
                consistentlyOverUtilized, consistentlyUnderUtilized, request);
        
        return ProjectUtilizationDTO.builder()
                .projectId(projectId)
                .projectName("Project " + projectId) // You might want to fetch actual project name
                .clientName("Client " + projectId) // You might want to fetch actual client name
                .totalHours(totalHours)
                .billableHours(billableHours)
                .plannedHours(plannedHours)
                .uniqueResources(uniqueResources.size())
                .averageHoursPerResource(averageHoursPerResource)
                .utilizationPercentage(utilizationPercentage)
                .billableRatio(billableRatio)
                .utilizationBand(utilizationBand)
                .trendSignal(trendSignal)
                .alerts(alerts)
                .consistentlyOverUtilized(consistentlyOverUtilized)
                .consistentlyUnderUtilized(consistentlyUnderUtilized)
                .consecutiveWeeksOverThreshold(consecutiveWeeksOverThreshold)
                .consecutiveWeeksUnderThreshold(consecutiveWeeksUnderThreshold)
                .confidenceScore(confidenceScore)
                .daysWithApprovedTimesheets(calculateApprovedDays(timeSheets))
                .totalWorkingDays(calculateWorkingDays(request.getStartDate(), request.getEndDate()))
                .build();
    }

    private ClientUtilizationDTO calculateClientUtilization(String clientName, 
                                                          List<TimeSheetEntry> entries, 
                                                          List<TimeSheet> timeSheets, 
                                                          UtilizationReportRequestDTO request) {
        
        // Calculate hours
        BigDecimal totalHours = calculateTotalHours(timeSheets, entries);
        BigDecimal billableHours = calculateBillableHours(entries);
        BigDecimal plannedHours = calculatePlannedHours(timeSheets, request.getStartDate(), request.getEndDate());
        
        // Calculate utilization
        BigDecimal utilizationPercentage = calculateUtilizationPercentage(totalHours, plannedHours);
        BigDecimal billableRatio = calculateBillableRatio(billableHours, totalHours);
        
        // Determine utilization band
        String utilizationBand = determineUtilizationBand(utilizationPercentage.doubleValue(), request);
        
        // Calculate trend
        String trendSignal = calculateTrendSignal(timeSheets, entries, request);
        
        // Calculate confidence score
        Integer confidenceScore = calculateConfidenceScore(timeSheets);
        
        // Calculate project and resource metrics
        Set<Long> uniqueProjects = entries.stream()
                .map(TimeSheetEntry::getProjectId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        
        Set<Long> uniqueResources = timeSheets.stream()
                .map(TimeSheet::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        
        BigDecimal averageHoursPerResource = uniqueResources.isEmpty() ? BigDecimal.ZERO :
                totalHours.divide(BigDecimal.valueOf(uniqueResources.size()), 2, RoundingMode.HALF_UP);
        
        BigDecimal averageHoursPerProject = uniqueProjects.isEmpty() ? BigDecimal.ZERO :
                totalHours.divide(BigDecimal.valueOf(uniqueProjects.size()), 2, RoundingMode.HALF_UP);
        
        // Analyze patterns
        boolean consistentlyOverUtilized = isConsistentlyOverUtilized(timeSheets, entries, request);
        boolean consistentlyUnderUtilized = isConsistentlyUnderUtilized(timeSheets, entries, request);
        Integer consecutiveWeeksOverThreshold = calculateConsecutiveWeeksOverThreshold(timeSheets, entries, request);
        Integer consecutiveWeeksUnderThreshold = calculateConsecutiveWeeksUnderThreshold(timeSheets, entries, request);
        
        // Generate alerts
        List<String> alerts = generateClientAlerts(utilizationPercentage.doubleValue(), utilizationBand, 
                consistentlyOverUtilized, consistentlyUnderUtilized, request);
        
        return ClientUtilizationDTO.builder()
                .clientName(clientName)
                .totalHours(totalHours)
                .billableHours(billableHours)
                .plannedHours(plannedHours)
                .uniqueProjects(uniqueProjects.size())
                .uniqueResources(uniqueResources.size())
                .averageHoursPerResource(averageHoursPerResource)
                .averageHoursPerProject(averageHoursPerProject)
                .utilizationPercentage(utilizationPercentage)
                .billableRatio(billableRatio)
                .utilizationBand(utilizationBand)
                .trendSignal(trendSignal)
                .alerts(alerts)
                .consistentlyOverUtilized(consistentlyOverUtilized)
                .consistentlyUnderUtilized(consistentlyUnderUtilized)
                .consecutiveWeeksOverThreshold(consecutiveWeeksOverThreshold)
                .consecutiveWeeksUnderThreshold(consecutiveWeeksUnderThreshold)
                .confidenceScore(confidenceScore)
                .daysWithApprovedTimesheets(calculateApprovedDays(timeSheets))
                .totalWorkingDays(calculateWorkingDays(request.getStartDate(), request.getEndDate()))
                .build();
    }

    private RoleUtilizationDTO calculateRoleUtilization(String roleName, 
                                                       List<TimeSheetEntry> entries, 
                                                       List<TimeSheet> timeSheets, 
                                                       UtilizationReportRequestDTO request) {
        
        // Calculate hours
        BigDecimal totalHours = calculateTotalHours(timeSheets, entries);
        BigDecimal billableHours = calculateBillableHours(entries);
        BigDecimal nonBillableHours = calculateNonBillableHours(entries);
        BigDecimal internalHours = calculateInternalHours(entries);
        BigDecimal plannedHours = calculatePlannedHours(timeSheets, request.getStartDate(), request.getEndDate());
        
        // Calculate utilization
        BigDecimal utilizationPercentage = calculateUtilizationPercentage(totalHours, plannedHours);
        BigDecimal billableRatio = calculateBillableRatio(billableHours, totalHours);
        
        // Determine utilization band
        String utilizationBand = determineUtilizationBand(utilizationPercentage.doubleValue(), request);
        
        // Calculate trend
        String trendSignal = calculateTrendSignal(timeSheets, entries, request);
        
        // Calculate confidence score
        Integer confidenceScore = calculateConfidenceScore(timeSheets);
        
        // Calculate resource metrics
        Set<Long> uniqueResources = timeSheets.stream()
                .map(TimeSheet::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        
        BigDecimal averageHoursPerResource = uniqueResources.isEmpty() ? BigDecimal.ZERO :
                totalHours.divide(BigDecimal.valueOf(uniqueResources.size()), 2, RoundingMode.HALF_UP);
        
        // Analyze patterns
        boolean consistentlyOverUtilized = isConsistentlyOverUtilized(timeSheets, entries, request);
        boolean consistentlyUnderUtilized = isConsistentlyUnderUtilized(timeSheets, entries, request);
        Integer consecutiveWeeksOverThreshold = calculateConsecutiveWeeksOverThreshold(timeSheets, entries, request);
        Integer consecutiveWeeksUnderThreshold = calculateConsecutiveWeeksUnderThreshold(timeSheets, entries, request);
        
        // Generate alerts
        List<String> alerts = generateRoleAlerts(utilizationPercentage.doubleValue(), utilizationBand, 
                consistentlyOverUtilized, consistentlyUnderUtilized, request);
        
        return RoleUtilizationDTO.builder()
                .roleName(roleName)
                .totalHours(totalHours)
                .billableHours(billableHours)
                .nonBillableHours(nonBillableHours)
                .internalHours(internalHours)
                .plannedHours(plannedHours)
                .uniqueResources(uniqueResources.size())
                .averageHoursPerResource(averageHoursPerResource)
                .utilizationPercentage(utilizationPercentage)
                .billableRatio(billableRatio)
                .utilizationBand(utilizationBand)
                .trendSignal(trendSignal)
                .alerts(alerts)
                .consistentlyOverUtilized(consistentlyOverUtilized)
                .consistentlyUnderUtilized(consistentlyUnderUtilized)
                .consecutiveWeeksOverThreshold(consecutiveWeeksOverThreshold)
                .consecutiveWeeksUnderThreshold(consecutiveWeeksUnderThreshold)
                .confidenceScore(confidenceScore)
                .daysWithApprovedTimesheets(calculateApprovedDays(timeSheets))
                .totalWorkingDays(calculateWorkingDays(request.getStartDate(), request.getEndDate()))
                .build();
    }

    private Map<String, List<TimeSheetEntry>> groupEntriesByClient(List<TimeSheetEntry> entries) {
        // This is a placeholder - you would need to implement actual client grouping logic
        // For now, group by project ID as a proxy for client
        Map<String, List<TimeSheetEntry>> entriesByClient = new HashMap<>();
        
        for (TimeSheetEntry entry : entries) {
            if (entry.getProjectId() != null) {
                String clientKey = "Client_" + entry.getProjectId(); // Placeholder
                entriesByClient.computeIfAbsent(clientKey, k -> new ArrayList<>()).add(entry);
            }
        }
        
        return entriesByClient;
    }

    private void calculateOverallMetricsFromProjects(UtilizationReportResponseDTO response, List<ProjectUtilizationDTO> projectUtilizations) {
        BigDecimal totalHours = projectUtilizations.stream()
                .map(ProjectUtilizationDTO::getTotalHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal plannedHours = projectUtilizations.stream()
                .map(ProjectUtilizationDTO::getPlannedHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal utilizationPercentage = calculateUtilizationPercentage(totalHours, plannedHours);
        
        BigDecimal billableHours = projectUtilizations.stream()
                .map(ProjectUtilizationDTO::getBillableHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal nonBillableHours = projectUtilizations.stream()
                .map(p -> p.getTotalHours().subtract(p.getBillableHours()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal internalHours = BigDecimal.ZERO; // Would need to be calculated from entries
        
        response.setTotalHours(totalHours);
        response.setPlannedHours(plannedHours);
        response.setUtilizationPercentage(utilizationPercentage);
        response.setBillableHours(billableHours);
        response.setNonBillableHours(nonBillableHours);
        response.setInternalHours(internalHours);
    }

    private List<String> generateProjectAlerts(Double utilization, String band, boolean consistentlyOver, boolean consistentlyUnder, UtilizationReportRequestDTO request) {
        List<String> alerts = new ArrayList<>();
        
        if (band.equals("CRITICAL")) {
            alerts.add("Critical under-utilization detected for project");
        }
        
        if (band.equals("HIGH")) {
            alerts.add("High utilization - project may be over-allocated");
        }
        
        if (consistentlyOver) {
            alerts.add("Sustained over-utilization pattern detected for project");
        }
        
        if (consistentlyUnder) {
            alerts.add("Sustained under-utilization pattern detected for project");
        }
        
        return alerts;
    }

    private List<String> generateClientAlerts(Double utilization, String band, boolean consistentlyOver, boolean consistentlyUnder, UtilizationReportRequestDTO request) {
        List<String> alerts = new ArrayList<>();
        
        if (band.equals("CRITICAL")) {
            alerts.add("Critical under-utilization detected for client");
        }
        
        if (band.equals("HIGH")) {
            alerts.add("High utilization - client projects may be over-allocated");
        }
        
        if (consistentlyOver) {
            alerts.add("Sustained over-utilization pattern detected for client");
        }
        
        if (consistentlyUnder) {
            alerts.add("Sustained under-utilization pattern detected for client");
        }
        
        return alerts;
    }

    private List<String> generateRoleAlerts(Double utilization, String band, boolean consistentlyOver, boolean consistentlyUnder, UtilizationReportRequestDTO request) {
        List<String> alerts = new ArrayList<>();
        
        if (band.equals("CRITICAL")) {
            alerts.add("Critical under-utilization detected for role");
        }
        
        if (band.equals("HIGH")) {
            alerts.add("High utilization - role may be over-allocated");
        }
        
        if (consistentlyOver) {
            alerts.add("Sustained over-utilization pattern detected for role");
        }
        
        if (consistentlyUnder) {
            alerts.add("Sustained under-utilization pattern detected for role");
        }
        
        return alerts;
    }

    // Original method for backward compatibility
    private UtilizationReportResponseDTO generateUtilizationReportOriginal(UtilizationReportRequestDTO request) {
        log.info("Generating utilization report (original logic): type={}, startDate={}, endDate={}", 
                request.getReportType(), request.getStartDate(), request.getEndDate());

        // Validate request
        validateRequest(request);

        // Get filtered timesheet data
        List<TimeSheet> filteredTimeSheets = getFilteredTimeSheets(request);
        List<TimeSheetEntry> filteredEntries = getFilteredEntries(filteredTimeSheets, request);

        // Build response
        UtilizationReportResponseDTO response = UtilizationReportResponseDTO.builder()
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .reportType(request.getReportType())
                .groupBy(request.getGroupBy())
                .approvedDataOnly(request.isApprovedOnly())
                .build();

        // Calculate based on report type
        switch (request.getReportType().toUpperCase()) {
            case "RESOURCE":
                buildResourceReport(response, filteredTimeSheets, filteredEntries, request);
                break;
            case "PROJECT":
                buildProjectReport(response, filteredTimeSheets, filteredEntries, request);
                break;
            case "CLIENT":
                buildClientReport(response, filteredTimeSheets, filteredEntries, request);
                break;
            case "ROLE":
                buildRoleReport(response, filteredTimeSheets, filteredEntries, request);
                break;
            case "SUMMARY":
            default:
                buildSummaryReport(response, filteredTimeSheets, filteredEntries, request);
                break;
        }

        // Add trends if requested
        if (request.isIncludeTrends()) {
            response.setTrends(buildTrends(filteredTimeSheets, filteredEntries, request));
        }

        // Add alerts if requested
        if (request.isIncludeAlerts()) {
            response.setAlerts(buildAlerts(response, request));
            response.setPatterns(buildPatterns(response, request));
        }

        return response;
    }

    // New method that includes ALL users from UMS
    private void buildResourceReportWithAllUsers(UtilizationReportResponseDTO response, 
                                               List<TimeSheet> timeSheets, 
                                               List<TimeSheetEntry> entries, 
                                               UtilizationReportRequestDTO request,
                                               Map<Long, String> resourceNames,
                                               Map<Long, String> resourceRoles,
                                               Map<Long, UserHoursDTO> hoursMap) {
        
        log.info("DEBUG: Building resource report with ALL users from UMS. Total users: {}", resourceNames.size());
        
        // Group by resource for users with timesheet data
        Map<Long, List<TimeSheet>> timeSheetsByResource = timeSheets.stream()
                .collect(Collectors.groupingBy(TimeSheet::getUserId));
        
        Map<Long, List<TimeSheetEntry>> entriesByResource = entries.stream()
                .collect(Collectors.groupingBy(entry -> entry.getTimeSheet().getUserId()));

        List<ResourceUtilizationDTO> resourceUtilizations = new ArrayList<>();
        
        // Process ALL users from UMS, including those with 0 hours
        for (Map.Entry<Long, String> userEntry : resourceNames.entrySet()) {
            Long resourceId = userEntry.getKey();
            if (resourceId == null) continue;
            
            List<TimeSheet> resourceTimeSheets = timeSheetsByResource.getOrDefault(resourceId, new ArrayList<>());
            List<TimeSheetEntry> resourceEntries = entriesByResource.getOrDefault(resourceId, new ArrayList<>());
            
            ResourceUtilizationDTO resourceUtil = calculateResourceUtilization(
                    resourceId, 
                    resourceNames.get(resourceId),
                    resourceRoles.get(resourceId),
                    resourceTimeSheets, 
                    resourceEntries, 
                    request);
            
            resourceUtilizations.add(resourceUtil);
        }
        
        log.info("DEBUG: Final resourceUtilizations size: {}", resourceUtilizations.size());
        
        // Sort by utilization percentage descending
        resourceUtilizations.sort((a, b) -> b.getUtilizationPercentage().compareTo(a.getUtilizationPercentage()));
        
        response.setResourceUtilizations(resourceUtilizations);
        response.setTotalResources(resourceUtilizations.size());
        
        // Calculate overall metrics
        calculateOverallMetrics(response, resourceUtilizations);
    }

    // New method for summary report with all users
    private void buildSummaryReportWithAllUsers(UtilizationReportResponseDTO response, 
                                              List<TimeSheet> timeSheets, 
                                              List<TimeSheetEntry> entries, 
                                              UtilizationReportRequestDTO request,
                                              Map<Long, String> resourceNames,
                                              Map<Long, String> resourceRoles,
                                              Map<Long, UserHoursDTO> hoursMap) {
        
        log.info("DEBUG: Building summary report with ALL users from UMS. Total users: {}", resourceNames.size());
        
        // Build resource report first (this will include all users)
        buildResourceReportWithAllUsers(response, timeSheets, entries, request, resourceNames, resourceRoles, hoursMap);
        
        // Build project, client, and role reports (these remain the same as they depend on actual data)
        buildProjectReport(response, timeSheets, entries, request);
        buildClientReport(response, timeSheets, entries, request);
        buildRoleReport(response, timeSheets, entries, request);
    }
}
