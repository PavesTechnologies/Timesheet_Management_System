package com.intranet.dto;

import lombok.Data;
import java.time.LocalDate;
import java.util.List;

import com.intranet.dto.external.ManagerInfoDTO;

@Data
public class HolidayDTO {
    private Long holidayId;
    private String holidayName;
    private LocalDate holidayDate;
    private String holidayDescription;
    private String type;
    private String state;
    private String country;
    private Integer year;
    public boolean  isLeave;
    private boolean submitTimesheet;
    private String timeSheetReviews;
    /**
     * For LEAVE entries: the raw LMS status, APPROVED or PENDING. Null for public
     * holidays and weekends. Only these two statuses ever reach here — a REJECTED or
     * CANCELLED leave leaves the day open, so it produces no entry at all.
     */
    private String leaveStatus;
    private List<ManagerInfoDTO> allowedManagers; // ✅ new field

}
