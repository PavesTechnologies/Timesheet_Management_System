package com.intranet.controller.RMS;

import com.intranet.dto.rms.RMSOrgSummaryResponseDTO;
import com.intranet.dto.rms.RMSPortfolioAnalyticsResponseDTO;
import com.intranet.dto.rms.RMSResourcePageResponseDTO;
import com.intranet.dto.rms.RMSTrendsResponseDTO;
import com.intranet.service.RMS.RMSPortfolioAnalyticsService;
import com.intranet.service.RMS.RMSResourceUtilizationService;
import com.intranet.service.RMS.RMSSummaryService;
import com.intranet.service.RMS.RMSTrendAnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * Optimized RMS analytics controller.
 *
 * Endpoints are intentionally split so the frontend can:
 *   1. Load /summary first  (fast, ~5 DB queries)
 *   2. Load /trends in parallel
 *   3. Lazy-load /resources with pagination
 *   4. Lazy-load /portfolio-analytics on demand
 */
@RestController
@RequestMapping("/api/timesheets/rms")
@RequiredArgsConstructor
@CrossOrigin(origins = "*", allowedHeaders = "*")
@Tag(name = "RMS Analytics", description = "Optimized RMS dashboard endpoints")
public class RMSAnalyticsController {

    private final RMSSummaryService summaryService;
    private final RMSTrendAnalyticsService trendAnalyticsService;
    private final RMSResourceUtilizationService resourceUtilizationService;
    private final RMSPortfolioAnalyticsService portfolioAnalyticsService;

    // ─── 1. Summary ───────────────────────────────────────────────────────────

    @GetMapping("/summary")
    @Operation(summary = "Lightweight org-level KPI summary (fast initial load)")
    public ResponseEntity<RMSOrgSummaryResponseDTO> getSummary(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            HttpServletRequest request) {

        String authHeader = resolveAuthHeader(request);
        LocalDate[] range = resolveDateRange(startDate, endDate);

        return ResponseEntity.ok(summaryService.getOrgSummary(range[0], range[1], authHeader));
    }

    // ─── 2. Trends ────────────────────────────────────────────────────────────

    @GetMapping("/trends")
    @Operation(summary = "Chart analytics — daily, weekly, monthly trends (no auth needed)")
    public ResponseEntity<RMSTrendsResponseDTO> getTrends(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        LocalDate[] range = resolveDateRange(startDate, endDate);

        return ResponseEntity.ok(trendAnalyticsService.getTrends(range[0], range[1]));
    }

    // ─── 3. Resources (paginated) ─────────────────────────────────────────────

    @GetMapping("/resources")
    @Operation(summary = "Paginated resource utilization — supports page, size, sort, search")
    public ResponseEntity<RMSResourcePageResponseDTO> getResources(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false, defaultValue = "name") String sortBy,
            @RequestParam(required = false, defaultValue = "asc") String sortDir,
            @RequestParam(required = false) String search,
            HttpServletRequest request) {

        if (size <= 0 || size > 100) size = 20;
        if (page < 0) page = 0;

        String authHeader = resolveAuthHeader(request);
        LocalDate[] range = resolveDateRange(startDate, endDate);

        return ResponseEntity.ok(resourceUtilizationService.getResourcesPage(
                range[0], range[1], page, size, sortBy, sortDir, search, authHeader));
    }

    // ─── 4. Portfolio Analytics ───────────────────────────────────────────────

    @GetMapping("/portfolio-analytics")
    @Operation(summary = "Advanced portfolio analytics — utilization breakdown, top/under performers, alerts")
    public ResponseEntity<RMSPortfolioAnalyticsResponseDTO> getPortfolioAnalytics(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            HttpServletRequest request) {

        String authHeader = resolveAuthHeader(request);
        LocalDate[] range = resolveDateRange(startDate, endDate);

        return ResponseEntity.ok(
                portfolioAnalyticsService.getPortfolioAnalytics(range[0], range[1], authHeader));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private LocalDate[] resolveDateRange(LocalDate startDate, LocalDate endDate) {
        LocalDate today = LocalDate.now();
        return new LocalDate[]{
                startDate != null ? startDate : today.withDayOfMonth(1),
                endDate != null ? endDate : today
        };
    }

    private String resolveAuthHeader(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth == null || auth.isBlank()) {
            throw new RuntimeException("Authorization header is required");
        }
        return auth;
    }
}
