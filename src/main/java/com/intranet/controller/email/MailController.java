package com.intranet.controller.email;

import lombok.RequiredArgsConstructor;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.intranet.service.email.usertoManger.EmailService;

import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequestMapping("/api/timesheet")
@RequiredArgsConstructor
public class MailController {

    private final EmailService emailService;

    @PreAuthorize("hasAuthority('APPROVE_TIMESHEET')")
    @PostMapping("/send_reminder")
    @Operation(summary = "Send reminder emails to users who have not logged their timesheet for 15 days.")
    public ResponseEntity<String> sendReminders(@RequestBody List<String> emailList) {
        if (emailList == null || emailList.isEmpty()) {
            return ResponseEntity.badRequest().body("Email list cannot be empty ❌");
        }

        emailService.sendReminderEmails(emailList);
        return ResponseEntity.ok("Reminder emails sent successfully to " + emailList.size() + " recipients ✅");
    }
}
