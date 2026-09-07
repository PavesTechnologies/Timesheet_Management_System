package com.intranet.repository;

import com.intranet.service.TimeUtil;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;


import com.intranet.dto.rms.RMSProjectHoursDTO;
import com.intranet.entity.TimeSheet;
import com.intranet.entity.TimeSheetEntry;




public interface TimeSheetEntryRepo extends JpaRepository<TimeSheetEntry, Long>{
    /*
     * HH.MM literals cannot be summed with SQL SUM(): 0.30 + 0.30 gives 0.60 where the answer
     * is 1.00. These queries therefore sum MINUTES - FLOOR(h)*60 + (h - FLOOR(h))*100, exact
     * because the column is DECIMAL - and the default methods below convert back to HH.MM,
     * so every existing caller keeps the same signature and return type.
     */
List<TimeSheetEntry> findByTimeSheetId(Long timeSheetId);
// ✅ Duplicate exact range
    boolean existsByTimeSheet_IdAndFromTimeAndToTimeAndIdNot(
            Long timeSheetId,
            LocalDateTime fromTime,
            LocalDateTime toTime,
            Long excludeId
    );
    
    // ✅ Overlapping range check
    @Query("""
        SELECT CASE WHEN COUNT(e) > 0 THEN TRUE ELSE FALSE END
        FROM TimeSheetEntry e
        WHERE e.timeSheet.id = :timeSheetId
          AND e.id <> :excludeId
          AND (
              (:fromTime BETWEEN e.fromTime AND e.toTime)
              OR (:toTime BETWEEN e.fromTime AND e.toTime)
              OR (e.fromTime BETWEEN :fromTime AND :toTime)
              OR (e.toTime BETWEEN :fromTime AND :toTime)
          )
    """)
    boolean existsOverlappingEntry(@Param("timeSheetId") Long timeSheetId,
                                   @Param("fromTime") LocalDateTime fromTime,
                                   @Param("toTime") LocalDateTime toTime,
                                   @Param("excludeId") Long excludeId);
                                   
    List<TimeSheetEntry> findByTimeSheet_IdOrderByFromTimeAsc(Long timesheetId);


    @Query("""
        SELECT e.taskId, SUM(FLOOR(e.hoursWorked) * 60 + (e.hoursWorked - FLOOR(e.hoursWorked)) * 100)
        FROM TimeSheetEntry e
        WHERE e.projectId = :projectId
          AND e.timeSheet.userId = :userId
        GROUP BY e.taskId
    """)
    List<Object[]> findTaskDurationsByProjectAndUserMinutes(Long projectId, Long userId);

    /** [taskId, HH.MM hours]; the query above sums minutes. */
    default List<Object[]> findTaskDurationsByProjectAndUser(Long projectId, Long userId) {
        List<Object[]> rows = findTaskDurationsByProjectAndUserMinutes(projectId, userId);
        rows.forEach(r -> r[1] = TimeUtil.minutesResultToHHMM(r[1]));
        return rows;
    }

    @Query("""
        SELECT e.taskId, SUM(FLOOR(e.hoursWorked) * 60 + (e.hoursWorked - FLOOR(e.hoursWorked)) * 100)
        FROM TimeSheetEntry e
        WHERE e.projectId = :projectId
          AND e.timeSheet.userId = :userId
          AND e.timeSheet.workDate BETWEEN :startDate AND :endDate
        GROUP BY e.taskId
    """)
    List<Object[]> findTaskDurationsByProjectAndUserAndDateRangeMinutes(
            Long projectId,
            Long userId,
            LocalDate startDate,
            LocalDate endDate
    );

    /** [taskId, HH.MM hours]; the query above sums minutes. */
    default List<Object[]> findTaskDurationsByProjectAndUserAndDateRange(
            Long projectId,
            Long userId,
            LocalDate startDate,
            LocalDate endDate
    ) {
        List<Object[]> rows = findTaskDurationsByProjectAndUserAndDateRangeMinutes(projectId, userId, startDate, endDate);
        rows.forEach(r -> r[1] = TimeUtil.minutesResultToHHMM(r[1]));
        return rows;
    }

