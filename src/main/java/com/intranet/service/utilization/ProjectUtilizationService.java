package com.intranet.service.utilization;

import com.intranet.dto.rms.ProjectUtilizationDTO;
import com.intranet.dto.rms.UtilizationPageResponseDTO;
import com.intranet.entity.InternalProject;
import com.intranet.entity.TimeSheet;
import com.intranet.entity.TimeSheetEntry;
import com.intranet.repository.InternalProjectRepo;
import com.intranet.repository.TimeSheetRepo;
import com.intranet.util.UtilizationCalculationUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Paginated project-level utilization with sort, filter, and search support.
 * Groups timesheet entries by projectId; internalProjectIds loaded ONCE.
 */
@Service
@RequiredArgsConstructor
public class ProjectUtilizationService {

    private final TimeSheetRepo timeSheetRepository;
    private final InternalProjectRepo internalProjectRepo;

    public UtilizationPageResponseDTO<ProjectUtilizationDTO> getProjectsPage(
            LocalDate startDate, LocalDate endDate,
            int page, int size,
            String sortBy, String sortDir,
            String search,
            boolean approvedOnly,
            double overThreshold, double underThreshold) {

        List<ProjectUtilizationDTO> all =
                buildAll(startDate, endDate, approvedOnly, overThreshold, underThreshold);

        if (search != null && !search.isBlank()) {
            String term = search.trim().toLowerCase();
            all = all.stream()
                    .filter(p -> (p.getProjectName() != null
                            && p.getProjectName().toLowerCase().contains(term))
                            || (p.getClientName() != null
                            && p.getClientName().toLowerCase().contains(term)))
                    .collect(Collectors.toList());
        }

        all = applySorting(all, sortBy, sortDir);
        return paginate(all, page, size);
    }

    private List<ProjectUtilizationDTO> buildAll(LocalDate startDate, LocalDate endDate,
                                                  boolean approvedOnly,
                                                  double overThreshold, double underThreshold) {

        List<TimeSheet> timeSheets =
                timeSheetRepository.findByWorkDateBetweenWithWeekInfoAndEntries(startDate, endDate);
        if (approvedOnly) {
            timeSheets = timeSheets.stream()
                    .filter(ts -> ts.getStatus() == TimeSheet.Status.APPROVED)
                    .collect(Collectors.toList());
        }

        List<TimeSheetEntry> allEntries = timeSheets.stream()
                .flatMap(ts -> ts.getEntries().stream())
                .collect(Collectors.toList());

        // Load internalProjectIds ONCE
        Set<Long> internalProjectIds = internalProjectRepo.findAll().stream()
                .map(InternalProject::getProjectId)
                .filter(Objects::nonNull)
                .map(Long::valueOf)
                .collect(Collectors.toSet());

        int totalWorkingDays = UtilizationCalculationUtils.calculateWorkingDays(startDate, endDate);

        // Group entries by projectId
        Map<Long, List<TimeSheetEntry>> entriesByProject = allEntries.stream()
                .filter(e -> e.getProjectId() != null)
                .collect(Collectors.groupingBy(TimeSheetEntry::getProjectId));

        List<ProjectUtilizationDTO> result = new ArrayList<>();

        for (Map.Entry<Long, List<TimeSheetEntry>> entry : entriesByProject.entrySet()) {
            Long projectId = entry.getKey();
            List<TimeSheetEntry> projectEntries = entry.getValue();

            List<TimeSheet> projectSheets = projectEntries.stream()
                    .map(TimeSheetEntry::getTimeSheet)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());

            BigDecimal totalHours = UtilizationCalculationUtils.calculateTotalHours(projectSheets, projectEntries);
            BigDecimal billable = UtilizationCalculationUtils.calculateBillableHours(projectEntries);
            BigDecimal planned = UtilizationCalculationUtils.calculatePlannedHours(projectSheets, startDate, endDate);

            BigDecimal utilPct = UtilizationCalculationUtils.calculateUtilizationPercentage(totalHours, planned);
            BigDecimal billableRatio = UtilizationCalculationUtils.calculateBillableRatio(billable, totalHours);
            String band = UtilizationCalculationUtils.determineUtilizationBand(
                    utilPct.doubleValue(), overThreshold, underThreshold);
            String trend = UtilizationCalculationUtils.calculateTrendSignal(projectSheets, startDate, endDate);
            int confidence = UtilizationCalculationUtils.calculateConfidenceScore(projectSheets);

            Set<Long> uniqueResourceIds = projectSheets.stream()
                    .map(TimeSheet::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
            BigDecimal avgHoursPerResource = uniqueResourceIds.isEmpty() ? BigDecimal.ZERO
                    : totalHours.divide(BigDecimal.valueOf(uniqueResourceIds.size()), 2, RoundingMode.HALF_UP);

            boolean overUtil = UtilizationCalculationUtils.isConsistentlyOverUtilized(projectSheets, overThreshold);
            boolean underUtil = UtilizationCalculationUtils.isConsistentlyUnderUtilized(projectSheets, underThreshold);
            int weeksOver = UtilizationCalculationUtils.calculateConsecutiveWeeksOverThreshold(projectSheets, overThreshold);
            int weeksUnder = UtilizationCalculationUtils.calculateConsecutiveWeeksUnderThreshold(projectSheets, underThreshold);

            List<String> alerts = UtilizationCalculationUtils.buildAlertMessages(
                    band, overUtil, underUtil, " for project " + projectId);

            result.add(ProjectUtilizationDTO.builder()
                    .projectId(projectId)
                    .projectName("Project " + projectId)
                    .clientName("Client " + projectId)
                    .totalHours(totalHours)
                    .billableHours(billable)
                    .plannedHours(planned)
                    .uniqueResources(uniqueResourceIds.size())
                    .averageHoursPerResource(avgHoursPerResource)
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
                    .daysWithApprovedTimesheets(UtilizationCalculationUtils.calculateApprovedDays(projectSheets))
                    .totalWorkingDays(totalWorkingDays)
                    .build());
        }

        return result;
    }

