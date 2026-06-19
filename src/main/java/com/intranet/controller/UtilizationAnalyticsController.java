package com.intranet.controller;

import com.intranet.dto.rms.RMSTrendsResponseDTO;
import com.intranet.dto.rms.ResourceUtilizationDTO;
import com.intranet.dto.rms.ClientUtilizationDTO;
import com.intranet.dto.rms.ProjectUtilizationDTO;
import com.intranet.dto.rms.RoleUtilizationDTO;
import com.intranet.dto.rms.UtilizationAlertDTO;
import com.intranet.dto.rms.UtilizationAnalyticsDTO;
import com.intranet.dto.rms.UtilizationPageResponseDTO;
import com.intranet.dto.rms.UtilizationSummaryDTO;
import com.intranet.service.utilization.ClientUtilizationService;
import com.intranet.service.utilization.ProjectUtilizationService;
import com.intranet.service.utilization.ResourceUtilizationService;
import com.intranet.service.utilization.RoleUtilizationService;
import com.intranet.service.utilization.UtilizationAnalyticsService;
import com.intranet.service.utilization.UtilizationSummaryService;
import com.intranet.service.utilization.UtilizationTrendService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Modular utilization analytics controller.
 *
 * Frontend loading strategy:
 *   1. Load /summary immediately  (fast, ~5 aggregate DB queries)
 *   2. Load /trends in parallel   (no UMS call, one join query)
 *   3. Lazy-load /resources, /projects, /clients, /roles with pagination
 *   4. Lazy-load /analytics and /alerts on demand
 *
 * Old POST /api/utilization/report is kept untouched for backward compatibility.
 */
@RestController
@RequestMapping("/api/utilization")
@RequiredArgsConstructor
@CrossOrigin(origins = "*", allowedHeaders = "*")
@Slf4j
@Tag(name = "Utilization Analytics", description = "Optimized modular utilization reporting endpoints")
public class UtilizationAnalyticsController {

    private final UtilizationSummaryService summaryService;
    private final UtilizationTrendService trendService;
    private final ResourceUtilizationService resourceService;
    private final ProjectUtilizationService projectService;
    private final ClientUtilizationService clientService;
    private final RoleUtilizationService roleService;
    private final UtilizationAnalyticsService analyticsService;

    // ─── 1. Summary ───────────────────────────────────────────────────────────

