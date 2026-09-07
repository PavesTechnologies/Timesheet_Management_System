package com.intranet.service.external;

import com.intranet.service.TimeUtil;

import com.intranet.entity.*;
import com.intranet.repository.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ManagerDashboardService {

    private final WeeklyTimeSheetReviewRepo weeklyReviewRepo;
    private final TimeSheetReviewRepo timeSheetReviewRepo;
    

    public Map<String, Object> getManagerSummary(Long managerId, Set<Long> memberIds,
                                                 LocalDate startDate, LocalDate endDate) {

        // Fetch submitted weeks by team members
        List<WeeklyTimeSheetReview> submissions =
                weeklyReviewRepo.findByUserIdInAndWeekInfo_StartDateBetween(
                        new ArrayList<>(memberIds), startDate, endDate);

        // --------------------------
        // ✅ Calculate Pending Weeks
        // --------------------------
        Set<Long> submittedWeekIds = submissions.stream()
                .map(w -> w.getWeekInfo().getId())
                .collect(Collectors.toSet());

        List<Long> reviewedWeekIds = timeSheetReviewRepo
                .findDistinctWeekIdsByManagerIdAndStatusIn(
                        managerId,
                        Arrays.asList(TimeSheetReview.Status.APPROVED, TimeSheetReview.Status.REJECTED)
                );

        // Pending = submitted but not yet reviewed by this manager
        Set<Long> pendingWeekIds = new HashSet<>(submittedWeekIds);
        pendingWeekIds.removeAll(reviewedWeekIds);

        long pendingCount = pendingWeekIds.size();

        // -------------------------------
        // ✅ Calculate Missing Timesheets
        // -------------------------------
        LocalDate today = LocalDate.now();
        LocalDate startRange = today.minusDays(15);
        if (today.getDayOfMonth() < 15) {
            startRange = today.withDayOfMonth(1);
        }

        List<WeeklyTimeSheetReview> recentSubmissions =
                weeklyReviewRepo.findByUserIdInAndWeekInfo_StartDateBetween(
                        new ArrayList<>(memberIds), startRange, today);

        Set<Long> activeUsers = recentSubmissions.stream()
                .map(WeeklyTimeSheetReview::getUserId)
                .collect(Collectors.toSet());

        List<Long> missingUserIds = memberIds.stream()
                .filter(id -> !activeUsers.contains(id))
                .collect(Collectors.toList());

        List<Map<String, Object>> missingTimesheets = missingUserIds.stream()
                .map(id -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("userId", id);
                    return map;
                })
                .collect(Collectors.toList());

        // -----------------------------
        // ✅ Static placeholders for demo
        // -----------------------------
        BigDecimal totalHours = BigDecimal.valueOf(320);
        BigDecimal billableHours = BigDecimal.valueOf(260);
        BigDecimal billablePercentage = TimeUtil.percentOfHoursScaled(billableHours, totalHours);

        List<Map<String, Object>> weeklySummary = Collections.emptyList();

        // -----------------------------
        // ✅ Final Response
        // -----------------------------
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalHours", totalHours);
        result.put("billableHours", billableHours);
        result.put("billablePercentage", billablePercentage);
        result.put("pending", pendingCount);
        result.put("dateRange", Map.of("startDate", startDate, "endDate", endDate));
        result.put("weeklySummary", weeklySummary);
        result.put("missingTimesheets", missingTimesheets);

        return result;
    }
}
