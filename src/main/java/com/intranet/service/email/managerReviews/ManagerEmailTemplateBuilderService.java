package com.intranet.service.email.managerReviews;

import com.intranet.dto.email.WeeklySubmissionEmailDTO;
import com.intranet.service.email.template.EmailContent;
import com.intranet.service.email.template.EmailFormats;
import com.intranet.service.email.template.EmailLayoutBuilder;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ManagerEmailTemplateBuilderService {

    private final EmailLayoutBuilder layoutBuilder;

    public String buildWeeklySubmissionEmail(WeeklySubmissionEmailDTO dto) {

        String employeeName = EmailFormats.text(dto.getUserName());

        EmailContent content = EmailContent.builder()
                .title("Weekly Timesheet Submission")
                .recipientName(dto.getManagerName())
                .messageBodyHtml("Your team member <strong>"
                        + EmailLayoutBuilder.escape(employeeName)
                        + "</strong> has submitted their weekly timesheet and it is awaiting your review.")
                .detailsTitle("Submission Details")
                .detail("Employee Name", employeeName)
                .detail("Date Range", EmailFormats.range(dto.getStartDate(), dto.getEndDate()))
                .detail("Total Hours Logged", EmailFormats.hours(dto.getTotalHoursLogged()))
                .closingMessage("Please log in to review and approve this submission. "
                        + "Timely approval keeps reporting and payroll processing accurate.")
                .ctaLabel("Review Timesheet")
                .build();

        return layoutBuilder.render(content);
    }
}
