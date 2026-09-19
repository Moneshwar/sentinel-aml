package com.moneshwar.hackathon.security;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/session")
public class SessionController {
    public record SessionResponse(String username, List<String> roles) { }

    @GetMapping
    public SessionResponse session(Authentication authentication) {
        return new SessionResponse(authentication.getName(), authentication.getAuthorities().stream()
                .filter(authority -> authority.getAuthority().startsWith("ROLE_"))
                .map(authority -> authority.getAuthority().replaceFirst("^ROLE_", ""))
                .sorted().toList());
    }
}
