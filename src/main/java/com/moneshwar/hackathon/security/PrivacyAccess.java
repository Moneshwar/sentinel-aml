package com.moneshwar.hackathon.security;

import org.springframework.security.core.context.SecurityContextHolder;

public final class PrivacyAccess {
    private PrivacyAccess() { }

    public static boolean canReadDetails() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated() && authentication.getAuthorities().stream()
                .anyMatch(role -> role.getAuthority().equals("ROLE_ADMIN") || role.getAuthority().equals("ROLE_ANALYST"));
    }
}
