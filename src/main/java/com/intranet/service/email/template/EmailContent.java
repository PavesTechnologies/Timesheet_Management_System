package com.intranet.service.email.template;

import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

import java.util.Map;

/**
 * The variable parts of a notification email. {@link EmailLayoutBuilder} wraps
 * these in the shared Paves-branded shell.
 */
@Getter
@Builder
public class EmailContent {

    /** Tint applied to the details box and section accent. Card chrome stays constant. */
    public enum Tone { INFO, ALERT, SUCCESS }

    /** Headline shown in the header and used as the document title. */
    private final String title;

    /** Addressed as "Dear {recipientName},". Escaped. */
    private final String recipientName;

    /** Lead paragraph. Trusted HTML - only ever built by our own code. */
    private final String messageBodyHtml;

    /** Optional status pill, e.g. APPROVED / REJECTED / SUBMITTED. Escaped. */
    private final String statusLabel;

    @Builder.Default
    private final String detailsTitle = "Details";

    /** Ordered key/value rows. Both sides escaped. */
    @Singular("detail")
    private final Map<String, String> details;

    /** Optional paragraph after the details box. Escaped. */
    private final String closingMessage;

    /** Optional small-print note above the sign-off. Trusted HTML. */
    private final String noteHtml;

    /** Optional call-to-action label. The link always points at the app front end. */
    private final String ctaLabel;

    /** Sign-off caption under "Timesheet Management Team". Escaped. */
    @Builder.Default
    private final String signOffCaption = "Automated Notification";

    @Builder.Default
    private final Tone tone = Tone.INFO;
}
