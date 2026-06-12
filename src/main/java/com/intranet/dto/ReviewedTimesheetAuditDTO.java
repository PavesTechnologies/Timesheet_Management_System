package com.intranet.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewedTimesheetAuditDTO {
    private Long reviewId;
    private Long userId;
    private String userName;
    private String userEmail;
    private Long weekId;
    private LocalDate weekStartDate;
    private LocalDate weekEndDate;
    private Long timesheetId;
    private LocalDate workDate;
    private BigDecimal hoursWorked;
    private String status;
    private String comments;
    private LocalDateTime reviewedAt;
}
