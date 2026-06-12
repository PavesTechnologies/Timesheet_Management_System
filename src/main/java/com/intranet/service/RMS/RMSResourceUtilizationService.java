package com.intranet.service.RMS;

import com.intranet.dto.rms.ResourceSummaryDTO;
import com.intranet.dto.rms.RMSResourcePageResponseDTO;
import com.intranet.entity.InternalProject;
import com.intranet.entity.TimeSheet;
import com.intranet.entity.TimeSheetEntry;
import com.intranet.repository.InternalProjectRepo;
import com.intranet.repository.TimeSheetRepo;
import com.intranet.util.RMSCalculationUtils;
import com.intranet.util.cache.UserDirectoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles per-resource utilization computation with pagination, sorting, and search.
 * Returns a page of ResourceSummaryDTO — never dumps all resources in one shot.
 */
@Service
@RequiredArgsConstructor
public class RMSResourceUtilizationService {

    private final TimeSheetRepo timeSheetRepository;
    private final InternalProjectRepo internalProjectRepo;
    private final UserDirectoryService userDirectoryService;

    public RMSResourcePageResponseDTO getResourcesPage(LocalDate startDate, LocalDate endDate,
                                                        int page, int size,
                                                        String sortBy, String sortDir,
                                                        String search,
                                                        String authHeader) {

        List<ResourceSummaryDTO> all = buildAllResourceSummaries(startDate, endDate, authHeader);

        // Apply search filter (case-insensitive name match)
        if (search != null && !search.isBlank()) {
            String term = search.trim().toLowerCase();
            all = all.stream()
                    .filter(r -> r.getName() != null && r.getName().toLowerCase().contains(term))
                    .collect(Collectors.toList());
        }

        // Apply sort
        all = applySorting(all, sortBy, sortDir);

        long totalElements = all.size();
        int totalPages = size > 0 ? (int) Math.ceil((double) totalElements / size) : 1;

        // Apply pagination
        int fromIndex = Math.min(page * size, all.size());
        int toIndex = Math.min(fromIndex + size, all.size());
        List<ResourceSummaryDTO> pageContent = all.subList(fromIndex, toIndex);

        return RMSResourcePageResponseDTO.builder()
                .content(pageContent)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .page(page)
                .size(size)
                .build();
    }

