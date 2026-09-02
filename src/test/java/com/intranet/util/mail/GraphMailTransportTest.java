package com.intranet.util.mail;

import com.intranet.dto.email.MissingTimesheetEmailDTO;
import com.intranet.service.email.missingWeekTimesheet.MissingTimesheetEmailTemplateBuilderService;
import com.intranet.service.email.template.EmailLayoutBuilder;

import jakarta.mail.MessagingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that what the templates render is what Graph is asked to send - in particular
 * that the Outlook conditional comments and VML in the call-to-action survive the JSON payload.
 */
class GraphMailTransportTest {

    private RestTemplate restTemplate;
    private GraphMailTransport transport;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        GraphTokenProvider tokenProvider = mock(GraphTokenProvider.class);
        when(tokenProvider.getAccessToken()).thenReturn("test-token");
        when(restTemplate.postForEntity(any(URI.class), any(), eq(Void.class)))
                .thenReturn(ResponseEntity.accepted().build());

        transport = new GraphMailTransport(restTemplate, tokenProvider);
        ReflectionTestUtils.setField(transport, "senderAddress", "noreply@pavestechnologies.com");
        ReflectionTestUtils.setField(transport, "graphBaseUrl", "https://graph.microsoft.com/v1.0");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> captureMessage() {
        ArgumentCaptor<URI> url = ArgumentCaptor.forClass(URI.class);
        ArgumentCaptor<HttpEntity<Map<String, Object>>> entity = ArgumentCaptor.forClass(HttpEntity.class);
        org.mockito.Mockito.verify(restTemplate).postForEntity(url.capture(), entity.capture(), eq(Void.class));

        // '@' is a legal path character, so it is passed through rather than percent-encoded -
        // which is the form Graph documents for /users/{userPrincipalName}.
        assertThat(url.getValue()).hasToString(
                "https://graph.microsoft.com/v1.0/users/noreply@pavestechnologies.com/sendMail");

        HttpHeaders headers = entity.getValue().getHeaders();
        assertThat(headers.getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer test-token");

        Map<String, Object> payload = entity.getValue().getBody();
        assertThat(payload).isNotNull();
        assertThat(payload.get("saveToSentItems")).isEqualTo(Boolean.TRUE);
        return (Map<String, Object>) payload.get("message");
    }

    private String renderRealTemplate() {
        EmailLayoutBuilder layout = new EmailLayoutBuilder();
        ReflectionTestUtils.setField(layout, "frontendBaseUrl",
                "https://enterpriseappdev.pavestechnologies.net/");

        MissingTimesheetEmailDTO dto = new MissingTimesheetEmailDTO();
        dto.setUserName("Ajay Kumar Bhukya");
        dto.setStartDate("24 Aug 2026");
        dto.setEndDate("30 Aug 2026");

        return new MissingTimesheetEmailTemplateBuilderService(layout).buildMissingTimesheetEmail(dto);
    }

    @Test
    @SuppressWarnings("unchecked")
    void carriesRenderedTemplateHtmlIntact() throws Exception {
        String html = renderRealTemplate();

        transport.send("ajay.bhukya@pavestechnologies.com", "Timesheet Submission Reminder", html, true);

        Map<String, Object> message = captureMessage();
        Map<String, String> body = (Map<String, String>) message.get("body");

        assertThat(body.get("contentType")).isEqualTo("HTML");
        assertThat(body.get("content")).isEqualTo(html);

        // The button's Outlook branch must not be mangled on the way through.
        assertThat(body.get("content")).contains("<!--[if mso]>", "v:roundrect", "arcsize=\"14%\"");
        assertThat(body.get("content")).doesNotContain("!important");

        List<Map<String, Map<String, String>>> to =
                (List<Map<String, Map<String, String>>>) message.get("toRecipients");
        assertThat(to.get(0).get("emailAddress").get("address"))
                .isEqualTo("ajay.bhukya@pavestechnologies.com");
        assertThat(message).doesNotContainKey("attachments");
    }

    @Test
    void sendsPlainTextAsText() throws Exception {
        transport.send("a@b.com", "Plain", "Hi,\n\nReport attached.", false);

        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) captureMessage().get("body");
        assertThat(body.get("contentType")).isEqualTo("Text");
    }

    @Test
    @SuppressWarnings("unchecked")
    void base64EncodesAttachment() throws Exception {
        byte[] pdf = "%PDF-1.4 fake".getBytes();

        transport.send("a@b.com", "Report", "Body", false, "Monthly_Report.pdf", pdf);

        List<Map<String, Object>> attachments =
                (List<Map<String, Object>>) captureMessage().get("attachments");
        assertThat(attachments).hasSize(1);
        assertThat(attachments.get(0))
                .containsEntry("@odata.type", "#microsoft.graph.fileAttachment")
                .containsEntry("name", "Monthly_Report.pdf")
                .containsEntry("contentType", "application/pdf")
                .containsEntry("contentBytes", java.util.Base64.getEncoder().encodeToString(pdf));
    }

    @Test
    void rejectsOversizedAttachment() {
        byte[] tooBig = new byte[3 * 1024 * 1024 + 1];

        assertThatThrownBy(() -> transport.send("a@b.com", "Report", "Body", false, "big.pdf", tooBig))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("Mail.ReadWrite");
    }
}
