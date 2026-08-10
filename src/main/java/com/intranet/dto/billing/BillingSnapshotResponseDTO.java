package com.intranet.dto.billing;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BillingSnapshotResponseDTO {
    private Long projectId;
    private LocalDate billingPeriodStart;
    private LocalDate billingPeriodEnd;
    private List<BillingTimesheetItemDTO> timesheets = new ArrayList<>();
}
