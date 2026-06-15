package com.intranet.service.RMS;

import com.intranet.dto.rms.AlertDTO;
import com.intranet.dto.rms.RMSPortfolioAnalyticsResponseDTO;
import com.intranet.dto.rms.ResourceSummaryDTO;
import com.intranet.entity.TimeSheet;
import com.intranet.entity.TimeSheetEntry;
import com.intranet.repository.TimeSheetRepo;
import com.intranet.util.RMSCalculationUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Advanced portfolio analytics: utilization breakdown, top/under performers, and alerts.
 * Delegates resource computation to RMSResourceUtilizationService to avoid duplication.
 */
@Service
@RequiredArgsConstructor
public class RMSPortfolioAnalyticsService {

    private final TimeSheetRepo timeSheetRepository;
    private final RMSResourceUtilizationService resourceUtilizationService;

    public RMSPortfolioAnalyticsResponseDTO getPortfolioAnalytics(LocalDate startDate, LocalDate endDate,
                                                                   String authHeader) {

        List<ResourceSummaryDTO> resources =
                resourceUtilizationService.buildAllResourceSummariesInternal(startDate, endDate, authHeader);

        // Aggregate totals from resource summaries
        BigDecimal overallBillable = sum(resources, ResourceSummaryDTO::getBillableHours);
        BigDecimal overallInternal = sum(resources, ResourceSummaryDTO::getInternalHours);
        BigDecimal overallNonBillable = sum(resources, ResourceSummaryDTO::getNonBillableHours);
        BigDecimal overallTotal = sum(resources, ResourceSummaryDTO::getTotalHours);
        BigDecimal overallPlanned = sum(resources, ResourceSummaryDTO::getPlannedCapacity);

        double avgUtilization = RMSCalculationUtils.safePercentage(overallTotal, overallPlanned);

        double billablePct = RMSCalculationUtils.safePercentage(overallBillable, overallTotal);
        double internalPct = RMSCalculationUtils.safePercentage(overallInternal, overallTotal);
        double otherNonBillablePct = RMSCalculationUtils.safePercentage(overallNonBillable, overallTotal);

        // Top 5 by utilization (highest first)
        List<ResourceSummaryDTO> topPerformers = resources.stream()
                .filter(r -> r.getUtilizationPercentage() != null)
                .sorted(Comparator.comparingDouble(ResourceSummaryDTO::getUtilizationPercentage).reversed())
                .limit(5)
                .collect(Collectors.toList());

        // Bottom 5 by utilization (lowest first)
        List<ResourceSummaryDTO> underPerformers = resources.stream()
                .filter(r -> r.getUtilizationPercentage() != null)
                .sorted(Comparator.comparingDouble(ResourceSummaryDTO::getUtilizationPercentage))
                .limit(5)
                .collect(Collectors.toList());

        // Alerts derived from trend data
        List<TimeSheet> timeSheets =
                timeSheetRepository.findByWorkDateBetweenWithWeekInfoAndEntries(startDate, endDate);
        List<TimeSheetEntry> entries = timeSheets.stream()
                .flatMap(ts -> ts.getEntries().stream())
                .collect(Collectors.toList());

        Map<LocalDate, BigDecimal> actualByDate = RMSCalculationUtils.buildActualByDate(entries);
        Map<LocalDate, Integer> plannedByDate =
                RMSCalculationUtils.buildPlannedByDate(startDate, endDate, timeSheets);

        int overallConfidence = (int) resources.stream()
                .mapToInt(r -> r.getConfidenceScore() != null ? r.getConfidenceScore() : 0)
                .average().orElse(50);

        List<AlertDTO> alerts = RMSCalculationUtils.buildAlerts(
                startDate, endDate, actualByDate, plannedByDate, avgUtilization, overallConfidence);

        return RMSPortfolioAnalyticsResponseDTO.builder()
                .billablePercentage(RMSCalculationUtils.round(billablePct))
                .internalNonBillablePercentage(RMSCalculationUtils.round(internalPct))
                .otherNonBillablePercentage(RMSCalculationUtils.round(otherNonBillablePct))
                .averageUtilization(RMSCalculationUtils.round(avgUtilization))
                .topPerformers(topPerformers)
                .underPerformers(underPerformers)
                .alerts(alerts)
                .build();
    }

    private BigDecimal sum(List<ResourceSummaryDTO> resources,
                           java.util.function.Function<ResourceSummaryDTO, BigDecimal> getter) {
        return resources.stream()
                .map(r -> RMSCalculationUtils.safe(getter.apply(r)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
