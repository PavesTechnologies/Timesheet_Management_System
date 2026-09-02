package com.intranet.service.email.MonthEndRemainder;

import com.intranet.dto.email.MissingTimesheetEmailDTO;
import com.intranet.service.email.template.EmailContent;
import com.intranet.service.email.template.EmailFormats;
import com.intranet.service.email.template.EmailLayoutBuilder;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MonthEndTimesheetEmailTemplateBuilderService {

    private final EmailLayoutBuilder layoutBuilder;

    public String buildMonthEndReminderEmail(MissingTimesheetEmailDTO dto) {

        EmailContent content = EmailContent.builder()
                .title("Month-End Timesheet Reminder")
                .recipientName(dto.getUserName())
                .messageBodyHtml("As we approach the end of the month, this is a reminder to complete and "
                        + "submit your <strong>monthly timesheet</strong> before the closing date.")
                .detailsTitle("Month Period")
                .detail("Period", EmailFormats.range(dto.getStartDate(), dto.getEndDate()))
                .closingMessage("Accurate timesheet logging supports project tracking, planning, invoicing "
                        + "and payroll. Please ensure all your working days and hours are filled in correctly.")
                .noteHtml("If you have already completed your timesheet, kindly disregard this message.")
                .ctaLabel("Open Timesheet Portal")
                .signOffCaption("Automated Reminder")
                .build();

        return layoutBuilder.render(content);
    }
}
