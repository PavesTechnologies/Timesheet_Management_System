package com.intranet.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.intranet.entity.TimeSheetReview;

@Repository
public interface TimeSheetReviewRepo  extends JpaRepository<TimeSheetReview, Long> {

    Optional<TimeSheetReview> findByTimeSheet_IdAndManagerId(Long timesheetId, Long managerId);

    Optional<TimeSheetReview> findByWeekInfo_IdAndUserIdAndManagerId(Long weekInfoId, Long userId, Long managerId);

    List<TimeSheetReview> findByTimeSheet_Id(Long id);

    List<TimeSheetReview> findByTimeSheet_IdIn(List<Long> timeSheetIds);

    @Query("SELECT DISTINCT r.weekInfo.id FROM TimeSheetReview r " +
           "WHERE r.managerId = :managerId " +
           "AND r.status IN :statuses")
    List<Long> findDistinctWeekIdsByManagerIdAndStatusIn(Long managerId, List<TimeSheetReview.Status> statuses);

    List<TimeSheetReview> findByManagerIdAndWeekInfo_IdIn(Long id, Set<Long> submittedWeekIds);

    List<TimeSheetReview> findByWeekInfo_IdAndManagerId(Long id, Long managerId);

    List<TimeSheetReview> findByTimeSheet_IdAndStatus(Long timeSheetId, TimeSheetReview.Status status);

    @Query("SELECT r FROM TimeSheetReview r " +
           "JOIN FETCH r.weekInfo w " +
           "LEFT JOIN FETCH r.timeSheet ts " +
           "WHERE r.managerId = :managerId " +
           "AND (:userId IS NULL OR r.userId = :userId) " +
           "AND w.endDate   >= :startDate " +
           "AND w.startDate <= :endDate " +
           "AND r.status IN :statuses " +
           "ORDER BY r.reviewedAt DESC")
    Page<TimeSheetReview> findReviewsByManagerWithFilters(
            @Param("managerId") Long managerId,
            @Param("userId")    Long userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate")   LocalDate endDate,
            @Param("statuses")  List<TimeSheetReview.Status> statuses,
            Pageable pageable);

}
