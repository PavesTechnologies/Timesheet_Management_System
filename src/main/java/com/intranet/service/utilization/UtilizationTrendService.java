package com.intranet.service.utilization;

import com.intranet.dto.rms.RMSTrendsResponseDTO;
import com.intranet.service.RMS.RMSTrendAnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Isolated trend analytics service.
 * Delegates to RMSTrendAnalyticsService — no UMS calls, no per-resource loops.
 * Result is cached per date range to avoid repeated timesheet+entry loads.
 */
@Service
@RequiredArgsConstructor
public class UtilizationTrendService {

    private final RMSTrendAnalyticsService trendAnalyticsService;

    @Cacheable(value = "utilizationTrends", key = "#startDate + '-' + #endDate")
    public RMSTrendsResponseDTO getTrends(LocalDate startDate, LocalDate endDate) {
        return trendAnalyticsService.getTrends(startDate, endDate);
    }
}
