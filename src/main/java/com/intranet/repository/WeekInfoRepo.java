package com.intranet.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.intranet.entity.WeekInfo;



@Repository
public interface WeekInfoRepo extends JpaRepository<WeekInfo, Long>{

    Optional<WeekInfo> findByStartDateAndEndDate(LocalDate startDate, LocalDate endDate);
    /**
     * The week covering a single date. Deliberately findFirst..OrderByStartDateDesc rather
     * than a plain Optional finder: legacy rows can overlap (a pre-fix fallback week could
     * spill across a month boundary), and a plain Optional finder throws
     * NonUniqueResultException -> HTTP 500 the moment two rows match. Ordering by start date
     * descending picks the later-starting, month-clamped row, which is the correct one.
     */
    Optional<WeekInfo> findFirstByStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(
        LocalDate startDate, LocalDate endDate);
    List<WeekInfo> findByStartDateGreaterThanEqualAndEndDateLessThanEqualOrderByStartDateAsc(LocalDate start, LocalDate end);
    List<WeekInfo> findByStartDateBetween(LocalDate startDate, LocalDate endDate);
    List<WeekInfo> findByMonthAndYear(Integer month, Integer year);
    List<WeekInfo> findByStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateAsc(
        LocalDate endDate,
        LocalDate startDate
    );
}
