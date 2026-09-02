package com.intranet.util;

import com.intranet.util.mail.MailTransport;

import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EmailUtil {

    private final MailTransport mailTransport;

    @Async
    public void sendEmail(String to, String subject, String htmlContent) throws MessagingException {
        mailTransport.send(to, subject, htmlContent, true);
    }
}
