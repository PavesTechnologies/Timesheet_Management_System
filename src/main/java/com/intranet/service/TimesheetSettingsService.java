package com.intranet.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intranet.dto.TimesheetSettingsDTO;
import com.intranet.entity.TimesheetSettings;
import com.intranet.repository.TimesheetSettingsRepo;

import lombok.RequiredArgsConstructor;

/**
 * Values are stored in HH.MM literal convention to match TimeUtil:
 * 8.30 means 8 hours 30 minutes (NOT 8.3 decimal hours).
 */
@Service
@RequiredArgsConstructor
public class TimesheetSettingsService {

    public static final BigDecimal DEFAULT_REGULAR = new BigDecimal("8.00");
    public static final BigDecimal DEFAULT_WEEKEND = new BigDecimal("4.00");
    public static final BigDecimal DEFAULT_LEAVE   = new BigDecimal("8.00");

    private static final int MAX_MINUTES = 24 * 60;

    private final TimesheetSettingsRepo settingsRepo;

    public TimesheetSettings getActiveSettings() {
        return settingsRepo.findFirstByIsActiveTrueOrderByIdDesc()
                .orElseGet(this::buildTransientDefault);
    }

    public TimesheetSettingsDTO getActiveAsDto() {
        return toDto(getActiveSettings());
    }

    @Transactional
    public TimesheetSettingsDTO upsert(Long userId, TimesheetSettingsDTO dto) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID is required.");
        }
        if (dto == null) {
            throw new IllegalArgumentException("Settings payload is required.");
        }

        Optional<TimesheetSettings> previousOpt = settingsRepo.findFirstByIsActiveTrueOrderByIdDesc();

        BigDecimal regular = pick(dto.getMinHrsRegular(), previousOpt.map(TimesheetSettings::getMinHrsRegular), DEFAULT_REGULAR);
        BigDecimal weekend = pick(dto.getMinHrsWeekend(), previousOpt.map(TimesheetSettings::getMinHrsWeekend), DEFAULT_WEEKEND);
        BigDecimal leave   = pick(dto.getAutogenLeaveHrs(), previousOpt.map(TimesheetSettings::getAutogenLeaveHrs), DEFAULT_LEAVE);

        validateHours("minHrsRegular", regular);
        validateHours("minHrsWeekend", weekend);
        validateHours("autogenLeaveHrs", leave);

        settingsRepo.deactivateAll();

        LocalDateTime now = LocalDateTime.now();
        TimesheetSettings newRow = new TimesheetSettings();
        newRow.setMinHrsRegular(regular.setScale(2, RoundingMode.HALF_UP));
        newRow.setMinHrsWeekend(weekend.setScale(2, RoundingMode.HALF_UP));
        newRow.setAutogenLeaveHrs(leave.setScale(2, RoundingMode.HALF_UP));
        newRow.setIsActive(true);
        newRow.setUserId(userId);
        newRow.setCreatedAt(now);
        newRow.setUpdatedAt(now);

        return toDto(settingsRepo.save(newRow));
    }

    private BigDecimal pick(BigDecimal incoming, Optional<BigDecimal> previous, BigDecimal fallback) {
        if (incoming != null) return incoming;
        return previous.orElse(fallback);
    }

    private void validateHours(String field, BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required.");
        }
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(field + " must be greater than 0.");
        }
        if (value.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(field + " supports at most 2 decimal places.");
        }

        // HH.MM literal: the fractional part ".XX" is minutes, not a decimal fraction.
        BigDecimal scaled = value.setScale(2, RoundingMode.HALF_UP);
        long centi = scaled.movePointRight(2).longValueExact();
        long hours = centi / 100;
        long minutes = centi % 100;
        if (minutes > 59) {
            throw new IllegalArgumentException(field + " minutes part must be 00–59 (got " + minutes + ").");
        }
        long totalMinutes = hours * 60 + minutes;
        if (totalMinutes >= MAX_MINUTES) {
            throw new IllegalArgumentException(field + " must be less than 24:00.");
        }
    }

    private TimesheetSettings buildTransientDefault() {
        TimesheetSettings t = new TimesheetSettings();
        t.setMinHrsRegular(DEFAULT_REGULAR);
        t.setMinHrsWeekend(DEFAULT_WEEKEND);
        t.setAutogenLeaveHrs(DEFAULT_LEAVE);
        t.setIsActive(true);
        return t;
    }

    private TimesheetSettingsDTO toDto(TimesheetSettings s) {
        TimesheetSettingsDTO dto = new TimesheetSettingsDTO();
        dto.setId(s.getId());
        dto.setMinHrsRegular(s.getMinHrsRegular());
        dto.setMinHrsWeekend(s.getMinHrsWeekend());
        dto.setAutogenLeaveHrs(s.getAutogenLeaveHrs());
        dto.setIsActive(s.getIsActive());
        dto.setUserId(s.getUserId());
        dto.setCreatedAt(s.getCreatedAt());
        dto.setUpdatedAt(s.getUpdatedAt());
        return dto;
    }
}
