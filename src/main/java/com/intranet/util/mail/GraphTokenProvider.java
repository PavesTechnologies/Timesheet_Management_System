package com.intranet.util.mail;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Map;

/**
 * Acquires an application (client credentials) access token for Microsoft Graph
 * and caches it until shortly before it expires.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "email.service", havingValue = "azure")
public class GraphTokenProvider {

    private static final String GRAPH_SCOPE = "https://graph.microsoft.com/.default";

    /** Refresh this many seconds before the token actually expires. */
    private static final long EXPIRY_SKEW_SECONDS = 120;

    private final RestTemplate restTemplate;

    @Value("${azure.tenant-id:}")
    private String tenantId;

    @Value("${azure.client-id:}")
    private String clientId;

    @Value("${azure.client-secret:}")
    private String clientSecret;

    @Value("${azure.login.base-url:https://login.microsoftonline.com}")
    private String loginBaseUrl;

    private volatile String cachedToken;
    private volatile Instant cachedTokenExpiry = Instant.EPOCH;

    @PostConstruct
    void validateConfig() {
        requireProperty(tenantId, "AZURE_TENANT_ID");
        requireProperty(clientId, "AZURE_CLIENT_ID");
        requireProperty(clientSecret, "AZURE_CLIENT_SECRET");
        log.info("Microsoft Graph mail transport configured for tenant {}", tenantId);
    }

    private void requireProperty(String value, String envKey) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(
                    envKey + " must be set when EMAIL_SERVICE=azure");
        }
    }

    public String getAccessToken() {
        String token = cachedToken;
        if (token != null && Instant.now().isBefore(cachedTokenExpiry)) {
            return token;
        }
        synchronized (this) {
            if (cachedToken != null && Instant.now().isBefore(cachedTokenExpiry)) {
                return cachedToken;
            }
            return acquireToken();
        }
    }

    private String acquireToken() {
        String url = trimTrailingSlash(loginBaseUrl) + "/" + tenantId + "/oauth2/v2.0/token";

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("scope", GRAPH_SCOPE);
        form.add("grant_type", "client_credentials");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        Map<String, Object> body;
        try {
            ResponseEntity<Map> response =
                    restTemplate.postForEntity(url, new HttpEntity<>(form, headers), Map.class);
            body = response.getBody();
        } catch (RestClientException e) {
            throw new IllegalStateException(
                    "Failed to acquire Microsoft Graph token: " + e.getMessage(), e);
        }

        if (body == null || !(body.get("access_token") instanceof String accessToken)
                || !StringUtils.hasText(accessToken)) {
            throw new IllegalStateException(
                    "Microsoft Graph token response did not contain an access_token");
        }

        long expiresIn = body.get("expires_in") instanceof Number n ? n.longValue() : 3600L;
        cachedToken = accessToken;
        cachedTokenExpiry = Instant.now().plusSeconds(Math.max(expiresIn - EXPIRY_SKEW_SECONDS, 30));
        log.debug("Acquired Microsoft Graph token, valid for {}s", expiresIn);
        return accessToken;
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
