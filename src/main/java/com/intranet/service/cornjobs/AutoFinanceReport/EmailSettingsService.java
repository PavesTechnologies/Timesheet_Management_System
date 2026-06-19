package com.intranet.service.cornjobs.AutoFinanceReport;

import org.springframework.stereotype.Service;

import com.intranet.entity.EmailSettings;
import com.intranet.repository.EmailSettingsRepo;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Service
@RequiredArgsConstructor
public class EmailSettingsService {

    private final EmailSettingsRepo emailSettingsRepository;

    public List<EmailSettings> getAllEmailSettings() {
        return emailSettingsRepository.findAll();
    }

    public EmailSettings createEmailSettings(String email, Long employeeId, String employeeName) {
        EmailSettings emailSettings = new EmailSettings();
        emailSettings.setEmail(email);
        emailSettings.setEmployeeid(employeeId);
        emailSettings.setEmployeeName(employeeName);
        return emailSettingsRepository.save(emailSettings);
    }

    public EmailSettings updateEmailSettings(Long id, String email, Long employeeId, String employeeName) {
    return emailSettingsRepository.findById(id)
        .map(existing -> {
            if (email != null && !email.isEmpty()) {
                existing.setEmail(email);
                existing.setEmployeeid(employeeId);
                existing.setEmployeeName(employeeName);
                // existing.setUpdatedAt(LocalDateTime.now());
            }
            // Do NOT change reason since only email is updated
            return emailSettingsRepository.save(existing);
        })
        .orElseThrow(() -> new RuntimeException("Email Settings not found with id: " + id));
    }

}
