package com.intranet.service;

import com.intranet.entity.WeekInfo;
import com.intranet.repository.WeekInfoRepo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Guards the invariant the week table depends on: for any month, every day is covered by
 * exactly one week row, and no row crosses a month boundary.
 *
 * Regression: the generator capped itself at 5 weeks, so a month starting late in the week
 * (August 2026 starts on a Saturday) left its final day uncovered. TimeSheetService's
 * fallback then created an unclamped week spanning into the next month, two rows ended up
 * covering the same date, and the single-result lookup threw NonUniqueResultException.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WeekInfoServiceTest {

    @Mock
    private WeekInfoRepo weekInfoRepository;

    @InjectMocks
    private WeekInfoService service;

    private List<WeekInfo> generate(int year, int month) {
        when(weekInfoRepository.findByStartDateAndEndDate(any(), any())).thenReturn(Optional.empty());
        when(weekInfoRepository.save(any(WeekInfo.class))).thenAnswer(i -> i.getArgument(0));

        service.generateWeeksForMonth(year, month);

        ArgumentCaptor<WeekInfo> captor = ArgumentCaptor.forClass(WeekInfo.class);
        verify(weekInfoRepository, atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void augustTwentyTwentySixCoversItsFinalDay() {
        // The month that broke production: 1 Aug 2026 is a Saturday, so it needs 6 rows.
        List<WeekInfo> weeks = generate(2026, 8);
        assertEquals(LocalDate.of(2026, 8, 1), weeks.get(0).getStartDate());
        assertEquals(LocalDate.of(2026, 8, 31), weeks.get(weeks.size() - 1).getEndDate(),
                "31 Aug 2026 must be covered; the 5-week cap used to drop it");
    }

    @Test
    void everyDayOfEveryMonthIsCoveredExactlyOnceAndNoWeekCrossesTheMonth() {
        for (YearMonth ym = YearMonth.of(2024, 1); ym.isBefore(YearMonth.of(2031, 1)); ym = ym.plusMonths(1)) {
            reset(weekInfoRepository);
            List<WeekInfo> weeks = generate(ym.getYear(), ym.getMonthValue());

            for (WeekInfo w : weeks) {
                assertEquals(ym, YearMonth.from(w.getStartDate()), ym + ": week starts outside its month");
                assertEquals(ym, YearMonth.from(w.getEndDate()), ym + ": week ends outside its month " + w.getStartDate());
                assertFalse(w.getEndDate().isBefore(w.getStartDate()), ym + ": inverted week");
            }

            for (LocalDate d = ym.atDay(1); !d.isAfter(ym.atEndOfMonth()); d = d.plusDays(1)) {
                final LocalDate day = d;
                long hits = weeks.stream()
                        .filter(w -> !w.getStartDate().isAfter(day) && !w.getEndDate().isBefore(day))
                        .count();
                assertEquals(1, hits, ym + ": " + day + " is covered by " + hits + " weeks, expected exactly 1");
            }
        }
    }
}
