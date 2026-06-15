package com.intranet.dto.rms;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RMSPortfolioAnalyticsResponseDTO {
    private double billablePercentage;
    private double internalNonBillablePercentage;
    private double otherNonBillablePercentage;
    private double averageUtilization;
    private List<ResourceSummaryDTO> topPerformers;
    private List<ResourceSummaryDTO> underPerformers;
    private List<AlertDTO> alerts;
}
