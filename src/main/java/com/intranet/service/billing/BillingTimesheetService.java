package com.intranet.service.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.intranet.dto.billing.BillingSnapshotResponseDTO;
import com.intranet.dto.billing.BillingTimesheetItemDTO;
import com.intranet.entity.TimeSheet;
import com.intranet.repository.TimeSheetEntryRepo;
import com.intranet.util.cache.UserDirectoryService;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BillingTimesheetService {

    private final TimeSheetEntryRepo timeSheetEntryRepo;
    private final UserDirectoryService userDirectoryService;

    public BillingSnapshotResponseDTO getApprovedBillableTimesheets(
            Long projectId, LocalDate startDate, LocalDate endDate) {

        Map<Long, Map<String, Object>> userDirectory =
                userDirectoryService.fetchAllUsers(getAuthorizationHeader());

        List<Object[]> rows = timeSheetEntryRepo.findApprovedBillableHoursByProjectAndDateRange(
                projectId, TimeSheet.Status.APPROVED, startDate, endDate);

        List<BillingTimesheetItemDTO> timesheets = rows.stream()
                .map(row -> {
                    Long userId = (Long) row[0];
                    BigDecimal hours = (BigDecimal) row[1];
                    Map<String, Object> user = userDirectory.get(userId);
                    String resourceName = user != null && user.get("name") != null
                            ? user.get("name").toString()
                            : "Unknown Resource";

                    return new BillingTimesheetItemDTO(
                            buildTimesheetId(projectId, userId, startDate, endDate),
                            userId,
                            resourceName,
                            hours);
                })
                .collect(Collectors.toList());

        return new BillingSnapshotResponseDTO(projectId, startDate, endDate, timesheets);
    }

    private String buildTimesheetId(Long projectId, Long userId, LocalDate startDate, LocalDate endDate) {
        return String.format("BILL-%d-%d-%s-%s", projectId, userId, startDate, endDate);
    }

    private String getAuthorizationHeader() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }

        HttpServletRequest request = attrs.getRequest();
        return request.getHeader("Authorization");
    }
}
