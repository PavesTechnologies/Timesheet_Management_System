package com.intranet.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "timesheet_settings")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TimesheetSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "min_hrs_regular", nullable = false, precision = 5, scale = 2)
    private BigDecimal minHrsRegular;

    @Column(name = "min_hrs_weekend", nullable = false, precision = 5, scale = 2)
    private BigDecimal minHrsWeekend;

    @Column(name = "autogen_leave_hrs", nullable = false, precision = 5, scale = 2)
    private BigDecimal autogenLeaveHrs;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
