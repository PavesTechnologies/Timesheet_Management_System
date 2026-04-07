package com.intranet.dto.rms;

import java.math.BigDecimal;

import lombok.Data;

@Data
public class RMSProjectHoursDTO {
    private Long projectId;
    private BigDecimal hours;
      // ✅ REQUIRED CONSTRUCTOR
    public RMSProjectHoursDTO(Long projectId, BigDecimal hours) {
        this.projectId = projectId;
        this.hours = hours;
    }
}