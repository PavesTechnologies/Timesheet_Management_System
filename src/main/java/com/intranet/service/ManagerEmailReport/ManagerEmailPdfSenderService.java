package com.intranet.service.ManagerEmailReport;

import com.intranet.util.mail.MailTransport;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ManagerEmailPdfSenderService {

    private final MailTransport mailTransport;

    public void sendEmailWithAttachment(String to, String subject, String body, byte[] pdf, String fileName) throws Exception {
        mailTransport.send(to, subject, body, false, fileName, pdf);
    }
}
