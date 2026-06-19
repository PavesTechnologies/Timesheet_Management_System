package com.intranet.service.utilization;

import com.intranet.dto.rms.ClientUtilizationDTO;
import com.intranet.dto.rms.UtilizationPageResponseDTO;
import com.intranet.entity.InternalProject;
import com.intranet.entity.TimeSheet;
import com.intranet.entity.TimeSheetEntry;
import com.intranet.repository.InternalProjectRepo;
import com.intranet.repository.TimeSheetRepo;
import com.intranet.util.UtilizationCalculationUtils;
import com.intranet.util.cache.ProjectDirectoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Paginated client-level utilization with sort, filter, and search support.
 * Groups entries by clientKey (currently "Client_<projectId>" as a proxy
 * until a proper client-project mapping is available).
 */
@Service
@RequiredArgsConstructor
public class ClientUtilizationService {

    private final TimeSheetRepo timeSheetRepository;
    private final InternalProjectRepo internalProjectRepo;
    private final ProjectDirectoryService projectDirectoryService;

    public UtilizationPageResponseDTO<ClientUtilizationDTO> getClientsPage(
            LocalDate startDate, LocalDate endDate,
            int page, int size,
            String sortBy, String sortDir,
            String search,
            boolean approvedOnly,
            double overThreshold, double underThreshold,
            String auth) {

        List<ClientUtilizationDTO> all =
                buildAll(startDate, endDate, approvedOnly, overThreshold, underThreshold, auth);

        if (search != null && !search.isBlank()) {
            String term = search.trim().toLowerCase();
            all = all.stream()
                    .filter(c -> c.getClientName() != null
                            && c.getClientName().toLowerCase().contains(term))
                    .collect(Collectors.toList());
        }

        all = applySorting(all, sortBy, sortDir);
        return paginate(all, page, size);
    }

    private List<ClientUtilizationDTO> buildAll(LocalDate startDate, LocalDate endDate,
                                                 boolean approvedOnly,
                                                 double overThreshold, double underThreshold,
                                                 String auth) {

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

        // Load internal projects ONCE: projectId (as Long) -> InternalProject
        Map<Long, InternalProject> internalProjectMap = internalProjectRepo.findAll().stream()
                .filter(ip -> ip.getProjectId() != null)
                .collect(Collectors.toMap(
                        ip -> ip.getProjectId().longValue(),
                        ip -> ip,
                        (a, b) -> a
                ));

        // Fetch external project directory from PMS (cached, fails gracefully)
        Map<Long, Map<String, Object>> projectDirectory = Collections.emptyMap();
        if (auth != null && !auth.isBlank()) {
            try {
                projectDirectory = projectDirectoryService.fetchAllProjects(auth);
            } catch (Exception ignored) {
            }
        }

        // Build projectId -> clientName lookup
        final Map<Long, Map<String, Object>> finalProjectDirectory = projectDirectory;
        final Map<Long, InternalProject> finalInternalProjectMap = internalProjectMap;
        Map<Long, String> clientNameByProjectId = new HashMap<>();
        for (TimeSheetEntry e : allEntries) {
            if (e.getProjectId() != null) {
                clientNameByProjectId.computeIfAbsent(e.getProjectId(), pid -> {
                    Map<String, Object> info = finalProjectDirectory.get(pid);
                    if (info != null && info.get("clientName") != null) {
                        return info.get("clientName").toString();
                    }
                    InternalProject ip = finalInternalProjectMap.get(pid);
                    if (ip != null && ip.getProjectName() != null) {
                        return ip.getProjectName();
                    }
                    return "Project " + pid;
                });
            }
        }

        int totalWorkingDays = UtilizationCalculationUtils.calculateWorkingDays(startDate, endDate);

        // Group entries by resolved client name
        Map<String, List<TimeSheetEntry>> entriesByClient = new LinkedHashMap<>();
        for (TimeSheetEntry e : allEntries) {
            if (e.getProjectId() != null) {
                String key = clientNameByProjectId.get(e.getProjectId());
                entriesByClient.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
            }
        }

        List<ClientUtilizationDTO> result = new ArrayList<>();

        for (Map.Entry<String, List<TimeSheetEntry>> entry : entriesByClient.entrySet()) {
            String clientName = entry.getKey();
            List<TimeSheetEntry> clientEntries = entry.getValue();

            List<TimeSheet> clientSheets = clientEntries.stream()
                    .map(TimeSheetEntry::getTimeSheet)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());

            BigDecimal totalHours = UtilizationCalculationUtils.calculateTotalHours(clientSheets, clientEntries);
            BigDecimal billable = UtilizationCalculationUtils.calculateBillableHours(clientEntries);
            BigDecimal planned = UtilizationCalculationUtils.calculatePlannedHours(clientSheets, startDate, endDate);

            BigDecimal utilPct = UtilizationCalculationUtils.calculateUtilizationPercentage(totalHours, planned);
            BigDecimal billableRatio = UtilizationCalculationUtils.calculateBillableRatio(billable, totalHours);
            String band = UtilizationCalculationUtils.determineUtilizationBand(
                    utilPct.doubleValue(), overThreshold, underThreshold);
            String trend = UtilizationCalculationUtils.calculateTrendSignal(clientSheets, startDate, endDate);
            int confidence = UtilizationCalculationUtils.calculateConfidenceScore(clientSheets);

            Set<Long> uniqueProjectIds = clientEntries.stream()
                    .map(TimeSheetEntry::getProjectId).filter(Objects::nonNull).collect(Collectors.toSet());
            Set<Long> uniqueResourceIds = clientSheets.stream()
                    .map(TimeSheet::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());

            BigDecimal avgHoursPerResource = uniqueResourceIds.isEmpty() ? BigDecimal.ZERO
                    : totalHours.divide(BigDecimal.valueOf(uniqueResourceIds.size()), 2, RoundingMode.HALF_UP);
            BigDecimal avgHoursPerProject = uniqueProjectIds.isEmpty() ? BigDecimal.ZERO
                    : totalHours.divide(BigDecimal.valueOf(uniqueProjectIds.size()), 2, RoundingMode.HALF_UP);

            boolean overUtil = UtilizationCalculationUtils.isConsistentlyOverUtilized(clientSheets, overThreshold);
            boolean underUtil = UtilizationCalculationUtils.isConsistentlyUnderUtilized(clientSheets, underThreshold);
            int weeksOver = UtilizationCalculationUtils.calculateConsecutiveWeeksOverThreshold(clientSheets, overThreshold);
            int weeksUnder = UtilizationCalculationUtils.calculateConsecutiveWeeksUnderThreshold(clientSheets, underThreshold);

            List<String> alerts = UtilizationCalculationUtils.buildAlertMessages(
                    band, overUtil, underUtil, " for " + clientName);

            result.add(ClientUtilizationDTO.builder()
                    .clientName(clientName)
                    .totalHours(totalHours)
                    .billableHours(billable)
                    .plannedHours(planned)
                    .uniqueProjects(uniqueProjectIds.size())
                    .uniqueResources(uniqueResourceIds.size())
                    .averageHoursPerResource(avgHoursPerResource)
                    .averageHoursPerProject(avgHoursPerProject)
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
                    .daysWithApprovedTimesheets(UtilizationCalculationUtils.calculateApprovedDays(clientSheets))
                    .totalWorkingDays(totalWorkingDays)
                    .build());
        }

