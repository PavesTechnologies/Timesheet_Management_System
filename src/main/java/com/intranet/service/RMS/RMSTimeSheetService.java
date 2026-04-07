package com.intranet.service.RMS;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;

import com.intranet.dto.rms.RMSProjectHoursDTO;
import com.intranet.dto.rms.TimeSheetSummaryResponseDTO;
import com.intranet.repository.TimeSheetEntryRepo;
import com.intranet.repository.TimeSheetRepo;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RMSTimeSheetService {

    private final TimeSheetRepo timeSheetRepository;
    private final TimeSheetEntryRepo entryRepository;

    public TimeSheetSummaryResponseDTO getSummary(
            Long userId,
            LocalDate startDate,
            LocalDate endDate) {

        BigDecimal totalHours = timeSheetRepository.getTotalHours(userId, startDate, endDate);
        BigDecimal billableHours = entryRepository.getBillableHours(userId, startDate, endDate);
        BigDecimal nonBillableHours = entryRepository.getNonBillableHours(userId, startDate, endDate);
        List<RMSProjectHoursDTO> projectHours = entryRepository.getProjectHours(userId, startDate, endDate);

        TimeSheetSummaryResponseDTO response = new TimeSheetSummaryResponseDTO();
        response.setResourceId(userId);
        response.setStartDate(startDate);
        response.setEndDate(endDate);
        response.setTotalHours(totalHours);
        response.setBillableHours(billableHours);
        response.setNonBillableHours(nonBillableHours);
        response.setProjectHours(projectHours);

        return response;
    }
}