    private List<ResourceSummaryDTO> buildAllResourceSummaries(LocalDate startDate, LocalDate endDate,
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

        List<TimeSheetEntry> entries = timeSheets.stream()
                .flatMap(ts -> ts.getEntries().stream())
                .collect(Collectors.toList());

        Set<Long> internalProjectIds = internalProjectRepo.findAll().stream()
                .map(InternalProject::getProjectId)
                .filter(pid -> pid != null)
                .map(Long::valueOf)
                .collect(Collectors.toSet());

        Map<Long, List<TimeSheet>> sheetsByUser =
                timeSheets.stream().collect(Collectors.groupingBy(TimeSheet::getUserId));

        Map<Long, List<TimeSheetEntry>> entriesByUser = entries.stream()
                .collect(Collectors.groupingBy(e -> e.getTimeSheet().getUserId()));

        List<ResourceSummaryDTO> summaries = new ArrayList<>();

        for (Map.Entry<Long, String> userEntry : resourceNames.entrySet()) {
            Long uid = userEntry.getKey();
            if (uid == null) continue;

            List<TimeSheet> userSheets = sheetsByUser.getOrDefault(uid, Collections.emptyList());
            List<TimeSheetEntry> userEntries = entriesByUser.getOrDefault(uid, Collections.emptyList());

            BigDecimal billable = RMSCalculationUtils.sumEntryHours(
                    userEntries, TimeSheetEntry::isBillable);
            BigDecimal internal = RMSCalculationUtils.sumEntryHours(
                    userEntries, e -> e.getProjectId() != null && internalProjectIds.contains(e.getProjectId()));
            BigDecimal nonBillable = RMSCalculationUtils.sumEntryHours(
                    userEntries, e -> !e.isBillable()
                            && !(e.getProjectId() != null && internalProjectIds.contains(e.getProjectId())));

            BigDecimal autoGenerated = userSheets.stream()
                    .filter(ts -> Boolean.TRUE.equals(ts.getAutoGenerated()))
                    .map(ts -> RMSCalculationUtils.safe(ts.getHoursWorked()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal entryTotal = RMSCalculationUtils.sumEntryHours(userEntries, e -> true);
            BigDecimal totalHours = entryTotal.add(autoGenerated);

            Map<LocalDate, Integer> plannedByDate =
                    RMSCalculationUtils.buildPlannedByDate(startDate, endDate, userSheets);
            BigDecimal plannedCapacity = plannedByDate.values().stream()
                    .map(BigDecimal::valueOf)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            double utilization = plannedCapacity.compareTo(BigDecimal.ZERO) > 0
                    ? RMSCalculationUtils.safePercentage(totalHours, plannedCapacity) : 0.0;
            int confidence = userSheets.isEmpty() ? 0
                    : RMSCalculationUtils.calculateConfidenceScore(userSheets);

            String hourlySplit = String.format("%.1f/%.1f/%.1f",
                    billable.doubleValue(),
                    nonBillable.add(autoGenerated).doubleValue(),
                    internal.doubleValue());

            summaries.add(ResourceSummaryDTO.builder()
                    .userId(uid)
                    .name(resourceNames.getOrDefault(uid, "User " + uid))
                    .billableHours(billable)
                    .nonBillableHours(nonBillable.add(autoGenerated))
                    .internalHours(internal)
                    .totalHours(totalHours)
                    .plannedCapacity(plannedCapacity)
                    .utilizationPercentage(RMSCalculationUtils.round(utilization))
                    .confidenceScore(confidence)
                    .resourceContext(resourceRoles.getOrDefault(uid, "Employee"))
                    .hourlySplit(hourlySplit)
                    .trendSignal(RMSCalculationUtils.calculateTrend(utilization))
                    .finalUtilPercentage(RMSCalculationUtils.round(utilization))
                    .build());
        }

        return summaries;
    }

    private List<ResourceSummaryDTO> applySorting(List<ResourceSummaryDTO> list,
                                                   String sortBy, String sortDir) {
        if (sortBy == null || sortBy.isBlank()) return list;

        Comparator<ResourceSummaryDTO> comparator = switch (sortBy.toLowerCase()) {
            case "name" -> Comparator.comparing(r -> r.getName() != null ? r.getName() : "");
            case "utilizationpercentage", "utilization" ->
                    Comparator.comparingDouble(r -> r.getUtilizationPercentage() != null
                            ? r.getUtilizationPercentage() : 0.0);
            case "billablehours" ->
                    Comparator.comparing(r -> r.getBillableHours() != null
                            ? r.getBillableHours() : BigDecimal.ZERO);
            case "totalhours" ->
                    Comparator.comparing(r -> r.getTotalHours() != null
                            ? r.getTotalHours() : BigDecimal.ZERO);
            case "confidencescore" ->
                    Comparator.comparingInt(r -> r.getConfidenceScore() != null
                            ? r.getConfidenceScore() : 0);
            default -> Comparator.comparing(r -> r.getName() != null ? r.getName() : "");
        };

        if ("desc".equalsIgnoreCase(sortDir)) comparator = comparator.reversed();

        return list.stream().sorted(comparator).collect(Collectors.toList());
    }

    /** Package-visible helper used by RMSPortfolioAnalyticsService to avoid duplicate data load. */
    List<ResourceSummaryDTO> buildAllResourceSummariesInternal(LocalDate startDate, LocalDate endDate,
                                                                String authHeader) {
        return buildAllResourceSummaries(startDate, endDate, authHeader);
    }
}
