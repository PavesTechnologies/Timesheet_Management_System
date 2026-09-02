package com.intranet.service.email.template;

import com.intranet.dto.email.MissingTimesheetEmailDTO;
import com.intranet.dto.email.TimeSheetSummaryEmailDTO;
import com.intranet.dto.email.WeeklySubmissionEmailDTO;
import com.intranet.service.email.MonthEndRemainder.MonthEndTimesheetEmailTemplateBuilderService;
import com.intranet.service.email.managerReviews.ManagerEmailTemplateBuilderService;
import com.intranet.service.email.missingWeekTimesheet.MissingTimesheetEmailTemplateBuilderService;
import com.intranet.service.email.usertoManger.EmailTemplateBuilderService;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class EmailPreviewTest {

    private final EmailLayoutBuilder layout = new EmailLayoutBuilder();

    EmailPreviewTest() {
        ReflectionTestUtils.setField(layout, "frontendBaseUrl",
                "https://enterpriseappdev.pavestechnologies.net/");
    }

    private Path outDir() throws Exception {
        Path dir = Path.of(System.getProperty("preview.dir", "target/email-previews"));
        Files.createDirectories(dir);
        return dir;
    }

    private void write(String name, String html) throws Exception {
        Path file = outDir().resolve(name);
        Files.writeString(file, html);
        System.out.println("PREVIEW " + file.toAbsolutePath());
    }

    @Test
    void renderAllTemplates() throws Exception {
        // 1. Review outcome, one per status
        EmailTemplateBuilderService review = new EmailTemplateBuilderService(layout);
        for (String status : new String[] {"APPROVED", "REJECTED", "SUBMITTED"}) {
            TimeSheetSummaryEmailDTO dto = TimeSheetSummaryEmailDTO.builder()
                    .userName("Ajay Kumar Bhukya")
                    .status(status)
                    .startDate(LocalDate.of(2026, 8, 24))
                    .endDate(LocalDate.of(2026, 8, 30))
                    .totalHoursLogged(new BigDecimal("40.00"))
                    .approvedBy("Sricharan Chilkuri")
                    .reason("Hours verified against project allocation.")
                    .build();
            String html = review.buildTimeSheetSummaryEmail(dto);
            assertThat(html).contains("Timesheet Review Update");
            write("1-review-" + status.toLowerCase() + ".html", html);
        }

        // 2. Weekly submission to manager
        WeeklySubmissionEmailDTO weekly = WeeklySubmissionEmailDTO.builder()
                .managerName("Sricharan Chilkuri")
                .userName("Ajay Kumar Bhukya")
                .startDate(LocalDate.of(2026, 8, 24))
                .endDate(LocalDate.of(2026, 8, 30))
                .totalHoursLogged(new BigDecimal("40"))
                .build();
        write("2-weekly-submission.html",
                new ManagerEmailTemplateBuilderService(layout).buildWeeklySubmissionEmail(weekly));

        // 3. Missing week reminder
        MissingTimesheetEmailDTO missing = new MissingTimesheetEmailDTO();
        missing.setUserName("Ajay Kumar Bhukya");
        missing.setStartDate("24 Aug 2026");
        missing.setEndDate("30 Aug 2026");
        write("3-missing-week.html",
                new MissingTimesheetEmailTemplateBuilderService(layout).buildMissingTimesheetEmail(missing));

        // 4. Month-end reminder
        write("4-month-end.html",
                new MonthEndTimesheetEmailTemplateBuilderService(layout).buildMonthEndReminderEmail(missing));
    }

    @Test
    void escapesInterpolatedValues() {
        TimeSheetSummaryEmailDTO dto = TimeSheetSummaryEmailDTO.builder()
                .userName("<script>alert(1)</script>")
                .status("APPROVED")
                .startDate(LocalDate.of(2026, 8, 24))
                .endDate(LocalDate.of(2026, 8, 30))
                .totalHoursLogged(new BigDecimal("40"))
                .approvedBy("A & B")
                .reason("Rejected: hours < expected")
                .build();

        String html = new EmailTemplateBuilderService(layout).buildTimeSheetSummaryEmail(dto);

        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
        assertThat(html).contains("A &amp; B");
        assertThat(html).contains("hours &lt; expected");
    }

    @Test
    void omitsCallToActionWhenFrontendUrlMissing() {
        EmailLayoutBuilder bare = new EmailLayoutBuilder();
        ReflectionTestUtils.setField(bare, "frontendBaseUrl", "");

        MissingTimesheetEmailDTO dto = new MissingTimesheetEmailDTO();
        dto.setUserName("Ajay");
        dto.setStartDate("24 Aug 2026");
        dto.setEndDate("30 Aug 2026");

        String html = new MissingTimesheetEmailTemplateBuilderService(bare).buildMissingTimesheetEmail(dto);

        assertThat(html).doesNotContain("Submit Timesheet");
        assertThat(html).contains("Timesheet Submission Reminder");
    }
}
