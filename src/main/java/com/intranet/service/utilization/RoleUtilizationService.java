package com.intranet.service.utilization;

import com.intranet.dto.rms.RoleUtilizationDTO;
import com.intranet.dto.rms.UtilizationPageResponseDTO;
import com.intranet.entity.InternalProject;
import com.intranet.entity.TimeSheet;
import com.intranet.entity.TimeSheetEntry;
import com.intranet.repository.InternalProjectRepo;
import com.intranet.repository.TimeSheetRepo;
import com.intranet.util.UtilizationCalculationUtils;
import com.intranet.util.cache.UserDirectoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Paginated role-level utilization with sort, filter, and search support.
 * Fetches role info from UMS; groups timesheets by userId → role.
 * internalProjectIds loaded ONCE per request.
 */
@Service
@RequiredArgsConstructor
public class RoleUtilizationService {

    private final TimeSheetRepo timeSheetRepository;
    private final InternalProjectRepo internalProjectRepo;
    private final UserDirectoryService userDirectoryService;

    public UtilizationPageResponseDTO<RoleUtilizationDTO> getRolesPage(
            LocalDate startDate, LocalDate endDate,
            int page, int size,
            String sortBy, String sortDir,
            String search,
            boolean approvedOnly,
            double overThreshold, double underThreshold,
            String authHeader) {

        List<RoleUtilizationDTO> all =
                buildAll(startDate, endDate, approvedOnly, overThreshold, underThreshold, authHeader);

        if (search != null && !search.isBlank()) {
            String term = search.trim().toLowerCase();
            all = all.stream()
                    .filter(r -> r.getRoleName() != null
                            && r.getRoleName().toLowerCase().contains(term))
                    .collect(Collectors.toList());
        }

        all = applySorting(all, sortBy, sortDir);
        return paginate(all, page, size);
    }

