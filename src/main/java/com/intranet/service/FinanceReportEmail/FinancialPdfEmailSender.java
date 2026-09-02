package com.intranet.service.FinanceReportEmail;

import com.intranet.util.mail.MailTransport;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FinancialPdfEmailSender {

    private final MailTransport mailTransport;

    public void sendFinancialReportPdf(
            String toEmail,
            byte[] pdfBytes,
            String monthName,
            int year,
            String senderName
    ) throws Exception {

        String subject = "Monthly Financial Report - " + monthName + " " + year;

        String body = "Hi " + senderName + ",\n\n" +
                "Your monthly financial report is attached.\n\n" +
                "Regards,\nTimesheet Management System";

        String fileName = "Financial_Report_" + monthName + "_" + year + ".pdf";

        mailTransport.send(toEmail, subject, body, false, fileName, pdfBytes);
    }
}
