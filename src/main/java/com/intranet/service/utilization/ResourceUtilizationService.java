package com.intranet.service.utilization;

import com.intranet.dto.rms.ResourceUtilizationDTO;
import com.intranet.dto.rms.UtilizationPageResponseDTO;
import com.intranet.entity.InternalProject;
import com.intranet.entity.TimeSheet;
import com.intranet.entity.TimeSheetEntry;
import com.intranet.repository.InternalProjectRepo;
import com.intranet.repository.TimeSheetRepo;
import com.intranet.util.UtilizationCalculationUtils;
import com.intranet.util.cache.UserDirectoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Paginated resource utilization with sort, filter, and search support.
 * Loads internalProjectIds ONCE per request — not per resource.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ResourceUtilizationService {

    private final TimeSheetRepo timeSheetRepository;
    private final InternalProjectRepo internalProjectRepo;
    private final UserDirectoryService userDirectoryService;

    public UtilizationPageResponseDTO<ResourceUtilizationDTO> getResourcesPage(
            LocalDate startDate, LocalDate endDate,
            int page, int size,
            String sortBy, String sortDir,
            String search,
            boolean approvedOnly,
            double overThreshold, double underThreshold,
            String authHeader) {

        List<ResourceUtilizationDTO> all =
                buildAll(startDate, endDate, approvedOnly, overThreshold, underThreshold, authHeader);

        if (search != null && !search.isBlank()) {
            String term = search.trim().toLowerCase();
            all = all.stream()
                    .filter(r -> r.getResourceName() != null
                            && r.getResourceName().toLowerCase().contains(term))
                    .collect(Collectors.toList());
        }

        all = applySorting(all, sortBy, sortDir);
        return paginate(all, page, size);
    }

    /** Package-visible — used by UtilizationAnalyticsService to avoid duplicate data load. */
    List<ResourceUtilizationDTO> buildAll(LocalDate startDate, LocalDate endDate,
                                          boolean approvedOnly,
                                          double overThreshold, double underThreshold,
                                          String authHeader) {

        List<Map<String, Object>> users = userDirectoryService.fetchAllUsers2(authHeader);
        Map<Long, String> resourceNames = users.stream()
                .filter(u -> u.get("id") != null)
                .collect(Collectors.toMap(
                        u -> ((Number) u.get("id")).longValue(),
                        u -> u.get("name") != null ? (String) u.get("name") : "Unknown User"
                ));
        Map<Long, String> resourceRoles = users.stream()
                .filter(u -> u.get("id") != null)
                .collect(Collectors.toMap(
                        u -> ((Number) u.get("id")).longValue(),
                        u -> u.get("designation") != null ? (String) u.get("designation") : "Employee"
                ));

        List<TimeSheet> timeSheets =
                timeSheetRepository.findByWorkDateBetweenWithWeekInfoAndEntries(startDate, endDate);
        if (approvedOnly) {
            timeSheets = timeSheets.stream()
                    .filter(ts -> ts.getStatus() == TimeSheet.Status.APPROVED)
                    .collect(Collectors.toList());
        }

        List<TimeSheetEntry> entries = timeSheets.stream()
                .flatMap(ts -> ts.getEntries().stream())
                .collect(Collectors.toList());

        // Load internal project IDs ONCE
        Set<Long> internalProjectIds = internalProjectRepo.findAll().stream()
                .map(InternalProject::getProjectId)
                .filter(Objects::nonNull)
                .map(Long::valueOf)
                .collect(Collectors.toSet());

        Map<Long, List<TimeSheet>> sheetsByUser =
                timeSheets.stream().collect(Collectors.groupingBy(TimeSheet::getUserId));
        Map<Long, List<TimeSheetEntry>> entriesByUser = entries.stream()
                .collect(Collectors.groupingBy(e -> e.getTimeSheet().getUserId()));

        int totalWorkingDays = UtilizationCalculationUtils.calculateWorkingDays(startDate, endDate);

        List<ResourceUtilizationDTO> result = new ArrayList<>();

        for (Map.Entry<Long, String> userEntry : resourceNames.entrySet()) {
            Long uid = userEntry.getKey();
            if (uid == null) continue;

            List<TimeSheet> userSheets = sheetsByUser.getOrDefault(uid, Collections.emptyList());
            List<TimeSheetEntry> userEntries = entriesByUser.getOrDefault(uid, Collections.emptyList());

            BigDecimal totalHours = UtilizationCalculationUtils.calculateTotalHours(userSheets, userEntries);
            BigDecimal billable = UtilizationCalculationUtils.calculateBillableHours(userEntries);
            BigDecimal nonBillable = UtilizationCalculationUtils.calculateNonBillableHours(userEntries, internalProjectIds);
            BigDecimal internal = UtilizationCalculationUtils.calculateInternalHours(userEntries, internalProjectIds);
            BigDecimal planned = UtilizationCalculationUtils.calculatePlannedHours(userSheets, startDate, endDate);

            BigDecimal utilPct = UtilizationCalculationUtils.calculateUtilizationPercentage(totalHours, planned);
            BigDecimal billableRatio = UtilizationCalculationUtils.calculateBillableRatio(billable, totalHours);
            String band = UtilizationCalculationUtils.determineUtilizationBand(
                    utilPct.doubleValue(), overThreshold, underThreshold);
            String trend = UtilizationCalculationUtils.calculateTrendSignal(userSheets, startDate, endDate);
            int confidence = UtilizationCalculationUtils.calculateConfidenceScore(userSheets);

            boolean overUtil = UtilizationCalculationUtils.isConsistentlyOverUtilized(userSheets, overThreshold);
            boolean underUtil = UtilizationCalculationUtils.isConsistentlyUnderUtilized(userSheets, underThreshold);
            int weeksOver = UtilizationCalculationUtils.calculateConsecutiveWeeksOverThreshold(userSheets, overThreshold);
            int weeksUnder = UtilizationCalculationUtils.calculateConsecutiveWeeksUnderThreshold(userSheets, underThreshold);

            List<String> alerts = UtilizationCalculationUtils.buildAlertMessages(
                    band, overUtil, underUtil, " for " + userEntry.getValue());

            result.add(ResourceUtilizationDTO.builder()
                    .resourceId(uid)
                    .resourceName(userEntry.getValue())
                    .role(resourceRoles.getOrDefault(uid, "Employee"))
                    .totalHours(totalHours)
                    .billableHours(billable)
                    .nonBillableHours(nonBillable)
                    .internalHours(internal)
                    .plannedHours(planned)
                    .utilizationPercentage(utilPct)
                    .billableRatio(billableRatio)
                    .utilizationBand(band)
                    .trendSignal(trend)
                    .alerts(alerts)
                    .consistentlyOverUtilized(overUtil)
                    .consistentlyUnderUtilized(underUtil)
                    .consecutiveWeeksOverThreshold(weeksOver)
                    .consecutiveWeeksUnderThreshold(weeksUnder)
                    .confidenceScore(confidence)
                    .daysWithApprovedTimesheets(UtilizationCalculationUtils.calculateApprovedDays(userSheets))
                    .totalWorkingDays(totalWorkingDays)
                    .build());
        }

        return result;
    }

    private List<ResourceUtilizationDTO> applySorting(List<ResourceUtilizationDTO> list,
                                                       String sortBy, String sortDir) {
        if (sortBy == null || sortBy.isBlank()) return list;
        Comparator<ResourceUtilizationDTO> cmp = switch (sortBy.toLowerCase()) {
            case "resourcename", "name" ->
                    Comparator.comparing(r -> r.getResourceName() != null ? r.getResourceName() : "");
            case "utilizationpercentage", "utilization" ->
                    Comparator.comparing(r -> r.getUtilizationPercentage() != null
                            ? r.getUtilizationPercentage() : BigDecimal.ZERO);
            case "billablehours" ->
                    Comparator.comparing(r -> r.getBillableHours() != null
                            ? r.getBillableHours() : BigDecimal.ZERO);
            case "totalhours" ->
                    Comparator.comparing(r -> r.getTotalHours() != null
                            ? r.getTotalHours() : BigDecimal.ZERO);
            case "confidencescore" ->
                    Comparator.comparingInt(r -> r.getConfidenceScore() != null
                            ? r.getConfidenceScore() : 0);
            case "utilizationband", "band" ->
                    Comparator.comparing(r -> r.getUtilizationBand() != null ? r.getUtilizationBand() : "");
            default ->
                    Comparator.comparing(r -> r.getUtilizationPercentage() != null
                            ? r.getUtilizationPercentage() : BigDecimal.ZERO);
        };
        if ("asc".equalsIgnoreCase(sortDir)) return list.stream().sorted(cmp).collect(Collectors.toList());
        return list.stream().sorted(cmp.reversed()).collect(Collectors.toList());
    }

    private UtilizationPageResponseDTO<ResourceUtilizationDTO> paginate(
            List<ResourceUtilizationDTO> all, int page, int size) {
        long total = all.size();
        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 1;
        int from = Math.min(page * size, all.size());
        int to = Math.min(from + size, all.size());
        return UtilizationPageResponseDTO.<ResourceUtilizationDTO>builder()
                .content(all.subList(from, to))
                .totalElements(total)
                .totalPages(totalPages)
                .page(page)
                .size(size)
                .build();
    }
}