    @Query("""
        SELECT e.taskId, SUM(FLOOR(e.hoursWorked) * 60 + (e.hoursWorked - FLOOR(e.hoursWorked)) * 100)
        FROM TimeSheetEntry e
        WHERE e.projectId = :projectId
          AND e.timeSheet.userId = :userId
          AND e.timeSheet.workDate BETWEEN :startDate AND :endDate
        GROUP BY e.taskId
    """)
    List<Object[]> findTaskDurationsByUserProjectAndDateRangeMinutes(
            Long userId,
            Long projectId,
            LocalDate startDate,
            LocalDate endDate
    );

    /** [taskId, HH.MM hours]; the query above sums minutes. */
    default List<Object[]> findTaskDurationsByUserProjectAndDateRange(
            Long userId,
            Long projectId,
            LocalDate startDate,
            LocalDate endDate
    ) {
        List<Object[]> rows = findTaskDurationsByUserProjectAndDateRangeMinutes(userId, projectId, startDate, endDate);
        rows.forEach(r -> r[1] = TimeUtil.minutesResultToHHMM(r[1]));
        return rows;
    }
    boolean existsByProjectIdAndTaskId(Integer projectId, Integer taskId);

    @Query("""
    SELECT COALESCE(SUM(FLOOR(e.hoursWorked) * 60 + (e.hoursWorked - FLOOR(e.hoursWorked)) * 100), 0)
    FROM TimeSheetEntry e
    WHERE e.timeSheet.userId = :userId
    AND e.timeSheet.workDate BETWEEN :startDate AND :endDate
    AND e.isBillable = true
    """)
    BigDecimal getBillableMinutes(Long userId, LocalDate startDate, LocalDate endDate);

    /** Total as an HH.MM literal; the query above sums minutes. */
    default BigDecimal getBillableHours(Long userId, LocalDate startDate, LocalDate endDate) {
        return TimeUtil.minutesResultToHHMM(getBillableMinutes(userId, startDate, endDate));
    }

    @Query("""
    SELECT COALESCE(SUM(FLOOR(e.hoursWorked) * 60 + (e.hoursWorked - FLOOR(e.hoursWorked)) * 100), 0)
    FROM TimeSheetEntry e
    WHERE e.timeSheet.userId = :userId
    AND e.timeSheet.workDate BETWEEN :startDate AND :endDate
    AND e.isBillable = false
    """)
    BigDecimal getNonBillableMinutes(Long userId, LocalDate startDate, LocalDate endDate);

    /** Total as an HH.MM literal; the query above sums minutes. */
    default BigDecimal getNonBillableHours(Long userId, LocalDate startDate, LocalDate endDate) {
        return TimeUtil.minutesResultToHHMM(getNonBillableMinutes(userId, startDate, endDate));
    }


@Query("""
    SELECT new com.intranet.dto.rms.RMSProjectHoursDTO(
        e.projectId,
        COALESCE(SUM(FLOOR(e.hoursWorked) * 60 + (e.hoursWorked - FLOOR(e.hoursWorked)) * 100), 0)
    )
    FROM TimeSheetEntry e
    WHERE e.timeSheet.userId = :userId
    AND e.timeSheet.workDate BETWEEN :startDate AND :endDate
        GROUP BY e.projectId
    """)
    List<RMSProjectHoursDTO> getProjectMinutes(Long userId, LocalDate startDate, LocalDate endDate);

    /** Per-project totals as HH.MM literals; the query above sums minutes. */
    default List<RMSProjectHoursDTO> getProjectHours(Long userId, LocalDate startDate, LocalDate endDate) {
        List<RMSProjectHoursDTO> rows = getProjectMinutes(userId, startDate, endDate);
        rows.forEach(r -> r.setHours(TimeUtil.minutesResultToHHMM(r.getHours())));
        return rows;
    }

