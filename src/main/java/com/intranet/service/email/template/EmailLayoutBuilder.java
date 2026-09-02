package com.intranet.service.email.template;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Year;
import java.util.Map;

/**
 * Renders the shared Paves-branded email shell used by every timesheet notification:
 * gradient bar, header, body, optional details box, call to action and footer.
 *
 * Built with plain concatenation rather than a formatted text block so that literal
 * percent signs in the CSS need no escaping.
 */
@Service
public class EmailLayoutBuilder {

    private static final String NAVY = "#0A1A44";
    private static final String BLUE = "#1A4DFF";
    private static final String PAGE_BG = "#f3f5f9";
    private static final String CARD_BORDER = "#e0e4ec";
    private static final String TEXT = "#444";
    private static final String MUTED = "#666";
    private static final String FOOTER_BG = "#f6f7fb";
    private static final String FONT = "Arial, Helvetica, sans-serif";

    @Value("${app.frontend.url:}")
    private String frontendBaseUrl;

    public String render(EmailContent content) {
        String accent = accentFor(content.getTone());
        String boxBg = boxBgFor(content.getTone());
        String boxBorder = boxBorderFor(content.getTone());

        StringBuilder html = new StringBuilder(4096);

        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
            .append("<meta charset=\"UTF-8\">\n")
            .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
            .append("<title>").append(escape(content.getTitle())).append("</title>\n")
            .append("</head>\n")
            .append("<body style=\"margin:0; padding:0; background:").append(PAGE_BG)
            .append("; font-family:").append(FONT).append(";\">\n");

        html.append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"padding:40px 0; background:")
            .append(PAGE_BG).append(";\">\n<tr><td align=\"center\">\n");

        // Main card
        html.append("<table width=\"640\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#ffffff;")
            .append(" border-radius:10px; border:1px solid ").append(CARD_BORDER).append(";\">\n");

        // Outlook-safe gradient bar
        html.append("<tr><td style=\"height:8px; padding:0; margin:0; line-height:8px;\">\n")
            .append("<!--[if gte mso 9]>\n")
            .append("<v:rect xmlns:v=\"urn:schemas-microsoft-com:vml\" fill=\"true\" stroke=\"false\"")
            .append(" style=\"width:640px;height:8px;\">\n")
            .append("<v:fill type=\"gradient\" angle=\"90\" color=\"").append(NAVY)
            .append("\" color2=\"").append(BLUE).append("\" />\n")
            .append("</v:rect>\n<![endif]-->\n")
            .append("<div style=\"background:linear-gradient(90deg, ").append(NAVY)
            .append(", #3B0E57, ").append(BLUE).append("); height:8px; width:100%;\"></div>\n")
            .append("</td></tr>\n");

        // Header
        html.append("<tr><td style=\"padding:32px 40px 20px;\">\n")
            .append("<h2 style=\"margin:0; font-size:22px; color:").append(NAVY)
            .append("; font-weight:700;\">").append(escape(content.getTitle())).append("</h2>\n")
            .append("<p style=\"margin:8px 0 0; font-size:14px; color:").append(MUTED)
            .append(";\">Notification from Paves Timesheet Management System</p>\n")
            .append("</td></tr>\n");

        // Body
        html.append("<tr><td style=\"padding:10px 40px 30px; font-size:15px; color:")
            .append(TEXT).append("; line-height:1.7;\">\n");

        html.append("<p style=\"margin:0 0 18px;\">Dear ")
            .append(escape(defaultIfBlank(content.getRecipientName(), "Employee")))
            .append(",</p>\n");

        if (StringUtils.hasText(content.getMessageBodyHtml())) {
            html.append("<p style=\"margin:0 0 25px;\">").append(content.getMessageBodyHtml()).append("</p>\n");
        }

        // Optional status pill
        if (StringUtils.hasText(content.getStatusLabel())) {
            html.append("<div style=\"margin:0 0 25px;\">")
                .append("<span style=\"display:inline-block; padding:6px 16px; border-radius:20px; font-size:13px;")
                .append(" font-weight:700; letter-spacing:0.3px; color:#ffffff; background:")
                .append(statusColor(content.getStatusLabel()))
                .append(";\">").append(escape(titleCase(content.getStatusLabel()))).append("</span>")
                .append("</div>\n");
        }

        // Optional details box
        Map<String, String> details = content.getDetails();
        if (details != null && !details.isEmpty()) {
            html.append("<div style=\"margin:0 0 15px;\">")
                .append("<div style=\"font-size:15px; font-weight:700; color:").append(NAVY)
                .append("; border-left:4px solid ").append(accent).append("; padding-left:10px;\">")
                .append(escape(content.getDetailsTitle())).append("</div></div>\n");

            html.append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:")
                .append(boxBg).append("; border:1px solid ").append(boxBorder)
                .append("; border-radius:8px;\">\n<tr><td style=\"padding:20px 25px;\">\n")
                .append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"font-size:14px; color:#333;\">\n");

            for (Map.Entry<String, String> row : details.entrySet()) {
                html.append("<tr><td style=\"padding:8px 0; width:170px; font-weight:bold; vertical-align:top;\">")
                    .append(escape(row.getKey())).append("</td>")
                    .append("<td style=\"padding:8px 0;\">").append(escape(row.getValue()))
                    .append("</td></tr>\n");
            }

