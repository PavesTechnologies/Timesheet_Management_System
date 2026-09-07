package com.intranet.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import com.intranet.entity.TimeSheet;
import com.intranet.entity.TimeSheetEntry;

public class TimeUtil {

    // Converts fromTime and toTime into HH.MM
    public static BigDecimal calculateHours(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null) return BigDecimal.ZERO;
        if (to.isBefore(from)) throw new IllegalArgumentException("toTime cannot be before fromTime");

        long mins = Duration.between(from, to).toMinutes();
        return minutesToHHMM(mins);
    }

    // Convert a raw minute count to HH.MM (e.g., 70 -> 1.10)
    public static BigDecimal minutesToHHMM(long totalMinutes) {
        if (totalMinutes < 0) totalMinutes = 0;
        long h = totalMinutes / 60;
        long m = totalMinutes % 60;
        return new BigDecimal(String.format("%d.%02d", h, m));
    }

    // Convert an HH.MM BigDecimal back to total minutes.
    // Tolerates: trailing-zero stripping ("1.1" == "1.10"), already-overflowed minutes (1.70 -> 130 min),
    // and ZERO / null.
    public static long hhmmToMinutes(BigDecimal hhmm) {
        if (hhmm == null) return 0;
        BigDecimal scaled = hhmm.setScale(2, RoundingMode.HALF_UP);
        long centi = scaled.movePointRight(2).longValueExact();
        long h = centi / 100;
        long m = centi % 100;
        h += m / 60;
        m = m % 60;
        return h * 60 + m;
    }

    /**
     * Add two HH.MM literals with proper minute roll-over: 0.30 + 0.30 = 1.00, not 0.60.
     *
     * <p>Drop-in replacement for {@code BigDecimal::add} wherever the operands are hours,
     * so an existing {@code reduce(BigDecimal.ZERO, BigDecimal::add)} becomes
     * {@code reduce(BigDecimal.ZERO, TimeUtil::addHours)} without restructuring the stream.
     */
    public static BigDecimal addHours(BigDecimal a, BigDecimal b) {
        return minutesToHHMM(hhmmToMinutes(a) + hhmmToMinutes(b));
    }

    /** Subtract HH.MM literals, floored at zero. */
    public static BigDecimal subtractHours(BigDecimal a, BigDecimal b) {
        return minutesToHHMM(Math.max(0, hhmmToMinutes(a) - hhmmToMinutes(b)));
    }

    /**
     * a / b as a percentage, computed in minutes. Dividing HH.MM literals directly reads
     * 7.30 as 7.3 and skews the result.
     */
    public static double percentOfHours(BigDecimal part, BigDecimal whole) {
        long wholeMinutes = hhmmToMinutes(whole);
        if (wholeMinutes == 0) return 0.0;
        return hhmmToMinutes(part) * 100.0 / wholeMinutes;
    }

    /**
     * Divide an HH.MM literal by a plain count, in minutes. Dividing the literal directly
     * produces impossible times: 7.30 / 2 = 3.65, i.e. 3h65m. This returns 3.45.
     */
    public static BigDecimal divideHours(BigDecimal hours, long divisor) {
        if (divisor == 0) return BigDecimal.ZERO;
        return minutesToHHMM(Math.round(hhmmToMinutes(hours) / (double) divisor));
    }

    /** {@link #percentOfHours} as a 2dp BigDecimal, for callers that return one. */
    public static BigDecimal percentOfHoursScaled(BigDecimal part, BigDecimal whole) {
        return BigDecimal.valueOf(percentOfHours(part, whole)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * HH.MM literal from a raw SUM(minutes) coming back from JPQL. Null-safe, and rounds
     * rather than truncating: the driver may hand back a Double or an unscaled DECIMAL, and
     * longValue() on 1919.9999 would silently drop a minute.
     */
    public static BigDecimal minutesResultToHHMM(Object minutes) {
        if (minutes == null) return BigDecimal.ZERO.setScale(2);
        return minutesToHHMM(Math.round(((Number) minutes).doubleValue()));
    }

    // Sum a list of HH.MM BigDecimals with proper minute roll-over.
    public static BigDecimal sumHours(List<BigDecimal> hoursList) {
        long totalMinutes = 0;
        if (hoursList != null) {
            for (BigDecimal h : hoursList) {
                totalMinutes += hhmmToMinutes(h);
            }
        }
        return minutesToHHMM(totalMinutes);
    }

    // Sum entry durations from raw fromTime/toTime; returns HH.MM.
    // Bypasses the (sometimes-corrupt) entry.hoursWorked column.
    public static BigDecimal sumEntryHours(List<TimeSheetEntry> entries) {
        if (entries == null || entries.isEmpty()) return BigDecimal.ZERO;
        long mins = 0;
        for (TimeSheetEntry e : entries) {
            if (e.getFromTime() == null || e.getToTime() == null) continue;
            mins += Duration.between(e.getFromTime(), e.getToTime()).toMinutes();
        }
        return minutesToHHMM(mins);
    }

    // Authoritative total for a single timesheet:
    //   - auto-generated: trust stored hoursWorked (no entries with times)
    //   - otherwise: recompute from entries' fromTime/toTime
    public static BigDecimal computeTimeSheetHours(TimeSheet ts) {
        if (ts == null) return BigDecimal.ZERO;
        if (Boolean.TRUE.equals(ts.getAutoGenerated())) {
            return ts.getHoursWorked() != null ? ts.getHoursWorked() : BigDecimal.ZERO;
        }
        return sumEntryHours(ts.getEntries());
    }
}
