package com.intranet.service.email.missingWeekTimesheet;

import com.intranet.dto.email.MissingTimesheetEmailDTO;
import com.intranet.service.email.template.EmailContent;
import com.intranet.service.email.template.EmailFormats;
import com.intranet.service.email.template.EmailLayoutBuilder;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MissingTimesheetEmailTemplateBuilderService {

    private final EmailLayoutBuilder layoutBuilder;

    public String buildMissingTimesheetEmail(MissingTimesheetEmailDTO dto) {

        EmailContent content = EmailContent.builder()
                .title("Timesheet Submission Reminder")
                .recipientName(dto.getUserName())
                .messageBodyHtml("Our records show that your <strong>weekly timesheet</strong> "
                        + "has not been submitted for the period below.")
                .tone(EmailContent.Tone.ALERT)
                .detailsTitle("Pending Week")
                .detail("Missing Week", EmailFormats.range(dto.getStartDate(), dto.getEndDate()))
                .closingMessage("Please log in and complete your timesheet as soon as possible. "
                        + "Timely submission keeps reporting, project tracking and payroll accurate.")
                .noteHtml("If you believe this timesheet has already been submitted, "
                        + "please reach out to the HR team for assistance.")
                .ctaLabel("Submit Timesheet")
                .build();

        return layoutBuilder.render(content);
    }
}
