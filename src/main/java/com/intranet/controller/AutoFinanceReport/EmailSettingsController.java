package com.intranet.controller.AutoFinanceReport;

import com.intranet.dto.UserDTO;
import com.intranet.entity.EmailSettings;
import com.intranet.security.CurrentUser;
import com.intranet.service.cornjobs.AutoFinanceReport.EmailSettingsService;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/emailSettings")
@RequiredArgsConstructor
public class EmailSettingsController {

    private final EmailSettingsService emailSettingsService;

    @GetMapping
    @Operation(summary = "Get all email settings")
    @PreAuthorize("hasAuthority('TIMESHEET_ADMIN')")
    public List<EmailSettings> getAllEmailSettings() {
        return emailSettingsService.getAllEmailSettings();
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update email settings")
    @PreAuthorize("hasAuthority('TIMESHEET_ADMIN')")
    public EmailSettings updateEmailSettings(
            @CurrentUser UserDTO user,
            @PathVariable Long id,
            @RequestBody Map<String, String> requestBody
    ) {

        String email = requestBody.get("email");

        if (email == null || email.trim().isEmpty()) {
            throw new IllegalArgumentException("Email field is required");
        }

        return emailSettingsService.updateEmailSettings(id, email, user.getId(), user.getName());
    }


}
