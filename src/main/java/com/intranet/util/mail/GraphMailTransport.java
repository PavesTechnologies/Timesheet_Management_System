package com.intranet.util.mail;

import jakarta.annotation.PostConstruct;
import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends mail through the Microsoft Graph {@code /users/{sender}/sendMail} endpoint
 * using an application token. Requires the {@code Mail.Send} application permission
 * with admin consent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "email.service", havingValue = "azure")
public class GraphMailTransport implements MailTransport {

    /**
     * Graph caps a sendMail request at 4 MB. Base64 inflates the payload by ~4/3,
     * so refuse raw attachments above 3 MB rather than letting Graph reject them.
     */
    private static final int MAX_ATTACHMENT_BYTES = 3 * 1024 * 1024;

    private final RestTemplate restTemplate;
    private final GraphTokenProvider tokenProvider;

    @Value("${azure.graph.sender:}")
    private String senderAddress;

    @Value("${azure.graph.base-url:https://graph.microsoft.com/v1.0}")
    private String graphBaseUrl;

    @PostConstruct
    void validateConfig() {
        if (!StringUtils.hasText(senderAddress)) {
            throw new IllegalStateException(
                    "GRAPH_SENDER_ADDRESS must be set when EMAIL_SERVICE=azure");
        }
        log.info("Microsoft Graph mail transport sending as {}", senderAddress);
    }

    @Override
    public void send(String to, String subject, String body, boolean html) throws MessagingException {
        send(to, subject, body, html, null, null);
    }

    @Override
    public void send(String to, String subject, String body, boolean html,
                     String attachmentName, byte[] attachment) throws MessagingException {

        if (!StringUtils.hasText(to)) {
            throw new MessagingException("Recipient address is required");
        }

        Map<String, Object> message = new HashMap<>();
        message.put("subject", subject);
        message.put("body", Map.of(
                "contentType", html ? "HTML" : "Text",
                "content", body == null ? "" : body));
        message.put("toRecipients", List.of(Map.of("emailAddress", Map.of("address", to))));

        if (attachment != null && attachment.length > 0) {
            if (attachment.length > MAX_ATTACHMENT_BYTES) {
                throw new MessagingException(
                        "Attachment '" + attachmentName + "' is " + attachment.length
                                + " bytes, which exceeds the " + MAX_ATTACHMENT_BYTES
                                + " byte limit of the Graph sendMail API. Sending it requires the"
                                + " draft + upload session flow and the Mail.ReadWrite permission.");
            }
            message.put("attachments", List.of(Map.of(
                    "@odata.type", "#microsoft.graph.fileAttachment",
                    "name", attachmentName,
                    "contentType", contentTypeFor(attachmentName),
                    "contentBytes", Base64.getEncoder().encodeToString(attachment))));
        }

        Map<String, Object> payload = Map.of(
                "message", message,
                "saveToSentItems", Boolean.TRUE);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(tokenProvider.getAccessToken());

        // Build a URI rather than handing RestTemplate a String: a String is treated as a URI
        // template, which would encode the address a second time and would also try to expand
        // any braces in it.
        URI uri = UriComponentsBuilder.fromUriString(trimTrailingSlash(graphBaseUrl))
                .pathSegment("users", senderAddress, "sendMail")
                .encode()
                .build()
                .toUri();

        try {
            restTemplate.postForEntity(uri, new HttpEntity<>(payload, headers), Void.class);
            log.debug("Graph mail sent to {} with subject '{}'", to, subject);
        } catch (RestClientException e) {
            throw new MessagingException(
                    "Microsoft Graph sendMail failed for " + to + ": " + e.getMessage(), e);
        }
    }

    private static String contentTypeFor(String fileName) {
        if (fileName != null && fileName.toLowerCase().endsWith(".pdf")) {
            return "application/pdf";
        }
        return "application/octet-stream";
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
