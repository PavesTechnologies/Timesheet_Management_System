package com.intranet.controller;

import com.intranet.service.WeekInfoService;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/weekinfo")
@RequiredArgsConstructor
public class WeekInfoController {

    private final WeekInfoService weekInfoService;

    @Operation(summary = "Generate WeekInfo entries for a specific month and year")
    @PreAuthorize("hasAuthority('APPROVE_TIMESHEET')")
    @PostMapping("/generate/{year}/{month}")
    public String generateForGivenMonth(@PathVariable int year, @PathVariable int month) {
        weekInfoService.generateWeeksForMonth(year, month);
        return "WeekInfo generated for " + month + "/" + year;
    }
}
