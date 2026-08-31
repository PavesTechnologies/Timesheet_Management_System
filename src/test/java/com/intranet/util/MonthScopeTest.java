package com.intranet.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MonthScope is the clamp that keeps the approval queues — and the review window — to the
 * current and previous calendar month. These assertions are all relative to "now" so they stay
 * true whenever they run, including across a year boundary.
 */
class MonthScopeTest {

    private static final YearMonth CURRENT = YearMonth.now();
    private static final YearMonth PREVIOUS = CURRENT.minusMonths(1);

    @Test
    void noParamsDefaultsToCurrentMonth() {
        assertEquals(CURRENT, MonthScope.resolve(null, null));
    }

    @Test
    void acceptsCurrentAndPreviousMonth() {
        assertEquals(CURRENT, MonthScope.resolve(CURRENT.getMonthValue(), CURRENT.getYear()));
        assertEquals(PREVIOUS, MonthScope.resolve(PREVIOUS.getMonthValue(), PREVIOUS.getYear()));
    }

    @Test
    void monthWithoutYearResolvesAcrossTheYearBoundary() {
        // In January this must pick December of the PREVIOUS year, not December of this one.
        assertEquals(PREVIOUS, MonthScope.resolve(PREVIOUS.getMonthValue(), null));
        assertEquals(CURRENT, MonthScope.resolve(CURRENT.getMonthValue(), null));
    }

    @Test
    void rejectsAnythingOlderThanThePreviousMonth() {
        YearMonth twoBack = CURRENT.minusMonths(2);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MonthScope.resolve(twoBack.getMonthValue(), twoBack.getYear()));
        assertTrue(e.getMessage().contains(twoBack.toString()), e.getMessage());
    }

    @Test
    void rejectsFutureMonths() {
        YearMonth next = CURRENT.plusMonths(1);
        assertThrows(IllegalArgumentException.class,
                () -> MonthScope.resolve(next.getMonthValue(), next.getYear()));
    }

    @Test
    void rejectsMonthOutOfRangeAndYearWithoutMonth() {
        assertThrows(IllegalArgumentException.class, () -> MonthScope.resolve(0, CURRENT.getYear()));
        assertThrows(IllegalArgumentException.class, () -> MonthScope.resolve(13, CURRENT.getYear()));
        assertThrows(IllegalArgumentException.class, () -> MonthScope.resolve(null, CURRENT.getYear()));
    }

    @Test
    void weeksInTheOpenWindowAreReviewable() {
        assertTrue(MonthScope.isReviewable(CURRENT.atDay(1)));
        assertTrue(MonthScope.isReviewable(CURRENT.atEndOfMonth()));
        assertTrue(MonthScope.isReviewable(PREVIOUS.atDay(1)));
        assertTrue(MonthScope.isReviewable(PREVIOUS.atEndOfMonth()));
    }

    @Test
    void weeksOutsideTheWindowAreNotReviewable() {
        // The day before the previous month began is closed — this is the case the old rolling
        // 30-day rule got wrong, expiring parts of the previous month partway through the month.
        assertFalse(MonthScope.isReviewable(PREVIOUS.atDay(1).minusDays(1)));
        assertFalse(MonthScope.isReviewable(CURRENT.plusMonths(1).atDay(1)));
        assertFalse(MonthScope.isReviewable(null));
    }

    @Test
    void aWeekEndingLastMonthStaysReviewableAllMonth() {
        // The whole point of the change: no matter what day of the month it is today, every day
        // of the previous month is still open for review.
        for (LocalDate d = PREVIOUS.atDay(1); !d.isAfter(PREVIOUS.atEndOfMonth()); d = d.plusDays(1)) {
            assertTrue(MonthScope.isReviewable(d), "should be reviewable: " + d);
        }
    }

    @Test
    void allowedRangeLabelSpansBothMonths() {
        assertEquals(PREVIOUS.atDay(1) + " to " + CURRENT.atEndOfMonth(), MonthScope.allowedRangeLabel());
    }
}
