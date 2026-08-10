package com.intranet.service.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.intranet.dto.billing.BillingSnapshotResponseDTO;
import com.intranet.entity.TimeSheet;
import com.intranet.repository.TimeSheetEntryRepo;
import com.intranet.util.cache.UserDirectoryService;

@ExtendWith(MockitoExtension.class)
class BillingTimesheetServiceTest {

    @Mock
    private TimeSheetEntryRepo timeSheetEntryRepo;

    @Mock
    private UserDirectoryService userDirectoryService;

    @InjectMocks
    private BillingTimesheetService billingTimesheetService;

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void getApprovedBillableTimesheetsReturnsRequestedFieldsAndEmptyListWhenNoRows() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer test-token");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        when(userDirectoryService.fetchAllUsers("Bearer test-token"))
                .thenReturn(Map.of(42L, Map.of("name", "Jane Doe", "role", "Developer")));
        when(timeSheetEntryRepo.findApprovedBillableTimesheetsByProjectAndDateRange(
                101L, TimeSheet.Status.APPROVED, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)))
                .thenReturn(List.<Object[]>of(new Object[]{77L, 42L, LocalDate.of(2026, 1, 15), new BigDecimal("8.0"), TimeSheet.Status.APPROVED}));

        BillingSnapshotResponseDTO response = billingTimesheetService.getApprovedBillableTimesheets(
                101L, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

        assertNotNull(response);
        assertNotNull(response.getTimesheets());
        assertEquals(1, response.getTimesheets().size());
        assertEquals("77", response.getTimesheets().get(0).getTimesheetId());
        assertEquals("Jane Doe", response.getTimesheets().get(0).getResourceName());
        assertEquals(LocalDate.of(2026, 1, 15), response.getTimesheets().get(0).getWorkDate());
        assertEquals(new BigDecimal("8.0"), response.getTimesheets().get(0).getHours());
        assertEquals("Developer", response.getTimesheets().get(0).getRole());
        assertEquals("APPROVED", response.getTimesheets().get(0).getApprovalStatus());
    }
}
