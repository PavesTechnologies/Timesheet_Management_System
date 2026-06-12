package com.intranet.controller.external;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import com.intranet.dto.UserDTO;
import com.intranet.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/manager")
public class ManagerTimeSheetController {

    @Value("${pms.api.base-url}")
    private String pmsBaseUrl;

    @Value("${ums.api.base-url}")
    private String umsBaseUrl;

    private final RestTemplate restTemplate = new RestTemplate();


    @Operation(summary = "Get all users under a manager")
    @PreAuthorize("hasAuthority('APPROVE_TIMESHEET')")
    @GetMapping("/users")
    // @PreAuthorize("hasAuthority('APPROVE_TIMESHEET')")
    public ResponseEntity<List<Map<String, Object>>> getUsersUnderManager(
        @CurrentUser UserDTO user,
        HttpServletRequest request) {

    // Step 1: Prepare Authorization header
    String authHeader = request.getHeader("Authorization");
    HttpHeaders headers = new HttpHeaders();
    if (authHeader != null && !authHeader.isBlank()) {
        headers.set("Authorization", authHeader); // forward the Bearer token
    }
    HttpEntity<Void> entity = new HttpEntity<>(headers);

    // Step 2: Get projects owned by this manager from PMS API
    String url = String.format("%s/projects/owner", pmsBaseUrl);
    ResponseEntity<List<Map<String, Object>>> response =
            restTemplate.exchange(url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
    List<Map<String, Object>> projects = response.getBody();

    if (projects == null || projects.isEmpty()) {
        return ResponseEntity.ok(Collections.emptyList());
    }

    // Step 3: Collect all unique member IDs from all projects
    Set<Long> memberIds = projects.stream()
            .flatMap(p -> Optional.ofNullable((List<Map<String, Object>>) p.get("members"))
                    .orElse(Collections.emptyList()).stream())
            .map(m -> ((Number) m.get("id")).longValue())
            .collect(Collectors.toSet());

    if (memberIds.isEmpty()) {
        return ResponseEntity.ok(Collections.emptyList());
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> users = new ArrayList<>();

    Map<Long, Map<String, Object>> userCache = new HashMap<>();

    try {
        // 🔹 Bulk fetch all users (adjust page/limit as needed)
        String umsUrl = String.format("%s/admin/users?page=1&limit=500", umsBaseUrl);
        ResponseEntity<Map<String, Object>> umsResponse = restTemplate.exchange(
                umsUrl, HttpMethod.GET, entity, new ParameterizedTypeReference<Map<String, Object>>() {});

        Map<String, Object> body = umsResponse.getBody();
        if (body != null && body.containsKey("users")) {
            Object usersObj = body.get("users");
            if (usersObj instanceof List<?>) {
                List<?> list = (List<?>) usersObj;
                for (Object obj : list) {
                    if (obj instanceof Map<?, ?>) {
                        Map<String, Object> u = (Map<String, Object>) obj;

                        Number idNum = (Number) u.get("user_id");
                        if (idNum != null) {
                            Long id = idNum.longValue();

                            String firstName = u.get("first_name") != null ? (String) u.get("first_name") : "";
                            String lastName  = u.get("last_name")  != null ? (String) u.get("last_name")  : "";
                            String email     = u.get("mail")       != null ? (String) u.get("mail")       : "";

                            Map<String, Object> info = new HashMap<>();
                            info.put("firstName", firstName);
                            info.put("lastName", lastName);
                            info.put("email", email);
                            info.put("fullName", (firstName + " " + lastName).trim());

                            userCache.put(id, info);
                        }
                    }
                }
            }
        }
    } catch (Exception e) {
        System.err.println("⚠️ Failed to load users from UMS: " + e.getMessage());
    }

    // --- 🔹 Build final user list from team members ---
    for (Long userId : memberIds) {
        Map<String, Object> info = userCache.get(userId);
        Map<String, Object> userN = new HashMap<>();

        userN.put("id", userId);

        if (info != null) {
            userN.putAll(info);
        } else {
            userN.put("firstName", "");
            userN.put("lastName", "");
            userN.put("email", "unknown@example.com");
            userN.put("fullName", "User not found in UMS");
        }

        users.add(userN);
    }



    return ResponseEntity.ok(users);
    }
    
}
