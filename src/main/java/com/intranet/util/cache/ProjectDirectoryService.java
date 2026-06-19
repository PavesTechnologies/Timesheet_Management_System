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
public class ProjectDirectoryService {

    @Value("${pms.api.base-url}")
    private String pmsBaseUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Fetch all projects from PMS (cached)
     */
    @Cacheable(value = "projectCache", key = "'allProjects'")
    public Map<Long, Map<String, Object>> fetchAllProjects(String authHeader) {

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", authHeader);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        String pmsUrl = String.format("%s/projects/tms", pmsBaseUrl);

        try {
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    pmsUrl, HttpMethod.GET, entity,
                    new ParameterizedTypeReference<>() {}
            );

            List<Map<String, Object>> projects =
                    Optional.ofNullable(response.getBody()).orElse(Collections.emptyList());

            return projects.stream()
                    .filter(p -> p.get("id") != null)
                    .collect(Collectors.toMap(
                            p -> ((Number) p.get("id")).longValue(),
                            this::convertProjectToMap
                    ));

        } catch (Exception e) {
            System.err.println("⚠️ PMS Fetch Error: " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Convert project object into standardized map format
     */
    private Map<String, Object> convertProjectToMap(Map<String, Object> p) {

        Map<String, Object> map = new LinkedHashMap<>();

        Long id = ((Number) p.get("id")).longValue();
        String name = (String) p.getOrDefault("name", "Unnamed Project");
        // -----------------------------
        // Extract Project Owner
        // -----------------------------
        Map<String, Object> owner = (Map<String, Object>) p.get("owner");
        String ownerName = "Unknown Manager";
        String ownerEmail = "unknown@example.com";
        Long ownerId = null;

        if (owner != null) {
            ownerId = owner.get("id") != null ? ((Number) owner.get("id")).longValue() : null;
            ownerName = (String) owner.getOrDefault("name", "Unknown Manager");
            ownerEmail = (String) owner.getOrDefault("email", "unknown@example.com");
        }

        // -----------------------------
        // Extract Members (team)
        // -----------------------------
        List<Map<String, Object>> membersList = new ArrayList<>();
        Object membersObj = p.get("members");  // PMS typically returns "members"

        if (membersObj instanceof List<?>) {
            List<?> members = (List<?>) membersObj;

            for (Object mObj : members) {
                if (mObj instanceof Map<?, ?> m) {
                    Long memberId = m.containsKey("id")
                            ? ((Number) m.get("id")).longValue()
                            : null;

                    String memberName = m.get("name") != null
                            ? m.get("name").toString()
                            : "Unknown";

                    String memberEmail = m.get("email") != null
                            ? m.get("email").toString()
                            : "unknown@example.com";

                    Map<String, Object> memberMap = new LinkedHashMap<>();
                    memberMap.put("id", memberId);
                    memberMap.put("name", memberName);
                    memberMap.put("email", memberEmail);

                    membersList.add(memberMap);
                }
            }
        }

        // -----------------------------
        // Final Project Object
        // -----------------------------
        // Extract client name (PMS may return "client" as an object or "clientName" as a string)
        Object clientObj = p.get("client");
        String clientName = null;
        if (clientObj instanceof Map<?, ?> clientMap) {
            clientName = clientMap.get("name") != null ? clientMap.get("name").toString() : null;
        } else if (clientObj instanceof String s && !s.isBlank()) {
            clientName = s;
        } else if (p.get("clientName") instanceof String s && !s.isBlank()) {
            clientName = s;
        }

        map.put("id", id);
        map.put("name", name);
        if (clientName != null) map.put("clientName", clientName);
        map.put("ownerId", ownerId);
        map.put("ownerName", ownerName);
        map.put("ownerEmail", ownerEmail);
        map.put("members", membersList);
        copyIfPresent(p, map, "startDate");
        copyIfPresent(p, map, "endDate");
        copyIfPresent(p, map, "plannedHours");
        copyIfPresent(p, map, "estimatedHours");
        copyIfPresent(p, map, "allocatedHours");
        copyIfPresent(p, map, "plannedEffort");
        copyIfPresent(p, map, "projectHours");
        copyIfPresent(p, map, "hours");

        return map;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key) && source.get(key) != null) {
            target.put(key, source.get(key));
        }
    }
}
