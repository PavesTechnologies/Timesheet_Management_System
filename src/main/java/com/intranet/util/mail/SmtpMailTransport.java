package com.intranet.util.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "email.service", havingValue = "gmail", matchIfMissing = true)
public class SmtpMailTransport implements MailTransport {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String fromEmail;

    @Override
    public void send(String to, String subject, String body, boolean html) throws MessagingException {
        send(to, subject, body, html, null, null);
    }

    @Override
    public void send(String to, String subject, String body, boolean html,
                     String attachmentName, byte[] attachment) throws MessagingException {

        boolean hasAttachment = attachment != null && attachment.length > 0;

        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, hasAttachment, "UTF-8");

        if (StringUtils.hasText(fromEmail)) {
            helper.setFrom(fromEmail);
        }
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(body, html);

        if (hasAttachment) {
            helper.addAttachment(attachmentName, new ByteArrayResource(attachment));
        }

        mailSender.send(message);
        log.debug("SMTP mail sent to {} with subject '{}'", to, subject);
    }
}
