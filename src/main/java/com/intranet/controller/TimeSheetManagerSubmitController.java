package com.intranet.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intranet.dto.ManagerTimeSheetSubmissionDTO;
import com.intranet.dto.UserDTO;
import com.intranet.entity.TimeSheet;
import com.intranet.entity.TimeSheetManagerSubmit;
import com.intranet.repository.TimeSheetManagerSubmitRepo;
import com.intranet.security.CurrentUser;
import com.intranet.service.TimeSheetService;

import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequestMapping("/api/manager_submit")
public class TimeSheetManagerSubmitController {
    
    @Autowired
    private TimeSheetService timeSheetService;

    @Autowired
    private TimeSheetManagerSubmitRepo managerSubmitRepo;

    /**
     * Manager submits timesheet for a user
     */
    @PostMapping("/submit")
    @Operation(summary = "Manager submits timesheet for a user")
    @Transactional
    public ResponseEntity<?> submitTimeSheetForUser(
            @CurrentUser UserDTO manager,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate workDate,
            @RequestBody ManagerTimeSheetSubmissionDTO submissions
        ) { 
        try {
            if (submissions.getUserId() == null) return ResponseEntity.badRequest().body("User ID cannot be null");
            if (manager.getId() == null) return ResponseEntity.badRequest().body("Manager ID cannot be null");
            if (workDate == null) return ResponseEntity.badRequest().body("Work date cannot be null");
            if (submissions.getEntries() == null || submissions.getEntries().isEmpty()) return ResponseEntity.badRequest().body("TimeSheet entries cannot be empty");
            
            TimeSheet timeSheet;
            try{
            // 1️⃣ Create or fetch timesheet for the user
            timeSheet = timeSheetService.createTimeSheet(submissions.getUserId(), workDate, submissions.getEntries());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(e.getMessage());
            }

            // 2️⃣ Create TimeSheetManagerSubmit entry
            TimeSheetManagerSubmit managerSubmit = new TimeSheetManagerSubmit();
            managerSubmit.setUserId(submissions.getUserId());
            managerSubmit.setTimeSheet(timeSheet);
            managerSubmit.setManagerId(manager.getId());
            managerSubmit.setSubmittedByManager(true);
            managerSubmit.setCreatedAt(LocalDateTime.now());
            managerSubmit.setUpdatedAt(LocalDateTime.now());
            managerSubmitRepo.save(managerSubmit);

            return ResponseEntity.ok("Timesheet submitted successfully by manager.");

        }

        catch(IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
        
        catch (Exception e) {
            return ResponseEntity.badRequest().body("Failed to submit timesheet");
        }
    }

    
}
