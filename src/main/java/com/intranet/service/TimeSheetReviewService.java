package com.intranet.service;

import com.intranet.dto.ReviewedTimesheetAuditDTO;
import com.intranet.dto.TimeSheetBulkReviewRequestDTO;
import com.intranet.entity.InternalProject;
import com.intranet.entity.TimeSheet;
import com.intranet.entity.TimeSheetReview;
import com.intranet.entity.WeekInfo;
import com.intranet.entity.WeeklyTimeSheetReview;
import com.intranet.repository.InternalProjectRepo;
import com.intranet.repository.TimeSheetRepo;
import com.intranet.repository.TimeSheetReviewRepo;
import com.intranet.repository.WeekInfoRepo;
import com.intranet.repository.WeeklyTimeSheetReviewRepo;
import com.intranet.service.email.managerReviews.TimeSheetNotificationService;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import com.intranet.util.cache.UserDirectoryService;
import com.intranet.dto.email.TimeSheetSummaryEmailDTO;
import java.math.BigDecimal;


@Service
@RequiredArgsConstructor
public class TimeSheetReviewService {

    private final TimeSheetRepo timeSheetRepo;
    private final TimeSheetReviewRepo reviewRepo;
    private final WeeklyTimeSheetReviewRepo weeklyReviewRepo;
    private final WeekInfoRepo weekInfoRepo;
    private final RestTemplate restTemplate = new RestTemplate();
    // ✅ Add this line
    private final TimeSheetNotificationService timeSheetNotificationService;
    private final UserDirectoryService userDirectoryService;
    private final InternalProjectRepo internalProjectRepo;


    @Value("${pms.api.base-url}")
    private String pmsBaseUrl;

