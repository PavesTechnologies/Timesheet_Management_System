package com.intranet.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.intranet.dto.UserDTO;
import com.intranet.dto.UserHoursDTO;
import com.intranet.dto.UserProjectDetailsDTO;
import com.intranet.security.CurrentUser;
import com.intranet.service.DashboardService;
import com.intranet.service.TimeSheetService;
import com.intranet.util.cache.UserDirectoryService;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UserContoller {

    
    private final UserDirectoryService userDirectoryService;
    private final DashboardService dashboardService;
    private final TimeSheetService timeSheetService;

    @GetMapping("/me")
    @PreAuthorize("hasAuthority('EDIT_TIMESHEET') OR hasAuthority('APPROVE_TIMESHEET')")
    @Operation(summary = "Get current user details")
    private UserDTO getCurrentUser(@CurrentUser UserDTO userDTO) {
        return userDTO;
        
    }


    @GetMapping("/users")
    @PreAuthorize("hasAuthority('TIMESHEET_ADMIN')")
    @Operation(summary = "Get all users from UMS")
    public ResponseEntity<?> getAllUsers(HttpServletRequest request) {

        String authHeader = request.getHeader("Authorization");
        
        if (authHeader == null || authHeader.isBlank()) {
            return ResponseEntity.status(401).build(); // Unauthorized
        }
        try {

        List<Map<String,Object>> users = userDirectoryService.fetchAllUsers2(authHeader);
            return ResponseEntity.ok(users);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Failed to fetch users");
        }
    }

    @GetMapping("/users/hours")
//    @PreAuthorize("hasAuthority('TIMESHEET_ADMIN') OR hasAuthority('APPROVE_TIMESHEET')")
    @Operation(summary = "Get users with their billable and non-billable hours for a time period")
    public ResponseEntity<?> getUsersWithHours(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            HttpServletRequest request) {

        String authHeader = request.getHeader("Authorization");
        
        if (authHeader == null || authHeader.isBlank()) {
            return ResponseEntity.status(401).build(); // Unauthorized
        }

        if (startDate.isAfter(endDate)) {
            return ResponseEntity.badRequest().body("Start date cannot be after end date");
        }

        try {
            // Get all users from UMS
            List<Map<String, Object>> users = userDirectoryService.fetchAllUsers2(authHeader);
            System.out.println("DEBUG: Total users fetched from UMS: " + users.size());
            
            // Debug: Print first few users to see structure
            if (!users.isEmpty()) {
                System.out.println("DEBUG: First user data: " + users.get(0));
            }
            
            // Extract user IDs
            List<Long> userIds = users.stream()
                    .filter(user -> user.get("id") != null)
                    .map(user -> ((Number) user.get("id")).longValue())
                    .collect(Collectors.toList());
            
            System.out.println("DEBUG: Users with valid id: " + userIds.size());

            // Get hours summary for all users
            List<UserHoursDTO> usersHours = dashboardService.getUsersHoursSummary(userIds, startDate, endDate);

            // Create a map of userId -> UserHoursDTO for quick lookup
            Map<Long, UserHoursDTO> hoursMap = usersHours.stream()
                    .collect(Collectors.toMap(UserHoursDTO::getUserId, userHours -> userHours));

            // Return ALL users with their hours data (0 if no data exists)
            List<Map<String, Object>> result = users.stream()
                    .filter(user -> user.get("id") != null)
                    .map(user -> {
                        Long userId = ((Number) user.get("id")).longValue();
                        UserHoursDTO userHours = hoursMap.get(userId);
                        
                        Map<String, Object> response = new java.util.HashMap<>();
                        response.put("userId", userId);
                        String userName = user.get("name") != null ? (String) user.get("name") : "Unknown User";
                        response.put("userName", userName);
                        
                        // Get designation from user data
                        String designation = user.get("designation") != null ? (String) user.get("designation") : "Not Specified";
                        
                        if (userHours != null) {
                            // User has hours data
                            response.put("billableHours", userHours.getBillableHours());
                            response.put("nonBillableHours", userHours.getNonBillableHours());
                            response.put("designation", designation);
                            response.put("billablePercentage", userHours.getBillablePercentage());
                        } else {
                            // User has no hours data for this period - set to 0
                            response.put("billableHours", "00:00");
                            response.put("nonBillableHours", "00:00");
                            response.put("designation", designation);
                            response.put("billablePercentage", 0.0);
                        }
                        
                        return response;
                    })
                    .collect(Collectors.toList());

            System.out.println("DEBUG: Final result size: " + result.size());
            if (!result.isEmpty()) {
                System.out.println("DEBUG: First result entry: " + result.get(0));
            }

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            System.out.println("DEBUG: Exception occurred: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.badRequest().body("Failed to fetch users with hours: " + e.getMessage());
        }
    }

    @GetMapping("/users/{userId}/project-details")
//    @PreAuthorize("hasAuthority('EDIT_TIMESHEET') OR hasAuthority('APPROVE_TIMESHEET') OR hasAuthority('TIMESHEET_ADMIN')")
    @Operation(summary = "Get user's active projects with billable/non-billable hours and utilization")
    public ResponseEntity<?> getUserProjectDetails(
            @PathVariable Long userId,
            HttpServletRequest request) {
        
        try {
            List<UserProjectDetailsDTO> projectDetails = timeSheetService.getUserProjectDetailsWithHours(userId);
            return ResponseEntity.ok(projectDetails);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Failed to fetch user project details: " + e.getMessage());
        }
    }
}
