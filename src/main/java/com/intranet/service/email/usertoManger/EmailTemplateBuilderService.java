package com.intranet.service.email.usertoManger;

import com.intranet.dto.email.TimeSheetSummaryEmailDTO;
import com.intranet.service.email.template.EmailContent;
import com.intranet.service.email.template.EmailFormats;
import com.intranet.service.email.template.EmailLayoutBuilder;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmailTemplateBuilderService {

    private final EmailLayoutBuilder layoutBuilder;

    public String buildTimeSheetSummaryEmail(TimeSheetSummaryEmailDTO dto) {

        EmailContent content = EmailContent.builder()
                .title("Timesheet Review Update")
                .recipientName(dto.getUserName())
                .messageBodyHtml("Your timesheet has been reviewed. A summary of the outcome is below.")
                .statusLabel(dto.getStatus())
                .tone(toneFor(dto.getStatus()))
                .detailsTitle("Review Summary")
                .detail("Date Range", EmailFormats.range(dto.getStartDate(), dto.getEndDate()))
                .detail("Total Hours Logged", EmailFormats.hours(dto.getTotalHoursLogged()))
                .detail("Reviewed By", EmailFormats.text(dto.getApprovedBy()))
                .detail("Comments", dto.getReason() != null && !dto.getReason().isBlank()
                        ? dto.getReason()
                        : "No comments provided.")
                .closingMessage("Please log in to the system if any action is required from your side.")
                .noteHtml("<strong>Note:</strong> If this timesheet requires multiple approvals, "
                        + "log in to check the complete approval status.")
                .ctaLabel("View Timesheet")
                .build();

        return layoutBuilder.render(content);
    }

    private EmailContent.Tone toneFor(String status) {
        return switch (status == null ? "" : status.toUpperCase()) {
            case "APPROVED" -> EmailContent.Tone.SUCCESS;
            case "REJECTED" -> EmailContent.Tone.ALERT;
            default -> EmailContent.Tone.INFO;
        };
    }
}