    /** Utility — reuse same approach from WeeklySummaryService */
    private HttpEntity<Void> buildEntityWithAuth() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) return new HttpEntity<>(new HttpHeaders());
        HttpServletRequest request = attrs.getRequest();

        String authHeader = request.getHeader("Authorization");
        HttpHeaders headers = new HttpHeaders();
        if (authHeader != null && !authHeader.isBlank()) {
            headers.set("Authorization", authHeader);
        }
        return new HttpEntity<>(headers);
    }
    public static String toTitleCase(String input) {
    if (input == null || input.isEmpty()) {
        return input;
    }
    return input.substring(0, 1).toUpperCase() + input.substring(1).toLowerCase();
}

    @Transactional
    public String reviewMultipleTimesheets(Long managerId, TimeSheetBulkReviewRequestDTO dto) {
        // Mixed-verdict mode: reviewer sent per-day buckets (approvedTimesheetIds + rejectedTimesheetIds).
        // Collapse them into the legacy timesheetIds list so the existing validations still apply.
        boolean mixedMode = (dto.getApprovedTimesheetIds() != null && !dto.getApprovedTimesheetIds().isEmpty())
                         || (dto.getRejectedTimesheetIds() != null && !dto.getRejectedTimesheetIds().isEmpty());
        if (mixedMode) {
            List<Long> approvedIds = dto.getApprovedTimesheetIds() != null ? dto.getApprovedTimesheetIds() : Collections.emptyList();
            List<Long> rejectedIds = dto.getRejectedTimesheetIds() != null ? dto.getRejectedTimesheetIds() : Collections.emptyList();

            Set<Long> overlap = approvedIds.stream().filter(rejectedIds::contains).collect(Collectors.toSet());
            if (!overlap.isEmpty()) {
                throw new IllegalArgumentException("A timesheet cannot appear in both approvedTimesheetIds and rejectedTimesheetIds: " + overlap);
            }
            if (!rejectedIds.isEmpty() && (dto.getComments() == null || dto.getComments().isBlank())) {
                throw new IllegalArgumentException("Comments are required when rejecting a timesheet.");
            }

            List<Long> merged = new ArrayList<>(approvedIds.size() + rejectedIds.size());
            merged.addAll(approvedIds);
            merged.addAll(rejectedIds);
            dto.setTimesheetIds(merged);
            // Synthesize a status so the legacy validation at L106-108 passes; the loop below
            // ignores this when mixedMode is true.
            dto.setStatus(rejectedIds.isEmpty() ? "APPROVED" : "REJECTED");
        }

        if (dto.getTimesheetIds() == null || dto.getTimesheetIds().isEmpty()) {
            throw new IllegalArgumentException("Timesheet IDs must be provided.");
        }

        List<TimeSheet> sheets = timeSheetRepo.findAllById(dto.getTimesheetIds());
        if (sheets.isEmpty()) {
            throw new IllegalArgumentException("No timesheets found for given IDs.");
        }

        Long firstWeekId = sheets.get(0).getWeekInfo().getId();
        boolean allSameWeek = sheets.stream()
                .allMatch(ts -> ts.getWeekInfo().getId().equals(firstWeekId));

        if (!allSameWeek) {
            throw new IllegalArgumentException("All timesheets must belong to the same week.");
        }

        boolean allSubmitted = sheets.stream()
                .allMatch(ts -> ts.getStatus() != TimeSheet.Status.DRAFT);

        if (!allSubmitted) {
            throw new IllegalArgumentException("Only 'Submitted' timesheets can be reviewed.");
        }

        String status = dto.getStatus().toUpperCase();
        if (!status.equals("APPROVED") && !status.equals("REJECTED")) {
            throw new IllegalArgumentException("Invalid status. Must be APPROVED or REJECTED.");
        }

        if ("REJECTED".equalsIgnoreCase(dto.getStatus()) &&
                (dto.getComments() == null || dto.getComments().isBlank())) {
            throw new IllegalArgumentException("Comments are required when rejecting a timesheet.");
        }

        WeekInfo weekInfo = sheets.get(0).getWeekInfo();
        Long userId = dto.getUserId();

        Optional<WeeklyTimeSheetReview> existingWeeklyReviewOpt =
        weeklyReviewRepo.findByUserIdAndWeekInfo_Id(userId, weekInfo.getId());

        if (existingWeeklyReviewOpt.isPresent()) {
            WeeklyTimeSheetReview existingReview = existingWeeklyReviewOpt.get();
            WeeklyTimeSheetReview.Status weeklyStatus = existingReview.getStatus();

            // 🚫 Case 1: Already fully reviewed (approved/rejected)
            if (weeklyStatus == WeeklyTimeSheetReview.Status.APPROVED ||
                weeklyStatus == WeeklyTimeSheetReview.Status.REJECTED) {
                throw new IllegalArgumentException(String.format(
                    "Cannot review timesheets for user ID %d in week %d (%s - %s) because it is already %s.",
                    userId,
                    weekInfo.getId(),
                    weekInfo.getStartDate(),
                    weekInfo.getEndDate(),
                    weeklyStatus.name()
                ));
            }

            // ⚠️ Case 2: Partially approved — ensure this manager hasn’t reviewed everything already
            if (weeklyStatus == WeeklyTimeSheetReview.Status.PARTIALLY_APPROVED) {

                // Fetch all timesheets for this user & week
                List<TimeSheet> userWeekSheets = timeSheetRepo.findByUserIdAndWeekInfo_Id(userId, weekInfo.getId());

                // Filter only non-auto-generated timesheets
                List<TimeSheet> nonAutoSheets = userWeekSheets.stream()
                        .filter(ts -> !Boolean.TRUE.equals(ts.getAutoGenerated()))
                        .collect(Collectors.toList());

                // Fetch all reviews done by this manager in this week
                List<TimeSheetReview> managerReviews =
                        reviewRepo.findByWeekInfo_IdAndManagerId(weekInfo.getId(), managerId);

                // Map reviewed TS with their status
                Map<Long, String> reviewedStatusMap = managerReviews.stream()
                        .collect(Collectors.toMap(
                                r -> r.getTimeSheet().getId(),
                                r -> r.getStatus().name()
                        ));

                // Check if any non-autogenerated timesheet is still SUBMITTED (not reviewed)
                boolean anyUnreviewed = nonAutoSheets.stream()
                        .anyMatch(ts -> {
                            String sr = reviewedStatusMap.get(ts.getId());
                            return sr == null || sr.equals("SUBMITTED");
                        });

                if (!anyUnreviewed) {
                    // Means manager has reviewed all valid TS → Cannot do again
                    throw new IllegalArgumentException(String.format(
                            "Manager ID %d has already reviewed all non-auto-generated timesheets "
                                    + "for user ID %d in week %d (%s - %s).",
                            managerId,
                            userId,
                            weekInfo.getId(),
                            weekInfo.getStartDate(),
                            weekInfo.getEndDate()
                    ));
                }
            }
    }


        if (weekInfo.getEndDate().isBefore(LocalDate.now().minusDays(30))) {
            throw new IllegalArgumentException("Cannot review timesheets older than 30 days.");
        }

        // ✅ Step 1: Fetch all projects once (to get managers)
        List<Map<String, Object>> managerProjects = fetchAllProjects();

        for (TimeSheet ts : sheets) {

             // 🚨 BLOCK: Do not allow review of auto-generated approved timesheets
        if (ts.getStatus() == TimeSheet.Status.APPROVED && Boolean.TRUE.equals(ts.getAutoGenerated())) {
            continue;
        }
            TimeSheetReview review = reviewRepo
                    .findByTimeSheet_IdAndManagerId(ts.getId(), managerId)
                    .orElseGet(TimeSheetReview::new);

            review.setUserId(userId);
            review.setManagerId(managerId);
            review.setTimeSheet(ts);
            review.setWeekInfo(weekInfo);

            TimeSheetReview.Status reviewStatus;
            String reviewComment;
            if (mixedMode) {
                boolean reject = dto.getRejectedTimesheetIds() != null
                              && dto.getRejectedTimesheetIds().contains(ts.getId());
                reviewStatus  = reject ? TimeSheetReview.Status.REJECTED : TimeSheetReview.Status.APPROVED;
                reviewComment = reject ? dto.getComments() : null;
            } else {
                reviewStatus  = TimeSheetReview.Status.valueOf(dto.getStatus().toUpperCase());
                reviewComment = dto.getComments();
            }
            review.setStatus(reviewStatus);
            review.setComments(reviewComment);
            review.setReviewedAt(LocalDateTime.now());
            reviewRepo.save(review);

            // ✅ Step 2: Recalculate correct overall timesheet status (considering all managers)
            TimeSheet.Status overallStatus = calculateOverallStatus(ts, managerProjects);
            ts.setStatus(overallStatus);
            timeSheetRepo.save(ts);
        }

        // ✅ Step 3: Update weekly review aggregate
        updateWeeklyTimeSheetReview(userId, firstWeekId);
         
        // ✅ Step 4: Send notification emails
        boolean result = sendReviewNotificationEmails(managerId, dto, sheets);

        if (result) {
           return String.format("%d Timesheets %s successfully with notification emails sent.",
                sheets.size(), toTitleCase(dto.getStatus()));
        }

        return String.format("%d Timesheets %s successfully but notification emails not sent.",
                sheets.size(), toTitleCase(dto.getStatus()));
    }

    /** Fetch all PMS projects to identify each project's owner (manager) */
    private List<Map<String, Object>> fetchAllProjects() {
        try {
            String projectsUrl = String.format("%s/projects/tms", pmsBaseUrl);
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    projectsUrl,
                    HttpMethod.GET,
                    buildEntityWithAuth(),
                    new ParameterizedTypeReference<>() {}
            );
            return Optional.ofNullable(response.getBody()).orElse(Collections.emptyList());
        } catch (Exception e) {
            System.err.println("⚠️ Failed to fetch PMS projects: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /** ✅ Corrected logic — checks all managers who SHOULD review, not only existing reviews */
    private TimeSheet.Status calculateOverallStatus(TimeSheet ts, List<Map<String, Object>> managerProjects) {
        // 1️⃣ Determine all required manager IDs based on project owners
        Set<Long> requiredManagerIds = ts.getEntries().stream()
                .map(entry -> {
                    Map<String, Object> project = managerProjects.stream()
                            .filter(p -> ((Number) p.get("id")).longValue() == entry.getProjectId())
                            .findFirst()
                            .orElse(null);
                    if (project == null || project.get("owner") == null) return null;
                    Map<String, Object> owner = (Map<String, Object>) project.get("owner");
                    return ((Number) owner.get("id")).longValue();
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (requiredManagerIds.isEmpty()) {
            return TimeSheet.Status.SUBMITTED; // no managers, fallback
        }

        // 2️⃣ Fetch existing reviews for this timesheet
        List<TimeSheetReview> existingReviews = reviewRepo.findByTimeSheet_Id(ts.getId());
        Map<Long, TimeSheetReview.Status> reviewMap = existingReviews.stream()
                .collect(Collectors.toMap(TimeSheetReview::getManagerId, TimeSheetReview::getStatus));

        // 3️⃣ Compare and derive overall status
        boolean anyRejected = reviewMap.values().stream()
                .anyMatch(status -> status == TimeSheetReview.Status.REJECTED);

        boolean allReviewed = requiredManagerIds.stream().allMatch(reviewMap::containsKey);
        boolean allApproved = allReviewed && reviewMap.values().stream()
                .allMatch(status -> status == TimeSheetReview.Status.APPROVED);
        boolean anyApproved = reviewMap.values().stream()
                .anyMatch(status -> status == TimeSheetReview.Status.APPROVED);

        if (anyRejected) return TimeSheet.Status.REJECTED;
        else if (allApproved) return TimeSheet.Status.APPROVED;
        else if (anyApproved) return TimeSheet.Status.PARTIALLY_APPROVED;
        else return TimeSheet.Status.SUBMITTED;
    }

    /** ✅ Improved to handle PARTIALLY_APPROVED at week level */
    @Transactional
    public void updateWeeklyTimeSheetReview(Long userId, Long weekInfoId) {
        List<TimeSheet> weekTimeSheets = timeSheetRepo.findByUserIdAndWeekInfo_Id(userId, weekInfoId);
        if (weekTimeSheets.isEmpty()) return;

        boolean anyRejected = weekTimeSheets.stream()
                .anyMatch(ts -> ts.getStatus() == TimeSheet.Status.REJECTED);
        boolean allApproved = weekTimeSheets.stream()
                .allMatch(ts -> ts.getStatus() == TimeSheet.Status.APPROVED);
        boolean anyApproved = weekTimeSheets.stream()
                .anyMatch(ts -> ts.getStatus() == TimeSheet.Status.APPROVED
                             || ts.getStatus() == TimeSheet.Status.PARTIALLY_APPROVED);
    

        WeeklyTimeSheetReview.Status weeklyStatus;
        if (anyRejected) weeklyStatus = WeeklyTimeSheetReview.Status.REJECTED;
        else if (allApproved) weeklyStatus = WeeklyTimeSheetReview.Status.APPROVED;
        else if (anyApproved) weeklyStatus = WeeklyTimeSheetReview.Status.PARTIALLY_APPROVED;
        else weeklyStatus = WeeklyTimeSheetReview.Status.SUBMITTED;

        WeeklyTimeSheetReview weeklyReview = weeklyReviewRepo
                .findByUserIdAndWeekInfo_Id(userId, weekInfoId)
                .orElseGet(() -> {
                    WeeklyTimeSheetReview r = new WeeklyTimeSheetReview();
                    r.setUserId(userId);
                    r.setWeekInfo(weekInfoRepo.findById(weekInfoId)
                            .orElseThrow(() -> new IllegalArgumentException("Week not found")));
                    return r;
                });

        weeklyReview.setStatus(weeklyStatus);
        weeklyReview.setReviewedAt(LocalDateTime.now());
        weeklyReviewRepo.save(weeklyReview);
    }

    /** ✅ Builds & Sends Email Notifications */
    private boolean sendReviewNotificationEmails(Long managerId, TimeSheetBulkReviewRequestDTO dto, List<TimeSheet> sheets) {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) throw new IllegalStateException("No request context available for Authorization header");

            HttpServletRequest request = attrs.getRequest();
            String authHeader = request.getHeader("Authorization");

            Map<Long, Map<String, Object>> allUsers = userDirectoryService.fetchAllUsers(authHeader);

            // Manager Name
            String managerName = allUsers.containsKey(managerId)
                    ? allUsers.get(managerId).get("name").toString()
                    : "Manager";

            // ✅ Group timesheets by userId (Long field)
        Map<Long, List<TimeSheet>> groupedByUser = sheets.stream()
        .collect(Collectors.groupingBy(TimeSheet::getUserId));


            List<TimeSheetSummaryEmailDTO> emails = new ArrayList<>();

            for (Map.Entry<Long, List<TimeSheet>> entry : groupedByUser.entrySet()) {
                Long userId = entry.getKey();
                List<TimeSheet> userSheets = entry.getValue();
                if (userSheets.isEmpty()) continue;

                WeekInfo weekInfo = userSheets.get(0).getWeekInfo();

                Map<String, Object> userInfo = allUsers.containsKey(userId)
                        ? allUsers.get(userId)
                        : new HashMap<String, Object>() {{
                            put("name", "Unknown User");
                            put("email", "unknown@example.com");
                        }};

                String userName = userInfo.get("name").toString();
                String email = userInfo.get("email").toString();

                BigDecimal totalHours = userSheets.stream()
                        .map(ts -> ts.getHoursWorked() != null ? ts.getHoursWorked() : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                TimeSheetSummaryEmailDTO emailDTO = new TimeSheetSummaryEmailDTO();
                emailDTO.setUserId(userId);
                emailDTO.setUserName(userName);
                emailDTO.setEmail(email);
                emailDTO.setStatus(dto.getStatus().toUpperCase());
                emailDTO.setStartDate(weekInfo.getStartDate());
                emailDTO.setEndDate(weekInfo.getEndDate());
                emailDTO.setTotalHoursLogged(totalHours);
                emailDTO.setApprovedBy(managerName);

                boolean mixedMode = (dto.getApprovedTimesheetIds() != null && !dto.getApprovedTimesheetIds().isEmpty())
                                 && (dto.getRejectedTimesheetIds() != null && !dto.getRejectedTimesheetIds().isEmpty());
                String baseReason = dto.getComments() != null ? dto.getComments() : "No comments provided.";
                if (mixedMode) {
                    int approvedCount = dto.getApprovedTimesheetIds().size();
                    int rejectedCount = dto.getRejectedTimesheetIds().size();
                    emailDTO.setReason(String.format(
                            "%d day(s) approved, %d day(s) rejected. Reason for rejection: %s",
                            approvedCount, rejectedCount, baseReason));
                } else {
                    emailDTO.setReason(baseReason);
                }

                emails.add(emailDTO);
            }

            if (!emails.isEmpty()) {
                timeSheetNotificationService.sendTimeSheetSummaryEmails(emails);
                System.out.println("✅ Timesheet review notification emails sent successfully.");
                return true;
            }

        } catch (Exception e) {
            System.err.println("⚠️ Failed to send timesheet review emails: " + e.getMessage());
            return false;
        }
        return false;
    }

     @Transactional
    public String reviewInternalTimesheets(Long managerId, TimeSheetBulkReviewRequestDTO dto) {

        // Mixed-verdict mode preprocessing (same as reviewMultipleTimesheets):
        // collapse approvedTimesheetIds + rejectedTimesheetIds into the legacy fields
        // so the existing validations apply, and let the loop below branch per-row.
        boolean mixedMode = (dto.getApprovedTimesheetIds() != null && !dto.getApprovedTimesheetIds().isEmpty())
                         || (dto.getRejectedTimesheetIds() != null && !dto.getRejectedTimesheetIds().isEmpty());
        if (mixedMode) {
            List<Long> approvedIds = dto.getApprovedTimesheetIds() != null ? dto.getApprovedTimesheetIds() : Collections.emptyList();
            List<Long> rejectedIds = dto.getRejectedTimesheetIds() != null ? dto.getRejectedTimesheetIds() : Collections.emptyList();

            Set<Long> overlap = approvedIds.stream().filter(rejectedIds::contains).collect(Collectors.toSet());
            if (!overlap.isEmpty()) {
                throw new IllegalArgumentException("A timesheet cannot appear in both approvedTimesheetIds and rejectedTimesheetIds: " + overlap);
            }
            if (!rejectedIds.isEmpty() && (dto.getComments() == null || dto.getComments().isBlank())) {
                throw new IllegalArgumentException("Comments are required when rejecting a timesheet.");
            }

            List<Long> merged = new ArrayList<>(approvedIds.size() + rejectedIds.size());
            merged.addAll(approvedIds);
            merged.addAll(rejectedIds);
            dto.setTimesheetIds(merged);
            dto.setStatus(rejectedIds.isEmpty() ? "APPROVED" : "REJECTED");
        }

        List<TimeSheet> sheets = timeSheetRepo.findAllById(dto.getTimesheetIds());

        if (sheets.isEmpty())
            throw new IllegalArgumentException("No timesheets found.");

        // All must belong to same user & week
        Long userId = dto.getUserId();
        WeekInfo weekInfo = sheets.get(0).getWeekInfo();

        boolean allSameWeek = sheets.stream()
                .allMatch(ts -> ts.getWeekInfo().getId().equals(weekInfo.getId()));

        if (!allSameWeek)
            throw new IllegalArgumentException("Select only one week at a time.");

        // Validate status
        if (!dto.getStatus().equalsIgnoreCase("APPROVED")
                && !dto.getStatus().equalsIgnoreCase("REJECTED"))
            throw new IllegalArgumentException("Status must be APPROVED or REJECTED.");

        if (dto.getStatus().equalsIgnoreCase("REJECTED")
                && (dto.getComments() == null || dto.getComments().isBlank()))
            throw new IllegalArgumentException("Comments required when rejecting.");

        // Cannot approve old weeks (> 30 days)
        if (weekInfo.getEndDate().isBefore(LocalDate.now().minusDays(30))) {
            throw new IllegalArgumentException("Cannot review timesheets older than 30 days.");
        }

        Map<Long, List<InternalProject>> internalMap =
        internalProjectRepo.findAll().stream()
                .collect(Collectors.groupingBy(
                        ip -> ip.getProjectId().longValue()
                ));

        for (TimeSheet ts : sheets) {
            boolean internalOnly = ts.getEntries().stream()
                    .allMatch(e -> internalMap.containsKey(e.getProjectId()));

            if (!internalOnly)
                throw new IllegalArgumentException(
                        "Cannot review external project timesheets here."
                );
        }


        // --------------------------------------------------------
        // CREATE / UPDATE REVIEWS
        // --------------------------------------------------------
        for (TimeSheet ts : sheets) {

            if (ts.getStatus() == TimeSheet.Status.APPROVED || ts.getStatus() == TimeSheet.Status.REJECTED) {
                throw new IllegalArgumentException("Timesheet already reviewed.");
            }

            TimeSheetReview review = reviewRepo
                    .findByTimeSheet_IdAndManagerId(ts.getId(), managerId)
                    .orElseGet(TimeSheetReview::new);

            review.setUserId(userId);
            review.setManagerId(managerId);
            review.setTimeSheet(ts);
            review.setWeekInfo(weekInfo);

            TimeSheetReview.Status reviewStatus;
            String reviewComment;
            if (mixedMode) {
                boolean reject = dto.getRejectedTimesheetIds() != null
                              && dto.getRejectedTimesheetIds().contains(ts.getId());
                reviewStatus  = reject ? TimeSheetReview.Status.REJECTED : TimeSheetReview.Status.APPROVED;
                reviewComment = reject ? dto.getComments() : null;
            } else {
                reviewStatus  = TimeSheetReview.Status.valueOf(dto.getStatus().toUpperCase());
                reviewComment = dto.getComments();
            }
            review.setComments(reviewComment);
            review.setStatus(reviewStatus);
            review.setReviewedAt(LocalDateTime.now());
            reviewRepo.save(review);

            // Internal project has only one manager → direct status
            ts.setStatus(TimeSheet.Status.valueOf(reviewStatus.name()));
            ts.setUpdatedAt(LocalDateTime.now());
            timeSheetRepo.save(ts);
        }

        // --------------------------------------------------------
        // Update weekly status
        // --------------------------------------------------------
        updateWeeklyTimeSheetReview(userId, weekInfo.getId());

        // --------------------------------------------------------
        // Send email summary
        // --------------------------------------------------------
        sendReviewNotificationEmails(managerId, dto, sheets);

        return dto.getStatus() + " " + sheets.size() + " internal timesheets.";
    }

    /**
     * Audit view: list every TimeSheetReview made by this manager, filterable
     * by reviewed user, week-date range, and review status. Pending/Submitted
     * reviews are excluded at the controller layer — this method trusts the
     * statuses passed in.
     */
    public Page<ReviewedTimesheetAuditDTO> getReviewedTimesheetsForManager(
            Long managerId,
            Long userId,
            LocalDate startDate,
            LocalDate endDate,
            List<TimeSheetReview.Status> statuses,
            String authHeader,
            Pageable pageable) {

        Page<TimeSheetReview> page = reviewRepo.findReviewsByManagerWithFilters(
                managerId, userId, startDate, endDate, statuses, pageable);

        if (page.isEmpty()) {
            return page.map(r -> ReviewedTimesheetAuditDTO.builder().build());
        }

        Map<Long, Map<String, Object>> userDir;
        try {
            userDir = userDirectoryService.fetchAllUsers(authHeader);
        } catch (Exception e) {
            userDir = Collections.emptyMap();
        }
        final Map<Long, Map<String, Object>> users = userDir;

        return page.map(review -> {
            WeekInfo week = review.getWeekInfo();
            TimeSheet ts = review.getTimeSheet();
            Map<String, Object> userInfo = users.get(review.getUserId());

            return ReviewedTimesheetAuditDTO.builder()
                    .reviewId(review.getId())
                    .userId(review.getUserId())
                    .userName(userInfo != null && userInfo.get("name") != null
                            ? userInfo.get("name").toString() : null)
                    .userEmail(userInfo != null && userInfo.get("email") != null
                            ? userInfo.get("email").toString() : null)
                    .weekId(week != null ? week.getId() : null)
                    .weekStartDate(week != null ? week.getStartDate() : null)
                    .weekEndDate(week != null ? week.getEndDate() : null)
                    .timesheetId(ts != null ? ts.getId() : null)
                    .workDate(ts != null ? ts.getWorkDate() : null)
                    .hoursWorked(ts != null ? ts.getHoursWorked() : null)
                    .status(review.getStatus() != null ? review.getStatus().name() : null)
                    .comments(review.getComments())
                    .reviewedAt(review.getReviewedAt())
                    .build();
        });
    }
}
