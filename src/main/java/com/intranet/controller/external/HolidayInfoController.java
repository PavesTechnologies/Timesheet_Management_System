package com.intranet.controller.external;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.intranet.dto.HolidayDTO;
import com.intranet.dto.UserDTO;
import com.intranet.security.CurrentUser;
import com.intranet.service.HolidayExcludeUsersService;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/holidays")
public class HolidayInfoController {


    @Autowired
    private HolidayExcludeUsersService userHolidayService;

    @Value("${lms.api.base-url}")
    private String lmsBaseUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    private HttpEntity<Void> buildEntityWithAuth() {

    ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    if (attrs == null) {
        return (HttpEntity<Void>) HttpEntity.EMPTY;
    }

    HttpServletRequest request = attrs.getRequest();
    String authHeader = request.getHeader("Authorization");

    HttpHeaders headers = new HttpHeaders();
    if (authHeader != null && !authHeader.isBlank()) {
        headers.set("Authorization", authHeader);
    }

    return new HttpEntity<>(headers);
    }
    

    @GetMapping("/check/{workDate}")
    @Operation(summary = "Check if a given date is a public holiday via LMS API")
    @PreAuthorize("hasAuthority('EDIT_TIMESHEET') or hasAuthority('APPROVE_TIMESHEET')")
    public ResponseEntity<?> checkHoliday(@PathVariable String workDate) {

            HttpEntity<Void> entity = buildEntityWithAuth();
            // ✅ 2. Check if date is a public holiday via LMS API
            String holidayUrl = String.format("%s/api/holidays/check?date=%s", lmsBaseUrl, workDate);
            try {
                ResponseEntity<Map<String, Object>> holidayResponse = restTemplate.exchange(
                        holidayUrl, HttpMethod.GET, entity,
                        new ParameterizedTypeReference<>() {}
                );

                Map<String, Object> holidayBody = holidayResponse.getBody();
                if (holidayBody != null && "yes".equalsIgnoreCase((String) holidayBody.get("status"))) {
                    return ResponseEntity.badRequest()
                            .body("Holiday: " + holidayBody.get("message"));
                }
            } catch (Exception e) {
                System.err.println("Holiday API check failed: " + e.getMessage());
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body("⚠️ Unable to verify public holiday status. Please try again later.");
            }
            return ResponseEntity.ok().body(" Date is not a holiday. You may proceed.");
    }

    @GetMapping("/currentMonthLeaves")
    @Operation(summary = "Get User Holidays for Current Month including public holidays and excluded holidays and leaves")
    @PreAuthorize("hasAuthority('EDIT_TIMESHEET') or hasAuthority('APPROVE_TIMESHEET')")
    public ResponseEntity<?> getUserHolidays2(
            @CurrentUser UserDTO currentUser) {

        int month = java.time.LocalDate.now().getMonthValue();
        int year = java.time.LocalDate.now().getYear();
        try {
        List<HolidayDTO> holidays = userHolidayService.getUserHolidaysAndLeave(currentUser.getId(), month,year);
        return ResponseEntity.ok(holidays);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("⚠️ " + e.getMessage());
        }
        catch (Exception e) {
            return ResponseEntity.badRequest().body("⚠️ " + e.getMessage());
        }
    }

    @GetMapping("/currentMonth")
    @Operation(summary = "Get User Holidays for Current Month including public holidays and excluded holidays and leaves")
    @PreAuthorize("hasAuthority('EDIT_TIMESHEET') or hasAuthority('APPROVE_TIMESHEET')")
    public ResponseEntity<?> getUserHolidays(
            @CurrentUser UserDTO currentUser) {

        int month = java.time.LocalDate.now().getMonthValue();
        int year = java.time.LocalDate.now().getYear();
        try {
        List<HolidayDTO> holidays = userHolidayService.getUserHolidays(currentUser.getId(), month);
        return ResponseEntity.ok(holidays);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("⚠️ " + e.getMessage());
        }
        catch (Exception e) {
            return ResponseEntity.badRequest().body("⚠️ " + e.getMessage());
        }
    }
}
