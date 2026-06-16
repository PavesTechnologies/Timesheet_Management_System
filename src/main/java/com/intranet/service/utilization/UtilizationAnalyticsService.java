package com.intranet.service.utilization;

import com.intranet.dto.rms.ResourceUtilizationDTO;
import com.intranet.dto.rms.UtilizationAlertDTO;
import com.intranet.dto.rms.UtilizationAnalyticsDTO;
import com.intranet.dto.rms.UtilizationPatternDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Advanced analytics: over/under-utilization breakdown, pattern detection, and alerts.
 * Delegates resource data loading to ResourceUtilizationService to avoid a second DB load.
 * Lazy-loaded endpoint — never part of initial dashboard render.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UtilizationAnalyticsService {

    private final ResourceUtilizationService resourceUtilizationService;

    public UtilizationAnalyticsDTO getAnalytics(LocalDate startDate, LocalDate endDate,
                                                  boolean approvedOnly,
                                                  double overThreshold, double underThreshold,
                                                  String authHeader) {

        List<ResourceUtilizationDTO> allResources = resourceUtilizationService.buildAll(
                startDate, endDate, approvedOnly, overThreshold, underThreshold, authHeader);

        log.info("UtilizationAnalyticsService: computing analytics over {} resources", allResources.size());

        // Band distribution
        Map<String, Long> bandDistribution = allResources.stream()
                .filter(r -> r.getUtilizationBand() != null)
                .collect(Collectors.groupingBy(ResourceUtilizationDTO::getUtilizationBand, Collectors.counting()));

        // Over/under-utilized resources
        List<ResourceUtilizationDTO> overUtilized = allResources.stream()
                .filter(r -> "HIGH".equals(r.getUtilizationBand()))
                .collect(Collectors.toList());

        List<ResourceUtilizationDTO> underUtilized = allResources.stream()
                .filter(r -> "CRITICAL".equals(r.getUtilizationBand()))
                .collect(Collectors.toList());

        // Patterns from resources that show sustained behavior
        List<UtilizationPatternDTO> patterns = buildPatterns(allResources, overThreshold, underThreshold);

        // Alerts from per-resource band + pattern flags
        List<UtilizationAlertDTO> alerts = buildAlerts(allResources, overThreshold, underThreshold);

        return UtilizationAnalyticsDTO.builder()
                .overUtilizedResources(overUtilized)
                .underUtilizedResources(underUtilized)
                .patterns(patterns)
                .alerts(alerts)
                .utilizationBandDistribution(bandDistribution)
                .build();
    }

    private List<UtilizationPatternDTO> buildPatterns(List<ResourceUtilizationDTO> resources,
                                                       double overThreshold, double underThreshold) {
        List<UtilizationPatternDTO> patterns = new ArrayList<>();

        for (ResourceUtilizationDTO r : resources) {
            if (r.isConsistentlyOverUtilized()) {
                patterns.add(UtilizationPatternDTO.builder()
                        .id("pattern-sustained-high-" + r.getResourceId())
                        .patternType("SUSTAINED_HIGH")
                        .severity("HIGH")
                        .scope("RESOURCE")
                        .title("Sustained High Utilization")
                        .description(String.format("%s above %.1f%% for %d consecutive weeks",
                                r.getResourceName(), overThreshold, r.getConsecutiveWeeksOverThreshold()))
                        .impact("High burnout risk, decreased quality, potential turnover")
                        .recommendation("Immediate workload redistribution required")
                        .resourceId(r.getResourceId())
                        .resourceName(r.getResourceName())
                        .averageUtilization(r.getUtilizationPercentage() != null
                                ? r.getUtilizationPercentage().doubleValue() : 0.0)
                        .durationWeeks(r.getConsecutiveWeeksOverThreshold())
                        .weeksOverThreshold(r.getConsecutiveWeeksOverThreshold())
                        .overThreshold(overThreshold)
                        .status("ACTIVE")
                        .detectedDate(LocalDate.now())
                        .lastUpdatedDate(LocalDate.now())
                        .build());
            }
            if (r.isConsistentlyUnderUtilized()) {
                patterns.add(UtilizationPatternDTO.builder()
                        .id("pattern-sustained-low-" + r.getResourceId())
                        .patternType("SUSTAINED_LOW")
                        .severity("MEDIUM")
                        .scope("RESOURCE")
                        .title("Sustained Low Utilization")
                        .description(String.format("%s below %.1f%% for %d consecutive weeks",
                                r.getResourceName(), underThreshold, r.getConsecutiveWeeksUnderThreshold()))
                        .impact("Reduced revenue, inefficient resource allocation")
                        .recommendation("Review project pipeline and capacity planning")
                        .resourceId(r.getResourceId())
                        .resourceName(r.getResourceName())
                        .averageUtilization(r.getUtilizationPercentage() != null
                                ? r.getUtilizationPercentage().doubleValue() : 0.0)
                        .durationWeeks(r.getConsecutiveWeeksUnderThreshold())
                        .weeksUnderThreshold(r.getConsecutiveWeeksUnderThreshold())
                        .underThreshold(underThreshold)
                        .status("ACTIVE")
                        .detectedDate(LocalDate.now())
                        .lastUpdatedDate(LocalDate.now())
                        .build());
            }
        }
        return patterns;
    }

    private List<UtilizationAlertDTO> buildAlerts(List<ResourceUtilizationDTO> resources,
                                                    double overThreshold, double underThreshold) {
        List<UtilizationAlertDTO> alerts = new ArrayList<>();

        for (ResourceUtilizationDTO r : resources) {
            double util = r.getUtilizationPercentage() != null
                    ? r.getUtilizationPercentage().doubleValue() : 0.0;

            if ("CRITICAL".equals(r.getUtilizationBand())) {
                alerts.add(UtilizationAlertDTO.builder()
                        .id("alert-critical-" + r.getResourceId())
                        .type("UNDER_UTILIZATION").severity("CRITICAL").scope("RESOURCE")
                        .title("Critical Under-Utilization")
                        .message(String.format("%s at %.1f%% utilization", r.getResourceName(), util))
                        .recommendation("Review workload allocation and project assignments")
                        .resourceId(r.getResourceId()).resourceName(r.getResourceName())
                        .currentValue(util).thresholdValue(underThreshold)
                        .status("OPEN").createdDate(LocalDate.now()).build());
            }

            if ("HIGH".equals(r.getUtilizationBand())) {
                alerts.add(UtilizationAlertDTO.builder()
                        .id("alert-high-" + r.getResourceId())
                        .type("OVER_UTILIZATION").severity("HIGH").scope("RESOURCE")
                        .title("High Utilization")
                        .message(String.format("%s at %.1f%% utilization", r.getResourceName(), util))
                        .recommendation("Monitor for burnout risk and consider workload redistribution")
                        .resourceId(r.getResourceId()).resourceName(r.getResourceName())
                        .currentValue(util).thresholdValue(overThreshold)
                        .status("OPEN").createdDate(LocalDate.now()).build());
            }

            if (r.isConsistentlyOverUtilized()) {
                alerts.add(UtilizationAlertDTO.builder()
                        .id("alert-sustained-high-" + r.getResourceId())
                        .type("PATTERN").severity("HIGH").scope("RESOURCE")
                        .title("Sustained Over-Utilization")
                        .message(String.format("%s over-utilized for %d consecutive weeks",
                                r.getResourceName(), r.getConsecutiveWeeksOverThreshold()))
                        .recommendation("Immediate action required to prevent burnout")
                        .resourceId(r.getResourceId()).resourceName(r.getResourceName())
                        .consecutiveWeeks(r.getConsecutiveWeeksOverThreshold())
                        .status("OPEN").createdDate(LocalDate.now()).build());
            }

            if (r.isConsistentlyUnderUtilized()) {
                alerts.add(UtilizationAlertDTO.builder()
                        .id("alert-sustained-low-" + r.getResourceId())
                        .type("PATTERN").severity("MEDIUM").scope("RESOURCE")
                        .title("Sustained Under-Utilization")
                        .message(String.format("%s under-utilized for %d consecutive weeks",
                                r.getResourceName(), r.getConsecutiveWeeksUnderThreshold()))
                        .recommendation("Review project assignments and capacity planning")
                        .resourceId(r.getResourceId()).resourceName(r.getResourceName())
                        .consecutiveWeeks(r.getConsecutiveWeeksUnderThreshold())
                        .status("OPEN").createdDate(LocalDate.now()).build());
            }
        }
        return alerts;
    }
}