            html.append("</table>\n</td></tr>\n</table>\n");
        }

        if (StringUtils.hasText(content.getClosingMessage())) {
            html.append("<p style=\"margin:25px 0 10px; color:#555;\">")
                .append(escape(content.getClosingMessage())).append("</p>\n");
        }

        // Call to action
        if (StringUtils.hasText(content.getCtaLabel()) && StringUtils.hasText(frontendBaseUrl)) {
            html.append(button(escape(frontendBaseUrl), escape(content.getCtaLabel())));
        }

        if (StringUtils.hasText(content.getNoteHtml())) {
            html.append("<p style=\"margin-top:25px; padding-top:14px; border-top:1px solid #e8ebf2;")
                .append(" font-size:13px; color:#888;\">").append(content.getNoteHtml()).append("</p>\n");
        }

        html.append("<p style=\"margin-top:30px; font-size:14px; color:#888;\">Regards,<br>")
            .append("<strong>Timesheet Management Team</strong><br>")
            .append("<span style=\"font-size:12px; color:#aaa;\">")
            .append(escape(content.getSignOffCaption())).append("</span></p>\n");

        html.append("</td></tr>\n");

        // Footer
        html.append("<tr><td style=\"background:").append(FOOTER_BG)
            .append("; text-align:center; padding:14px; font-size:12px; color:#888;\">\n")
            .append("&copy; ").append(Year.now().getValue())
            .append(" Paves Global Infotech Private Limited. All rights reserved.\n")
            .append("</td></tr>\n");

        html.append("</table>\n</td></tr>\n</table>\n</body>\n</html>");

        return html.toString();
    }

    /**
     * Bulletproof call-to-action button.
     *
     * Outlook's Word rendering engine drops inline declarations that carry {@code !important}
     * and then falls back to its own link colour, which turns white label text blue on the navy
     * fill. So the label colour is set without {@code !important}, repeated on a nested span
     * (clients that override the anchor colour usually leave the span alone), and Outlook gets a
     * VML roundrect where the fill and text colour are attributes rather than CSS.
     *
     * @param href  already-escaped destination
     * @param label already-escaped button text
     */
    private static String button(String href, String label) {
        int width = Math.max(200, label.length() * 9 + 64);

        return "<div style=\"text-align:center; margin:32px 0;\">\n"
                + "<!--[if mso]>\n"
                + "<v:roundrect xmlns:v=\"urn:schemas-microsoft-com:vml\""
                + " xmlns:w=\"urn:schemas-microsoft-com:office:word\" href=\"" + href + "\""
                + " style=\"height:44px; v-text-anchor:middle; width:" + width + "px;\""
                + " arcsize=\"14%\" strokecolor=\"" + BLUE + "\" fillcolor=\"" + NAVY + "\">\n"
                + "<w:anchorlock/>\n"
                + "<center style=\"color:#ffffff; font-family:" + FONT + "; font-size:15px;"
                + " font-weight:bold;\">" + label + "</center>\n"
                + "</v:roundrect>\n"
                + "<![endif]-->\n"
                + "<!--[if !mso]><!-->\n"
                + "<table border=\"0\" cellpadding=\"0\" cellspacing=\"0\" role=\"presentation\""
                + " align=\"center\" style=\"margin:0 auto; border-collapse:separate;\">\n"
                + "<tr><td align=\"center\" bgcolor=\"" + NAVY + "\""
                + " style=\"border-radius:6px; border:1px solid " + BLUE + ";\">\n"
                + "<a href=\"" + href + "\" target=\"_blank\""
                + " style=\"display:inline-block; padding:12px 32px; font-family:" + FONT + ";"
                + " font-size:15px; font-weight:600; line-height:1.2; color:#ffffff;"
                + " text-decoration:none; border-radius:6px;\">"
                + "<span style=\"color:#ffffff; text-decoration:none;\">" + label + "</span>"
                + "</a>\n"
                + "</td></tr>\n</table>\n"
                + "<!--<![endif]-->\n"
                + "</div>\n";
    }

    private static String accentFor(EmailContent.Tone tone) {
        return switch (tone) {
            case ALERT -> "#D92D20";
            case SUCCESS -> "#0F9D58";
            case INFO -> BLUE;
        };
    }

    private static String boxBgFor(EmailContent.Tone tone) {
        return switch (tone) {
            case ALERT -> "#fffaf9";
            case SUCCESS -> "#f8fdfa";
            case INFO -> "#fafbff";
        };
    }

    private static String boxBorderFor(EmailContent.Tone tone) {
        return switch (tone) {
            case ALERT -> "#f3d9d6";
            case SUCCESS -> "#d5ece0";
            case INFO -> "#e2e6ef";
        };
    }

    private static String statusColor(String status) {
        return switch (status == null ? "" : status.toUpperCase()) {
            case "APPROVED" -> "#0F9D58";
            case "REJECTED" -> "#D92D20";
            case "SUBMITTED" -> "#B54708";
            default -> "#555555";
        };
    }

    private static String titleCase(String input) {
        if (!StringUtils.hasText(input)) return input;
        return input.substring(0, 1).toUpperCase() + input.substring(1).toLowerCase();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    /**
     * Escapes interpolated values so a name or comment containing HTML cannot break the layout.
     * Public so callers can escape values they embed into {@code messageBodyHtml}.
     */
    public static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&#39;");
    }
}
