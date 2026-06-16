package com.intranet.dto.rms;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Advanced utilization analytics: over/under-utilization analysis,
 * utilization patterns, alerts, and distribution breakdown.
 * Lazy-loaded by the dashboard — never part of the initial page load.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UtilizationAnalyticsDTO {
    private List<ResourceUtilizationDTO> overUtilizedResources;
    private List<ResourceUtilizationDTO> underUtilizedResources;
    private List<UtilizationPatternDTO> patterns;
    private List<UtilizationAlertDTO> alerts;
    /** Band label → count of resources in that band. */
    private Map<String, Long> utilizationBandDistribution;
}
