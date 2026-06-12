package com.intranet.service.email.usertoManger;

import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.intranet.util.EmailUtil;


@Service
@RequiredArgsConstructor
public class EmailService {

    private final EmailUtil emailUtil;

    @Value("${spring.mail.username}")
    private String fromEmail;

    @Value("${app.frontend.url}")
    private String frontendBaseUrl;

     /**
     * Sends reminder emails to a list of recipients.
     *
     * @param emailList list of email IDs
     */
    public void sendReminderEmails(List<String> emailList) {
        // Define the subject line
        String subject = "Reminder: Timesheet Submission Pending";

        String viewLink = frontendBaseUrl != null && !frontendBaseUrl.isBlank()
                ? frontendBaseUrl
                : "#";

        // Beautiful, professional HTML email body (link substituted at runtime)
        String htmlBody = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <title>Timesheet Reminder</title>
        </head>
        <body style="font-family: 'Segoe UI', Arial, sans-serif; background-color:#f4f6f8; margin:0; padding:0;">
            <table align="center" width="100%" style="max-width:600px; background-color:#ffffff; border-radius:10px; box-shadow:0 3px 8px rgba(0,0,0,0.1); overflow:hidden;">
            <tr>
                <td style="background-color:#0d6efd; padding:20px 30px; text-align:center;">
                <h1 style="color:#ffffff; margin:0; font-size:24px;">Timesheet Management</h1>
                </td>
            </tr>
            <tr>
                <td style="padding:30px;">
                <h2 style="color:#333333; margin-top:0;">🕒 Reminder to Submit Your Timesheet</h2>
                <p style="font-size:16px; color:#555555; line-height:1.6;">
                    Hello,
                </p>
                <p style="font-size:16px; color:#555555; line-height:1.6;">
                    We’ve noticed that you haven’t submitted your <b>timesheets for the past 15 days</b>.
                    Please take a moment to update your timesheets to ensure accurate record keeping and payroll processing.
                </p>
                <div style="text-align:center; margin:30px 0;">
                    <a href="{{VIEW_LINK}}"
                    style="background-color:#0d6efd; color:#ffffff; text-decoration:none;
                            padding:12px 24px; border-radius:6px; font-size:16px; display:inline-block;">
                    Submit Timesheet
                    </a>
                </div>
                <p style="font-size:15px; color:#666666;">
                    If you’ve already submitted your timesheet, please disregard this message.
                </p>
                <p style="margin-top:30px; font-size:14px; color:#888888;">
                    Regards,<br>
                    <strong>Timesheet Management Team</strong><br>
                    <span style="font-size:12px; color:#aaaaaa;">Automated Reminder System</span>
                </p>
                </td>
            </tr>
            <tr>
                <td style="background-color:#f0f0f0; text-align:center; padding:15px; font-size:12px; color:#999999;">
                © 2025 Timesheet Management System. All rights reserved.
                </td>
            </tr>
            </table>
        </body>
        </html>
        """.replace("{{VIEW_LINK}}", viewLink);

        // Send to each email in the list
        for (String email : emailList) {
            try {
                emailUtil.sendEmail(email, subject, htmlBody);
                System.out.println("✅ Reminder sent to: " + email);
            } catch (MessagingException e) {
                System.err.println("❌ Failed to send reminder to " + email + ": " + e.getMessage());
            }
        }
    }
}