    private List<ProjectUtilizationDTO> applySorting(List<ProjectUtilizationDTO> list,
                                                      String sortBy, String sortDir) {
        if (sortBy == null || sortBy.isBlank()) return list;
        Comparator<ProjectUtilizationDTO> cmp = switch (sortBy.toLowerCase()) {
            case "projectname", "name" ->
                    Comparator.comparing(p -> p.getProjectName() != null ? p.getProjectName() : "");
            case "clientname", "client" ->
                    Comparator.comparing(p -> p.getClientName() != null ? p.getClientName() : "");
            case "totalhours" ->
                    Comparator.comparing(p -> p.getTotalHours() != null ? p.getTotalHours() : BigDecimal.ZERO);
            case "billablehours" ->
                    Comparator.comparing(p -> p.getBillableHours() != null ? p.getBillableHours() : BigDecimal.ZERO);
            case "uniqueresources" ->
                    Comparator.comparingInt(p -> p.getUniqueResources() != null ? p.getUniqueResources() : 0);
            default ->
                    Comparator.comparing(p -> p.getUtilizationPercentage() != null
                            ? p.getUtilizationPercentage() : BigDecimal.ZERO);
        };
        if ("asc".equalsIgnoreCase(sortDir)) return list.stream().sorted(cmp).collect(Collectors.toList());
        return list.stream().sorted(cmp.reversed()).collect(Collectors.toList());
    }

    private UtilizationPageResponseDTO<ProjectUtilizationDTO> paginate(
            List<ProjectUtilizationDTO> all, int page, int size) {
        long total = all.size();
        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 1;
        int from = Math.min(page * size, all.size());
        int to = Math.min(from + size, all.size());
        return UtilizationPageResponseDTO.<ProjectUtilizationDTO>builder()
                .content(all.subList(from, to))
                .totalElements(total)
                .totalPages(totalPages)
                .page(page)
                .size(size)
                .build();
    }
}
