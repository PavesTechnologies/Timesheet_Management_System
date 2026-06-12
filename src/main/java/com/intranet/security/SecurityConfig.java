package com.intranet.security;

import static org.springframework.security.config.Customizer.withDefaults;

import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true, jsr250Enabled = true)
public class SecurityConfig {

  @Value("${app.cors.allowed-origins:http://localhost:5173}")
  private String allowedOrigins;

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration cfg = new CorsConfiguration();
    cfg.setAllowedOrigins(List.of(allowedOrigins.split("\\s*,\\s*")));
    cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Requested-With"));
    cfg.setExposedHeaders(List.of("Authorization"));
    cfg.setAllowCredentials(true);
    cfg.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
    src.registerCorsConfiguration("/**", cfg);
    return src;
  }

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
      .authorizeHttpRequests(auth -> auth
        .requestMatchers("/public/**", "/swagger-ui/**", "/v3/api-docs/**", "/actuator/**").permitAll()
        .anyRequest().authenticated()
      )
      .oauth2ResourceServer(oauth2 -> oauth2
        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
        .authenticationEntryPoint(jwtAuthenticationEntryPoint())
      )
      .csrf(csrf -> csrf.disable())
      .cors(withDefaults());

    return http.build();
  }

  @Bean
  public AuthenticationEntryPoint jwtAuthenticationEntryPoint() {
    return (HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) -> {

      String errorCode;
      String errorDetail;

      Throwable cause = authException.getCause() != null ? authException.getCause() : authException;
      String causeMessage = cause.getMessage() != null ? cause.getMessage().toLowerCase() : "";

      if (causeMessage.contains("expired")) {
        errorCode   = "TOKEN_EXPIRED";
        errorDetail = "Token has expired";

      } else if (causeMessage.contains("iss") || causeMessage.contains("issuer")) {
        errorCode   = "INVALID_ISSUER";
        errorDetail = "Invalid issuer.";

      } else {
        errorCode   = "AUTHENTICATION_FAILED";
        errorDetail = authException.getMessage();
      }

      Map<String, Object> body = new HashMap<>();
      // body.put("status", HttpServletResponse.SC_UNAUTHORIZED);
      // body.put("error", errorCode);
      // body.put("errorDetail", errorDetail);
      // body.put("path", request.getRequestURI());
      // body.put("timestamp", Instant.now().toString());

      body.put("detail", errorDetail);

      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      new ObjectMapper().writeValue(response.getOutputStream(), body);
    };
  }

  private JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(jwt -> {
      List<String> permissions = jwt.getClaimAsStringList("permissions");
      if (permissions == null) return List.of();
      return permissions.stream()
        .map(p -> p.trim().toUpperCase())
        .map(SimpleGrantedAuthority::new)
        .collect(Collectors.toList());
    });
    return converter;
  }
}