    @Query("""
    SELECT COALESCE(SUM(FLOOR(e.hoursWorked) * 60 + (e.hoursWorked - FLOOR(e.hoursWorked)) * 100), 0)
    FROM TimeSheetEntry e
    WHERE e.timeSheet.workDate BETWEEN :startDate AND :endDate
    AND e.isBillable = true
    """)
    BigDecimal getBillableMinutesForAllUsers(LocalDate startDate, LocalDate endDate);

    /** Total as an HH.MM literal; the query above sums minutes. */
    default BigDecimal getBillableHoursForAllUsers(LocalDate startDate, LocalDate endDate) {
        return TimeUtil.minutesResultToHHMM(getBillableMinutesForAllUsers(startDate, endDate));
    }

    @Query("""
    SELECT COALESCE(SUM(FLOOR(e.hoursWorked) * 60 + (e.hoursWorked - FLOOR(e.hoursWorked)) * 100), 0)
    FROM TimeSheetEntry e
    WHERE e.timeSheet.workDate BETWEEN :startDate AND :endDate
    AND e.isBillable = false
    """)
    BigDecimal getNonBillableMinutesForAllUsers(LocalDate startDate, LocalDate endDate);

    /** Total as an HH.MM literal; the query above sums minutes. */
    default BigDecimal getNonBillableHoursForAllUsers(LocalDate startDate, LocalDate endDate) {
        return TimeUtil.minutesResultToHHMM(getNonBillableMinutesForAllUsers(startDate, endDate));
    }

    @Query("""
    SELECT new com.intranet.dto.rms.RMSProjectHoursDTO(
        e.projectId,
        COALESCE(SUM(FLOOR(e.hoursWorked) * 60 + (e.hoursWorked - FLOOR(e.hoursWorked)) * 100), 0)
    )
    FROM TimeSheetEntry e
    WHERE e.timeSheet.workDate BETWEEN :startDate AND :endDate
        GROUP BY e.projectId
    """)
    List<RMSProjectHoursDTO> getProjectMinutesForAllUsers(LocalDate startDate, LocalDate endDate);

    /** Per-project totals as HH.MM literals; the query above sums minutes. */
    default List<RMSProjectHoursDTO> getProjectHoursForAllUsers(LocalDate startDate, LocalDate endDate) {
        List<RMSProjectHoursDTO> rows = getProjectMinutesForAllUsers(startDate, endDate);
        rows.forEach(r -> r.setHours(TimeUtil.minutesResultToHHMM(r.getHours())));
        return rows;
    }

    @Query("""
    SELECT t.id, t.userId, t.workDate, COALESCE(SUM(FLOOR(e.hoursWorked) * 60 + (e.hoursWorked - FLOOR(e.hoursWorked)) * 100), 0), t.status
    FROM TimeSheet t
    LEFT JOIN TimeSheetEntry e ON e.timeSheet.id = t.id
        AND e.projectId = :projectId
        AND e.isBillable = true
    WHERE t.status = :status
      AND t.workDate BETWEEN :startDate AND :endDate
      AND EXISTS (
          SELECT 1
          FROM TimeSheetEntry e2
          WHERE e2.timeSheet.id = t.id
            AND e2.projectId = :projectId
            AND e2.isBillable = true
      )
    GROUP BY t.id, t.userId, t.workDate, t.status
    ORDER BY t.workDate ASC, t.userId ASC
    """)
    List<Object[]> findApprovedBillableTimesheetsByProjectAndDateRangeMinutes(
            @Param("projectId") Long projectId,
            @Param("status") TimeSheet.Status status,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /** [id, userId, workDate, HH.MM hours, status]; the query above sums minutes. */
    default List<Object[]> findApprovedBillableTimesheetsByProjectAndDateRange(
            @Param("projectId") Long projectId,
            @Param("status") TimeSheet.Status status,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate) {
        List<Object[]> rows = findApprovedBillableTimesheetsByProjectAndDateRangeMinutes(projectId, status, startDate, endDate);
        rows.forEach(r -> r[3] = TimeUtil.minutesResultToHHMM(r[3]));
        return rows;
    }
}
