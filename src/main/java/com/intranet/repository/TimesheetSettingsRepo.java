package com.intranet.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.intranet.entity.TimesheetSettings;

@Repository
public interface TimesheetSettingsRepo extends JpaRepository<TimesheetSettings, Long> {

    Optional<TimesheetSettings> findFirstByIsActiveTrueOrderByIdDesc();

    @Modifying
    @Query("UPDATE TimesheetSettings s SET s.isActive = false WHERE s.isActive = true")
    int deactivateAll();
}