    @GetMapping("/summary")
    @Operation(summary = "Lightweight org-level utilization summary (fast initial load)")
    public ResponseEntity<UtilizationSummaryDTO> getSummary(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "true") boolean approvedOnly,
            HttpServletRequest request) {

        String auth = resolveAuth(request);
        LocalDate[] range = range(startDate, endDate);
        return ResponseEntity.ok(summaryService.getSummary(range[0], range[1], approvedOnly, auth));
    }

    // ─── 2. Trends ────────────────────────────────────────────────────────────

    @GetMapping("/trends")
    @Operation(summary = "Daily, weekly, and monthly utilization trends (cached)")
    public ResponseEntity<RMSTrendsResponseDTO> getTrends(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        LocalDate[] range = range(startDate, endDate);
        return ResponseEntity.ok(trendService.getTrends(range[0], range[1]));
    }

    // ─── 3a. Resources (paginated) ────────────────────────────────────────────

    @GetMapping("/resources")
    @Operation(summary = "Paginated resource utilization — supports page, size, sortBy, sortDir, search")
    public ResponseEntity<UtilizationPageResponseDTO<ResourceUtilizationDTO>> getResources(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "utilizationPercentage") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "true") boolean approvedOnly,
            @RequestParam(defaultValue = "90.0") double overThreshold,
            @RequestParam(defaultValue = "60.0") double underThreshold,
            HttpServletRequest request) {

        size = clampSize(size);
        String auth = resolveAuth(request);
        LocalDate[] range = range(startDate, endDate);
        return ResponseEntity.ok(resourceService.getResourcesPage(
                range[0], range[1], page, size, sortBy, sortDir, search,
                approvedOnly, overThreshold, underThreshold, auth));
    }

    // ─── 3b. Projects (paginated) ─────────────────────────────────────────────

    @GetMapping("/projects")
    @Operation(summary = "Paginated project utilization — supports page, size, sortBy, sortDir, search")
    public ResponseEntity<UtilizationPageResponseDTO<ProjectUtilizationDTO>> getProjects(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "utilizationPercentage") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "true") boolean approvedOnly,
            @RequestParam(defaultValue = "90.0") double overThreshold,
            @RequestParam(defaultValue = "60.0") double underThreshold,
            HttpServletRequest request) {

        size = clampSize(size);
        String auth = resolveAuth(request);
        LocalDate[] range = range(startDate, endDate);
        return ResponseEntity.ok(projectService.getProjectsPage(
                range[0], range[1], page, size, sortBy, sortDir, search,
                approvedOnly, overThreshold, underThreshold, auth));
    }

    // ─── 3c. Clients (paginated) ──────────────────────────────────────────────

    @GetMapping("/clients")
    @Operation(summary = "Paginated client utilization — supports page, size, sortBy, sortDir, search")
    public ResponseEntity<UtilizationPageResponseDTO<ClientUtilizationDTO>> getClients(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "utilizationPercentage") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "true") boolean approvedOnly,
            @RequestParam(defaultValue = "90.0") double overThreshold,
            @RequestParam(defaultValue = "60.0") double underThreshold,
            HttpServletRequest request) {

        size = clampSize(size);
        String auth = resolveAuth(request);
        LocalDate[] range = range(startDate, endDate);
        return ResponseEntity.ok(clientService.getClientsPage(
                range[0], range[1], page, size, sortBy, sortDir, search,
                approvedOnly, overThreshold, underThreshold, auth));
    }

    // ─── 3d. Roles (paginated) ────────────────────────────────────────────────

    @GetMapping("/roles")
    @Operation(summary = "Paginated role utilization — supports page, size, sortBy, sortDir, search")
    public ResponseEntity<UtilizationPageResponseDTO<RoleUtilizationDTO>> getRoles(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "utilizationPercentage") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "true") boolean approvedOnly,
            @RequestParam(defaultValue = "90.0") double overThreshold,
            @RequestParam(defaultValue = "60.0") double underThreshold,
            HttpServletRequest request) {

        size = clampSize(size);
        String auth = resolveAuth(request);
        LocalDate[] range = range(startDate, endDate);
        return ResponseEntity.ok(roleService.getRolesPage(
                range[0], range[1], page, size, sortBy, sortDir, search,
                approvedOnly, overThreshold, underThreshold, auth));
    }

    // ─── 4. Analytics ─────────────────────────────────────────────────────────

    @GetMapping("/analytics")
    @Operation(summary = "Advanced analytics — over/under-utilization, patterns, band distribution")
    public ResponseEntity<UtilizationAnalyticsDTO> getAnalytics(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "true") boolean approvedOnly,
            @RequestParam(defaultValue = "90.0") double overThreshold,
            @RequestParam(defaultValue = "60.0") double underThreshold,
            HttpServletRequest request) {

        String auth = resolveAuth(request);
        LocalDate[] range = range(startDate, endDate);
        return ResponseEntity.ok(analyticsService.getAnalytics(
                range[0], range[1], approvedOnly, overThreshold, underThreshold, auth));
    }

    // ─── 5. Alerts (isolated) ─────────────────────────────────────────────────

    @GetMapping("/alerts")
    @Operation(summary = "Isolated alerts endpoint — over/under-utilization and sustained-pattern alerts")
    public ResponseEntity<List<UtilizationAlertDTO>> getAlerts(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "true") boolean approvedOnly,
            @RequestParam(defaultValue = "90.0") double overThreshold,
            @RequestParam(defaultValue = "60.0") double underThreshold,
            HttpServletRequest request) {

        String auth = resolveAuth(request);
        LocalDate[] range = range(startDate, endDate);
        UtilizationAnalyticsDTO analytics = analyticsService.getAnalytics(
                range[0], range[1], approvedOnly, overThreshold, underThreshold, auth);
        return ResponseEntity.ok(analytics.getAlerts());
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private LocalDate[] range(LocalDate startDate, LocalDate endDate) {
        LocalDate today = LocalDate.now();
        return new LocalDate[]{
                startDate != null ? startDate : today.withDayOfMonth(1),
                endDate != null ? endDate : today
        };
    }

    private String resolveAuth(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth == null || auth.isBlank()) {
            throw new RuntimeException("Authorization header is required");
        }
        return auth;
    }

    private int clampSize(int size) {
        if (size <= 0 || size > 100) return 20;
        return size;
    }
}
