package com.intranet.service.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
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

        List<Object[]> rows = timeSheetEntryRepo.findApprovedBillableTimesheetsByProjectAndDateRange(
                projectId, TimeSheet.Status.APPROVED, startDate, endDate);

        List<BillingTimesheetItemDTO> timesheets = rows.stream()
                .map(row -> {
                    Long timesheetId = ((Number) row[0]).longValue();
                    Long userId = row[1] != null ? ((Number) row[1]).longValue() : null;
                    LocalDate workDate = (LocalDate) row[2];
                    BigDecimal hours = row[3] != null ? (BigDecimal) row[3] : BigDecimal.ZERO;
                    TimeSheet.Status approvalStatus = row[4] != null ? (TimeSheet.Status) row[4] : TimeSheet.Status.APPROVED;

                    Map<String, Object> user = userDirectory.get(userId);
                    String resourceName = user != null && user.get("name") != null
                            ? user.get("name").toString()
                            : "Unknown Resource";
                    String role = user != null && user.get("role") != null
                            ? user.get("role").toString()
                            : "Unknown";

                    return new BillingTimesheetItemDTO(
                            String.valueOf(timesheetId),
                            userId,
                            resourceName,
                            workDate,
                            hours,
                            role,
                            approvalStatus.name());
                })
                .collect(Collectors.toCollection(ArrayList::new));

        BillingSnapshotResponseDTO response = new BillingSnapshotResponseDTO();
        response.setProjectId(projectId);
        response.setBillingPeriodStart(startDate);
        response.setBillingPeriodEnd(endDate);
        response.setTimesheets(timesheets);

        return response;
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
