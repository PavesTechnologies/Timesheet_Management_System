package com.intranet.dto.rms;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import lombok.Data;

@Data
public class TimeSheetSummaryResponseDTO {
    private Long resourceId;
    private LocalDate startDate;
    private LocalDate endDate;
    private BigDecimal totalHours;
    private BigDecimal billableHours;
    private BigDecimal nonBillableHours;
    private List<RMSProjectHoursDTO> projectHours;
}