package com.intranet.controller;

import com.intranet.dto.HolidayExcludeUsersRequestDTO;
import com.intranet.dto.UserDTO;

import com.intranet.security.CurrentUser;
import com.intranet.service.HolidayExcludeUsersService;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/holiday-exclude-users")
public class HolidayExcludeUsersController {

    @Autowired
    private HolidayExcludeUsersService service;

    @PostMapping("/create")
    @PreAuthorize("hasAuthority('APPROVE_TIMESHEET')")
    @Operation(summary = "Create Holiday Exclude Users Entry by a manager")
    public ResponseEntity<String> createHolidayExclude(
        @CurrentUser UserDTO manager,
        @RequestBody HolidayExcludeUsersRequestDTO request) {
        try{
        String created = service.createHolidayExclude(manager.getId(),request);
        return ResponseEntity.ok(created);
        }
        catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping
    @PreAuthorize("hasAuthority('APPROVE_TIMESHEET')")
    @Operation(summary = "Get all Holiday Exclude Users for current manager")
    public ResponseEntity<?> getAllForManager(@CurrentUser UserDTO manager) {
        try {
            return ResponseEntity.ok(service.getAllByManager(manager.getId()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('APPROVE_TIMESHEET')")
    @Operation(summary = "Delete Holiday Exclude Users Entry")
    public ResponseEntity<?> deleteHolidayExclude(
            @CurrentUser UserDTO manager,
            @PathVariable Long id) {
        try {
            String deleted = service.deleteHolidayExclude(manager.getId(), id);
            return ResponseEntity.ok(deleted);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

        @PutMapping("/{id}")
        @PreAuthorize("hasAuthority('APPROVE_TIMESHEET')")
        @Operation(summary = "Update Holiday Exclude Users Entry")
        public ResponseEntity<?> updateHolidayExclude(
                @CurrentUser UserDTO manager,
                @PathVariable Long id,
                @RequestBody HolidayExcludeUsersRequestDTO request) {
            try {
                String updated = service.updateHolidayExclude(manager.getId(), id, request);
                return ResponseEntity.ok(updated);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(e.getMessage());
            }
        }

    @GetMapping("/all")
        @PreAuthorize("hasAuthority('TIMESHEET_ADMIN')")
        @Operation(summary = "Get all Holiday Exclude Users for all managers")
        public ResponseEntity<?> getAllForAllManagers() {
            try {
                int month=java.time.LocalDate.now().getMonthValue();
                int year=java.time.LocalDate.now().getYear();
                return ResponseEntity.ok(service.getAllForAllManagers(month,year));
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(e.getMessage());
            }
        }

     @GetMapping("/allusers")
    @PreAuthorize("hasAuthority('TIMESHEET_ADMIN')")
    @Operation(summary = "Get all users from UMS")
    public ResponseEntity<?> getAllUsers(HttpServletRequest request) {
        try {
            String authHeader = request.getHeader("Authorization");

            if (authHeader == null || authHeader.isBlank()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body("Authorization header missing");
            }

            return ResponseEntity.ok(service.fetchAllUsers(authHeader));

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
        }


}
