package com.intranet.repository;

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
        SELECT e.taskId, SUM(e.hoursWorked)
        FROM TimeSheetEntry e
        WHERE e.projectId = :projectId
          AND e.timeSheet.userId = :userId
        GROUP BY e.taskId
    """)
    List<Object[]> findTaskDurationsByProjectAndUser(Long projectId, Long userId);

    @Query("""
        SELECT e.taskId, SUM(e.hoursWorked)
        FROM TimeSheetEntry e
        WHERE e.projectId = :projectId
          AND e.timeSheet.userId = :userId
          AND e.timeSheet.workDate BETWEEN :startDate AND :endDate
        GROUP BY e.taskId
    """)
    List<Object[]> findTaskDurationsByProjectAndUserAndDateRange(
            Long projectId,
            Long userId,
            LocalDate startDate,
            LocalDate endDate
    );

    @Query("""
        SELECT e.taskId, SUM(e.hoursWorked)
        FROM TimeSheetEntry e
        WHERE e.projectId = :projectId
          AND e.timeSheet.userId = :userId
          AND e.timeSheet.workDate BETWEEN :startDate AND :endDate
        GROUP BY e.taskId
    """)
    List<Object[]> findTaskDurationsByUserProjectAndDateRange(
            Long userId,
            Long projectId,
            LocalDate startDate,
            LocalDate endDate
    );
    boolean existsByProjectIdAndTaskId(Integer projectId, Integer taskId);

    @Query("""
    SELECT COALESCE(SUM(e.hoursWorked), 0)
    FROM TimeSheetEntry e
    WHERE e.timeSheet.userId = :userId
    AND e.timeSheet.workDate BETWEEN :startDate AND :endDate
    AND e.isBillable = true
    """)
    BigDecimal getBillableHours(Long userId, LocalDate startDate, LocalDate endDate);

    @Query("""
    SELECT COALESCE(SUM(e.hoursWorked), 0)
    FROM TimeSheetEntry e
    WHERE e.timeSheet.userId = :userId
    AND e.timeSheet.workDate BETWEEN :startDate AND :endDate
    AND e.isBillable = false
    """)
    BigDecimal getNonBillableHours(Long userId, LocalDate startDate, LocalDate endDate);


@Query("""
    SELECT new com.intranet.dto.rms.RMSProjectHoursDTO(
        e.projectId,
        COALESCE(SUM(e.hoursWorked), 0)
    )
    FROM TimeSheetEntry e
    WHERE e.timeSheet.userId = :userId
    AND e.timeSheet.workDate BETWEEN :startDate AND :endDate
        GROUP BY e.projectId
    """)
    List<RMSProjectHoursDTO> getProjectHours(Long userId, LocalDate startDate, LocalDate endDate);

    @Query("""
    SELECT COALESCE(SUM(e.hoursWorked), 0)
    FROM TimeSheetEntry e
    WHERE e.timeSheet.workDate BETWEEN :startDate AND :endDate
    AND e.isBillable = true
    """)
    BigDecimal getBillableHoursForAllUsers(LocalDate startDate, LocalDate endDate);

    @Query("""
    SELECT COALESCE(SUM(e.hoursWorked), 0)
    FROM TimeSheetEntry e
    WHERE e.timeSheet.workDate BETWEEN :startDate AND :endDate
    AND e.isBillable = false
    """)
    BigDecimal getNonBillableHoursForAllUsers(LocalDate startDate, LocalDate endDate);

    @Query("""
    SELECT new com.intranet.dto.rms.RMSProjectHoursDTO(
        e.projectId,
        COALESCE(SUM(e.hoursWorked), 0)
    )
    FROM TimeSheetEntry e
    WHERE e.timeSheet.workDate BETWEEN :startDate AND :endDate
        GROUP BY e.projectId
    """)
    List<RMSProjectHoursDTO> getProjectHoursForAllUsers(LocalDate startDate, LocalDate endDate);

    @Query("""
    SELECT t.id, t.userId, t.workDate, COALESCE(SUM(e.hoursWorked), 0), t.status
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
    List<Object[]> findApprovedBillableTimesheetsByProjectAndDateRange(
            @Param("projectId") Long projectId,
            @Param("status") TimeSheet.Status status,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);
}
