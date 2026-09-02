package com.intranet.util.mail;

import jakarta.mail.MessagingException;

/**
 * Provider-neutral mail sender. The active implementation is chosen by the
 * {@code email.service} property: {@code gmail} uses SMTP, {@code azure} uses
 * the Microsoft Graph sendMail API.
 */
public interface MailTransport {

    void send(String to, String subject, String body, boolean html) throws MessagingException;

    void send(String to, String subject, String body, boolean html,
              String attachmentName, byte[] attachment) throws MessagingException;
}
