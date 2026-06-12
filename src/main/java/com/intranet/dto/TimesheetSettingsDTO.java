package com.intranet.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TimesheetSettingsDTO {

    private Long id;

    private BigDecimal minHrsRegular;

    private BigDecimal minHrsWeekend;

    private BigDecimal autogenLeaveHrs;

    private Boolean isActive;

    private Long userId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
