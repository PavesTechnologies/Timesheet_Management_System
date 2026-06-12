package com.intranet.service.RMS;

import com.intranet.dto.rms.*;
import com.intranet.entity.InternalProject;
import com.intranet.entity.TimeSheet;
import com.intranet.entity.TimeSheetEntry;
import com.intranet.repository.InternalProjectRepo;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.intranet.dto.rms.RMSProjectHoursDTO;
import com.intranet.dto.rms.TimePeriodDataDTO;
import com.intranet.dto.rms.TimeSheetSummaryResponseDTO;
import com.intranet.dto.UserHoursDTO;
import com.intranet.repository.TimeSheetEntryRepo;
import com.intranet.repository.TimeSheetRepo;
import com.intranet.service.DashboardService;
import com.intranet.util.cache.UserDirectoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestTemplate;

import java.time.DayOfWeek;
import java.time.YearMonth;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RMSTimeSheetService {

    private final TimeSheetRepo timeSheetRepository;
    private final TimeSheetEntryRepo entryRepository;
    private final InternalProjectRepo internalProjectRepo;
    private final RestTemplate restTemplate;
    private final DashboardService dashboardService;
    private final UserDirectoryService userDirectoryService;

    @Value("${UMS_API_BASE_URL}")
    private String umsUrl;

    public TimeSheetSummaryResponseDTO getSummary(
            LocalDate startDate,
            LocalDate endDate,
            jakarta.servlet.http.HttpServletRequest request) {

        // Handle backward compatibility - if request is null, use original logic
        if (request == null) {
            return getSummaryOriginal(startDate, endDate);
        }

        String authHeader = request.getHeader("Authorization");
        
        if (authHeader == null || authHeader.isBlank()) {
            throw new RuntimeException("Authorization header is required");
        }

        try {
            // Get all users from UMS using getUsersWithHours() approach
            List<Map<String, Object>> users = userDirectoryService.fetchAllUsers2(authHeader);
            System.out.println("DEBUG: Total users fetched from UMS: " + users.size());
            
            // Extract user IDs
            List<Long> userIds = users.stream()
                    .filter(user -> user.get("id") != null)
                    .map(user -> ((Number) user.get("id")).longValue())
                    .collect(Collectors.toList());
            
            System.out.println("DEBUG: Users with valid id: " + userIds.size());

            // Get hours summary for all users
            List<UserHoursDTO> usersHours = dashboardService.getUsersHoursSummary(userIds, startDate, endDate);

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

            Long totalUsers = (long) resourceNames.size();

            // Get timesheet data for calculations
            List<TimeSheet> timeSheets = timeSheetRepository.findByWorkDateBetweenWithWeekInfoAndEntries(startDate, endDate);
            List<TimeSheetEntry> entries = timeSheets.stream()
                    .flatMap(ts -> ts.getEntries().stream())
                    .collect(Collectors.toList());

            Set<Long> internalProjectIds = internalProjectRepo.findAll().stream()
                    .map(InternalProject::getProjectId)
                    .filter(pid -> pid != null)
                    .map(Long::valueOf)
                    .collect(Collectors.toSet());

            // Group by userId for users with timesheet data
            Map<Long, List<TimeSheet>> timeSheetsByUser = timeSheets.stream()
                    .collect(Collectors.groupingBy(TimeSheet::getUserId));

            Map<Long, List<TimeSheetEntry>> entriesByUser = entries.stream()
                    .collect(Collectors.groupingBy(entry -> entry.getTimeSheet().getUserId()));

            // Calculate overall totals
            BigDecimal totalHours = timeSheetRepository.getTotalHoursForAllUsers(startDate, endDate);
            BigDecimal billableHours = entryRepository.getBillableHoursForAllUsers(startDate, endDate);
            BigDecimal nonBillableHours = entryRepository.getNonBillableHoursForAllUsers(startDate, endDate);
            List<RMSProjectHoursDTO> projectHours = entryRepository.getProjectHoursForAllUsers(startDate, endDate);

            // Calculate averages
            BigDecimal averageTotalHours = totalUsers > 0 ? totalHours.divide(BigDecimal.valueOf(totalUsers), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            BigDecimal averageBillableHours = totalUsers > 0 ? billableHours.divide(BigDecimal.valueOf(totalUsers), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            BigDecimal averageNonBillableHours = totalUsers > 0 ? nonBillableHours.divide(BigDecimal.valueOf(totalUsers), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

            List<ResourceSummaryDTO> resourceSummaries = new ArrayList<>();
            
            // Process ALL users from UMS, including those with 0 hours
            for (Map.Entry<Long, String> userEntry : resourceNames.entrySet()) {
                Long uid = userEntry.getKey();
                if (uid == null) continue; // Skip users without valid ID
                
                List<TimeSheet> userTimeSheets = timeSheetsByUser.getOrDefault(uid, new ArrayList<>());
                List<TimeSheetEntry> userEntries = entriesByUser.getOrDefault(uid, new ArrayList<>());

                BigDecimal userBillableHours = sumEntryHours(userEntries, TimeSheetEntry::isBillable);
                BigDecimal internalHours = sumEntryHours(userEntries, entry2 -> entry2.getProjectId() != null && internalProjectIds.contains(entry2.getProjectId()));
                // Non-billable hours exclude internal project hours
                BigDecimal userNonBillableHours = sumEntryHours(userEntries, entry2 -> !entry2.isBillable() && !(entry2.getProjectId() != null && internalProjectIds.contains(entry2.getProjectId())));
                BigDecimal autoGeneratedHours = userTimeSheets.stream()
                        .filter(ts -> Boolean.TRUE.equals(ts.getAutoGenerated()))
                        .map(ts -> safe(ts.getHoursWorked()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal entryHours = sumEntryHours(userEntries, entry2 -> true);
                BigDecimal userTotalHours = entryHours.add(autoGeneratedHours);

                Map<LocalDate, BigDecimal> actualByDate = buildActualByDate(userEntries);
                Map<LocalDate, Integer> plannedByDate = buildPlannedByDate(startDate, endDate, userTimeSheets);
                BigDecimal plannedCapacity = plannedByDate.values().stream()
                        .map(BigDecimal::valueOf)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                double utilizationPercentage = plannedCapacity.compareTo(BigDecimal.ZERO) > 0 ? 
                    safePercentage(userTotalHours, plannedCapacity) : 0.0;
                int confidenceScore = userTimeSheets.isEmpty() ? 0 : calculateConfidenceScore(userTimeSheets);

                String hourlySplit = String.format("%.1f/%.1f/%.1f",
                        userBillableHours.doubleValue(),
                        userNonBillableHours.add(autoGeneratedHours).doubleValue(),
                        internalHours.doubleValue());

                String trendSignal = calculateTrend(utilizationPercentage);

                resourceSummaries.add(ResourceSummaryDTO.builder()
                        .userId(uid)
                        .name(resourceNames.getOrDefault(uid, "User " + uid))
                        .billableHours(userBillableHours)
                        .nonBillableHours(userNonBillableHours.add(autoGeneratedHours))
                        .internalHours(internalHours)
                        .totalHours(userTotalHours)
                        .plannedCapacity(plannedCapacity)
                        .utilizationPercentage(round(utilizationPercentage))
                        .confidenceScore(confidenceScore)
                        .resourceContext(resourceRoles.getOrDefault(uid, "Employee"))
                        .hourlySplit(hourlySplit)
                        .trendSignal(trendSignal)
                        .finalUtilPercentage(round(utilizationPercentage))
                        .build());
            }

            System.out.println("DEBUG: Final resourceSummaries size: " + resourceSummaries.size());

        // Overall KPIs
        BigDecimal overallBillable = resourceSummaries.stream()
                .map(ResourceSummaryDTO::getBillableHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal overallTotal = resourceSummaries.stream()
                .map(ResourceSummaryDTO::getTotalHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal overallPlanned = resourceSummaries.stream()
                .map(ResourceSummaryDTO::getPlannedCapacity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        double overallUtil = safePercentage(overallTotal, overallPlanned);
        BigDecimal overallBillableRatio = overallTotal.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO : overallBillable.multiply(BigDecimal.valueOf(100)).divide(overallTotal, 2, RoundingMode.HALF_UP);
        BigDecimal overallNonBillable = resourceSummaries.stream()
                .map(ResourceSummaryDTO::getNonBillableHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal overallInternal = resourceSummaries.stream()
                .map(ResourceSummaryDTO::getInternalHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        double billablePercentage = overallBillableRatio.doubleValue();
        double internalNonBillablePercentage = overallTotal.compareTo(BigDecimal.ZERO) == 0 ? 0.0 : overallInternal.multiply(BigDecimal.valueOf(100)).divide(overallTotal, 2, RoundingMode.HALF_UP).doubleValue();
        double otherNonBillablePercentage = overallTotal.compareTo(BigDecimal.ZERO) == 0 ? 0.0 : overallNonBillable.multiply(BigDecimal.valueOf(100)).divide(overallTotal, 2, RoundingMode.HALF_UP).doubleValue();
        double totalPercentage = 100.0;
        int overallConfidence = (int) resourceSummaries.stream().mapToInt(ResourceSummaryDTO::getConfidenceScore).average().orElse(50);

        List<KPIStatDTO> kpiStats = List.of(
                KPIStatDTO.builder()
                        .label("Utilization")
                        .value(String.format("%.2f%%", overallUtil))
                        .trend(calculateTrend(overallUtil))
                        .build(),
                KPIStatDTO.builder()
                        .label("Billable Ratio")
                        .value(String.format("%.2f%%", overallBillableRatio.doubleValue()))
                        .trend(calculateTrend(overallBillableRatio.doubleValue()))
                        .build(),
                KPIStatDTO.builder()
                        .label("Confidence Score")
                        .value(String.format("%d%%", overallConfidence))
                        .trend(overallConfidence >= 75 ? "up" : "down")
                        .build()
        );

        Map<String, List<PortfolioTrendDTO>> portfolioTrends = Map.of(
                "daily", buildDailyTrends(startDate, endDate, buildActualByDate(entries), buildPlannedByDate(startDate, endDate, timeSheets)),
                "weekly", buildWeeklyTrends(startDate, endDate, buildActualByDate(entries), buildPlannedByDate(startDate, endDate, timeSheets)),
                "monthly", buildMonthlyTrends(startDate, endDate, buildActualByDate(entries), buildPlannedByDate(startDate, endDate, timeSheets))
        );

                List<AlertDTO> alerts = buildAlerts(startDate, endDate, buildActualByDate(entries), buildPlannedByDate(startDate, endDate, timeSheets), overallUtil, overallConfidence);

        return TimeSheetSummaryResponseDTO.builder()
                .startDate(startDate)
                .endDate(endDate)
                .totalHours(totalHours)
                .billableHours(billableHours)
                .nonBillableHours(nonBillableHours)
                .projectHours(projectHours)
                .resourceSummaries(resourceSummaries)
                .kpiStats(kpiStats)
                .portfolioTrends(portfolioTrends)
                .alerts(alerts)
                .billablePercentage(billablePercentage)
                .internalNonBillablePercentage(internalNonBillablePercentage)
                .otherNonBillablePercentage(otherNonBillablePercentage)
                .totalPercentage(totalPercentage)
                .build();
        } catch (Exception e) {
            System.out.println("DEBUG: Exception occurred in getSummary: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to fetch RMS summary: " + e.getMessage(), e);
        }
    }

    // Original method for backward compatibility
    private TimeSheetSummaryResponseDTO getSummaryOriginal(LocalDate startDate, LocalDate endDate) {
        BigDecimal totalHours = timeSheetRepository.getTotalHoursForAllUsers(startDate, endDate);
        BigDecimal billableHours = entryRepository.getBillableHoursForAllUsers(startDate, endDate);
        BigDecimal nonBillableHours = entryRepository.getNonBillableHoursForAllUsers(startDate, endDate);
        List<RMSProjectHoursDTO> projectHours = entryRepository.getProjectHoursForAllUsers(startDate, endDate);
        
        // Fetch all users from UMS first to get accurate total count
        Map<Long, String> resourceNames = fetchResourceNames();
        Long totalUsers = (long) resourceNames.size();

        // Calculate averages
        BigDecimal averageTotalHours = totalUsers > 0 ? totalHours.divide(BigDecimal.valueOf(totalUsers), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal averageBillableHours = totalUsers > 0 ? billableHours.divide(BigDecimal.valueOf(totalUsers), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal averageNonBillableHours = totalUsers > 0 ? nonBillableHours.divide(BigDecimal.valueOf(totalUsers), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        List<TimeSheet> timeSheets = timeSheetRepository.findByWorkDateBetweenWithWeekInfoAndEntries(startDate, endDate);

        List<TimeSheetEntry> entries = timeSheets.stream()
                .flatMap(ts -> ts.getEntries().stream())
                .collect(Collectors.toList());

        Set<Long> internalProjectIds = internalProjectRepo.findAll().stream()
                .map(InternalProject::getProjectId)
                .filter(pid -> pid != null)
                .map(Long::valueOf)
                .collect(Collectors.toSet());

        // Fetch resource roles (resourceNames already fetched above)
        Map<Long, String> resourceRoles = fetchResourceRoles();
        
        // Group by userId if aggregating (only users with timesheet data)
        Map<Long, List<TimeSheet>> timeSheetsByUser = timeSheets.stream()
                .collect(Collectors.groupingBy(TimeSheet::getUserId));

        Map<Long, List<TimeSheetEntry>> entriesByUser = entries.stream()
                .collect(Collectors.groupingBy(entry -> entry.getTimeSheet().getUserId()));

        List<ResourceSummaryDTO> resourceSummaries = new ArrayList<>();
        
        // Process ALL users from UMS, not just those with timesheet data
        for (Map.Entry<Long, String> userEntry : resourceNames.entrySet()) {
            Long uid = userEntry.getKey();
            if (uid == null) continue; // Skip users without valid ID
            
            List<TimeSheet> userTimeSheets = timeSheetsByUser.getOrDefault(uid, new ArrayList<>());
            List<TimeSheetEntry> userEntries = entriesByUser.getOrDefault(uid, new ArrayList<>());

            BigDecimal userBillableHours = sumEntryHours(userEntries, TimeSheetEntry::isBillable);
            BigDecimal internalHours = sumEntryHours(userEntries, entry2 -> entry2.getProjectId() != null && internalProjectIds.contains(entry2.getProjectId()));
            // Non-billable hours exclude internal project hours
            BigDecimal userNonBillableHours = sumEntryHours(userEntries, entry2 -> !entry2.isBillable() && !(entry2.getProjectId() != null && internalProjectIds.contains(entry2.getProjectId())));
            BigDecimal autoGeneratedHours = userTimeSheets.stream()
                    .filter(ts -> Boolean.TRUE.equals(ts.getAutoGenerated()))
                    .map(ts -> safe(ts.getHoursWorked()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal entryHours = sumEntryHours(userEntries, entry2 -> true);
            BigDecimal userTotalHours = entryHours.add(autoGeneratedHours);

            Map<LocalDate, BigDecimal> actualByDate = buildActualByDate(userEntries);
            Map<LocalDate, Integer> plannedByDate = buildPlannedByDate(startDate, endDate, userTimeSheets);
            BigDecimal plannedCapacity = plannedByDate.values().stream()
                    .map(BigDecimal::valueOf)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            double utilizationPercentage = plannedCapacity.compareTo(BigDecimal.ZERO) > 0 ? 
                safePercentage(userTotalHours, plannedCapacity) : 0.0;
            int confidenceScore = userTimeSheets.isEmpty() ? 0 : calculateConfidenceScore(userTimeSheets);

            String hourlySplit = String.format("%.1f/%.1f/%.1f",
                    userBillableHours.doubleValue(),
                    userNonBillableHours.add(autoGeneratedHours).doubleValue(),
                    internalHours.doubleValue());

            String trendSignal = calculateTrend(utilizationPercentage);

            resourceSummaries.add(ResourceSummaryDTO.builder()
                    .userId(uid)
                    .name(resourceNames.getOrDefault(uid, "User " + uid))
                    .billableHours(userBillableHours)
                    .nonBillableHours(userNonBillableHours.add(autoGeneratedHours))
                    .internalHours(internalHours)
                    .totalHours(userTotalHours)
                    .plannedCapacity(plannedCapacity)
                    .utilizationPercentage(round(utilizationPercentage))
                    .confidenceScore(confidenceScore)
                    .resourceContext(resourceRoles.getOrDefault(uid, "Employee"))
                    .hourlySplit(hourlySplit)
                    .trendSignal(trendSignal)
                    .finalUtilPercentage(round(utilizationPercentage))
                    .build());
        }

        // Overall KPIs
        BigDecimal overallBillable = resourceSummaries.stream()
                .map(ResourceSummaryDTO::getBillableHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal overallTotal = resourceSummaries.stream()
                .map(ResourceSummaryDTO::getTotalHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal overallPlanned = resourceSummaries.stream()
                .map(ResourceSummaryDTO::getPlannedCapacity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        double overallUtil = safePercentage(overallTotal, overallPlanned);
        BigDecimal overallBillableRatio = overallTotal.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO : overallBillable.multiply(BigDecimal.valueOf(100)).divide(overallTotal, 2, RoundingMode.HALF_UP);
        BigDecimal overallNonBillable = resourceSummaries.stream()
                .map(ResourceSummaryDTO::getNonBillableHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal overallInternal = resourceSummaries.stream()
                .map(ResourceSummaryDTO::getInternalHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        double billablePercentage = overallBillableRatio.doubleValue();
        double internalNonBillablePercentage = overallTotal.compareTo(BigDecimal.ZERO) == 0 ? 0.0 : overallInternal.multiply(BigDecimal.valueOf(100)).divide(overallTotal, 2, RoundingMode.HALF_UP).doubleValue();
        double otherNonBillablePercentage = overallTotal.compareTo(BigDecimal.ZERO) == 0 ? 0.0 : overallNonBillable.multiply(BigDecimal.valueOf(100)).divide(overallTotal, 2, RoundingMode.HALF_UP).doubleValue();
        double totalPercentage = 100.0;
        int overallConfidence = (int) resourceSummaries.stream().mapToInt(ResourceSummaryDTO::getConfidenceScore).average().orElse(50);

        List<KPIStatDTO> kpiStats = List.of(
                KPIStatDTO.builder()
                        .label("Utilization")
                        .value(String.format("%.2f%%", overallUtil))
                        .trend(calculateTrend(overallUtil))
                        .build(),
                KPIStatDTO.builder()
                        .label("Billable Ratio")
                        .value(String.format("%.2f%%", overallBillableRatio.doubleValue()))
                        .trend(calculateTrend(overallBillableRatio.doubleValue()))
                        .build(),
                KPIStatDTO.builder()
                        .label("Confidence Score")
                        .value(String.format("%d%%", overallConfidence))
                        .trend(overallConfidence >= 75 ? "up" : "down")
                        .build()
        );

        Map<String, List<PortfolioTrendDTO>> portfolioTrends = Map.of(
                "daily", buildDailyTrends(startDate, endDate, buildActualByDate(entries), buildPlannedByDate(startDate, endDate, timeSheets)),
                "weekly", buildWeeklyTrends(startDate, endDate, buildActualByDate(entries), buildPlannedByDate(startDate, endDate, timeSheets)),
                "monthly", buildMonthlyTrends(startDate, endDate, buildActualByDate(entries), buildPlannedByDate(startDate, endDate, timeSheets))
        );

                List<AlertDTO> alerts = buildAlerts(startDate, endDate, buildActualByDate(entries), buildPlannedByDate(startDate, endDate, timeSheets), overallUtil, overallConfidence);

        return TimeSheetSummaryResponseDTO.builder()
                .startDate(startDate)
                .endDate(endDate)
                .totalHours(totalHours)
                .billableHours(billableHours)
                .nonBillableHours(nonBillableHours)
                .projectHours(projectHours)
                .resourceSummaries(resourceSummaries)
                .kpiStats(kpiStats)
                .portfolioTrends(portfolioTrends)
                .alerts(alerts)
                .billablePercentage(billablePercentage)
                .internalNonBillablePercentage(internalNonBillablePercentage)
                .otherNonBillablePercentage(otherNonBillablePercentage)
                .totalPercentage(totalPercentage)
                .build();
    }

    public List<ResourceSummaryMinimalDTO> getAllResourceSummaries(LocalDate startDate, LocalDate endDate) {
        return getSummary(startDate, endDate, null).getResourceSummaries().stream()
                .map(this::toMinimalResourceSummary)
                .collect(Collectors.toList());
    }

    public List<UserSummarySimplifiedDTO> getAllUserSummariesSimplified(LocalDate startDate, LocalDate endDate) {
        return getSummary(startDate, endDate, null).getResourceSummaries().stream()
                .map(this::toUserSummarySimplified)
                .collect(Collectors.toList());
    }

    private ResourceSummaryMinimalDTO toMinimalResourceSummary(ResourceSummaryDTO summary) {
        return ResourceSummaryMinimalDTO.builder()
                .resourceContext(summary.getResourceContext())
                .hourlySplit(summary.getHourlySplit())
                .trendSignal(summary.getTrendSignal())
                .finalUtilPercentage(summary.getFinalUtilPercentage())
                .build();
    }

    private UserSummarySimplifiedDTO toUserSummarySimplified(ResourceSummaryDTO summary) {
        return UserSummarySimplifiedDTO.builder()
                .userId(summary.getUserId())
                .name(summary.getName())
                .resourceContext(summary.getResourceContext())
                .hourlySplit(summary.getHourlySplit())
                .trendSignal(summary.getTrendSignal())
                .finalUtilPercentage(summary.getFinalUtilPercentage())
                .build();
    }

    private BigDecimal sumEntryHours(List<TimeSheetEntry> entries, Predicate<TimeSheetEntry> predicate) {
        return entries.stream()
                .filter(predicate)
                .map(entry -> safe(entry.getHoursWorked()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Map<LocalDate, BigDecimal> buildActualByDate(List<TimeSheetEntry> entries) {
        return entries.stream()
                .filter(entry -> entry.getTimeSheet() != null && entry.getTimeSheet().getWorkDate() != null)
                .collect(Collectors.groupingBy(
                        entry -> entry.getTimeSheet().getWorkDate(),
                        Collectors.mapping(entry -> safe(entry.getHoursWorked()), Collectors.reducing(BigDecimal.ZERO, BigDecimal::add))
                ));
    }

    private Map<LocalDate, Integer> buildPlannedByDate(LocalDate startDate, LocalDate endDate, List<TimeSheet> timeSheets) {
        Set<LocalDate> holidayDates = timeSheets.stream()
                .filter(ts -> Boolean.TRUE.equals(ts.getAutoGenerated()))
                .map(TimeSheet::getWorkDate)
                .collect(Collectors.toSet());

        Map<LocalDate, Integer> plannedByDate = new LinkedHashMap<>();
        LocalDate cursor = startDate;

        while (!cursor.isAfter(endDate)) {
            boolean weekend = cursor.getDayOfWeek() == DayOfWeek.SATURDAY || cursor.getDayOfWeek() == DayOfWeek.SUNDAY;
            int planned = 0;
            if (!weekend && !holidayDates.contains(cursor)) {
                planned = 8;
            }
            plannedByDate.put(cursor, planned);
            cursor = cursor.plusDays(1);
        }

        return plannedByDate;
    }

    private List<PortfolioTrendDTO> buildDailyTrends(LocalDate startDate,
                                                     LocalDate endDate,
                                                     Map<LocalDate, BigDecimal> actualByDate,
                                                     Map<LocalDate, Integer> plannedByDate) {
        List<PortfolioTrendDTO> trends = new ArrayList<>();
        LocalDate cursor = startDate;

        while (!cursor.isAfter(endDate)) {
            BigDecimal actual = actualByDate.getOrDefault(cursor, BigDecimal.ZERO);
            BigDecimal planned = BigDecimal.valueOf(plannedByDate.getOrDefault(cursor, 0));
            trends.add(PortfolioTrendDTO.builder()
                    .period(cursor.toString())
                    .actual(actual.intValue())
                    .planned(planned.intValue())
                    .util(round(safePercentage(actual, planned)))
                    .build());
            cursor = cursor.plusDays(1);
        }
        return trends;
    }

    private List<PortfolioTrendDTO> buildWeeklyTrends(LocalDate startDate,
                                                      LocalDate endDate,
                                                      Map<LocalDate, BigDecimal> actualByDate,
                                                      Map<LocalDate, Integer> plannedByDate) {
        Map<LocalDate, BigDecimal> actualByWeek = new LinkedHashMap<>();
        Map<LocalDate, BigDecimal> plannedByWeek = new LinkedHashMap<>();

        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            LocalDate weekKey = cursor.with(DayOfWeek.MONDAY);
            actualByWeek.put(weekKey, actualByWeek.getOrDefault(weekKey, BigDecimal.ZERO).add(actualByDate.getOrDefault(cursor, BigDecimal.ZERO)));
            plannedByWeek.put(weekKey, plannedByWeek.getOrDefault(weekKey, BigDecimal.ZERO).add(BigDecimal.valueOf(plannedByDate.getOrDefault(cursor, 0))));
            cursor = cursor.plusDays(1);
        }

        return actualByWeek.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(entry -> {
                    LocalDate weekStart = entry.getKey();
                    BigDecimal actual = entry.getValue();
                    BigDecimal planned = plannedByWeek.getOrDefault(weekStart, BigDecimal.ZERO);
                    return PortfolioTrendDTO.builder()
                            .period(String.format("Wk of %s", weekStart))
                            .actual(actual.intValue())
                            .planned(planned.intValue())
                            .util(round(safePercentage(actual, planned)))
                            .build();
                })
                .collect(Collectors.toList());
    }

    private List<PortfolioTrendDTO> buildMonthlyTrends(LocalDate startDate,
                                                       LocalDate endDate,
                                                       Map<LocalDate, BigDecimal> actualByDate,
                                                       Map<LocalDate, Integer> plannedByDate) {
        Map<YearMonth, BigDecimal> actualByMonth = new LinkedHashMap<>();
        Map<YearMonth, BigDecimal> plannedByMonth = new LinkedHashMap<>();

        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            YearMonth monthKey = YearMonth.from(cursor);
            actualByMonth.put(monthKey, actualByMonth.getOrDefault(monthKey, BigDecimal.ZERO).add(actualByDate.getOrDefault(cursor, BigDecimal.ZERO)));
            plannedByMonth.put(monthKey, plannedByMonth.getOrDefault(monthKey, BigDecimal.ZERO).add(BigDecimal.valueOf(plannedByDate.getOrDefault(cursor, 0))));
            cursor = cursor.plusDays(1);
        }

        return actualByMonth.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(entry -> {
                    YearMonth monthKey = entry.getKey();
                    BigDecimal actual = entry.getValue();
                    BigDecimal planned = plannedByMonth.getOrDefault(monthKey, BigDecimal.ZERO);
                    return PortfolioTrendDTO.builder()
                            .period(monthKey.getMonth().name() + " " + monthKey.getYear())
                            .actual(actual.intValue())
                            .planned(planned.intValue())
                            .util(round(safePercentage(actual, planned)))
                            .build();
                })
                .collect(Collectors.toList());
    }

    
    private List<AlertDTO> buildAlerts(LocalDate startDate,
                                       LocalDate endDate,
                                       Map<LocalDate, BigDecimal> actualByDate,
                                       Map<LocalDate, Integer> plannedByDate,
                                       double utilizationPercentage,
                                       int confidenceScore) {
        List<AlertDTO> alerts = new ArrayList<>();

        if (hasFourConsecutiveCriticalWeeks(startDate, endDate, actualByDate, plannedByDate)) {
            alerts.add(AlertDTO.builder()
                    .id("rms-breach-1")
                    .scope("weekly")
                    .message("Detected 4 consecutive critical weeks with under-performance.")
                    .severity("high")
                    .status("open")
                    .build());
        }

        if (utilizationPercentage < 60) {
            alerts.add(AlertDTO.builder()
                    .id("rms-utilization-low")
                    .scope("resource")
                    .message("Overall utilization is below 60%, red zone for the business.")
                    .severity("medium")
                    .status("open")
                    .build());
        }

        if (confidenceScore < 50) {
            alerts.add(AlertDTO.builder()
                    .id("rms-confidence-low")
                    .scope("approval")
                    .message("Low confidence due to draft timesheets in the selected period.")
                    .severity("medium")
                    .status("open")
                    .build());
        }

        return alerts;
    }

    private boolean hasFourConsecutiveCriticalWeeks(LocalDate startDate,
                                                    LocalDate endDate,
                                                    Map<LocalDate, BigDecimal> actualByDate,
                                                    Map<LocalDate, Integer> plannedByDate) {
        List<LocalDate> orderedWeeks = new ArrayList<>(buildWeeklyKeys(startDate, endDate));
        int consecutive = 0;

        for (LocalDate weekStart : orderedWeeks) {
            BigDecimal actual = actualByDate.entrySet().stream()
                    .filter(entry -> weekStart.equals(entry.getKey().with(DayOfWeek.MONDAY)))
                    .map(Map.Entry::getValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal planned = plannedByDate.entrySet().stream()
                    .filter(entry -> weekStart.equals(entry.getKey().with(DayOfWeek.MONDAY)))
                    .map(entry -> BigDecimal.valueOf(entry.getValue()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            double util = safePercentage(actual, planned);
            if (util < 70) {
                consecutive++;
                if (consecutive >= 4) {
                    return true;
                }
            } else {
                consecutive = 0;
            }
        }
        return false;
    }

    private List<LocalDate> buildWeeklyKeys(LocalDate startDate, LocalDate endDate) {
        Map<LocalDate, Boolean> weekMap = new LinkedHashMap<>();
        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            weekMap.put(cursor.with(DayOfWeek.MONDAY), Boolean.TRUE);
            cursor = cursor.plusDays(1);
        }
        return new ArrayList<>(weekMap.keySet()).stream().sorted().toList();
    }

    private int calculateConfidenceScore(List<TimeSheet> timeSheets) {
        BigDecimal approved = timeSheets.stream()
                .filter(ts -> ts.getStatus() == TimeSheet.Status.APPROVED)
                .map(ts -> safe(ts.getHoursWorked()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal draft = timeSheets.stream()
                .filter(ts -> ts.getStatus() == TimeSheet.Status.DRAFT)
                .map(ts -> safe(ts.getHoursWorked()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal total = approved.add(draft);
        if (total.compareTo(BigDecimal.ZERO) == 0) {
            return 50;
        }
        return approved.multiply(BigDecimal.valueOf(100))
                .divide(total, 0, RoundingMode.HALF_UP)
                .intValue();
    }

    private double safePercentage(BigDecimal actual, BigDecimal planned) {
        if (planned == null || planned.compareTo(BigDecimal.ZERO) == 0) {
            return 0.0;
        }
        return actual.multiply(BigDecimal.valueOf(100))
                .divide(planned, 2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String calculateTrend(double value) {
        if (value >= 80) {
            return "up";
        }
        if (value >= 50) {
            return "flat";
        }
        return "down";
    }

    private String formatDouble(double value) {
        return String.format("%.2f", value);
    }

    private double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    public Map<Long, String> fetchResourceNames() {
        try {
            String url = umsUrl + "/resource/get-all-resources";
            ResourceListResponse response = restTemplate.getForObject(url, ResourceListResponse.class);
            if (response != null && response.isSuccess() && response.getData() != null) {
                return response.getData().stream()
                        .collect(Collectors.toMap(ResourceDTO::getResourceId, ResourceDTO::getResourceName));
            }
        } catch (Exception e) {
            // Log error or handle
        }
        return new HashMap<>();
    }

    public Map<Long, String> fetchResourceRoles() {
        try {
            String url = umsUrl + "/resource/get-all-resources";
            ResourceListResponse response = restTemplate.getForObject(url, ResourceListResponse.class);
            if (response != null && response.isSuccess() && response.getData() != null) {
                return response.getData().stream()
                        .collect(Collectors.toMap(ResourceDTO::getResourceId, ResourceDTO::getResourceRole));
            }
        } catch (Exception e) {
            // Log error or handle
        }
        return new HashMap<>();
    }

    public MonthlySummaryResponseDTO getMonthlySummary(LocalDate startDate, LocalDate endDate) {
        // Get the base summary
        TimeSheetSummaryResponseDTO baseSummary = getSummary(startDate, endDate, null);

        // Format the period label
        String periodLabel = String.format("%s %d PATTERN",
            startDate.getMonth().toString(),
            startDate.getYear());

        // Calculate total hours
        BigDecimal actualHours = baseSummary.getResourceSummaries().stream()
                .map(ResourceSummaryDTO::getTotalHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal plannedHours = baseSummary.getResourceSummaries().stream()
                .map(ResourceSummaryDTO::getPlannedCapacity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal overallUtilization = plannedHours.compareTo(BigDecimal.ZERO) == 0 ?
                BigDecimal.ZERO :
                actualHours.multiply(BigDecimal.valueOf(100)).divide(plannedHours, 2, RoundingMode.HALF_UP);

        // Calculate total hours by category
        BigDecimal totalBillableHours = baseSummary.getResourceSummaries().stream()
                .map(ResourceSummaryDTO::getBillableHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalNonBillableHours = baseSummary.getResourceSummaries().stream()
                .map(ResourceSummaryDTO::getNonBillableHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalInternalHours = baseSummary.getResourceSummaries().stream()
                .map(ResourceSummaryDTO::getInternalHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Calculate average confidence score
        Integer averageConfidenceScore = (int) baseSummary.getResourceSummaries().stream()
                .mapToInt(ResourceSummaryDTO::getConfidenceScore)
                .average()
                .orElse(50);

        return MonthlySummaryResponseDTO.builder()
                .periodLabel(periodLabel)
                .startDate(startDate.toString())
                .endDate(endDate.toString())
                .overallUtilizationPercentage(overallUtilization)
                .actualHours(actualHours)
                .plannedHours(plannedHours)
                .resourceSummaries(baseSummary.getResourceSummaries())
                .kpiStats(baseSummary.getKpiStats())
                .portfolioTrends(baseSummary.getPortfolioTrends())
                .billablePercentage(baseSummary.getBillablePercentage())
                .internalNonBillablePercentage(baseSummary.getInternalNonBillablePercentage())
                .otherNonBillablePercentage(baseSummary.getOtherNonBillablePercentage())
                .totalPercentage(baseSummary.getTotalPercentage())
                .totalResources(baseSummary.getResourceSummaries().size())
                .totalBillableHours(totalBillableHours)
                .totalNonBillableHours(totalNonBillableHours)
                .totalInternalHours(totalInternalHours)
                .averageConfidenceScore(averageConfidenceScore)
                .build();
    }

    private TimePeriodDataDTO createTimePeriodData(String period, BigDecimal actual, BigDecimal planned) {
        TimePeriodDataDTO data = new TimePeriodDataDTO();
        data.setPeriod(period);
        data.setActual(actual);
        data.setPlanned(planned);

        // Calculate utilization as percentage
        BigDecimal util = planned.compareTo(BigDecimal.ZERO) > 0
            ? actual.divide(planned, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
            : BigDecimal.ZERO;
        data.setUtil(util);

        return data;
    }

    private List<TimePeriodDataDTO> calculateDailyData(LocalDate startDate, LocalDate endDate) {
        List<Object[]> dailyData = timeSheetRepository.getDailyHoursBreakdown(startDate, endDate);
        List<TimePeriodDataDTO> result = new ArrayList<>();

        // Default planned hours per day (8 hours)
        BigDecimal plannedHours = BigDecimal.valueOf(8);

        for (Object[] row : dailyData) {
            String period = (String) row[0];
            BigDecimal actual = (BigDecimal) row[1];
            result.add(createTimePeriodData(period, actual, plannedHours));
        }

        return result;
    }

    private List<TimePeriodDataDTO> calculateWeeklyData(LocalDate startDate, LocalDate endDate) {
        List<Object[]> weeklyData = timeSheetRepository.getWeeklyHoursBreakdown(startDate, endDate);
        List<TimePeriodDataDTO> result = new ArrayList<>();

        // Default planned hours per week (40 hours)
        BigDecimal plannedHours = BigDecimal.valueOf(40);

        for (Object[] row : weeklyData) {
            String period = (String) row[0];
            BigDecimal actual = (BigDecimal) row[1];
            result.add(createTimePeriodData(period, actual, plannedHours));
        }

        return result;
    }

    private List<TimePeriodDataDTO> calculateMonthlyData(LocalDate startDate, LocalDate endDate) {
        List<Object[]> monthlyData = timeSheetRepository.getMonthlyHoursBreakdown(startDate, endDate);
        List<TimePeriodDataDTO> result = new ArrayList<>();

        // Default planned hours per month (approximately 160 hours)
        BigDecimal plannedHours = BigDecimal.valueOf(160);

        for (Object[] row : monthlyData) {
            String period = (String) row[0];
            BigDecimal actual = (BigDecimal) row[1];
            result.add(createTimePeriodData(period, actual, plannedHours));
        }

        return result;
    }

    private BigDecimal calculateUtilization(BigDecimal totalHours, Long totalUsers, LocalDate startDate, LocalDate endDate) {
        if (totalUsers == 0 || totalHours.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        // Calculate working days between start and end date (excluding weekends)
        long workingDays = calculateWorkingDays(startDate, endDate);
        // Standard 8 hours per working day
        BigDecimal totalPlannedHours = BigDecimal.valueOf(workingDays * 8 * totalUsers);

        if (totalPlannedHours.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        return totalHours.divide(totalPlannedHours, 4, RoundingMode.HALF_UP)
                       .multiply(BigDecimal.valueOf(100));
    }

    private BigDecimal calculateBillableRatio(BigDecimal billableHours, BigDecimal totalHours) {
        if (totalHours.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        return billableHours.divide(totalHours, 4, RoundingMode.HALF_UP)
                          .multiply(BigDecimal.valueOf(100));
    }

    private BigDecimal calculateConfidenceScore(BigDecimal totalHours, Long totalUsers, LocalDate startDate, LocalDate endDate) {
        if (totalUsers == 0 || totalHours.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        // Confidence score based on data completeness
        // Factors: total users, average hours per user, data consistency
        BigDecimal avgHoursPerUser = totalHours.divide(BigDecimal.valueOf(totalUsers), 2, RoundingMode.HALF_UP);

        // Base score: 50 points
        BigDecimal score = BigDecimal.valueOf(50);

        // Calculate weekly average for confidence scoring
        long weeks = calculateWeeksBetween(startDate, endDate);
        BigDecimal weeklyAvgHours = weeks > 0 ? avgHoursPerUser.divide(BigDecimal.valueOf(weeks), 2, RoundingMode.HALF_UP) : avgHoursPerUser;

        // Add points for good average (35-45 hours per week = standard full-time work)
        if (weeklyAvgHours.compareTo(BigDecimal.valueOf(35)) >= 0 &&
            weeklyAvgHours.compareTo(BigDecimal.valueOf(45)) <= 0) {
            score = score.add(BigDecimal.valueOf(30));
        } else if (weeklyAvgHours.compareTo(BigDecimal.valueOf(20)) >= 0) {
            score = score.add(BigDecimal.valueOf(15));
        }

        // Add points for user count (more users = more confidence)
        if (totalUsers >= 10) {
            score = score.add(BigDecimal.valueOf(20));
        } else if (totalUsers >= 5) {
            score = score.add(BigDecimal.valueOf(10));
        }

        // Cap at 100
        return score.min(BigDecimal.valueOf(100));
    }

    private long calculateWorkingDays(LocalDate startDate, LocalDate endDate) {
        long workingDays = 0;
        LocalDate current = startDate;

        while (!current.isAfter(endDate)) {
            int dayOfWeek = current.getDayOfWeek().getValue();
            // Monday=1, Friday=5 (exclude Saturday=6, Sunday=7)
            if (dayOfWeek <= 5) {
                workingDays++;
            }
            current = current.plusDays(1);
        }

        return workingDays;
    }

    private long calculateWeeksBetween(LocalDate startDate, LocalDate endDate) {
        if (startDate.isAfter(endDate)) {
            return 0;
        }

        long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate);
        return Math.max(1, (daysBetween / 7) + 1); // At least 1 week, partial weeks count as full weeks
    }
}
