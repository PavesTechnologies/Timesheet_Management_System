package com.intranet.service;

import com.intranet.entity.TimeSheetEntry;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the HH.MM contract. Hours are stored as an HH.MM *literal*: 8.30 is 8h30m, NOT
 * 8.3 hours. Summing them with plain BigDecimal.add loses 40 minutes on every carry,
 * which is what made /api/dashboard/summary report 30 hrs for a 32 hr week.
 */
class TimeUtilTest {

    private static TimeSheetEntry entry(String from, String to) {
        TimeSheetEntry e = new TimeSheetEntry();
        e.setFromTime(LocalDateTime.parse(from));
        e.setToTime(LocalDateTime.parse(to));
        return e;
    }

    @Test
    void halfHoursCarryIntoWholeHours() {
        // 0.30 + 0.30 is one hour, not 0.60.
        assertEquals(new BigDecimal("1.00"),
                TimeUtil.sumHours(List.of(new BigDecimal("0.30"), new BigDecimal("0.30"))));
        // The regression in one line: plain arithmetic disagrees.
        assertEquals(new BigDecimal("0.60"),
                new BigDecimal("0.30").add(new BigDecimal("0.30")));
    }

    @Test
    void theDayFromTheBugReportSumsToEightHours() {
        List<BigDecimal> day = List.of(
                new BigDecimal("1.00"), new BigDecimal("1.00"), new BigDecimal("0.30"),
                new BigDecimal("0.30"), new BigDecimal("1.00"), new BigDecimal("1.00"),
                new BigDecimal("1.30"), new BigDecimal("0.30"), new BigDecimal("1.00"));

        assertEquals(new BigDecimal("8.00"), TimeUtil.sumHours(day));
        assertEquals(new BigDecimal("7.20"), day.stream().reduce(BigDecimal.ZERO, BigDecimal::add),
                "plain add under-reports by 40 minutes - this is the bug being guarded against");
    }

    @Test
    void fourSuchDaysAreThirtyTwoHoursNotThirty() {
        List<BigDecimal> week = new ArrayList<>();
        for (int i = 0; i < 4; i++) week.add(new BigDecimal("8.00"));
        assertEquals(new BigDecimal("32.00"), TimeUtil.sumHours(week));

        List<BigDecimal> halves = List.of(new BigDecimal("7.30"), new BigDecimal("7.30"));
        assertEquals(new BigDecimal("15.00"), TimeUtil.sumHours(halves));
        assertEquals(new BigDecimal("14.60"), halves.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Test
    void hhmmToMinutesReadsTheFractionAsMinutes() {
        assertEquals(0, TimeUtil.hhmmToMinutes(null));
        assertEquals(0, TimeUtil.hhmmToMinutes(BigDecimal.ZERO));
        assertEquals(510, TimeUtil.hhmmToMinutes(new BigDecimal("8.30")));
        // trailing-zero stripping must not change the reading
        assertEquals(TimeUtil.hhmmToMinutes(new BigDecimal("1.10")),
                     TimeUtil.hhmmToMinutes(new BigDecimal("1.1")));
        // already-overflowed minutes normalise
        assertEquals(130, TimeUtil.hhmmToMinutes(new BigDecimal("1.70")));
    }

    @Test
    void minutesToHhmmRoundTrips() {
        for (long m : new long[]{0, 1, 59, 60, 61, 90, 480, 1439, 5000}) {
            assertEquals(m, TimeUtil.hhmmToMinutes(TimeUtil.minutesToHHMM(m)), "round trip for " + m);
        }
        assertEquals(new BigDecimal("1.10"), TimeUtil.minutesToHHMM(70));
    }

    @Test
    void sumEntryHoursIgnoresTheStoredColumnAndUsesRawTimes() {
        TimeSheetEntry e = entry("2026-09-01T09:00:00", "2026-09-01T09:30:00");
        e.setHoursWorked(new BigDecimal("99.00")); // deliberately corrupt
        assertEquals(new BigDecimal("0.30"), TimeUtil.sumEntryHours(List.of(e)));
    }

    @Test
    void sumEntryHoursSkipsIncompleteEntriesAndEmptyLists() {
        assertEquals(BigDecimal.ZERO, TimeUtil.sumEntryHours(null));
        assertEquals(BigDecimal.ZERO, TimeUtil.sumEntryHours(List.of()));

        TimeSheetEntry open = new TimeSheetEntry();
        open.setFromTime(LocalDateTime.parse("2026-09-01T09:00:00"));
        assertEquals(new BigDecimal("0.00"), TimeUtil.sumEntryHours(List.of(open)));
    }

    @Test
    void sumEntryHoursCarriesAcrossManyHalfHours() {
        List<TimeSheetEntry> entries = List.of(
                entry("2026-09-01T09:00:00", "2026-09-01T09:30:00"),
                entry("2026-09-01T10:00:00", "2026-09-01T10:30:00"),
                entry("2026-09-01T11:00:00", "2026-09-01T11:30:00"),
                entry("2026-09-01T12:00:00", "2026-09-01T12:30:00"));
        assertEquals(new BigDecimal("2.00"), TimeUtil.sumEntryHours(entries));
    }

    @Test
    void subtractHoursBorrowsFromTheHour() {
        // 8.00 - 0.30 is 7h30m. BigDecimal.subtract gives 7.70, i.e. 7h70m - not a real time.
        assertEquals(new BigDecimal("7.30"),
                TimeUtil.subtractHours(new BigDecimal("8.00"), new BigDecimal("0.30")));
        assertEquals(new BigDecimal("7.70"),
                new BigDecimal("8.00").subtract(new BigDecimal("0.30")));
    }

    @Test
    void subtractHoursFloorsAtZero() {
        assertEquals(new BigDecimal("0.00"),
                TimeUtil.subtractHours(new BigDecimal("1.00"), new BigDecimal("5.00")));
    }

    @Test
    void divideHoursSplitsMinutesNotDecimals() {
        // 7.30 / 2 is 3h45m. Plain divide gives 3.65, i.e. 3h65m.
        assertEquals(new BigDecimal("3.45"), TimeUtil.divideHours(new BigDecimal("7.30"), 2));
        assertEquals(new BigDecimal("8.00"), TimeUtil.divideHours(new BigDecimal("32.00"), 4));
        assertEquals(BigDecimal.ZERO, TimeUtil.divideHours(new BigDecimal("8.00"), 0));
    }

    @Test
    void percentOfHoursUsesMinutes() {
        // 7.30 of 15.00 is exactly half: 450 of 900 minutes.
        assertEquals(50.0, TimeUtil.percentOfHours(new BigDecimal("7.30"), new BigDecimal("15.00")), 0.0001);
        assertEquals(0.0, TimeUtil.percentOfHours(new BigDecimal("1.00"), BigDecimal.ZERO), 0.0001);
        assertEquals(new BigDecimal("50.00"),
                TimeUtil.percentOfHoursScaled(new BigDecimal("7.30"), new BigDecimal("15.00")));
    }

    @Test
    void minutesResultToHhmmIsNullSafeAndRounds() {
        assertEquals(new BigDecimal("0.00"), TimeUtil.minutesResultToHHMM(null));
        assertEquals(new BigDecimal("8.00"), TimeUtil.minutesResultToHHMM(new BigDecimal("480")));
        // a driver handing back 479.9999 must not lose a minute
        assertEquals(new BigDecimal("8.00"), TimeUtil.minutesResultToHHMM(479.9999d));
    }
}