    private List<RoleUtilizationDTO> buildAll(LocalDate startDate, LocalDate endDate,
                                               boolean approvedOnly,
                                               double overThreshold, double underThreshold,
                                               String authHeader) {

        List<Map<String, Object>> users = userDirectoryService.fetchAllUsers2(authHeader);
        Map<Long, String> userRoles = users.stream()
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

        List<TimeSheetEntry> allEntries = timeSheets.stream()
                .flatMap(ts -> ts.getEntries().stream())
                .collect(Collectors.toList());

        Set<Long> internalProjectIds = internalProjectRepo.findAll().stream()
                .map(InternalProject::getProjectId)
                .filter(Objects::nonNull)
                .map(Long::valueOf)
                .collect(Collectors.toSet());

        int totalWorkingDays = UtilizationCalculationUtils.calculateWorkingDays(startDate, endDate);

        // Group timesheets and entries by role
        Map<String, List<TimeSheetEntry>> entriesByRole = new LinkedHashMap<>();
        Map<String, Set<Long>> resourcesByRole = new LinkedHashMap<>();

        for (TimeSheetEntry entry : allEntries) {
            if (entry.getTimeSheet() != null && entry.getTimeSheet().getUserId() != null) {
                Long uid = entry.getTimeSheet().getUserId();
                String role = userRoles.getOrDefault(uid, "Employee");
                entriesByRole.computeIfAbsent(role, k -> new ArrayList<>()).add(entry);
                resourcesByRole.computeIfAbsent(role, k -> new HashSet<>()).add(uid);
            }
        }

        Map<String, List<TimeSheet>> sheetsByRole = new LinkedHashMap<>();
        for (TimeSheet ts : timeSheets) {
            if (ts.getUserId() != null) {
                String role = userRoles.getOrDefault(ts.getUserId(), "Employee");
                sheetsByRole.computeIfAbsent(role, k -> new ArrayList<>()).add(ts);
            }
        }

        List<RoleUtilizationDTO> result = new ArrayList<>();

        for (String roleName : entriesByRole.keySet()) {
            List<TimeSheetEntry> roleEntries = entriesByRole.get(roleName);
            List<TimeSheet> roleSheets = sheetsByRole.getOrDefault(roleName, Collections.emptyList());
            int uniqueResources = resourcesByRole.getOrDefault(roleName, Collections.emptySet()).size();

            BigDecimal totalHours = UtilizationCalculationUtils.calculateTotalHours(roleSheets, roleEntries);
            BigDecimal billable = UtilizationCalculationUtils.calculateBillableHours(roleEntries);
            BigDecimal nonBillable = UtilizationCalculationUtils.calculateNonBillableHours(roleEntries, internalProjectIds);
            BigDecimal internal = UtilizationCalculationUtils.calculateInternalHours(roleEntries, internalProjectIds);
            BigDecimal planned = UtilizationCalculationUtils.calculatePlannedHours(roleSheets, startDate, endDate);

            BigDecimal utilPct = UtilizationCalculationUtils.calculateUtilizationPercentage(totalHours, planned);
            BigDecimal billableRatio = UtilizationCalculationUtils.calculateBillableRatio(billable, totalHours);
            String band = UtilizationCalculationUtils.determineUtilizationBand(
                    utilPct.doubleValue(), overThreshold, underThreshold);
            String trend = UtilizationCalculationUtils.calculateTrendSignal(roleSheets, startDate, endDate);
            int confidence = UtilizationCalculationUtils.calculateConfidenceScore(roleSheets);

            BigDecimal avgHoursPerResource = uniqueResources == 0 ? BigDecimal.ZERO
                    : totalHours.divide(BigDecimal.valueOf(uniqueResources), 2, RoundingMode.HALF_UP);

            boolean overUtil = UtilizationCalculationUtils.isConsistentlyOverUtilized(roleSheets, overThreshold);
            boolean underUtil = UtilizationCalculationUtils.isConsistentlyUnderUtilized(roleSheets, underThreshold);
            int weeksOver = UtilizationCalculationUtils.calculateConsecutiveWeeksOverThreshold(roleSheets, overThreshold);
            int weeksUnder = UtilizationCalculationUtils.calculateConsecutiveWeeksUnderThreshold(roleSheets, underThreshold);

            List<String> alerts = UtilizationCalculationUtils.buildAlertMessages(
                    band, overUtil, underUtil, " for role " + roleName);

            result.add(RoleUtilizationDTO.builder()
                    .roleName(roleName)
                    .totalHours(totalHours)
                    .billableHours(billable)
                    .nonBillableHours(nonBillable)
                    .internalHours(internal)
                    .plannedHours(planned)
                    .uniqueResources(uniqueResources)
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
                    .daysWithApprovedTimesheets(UtilizationCalculationUtils.calculateApprovedDays(roleSheets))
                    .totalWorkingDays(totalWorkingDays)
                    .build());
        }

        return result;
    }

    private List<RoleUtilizationDTO> applySorting(List<RoleUtilizationDTO> list,
                                                   String sortBy, String sortDir) {
        if (sortBy == null || sortBy.isBlank()) return list;
        Comparator<RoleUtilizationDTO> cmp = switch (sortBy.toLowerCase()) {
            case "rolename", "role", "name" ->
                    Comparator.comparing(r -> r.getRoleName() != null ? r.getRoleName() : "");
            case "totalhours" ->
                    Comparator.comparing(r -> r.getTotalHours() != null ? r.getTotalHours() : BigDecimal.ZERO);
            case "billablehours" ->
                    Comparator.comparing(r -> r.getBillableHours() != null ? r.getBillableHours() : BigDecimal.ZERO);
            case "uniqueresources" ->
                    Comparator.comparingInt(r -> r.getUniqueResources() != null ? r.getUniqueResources() : 0);
            default ->
                    Comparator.comparing(r -> r.getUtilizationPercentage() != null
                            ? r.getUtilizationPercentage() : BigDecimal.ZERO);
        };
        if ("asc".equalsIgnoreCase(sortDir)) return list.stream().sorted(cmp).collect(Collectors.toList());
        return list.stream().sorted(cmp.reversed()).collect(Collectors.toList());
    }

    private UtilizationPageResponseDTO<RoleUtilizationDTO> paginate(
            List<RoleUtilizationDTO> all, int page, int size) {
        long total = all.size();
        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 1;
        int from = Math.min(page * size, all.size());
        int to = Math.min(from + size, all.size());
        return UtilizationPageResponseDTO.<RoleUtilizationDTO>builder()
                .content(all.subList(from, to))
                .totalElements(total)
                .totalPages(totalPages)
                .page(page)
                .size(size)
                .build();
    }
}
