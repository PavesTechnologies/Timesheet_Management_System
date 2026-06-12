package com.intranet.controller.pms;

import java.time.LocalDate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intranet.dto.pms.ProjectUserRequestDTO;
import com.intranet.service.pms.TaskDurationService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskDurationController {

    private final TaskDurationService service;

    @GetMapping("/project/{projectId}/user/{userId}")
    public ResponseEntity<?> getTasks(
            @PathVariable Long projectId,
            @PathVariable Long userId) {

        try{
            return ResponseEntity.ok(service.getTaskDurations(projectId, userId));
        }
        catch (Exception e){
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/project/{projectId}/user/{userId}/range")
    public ResponseEntity<?> getTasksByDateRange(
            @PathVariable Long projectId,
            @PathVariable Long userId,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {

        try{
            return ResponseEntity.ok(service.getTaskDurations(projectId, userId, startDate, endDate));
        }
        catch (Exception e){
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }


    @PostMapping("/project/user/tasks")
    public ResponseEntity<?> getTasksByUsersAndProject(
            @RequestBody ProjectUserRequestDTO dto) {

        try {
            return ResponseEntity.ok(service.getTaskDurationsForUsers(dto));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/project/user/tasks/dateRange")
    public ResponseEntity<?> getTaskDurationsForUsersWithDateRange(
            @RequestBody ProjectUserRequestDTO dto,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {

        try {
            return ResponseEntity.ok(
                    service.getTaskDurationsForUsersWithDateRange(dto, startDate, endDate)
            );
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                    e.getMessage()
            );
        }
    }
}
