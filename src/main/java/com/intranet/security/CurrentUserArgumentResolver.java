package com.intranet.security;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import com.intranet.dto.UserDTO;

import org.springframework.web.context.request.NativeWebRequest;


@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterAnnotation(CurrentUser.class) != null &&
               parameter.getParameterType().equals(UserDTO.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, org.springframework.web.bind.support.WebDataBinderFactory binderFactory) {

        Authentication auth = (Authentication) webRequest.getUserPrincipal();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt)) {
            return null;
        }

         // Normalize roles: remove spaces, replace with underscores, uppercase
        

        Jwt jwt = (Jwt) auth.getPrincipal();
        // Normalize roles: trim, replace spaces with underscores, convert to uppercase
        List<String> normalizedRoles = jwt.getClaimAsStringList("roles").stream()
                .map(role -> role.trim().replace(" ", "_").toUpperCase())
                .collect(Collectors.toList());

        // Normalize permissions: trim and convert to uppercase
        List<String> normalizedPermissions = jwt.getClaimAsStringList("permissions").stream()
                .map(permission -> permission.trim().toUpperCase())
                .collect(Collectors.toList());
        // Build UserDTO
        UserDTO userDTO = new UserDTO(
                Long.valueOf(jwt.getClaimAsString("user_id")), // user_id as Long
                jwt.getClaimAsString("employee_id") != null ? jwt.getClaimAsString("employee_id") : "0", // employee_id as String, defaults to "0" if null
                jwt.getClaimAsString("obs_user_uuid") != null ? jwt.getClaimAsString("obs_user_uuid") : "No OBS User UUID",     // user_uuid as String
                jwt.getClaimAsString("name"),                 // name
                jwt.getClaimAsString("email"),                // email as String
                normalizedRoles,                              // roles
                normalizedPermissions                          // permissions
        );
        return userDTO;
    }
}
