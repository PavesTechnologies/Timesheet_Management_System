package com.intranet.dto.billing;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BillingTimesheetItemDTO {
    private String timesheetId;
    private Long resourceId;
    private String resourceName;
    private LocalDate workDate;
    private BigDecimal hours;
    private String role;
    private String approvalStatus;
}
