package com.intranet.service.MonthReportEmailSend;

import com.intranet.util.mail.MailTransport;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmailPdfSenderService {

    private final MailTransport mailTransport;

    public void sendPdfReport(String toEmail, byte[] pdfBytes, String employeeName) throws Exception {

        String subject = "Monthly Timesheet PDF Report - " + employeeName;

        String body = "Hi,\n\nPlease find attached your monthly timesheet report.\n\n"
                + "Regards,\nTimesheet Management System";

        mailTransport.send(toEmail, subject, body, false, "Monthly_Report.pdf", pdfBytes);
    }
}
