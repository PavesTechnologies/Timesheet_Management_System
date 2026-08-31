package com.intranet.util;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * The one definition of "which calendar months a reviewer may work in".
 *
 * <p>The approval queues (manager, reporting manager, admin) can be viewed for the CURRENT
 * or the PREVIOUS calendar month, and a week is reviewable exactly when its end date falls
 * inside that same two-month window. The query side (controllers turning {@code ?month=&year=}
 * into a date range) and the write side ({@code TimeSheetReviewService}) both read the window
 * from here, so the list a reviewer sees and the list they may act on can never drift apart.
 *
 * <p>This replaced a rolling 30-day cutoff, which expired most of the previous month by
 * mid-month and all of it by month-end — making previous-month approval impossible.
 */
public final class MonthScope {

    private MonthScope() {
    }

    /** The month the queues default to. */
    public static YearMonth current() {
        return YearMonth.from(LocalDate.now());
    }

    /** The only other month the queues may show. */
    public static YearMonth previous() {
        return current().minusMonths(1);
    }

    /** True when the month is the current or the previous calendar month. */
    public static boolean isAllowed(YearMonth ym) {
        return ym != null && (ym.equals(current()) || ym.equals(previous()));
    }

    /**
     * Resolve the optional {@code month}/{@code year} query params.
     * <ul>
     *   <li>both null &rarr; {@link #current()}, so existing callers are unaffected</li>
     *   <li>month only &rarr; the year that makes it current or previous (handles Dec asked
     *       for in January)</li>
     *   <li>month + year &rarr; that month, when it is current or previous</li>
     * </ul>
     *
     * @throws IllegalArgumentException when month is outside 1..12, a year is supplied without
     *         a month, or the month is neither the current nor the previous calendar month.
     */
    public static YearMonth resolve(Integer month, Integer year) {
        if (month == null && year == null) {
            return current();
        }
        if (month == null) {
            throw new IllegalArgumentException("'month' is required when 'year' is supplied.");
        }
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("'month' must be between 1 and 12 (got " + month + ").");
        }
        if (year == null) {
            YearMonth thisYear = YearMonth.of(current().getYear(), month);
            if (isAllowed(thisYear)) {
                return thisYear;
            }
            // December requested in January belongs to the year before.
            YearMonth lastYear = thisYear.minusYears(1);
            if (isAllowed(lastYear)) {
                return lastYear;
            }
            throw outOfRange(thisYear);
        }
        YearMonth requested = YearMonth.of(year, month);
        if (!isAllowed(requested)) {
            throw outOfRange(requested);
        }
        return requested;
    }

    /**
     * True when a week may still be reviewed: its end date falls in the current or the previous
     * calendar month. A week ending in July stays reviewable all through August and closes on
     * 1 September.
     */
    public static boolean isReviewable(LocalDate weekEndDate) {
        return weekEndDate != null && isAllowed(YearMonth.from(weekEndDate));
    }

    /** e.g. "2026-07-01 to 2026-08-31" — for user-facing messages. */
    public static String allowedRangeLabel() {
        return previous().atDay(1) + " to " + current().atEndOfMonth();
    }

    private static IllegalArgumentException outOfRange(YearMonth requested) {
        return new IllegalArgumentException(String.format(
                "Only the current (%s) or previous (%s) calendar month can be requested (got %s).",
                current(), previous(), requested));
    }
}