        return result;
    }

    private List<ClientUtilizationDTO> applySorting(List<ClientUtilizationDTO> list,
                                                     String sortBy, String sortDir) {
        if (sortBy == null || sortBy.isBlank()) return list;
        Comparator<ClientUtilizationDTO> cmp = switch (sortBy.toLowerCase()) {
            case "clientname", "name" ->
                    Comparator.comparing(c -> c.getClientName() != null ? c.getClientName() : "");
            case "totalhours" ->
                    Comparator.comparing(c -> c.getTotalHours() != null ? c.getTotalHours() : BigDecimal.ZERO);
            case "billablehours" ->
                    Comparator.comparing(c -> c.getBillableHours() != null ? c.getBillableHours() : BigDecimal.ZERO);
            case "uniqueprojects" ->
                    Comparator.comparingInt(c -> c.getUniqueProjects() != null ? c.getUniqueProjects() : 0);
            case "uniqueresources" ->
                    Comparator.comparingInt(c -> c.getUniqueResources() != null ? c.getUniqueResources() : 0);
            default ->
                    Comparator.comparing(c -> c.getUtilizationPercentage() != null
                            ? c.getUtilizationPercentage() : BigDecimal.ZERO);
        };
        if ("asc".equalsIgnoreCase(sortDir)) return list.stream().sorted(cmp).collect(Collectors.toList());
        return list.stream().sorted(cmp.reversed()).collect(Collectors.toList());
    }

    private UtilizationPageResponseDTO<ClientUtilizationDTO> paginate(
            List<ClientUtilizationDTO> all, int page, int size) {
        long total = all.size();
        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 1;
        int from = Math.min(page * size, all.size());
        int to = Math.min(from + size, all.size());
        return UtilizationPageResponseDTO.<ClientUtilizationDTO>builder()
                .content(all.subList(from, to))
                .totalElements(total)
                .totalPages(totalPages)
                .page(page)
                .size(size)
                .build();
    }
}
