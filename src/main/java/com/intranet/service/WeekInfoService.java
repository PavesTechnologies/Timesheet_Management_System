package com.intranet.service;

import com.intranet.entity.WeekInfo;
import com.intranet.repository.WeekInfoRepo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class WeekInfoService {

    private final WeekInfoRepo weekInfoRepository;

    /**
     * Generate and persist all weeks for a given month.
     */
    @Transactional
    public void generateWeeksForMonth(int year, int month) {
        log.info("🧮 Generating WeekInfo for {}/{}", month, year);

        LocalDate firstDayOfMonth = LocalDate.of(year, month, 1);
        LocalDate lastDayOfMonth = firstDayOfMonth.withDayOfMonth(firstDayOfMonth.lengthOfMonth());

        // Find the first Monday before or equal to the 1st of the month
        LocalDate currentStart = firstDayOfMonth.with(DayOfWeek.MONDAY);
        if (currentStart.isAfter(firstDayOfMonth)) {
            currentStart = currentStart.minusWeeks(1);
        }

        int weekNo = 1;

        // No week cap: a month spans 6 calendar weeks whenever it starts late in the week
        // (e.g. Aug 2026 starts on a Saturday and needs 6 rows). Capping at 5 left the
        // last day of such months with no week row at all, which then made
        // TimeSheetService's fallback invent an unclamped, overlapping week.
        while (currentStart.isBefore(lastDayOfMonth.plusDays(1))) {
            LocalDate currentEnd = currentStart.plusDays(6);

            // Boundaries (clamped to the month)
            LocalDate effectiveStart = currentStart.isBefore(firstDayOfMonth) ? firstDayOfMonth : currentStart;
            LocalDate effectiveEnd = currentEnd.isAfter(lastDayOfMonth) ? lastDayOfMonth : currentEnd;

            boolean isIncompleteWeek = !effectiveStart.equals(currentStart) || !effectiveEnd.equals(currentEnd);

            // Avoid duplicates
            Optional<WeekInfo> existing = weekInfoRepository.findByStartDateAndEndDate(effectiveStart, effectiveEnd);
            if (existing.isEmpty()) {
                WeekInfo weekInfo = new WeekInfo();
                weekInfo.setStartDate(effectiveStart);
                weekInfo.setEndDate(effectiveEnd);
                weekInfo.setWeekNo(weekNo);
                weekInfo.setYear(year);
                weekInfo.setMonth(month);
                weekInfo.setIncompleteWeek(isIncompleteWeek);

                weekInfoRepository.save(weekInfo);
                log.info("✅ Saved week {} → {} to {} (incomplete: {})", weekNo, effectiveStart, effectiveEnd, isIncompleteWeek);
            } else {
                log.info("⚠️ Week already exists: {} to {}", effectiveStart, effectiveEnd);
            }

            currentStart = currentStart.plusWeeks(1);
            weekNo++;
        }

        log.info("✅ Completed generating WeekInfo for {}/{}", month, year);
    }
}
