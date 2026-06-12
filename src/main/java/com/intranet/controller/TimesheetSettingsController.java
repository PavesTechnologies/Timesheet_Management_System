package com.intranet.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intranet.dto.TimesheetSettingsDTO;
import com.intranet.dto.UserDTO;
import com.intranet.security.CurrentUser;
import com.intranet.service.TimesheetSettingsService;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/timesheet-settings")
@RequiredArgsConstructor
public class TimesheetSettingsController {

    private final TimesheetSettingsService settingsService;

    @GetMapping("/active")
    @PreAuthorize("hasAuthority('TIMESHEET_ADMIN')")
    @Operation(summary = "Get the currently active timesheet hour settings (or defaults if none configured)")
    public ResponseEntity<?> getActive() {
        try {
            return ResponseEntity.ok(settingsService.getActiveAsDto());
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().body("Failed to load settings: " + ex.getMessage());
        }
    }

    @PostMapping
    @PreAuthorize("hasAuthority('TIMESHEET_ADMIN')")
    @Operation(summary = "Create a new timesheet settings record; previous active record is deactivated")
    public ResponseEntity<?> upsert(@CurrentUser UserDTO user,
                                    @RequestBody TimesheetSettingsDTO dto) {
        try {
            if (user == null || user.getId() == null) {
                return ResponseEntity.status(401).body("Unauthorized");
            }
            return ResponseEntity.ok(settingsService.upsert(user.getId(), dto));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().body("Failed to save settings: " + ex.getMessage());
        }
    }
}
