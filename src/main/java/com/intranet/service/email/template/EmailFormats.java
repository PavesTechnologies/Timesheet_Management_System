package com.intranet.service.email.template;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Shared value formatting so every notification renders dates and hours the same way. */
public final class EmailFormats {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private EmailFormats() {
    }

    public static String date(LocalDate value) {
        return value == null ? "N/A" : DATE.format(value);
    }

    public static String range(LocalDate start, LocalDate end) {
        return date(start) + " to " + date(end);
    }

    public static String range(String start, String end) {
        return text(start) + " to " + text(end);
    }

    public static String hours(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).stripTrailingZeros().toPlainString() + " hours";
    }

    public static String text(String value) {
        return value == null || value.isBlank() ? "N/A" : value;
    }
}
