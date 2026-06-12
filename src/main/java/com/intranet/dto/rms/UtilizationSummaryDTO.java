package com.intranet.dto.rms;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Lightweight org-level utilization summary.
 * Contains no per-resource, per-project, per-client, or per-role data.
 * Designed for fast initial dashboard loading.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UtilizationSummaryDTO {
    private LocalDate startDate;
    private LocalDate endDate;

    // Core hour metrics
    private BigDecimal totalHours;
    private BigDecimal plannedHours;
    private BigDecimal utilizationPercentage;
    private BigDecimal billableHours;
    private BigDecimal nonBillableHours;
    private BigDecimal internalHours;

    // Count metadata
    private Integer totalResources;
    private Integer totalProjects;
    private Integer totalClients;
    private Integer totalRoles;

    // Data quality
    private BigDecimal confidenceScore;
    private boolean approvedDataOnly;
}
