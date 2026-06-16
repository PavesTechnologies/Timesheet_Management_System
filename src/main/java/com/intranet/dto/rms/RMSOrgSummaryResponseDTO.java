package com.intranet.dto.rms;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RMSOrgSummaryResponseDTO {
    private LocalDate startDate;
    private LocalDate endDate;
    private BigDecimal totalHours;
    private BigDecimal billableHours;
    private BigDecimal nonBillableHours;
    private Long totalUsers;
    private Long totalResources;
    private Double utilization;
    private Double billableRatio;
    private Integer confidenceScore;
    private BigDecimal averageTotalHours;
    private BigDecimal averageBillableHours;
    private BigDecimal averageNonBillableHours;
    private double billablePercentage;
    private double internalNonBillablePercentage;
    private double otherNonBillablePercentage;
    private double totalPercentage;
    private List<KPIStatDTO> kpiStats;
}
