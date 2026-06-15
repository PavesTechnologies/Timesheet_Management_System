package com.intranet.service.RMS;

import com.intranet.dto.rms.PortfolioTrendDTO;
import com.intranet.dto.rms.RMSTrendsResponseDTO;
import com.intranet.entity.TimeSheet;
import com.intranet.entity.TimeSheetEntry;
import com.intranet.repository.TimeSheetRepo;
import com.intranet.util.RMSCalculationUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Dedicated service for chart trend analytics.
 * Loads timesheets+entries once and produces daily/weekly/monthly trend data.
 * No per-resource computation — only aggregate time-series aggregation.
 */
@Service
@RequiredArgsConstructor
public class RMSTrendAnalyticsService {

    private final TimeSheetRepo timeSheetRepository;

    public RMSTrendsResponseDTO getTrends(LocalDate startDate, LocalDate endDate) {

        // Load timesheets with entries in a single fetch-join query
        List<TimeSheet> timeSheets =
                timeSheetRepository.findByWorkDateBetweenWithWeekInfoAndEntries(startDate, endDate);

        List<TimeSheetEntry> entries = timeSheets.stream()
                .flatMap(ts -> ts.getEntries().stream())
                .collect(Collectors.toList());

        Map<LocalDate, BigDecimal> actualByDate = RMSCalculationUtils.buildActualByDate(entries);
        Map<LocalDate, Integer> plannedByDate =
                RMSCalculationUtils.buildPlannedByDate(startDate, endDate, timeSheets);

        List<PortfolioTrendDTO> daily =
                RMSCalculationUtils.buildDailyTrends(startDate, endDate, actualByDate, plannedByDate);
        List<PortfolioTrendDTO> weekly =
                RMSCalculationUtils.buildWeeklyTrends(startDate, endDate, actualByDate, plannedByDate);
        List<PortfolioTrendDTO> monthly =
                RMSCalculationUtils.buildMonthlyTrends(startDate, endDate, actualByDate, plannedByDate);

        return RMSTrendsResponseDTO.builder()
                .daily(daily)
                .weekly(weekly)
                .monthly(monthly)
                .build();
    }
}
