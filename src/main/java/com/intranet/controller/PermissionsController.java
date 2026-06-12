package com.intranet.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequestMapping("/api")
public class PermissionsController {

    @PreAuthorize("hasAuthority('EDIT_TIMESHEET')  OR hasAuthority('APPROVE_TIMESHEET')")
    @Operation(summary = "Debug permissions of the authenticated user")
    @GetMapping("/debug/permissions")
    public List<String> debugRoles(Authentication auth) {
        return auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .toList();
    }
    
}
