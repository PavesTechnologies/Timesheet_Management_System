package com.intranet.util.cache;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import lombok.RequiredArgsConstructor;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserDirectoryService {

    @Value("${ums.api.base-url}")
    private String umsBaseUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Fetch all users from UMS and return a user cache:
     * key = userId, value = Map with "name", "email", etc.
     */
    
    @Cacheable(value = "userCache", key = "'allUsers'")
    public Map<Long, Map<String, Object>> fetchAllUsers(String authHeader) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", authHeader);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        String umsUrl = String.format("%s/admin/users?page=1&limit=500", umsBaseUrl);

        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    umsUrl, HttpMethod.GET, entity,
                    new ParameterizedTypeReference<>() {}
            );

            Map<String, Object> body = response.getBody();
            if (body == null || !body.containsKey("users")) return Collections.emptyMap();

            List<Map<String, Object>> users = (List<Map<String, Object>>) body.get("users");

            return users.stream()
                    .collect(Collectors.toMap(
                            u -> ((Number) u.get("user_id")).longValue(),
                            u -> {
                                String firstName = (String) u.getOrDefault("first_name", "");
                                String lastName = (String) u.getOrDefault("last_name", "");
                                String fullName = (firstName + " " + lastName).trim();
                                String email = (String) u.getOrDefault("mail", "unknown@example.com");

                                Map<String, Object> userMap = new HashMap<>();
                                userMap.put("name", fullName.isEmpty() ? "Unknown User" : fullName);
                                userMap.put("email", email);
                                userMap.put("role", extractRole(u));
                                return userMap;
                            }
                    ));

        } catch (Exception e) {
            System.err.println("⚠️ Failed to fetch users from UMS: " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    public List<Map<String, Object>> fetchAllUsers2(String authHeader) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", authHeader);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        String umsUrl = String.format("%s/admin/users?page=1&limit=500", umsBaseUrl);

        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    umsUrl, HttpMethod.GET, entity,
                    new ParameterizedTypeReference<>() {}
            );

            Map<String, Object> body = response.getBody();
            if (body == null || !body.containsKey("users")) return List.of();

            List<Map<String, Object>> users = (List<Map<String, Object>>) body.get("users");

            return users.stream()
                    .map(u -> {
                        Long id = ((Number) u.get("user_id")).longValue();
                        String firstName = (String) u.getOrDefault("first_name", "");
                        String lastName = (String) u.getOrDefault("last_name", "");
                        String fullName = (firstName + " " + lastName).trim();
                        String email = (String) u.getOrDefault("mail", "unknown@example.com");

                        Map<String, Object> userMap = new HashMap<>();
                        userMap.put("id", id);
                        userMap.put("name", fullName.isEmpty() ? "Unknown User" : fullName);
                        userMap.put("email", email);
                        userMap.put("role", extractRole(u));
                        return userMap;
                    })
                    .collect(Collectors.toList());

        } catch (Exception e) {
            System.err.println("⚠️ Failed to fetch users from UMS: " + e.getMessage());
            return List.of();
        }
        }

    private String extractRole(Map<String, Object> user) {
        Object role = user.get("role");
        if (role instanceof String roleText && !roleText.isBlank()) {
            return roleText;
        }

        Object roleName = user.get("role_name");
        if (roleName instanceof String roleNameText && !roleNameText.isBlank()) {
            return roleNameText;
        }

        Object roles = user.get("roles");
        if (roles instanceof List<?> roleList && !roleList.isEmpty()) {
            Object firstRole = roleList.get(0);
            if (firstRole instanceof String firstRoleText && !firstRoleText.isBlank()) {
                return firstRoleText;
            }
        }

        return "Unknown";
    }

}
