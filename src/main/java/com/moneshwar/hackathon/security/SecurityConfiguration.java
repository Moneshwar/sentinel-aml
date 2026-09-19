package com.moneshwar.hackathon.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(Environment environment, PasswordEncoder encoder) {
        List<UserDetails> users = new ArrayList<>();
        for (String role : List.of("ADMIN", "ANALYST", "VIEWER")) {
            String password = environment.getProperty("SENTINEL_" + role + "_PASSWORD", "");
            if (!password.isBlank()) {
                users.add(User.withUsername(role.toLowerCase(java.util.Locale.ROOT))
                        .password(encoder.encode(password)).roles(role).build());
            }
        }
        // No generated user or fallback password. Unconfigured roles cannot sign in.
        return new InMemoryUserDetailsManager(users);
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .httpBasic(basic -> basic.authenticationEntryPoint((request, response, exception) ->
                        problem(request, response, 401, "Unauthorized", "Valid credentials are required")))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                problem(request, response, 401, "Unauthorized", "Valid credentials are required"))
                        .accessDeniedHandler((request, response, exception) ->
                                problem(request, response, 403, "Forbidden", "Your role cannot perform this action")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/v1/session").authenticated()
                        .requestMatchers("/api/v1/admin/**", "/api/v1/rules/**", "/api/v1/config/**", "/api/v1/settings/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/ingestion/errors").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/ingestion/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/customers/*", "/api/v1/customers/by-id/*", "/api/v1/accounts/*", "/api/v1/transactions/*")
                            .hasAnyRole("ADMIN", "ANALYST")
                        .requestMatchers(HttpMethod.GET, "/api/v1/audit/**").hasAnyRole("ADMIN", "ANALYST")
                        .requestMatchers(HttpMethod.GET, "/api/v1/**", "/v3/api-docs/**", "/swagger-ui/**")
                            .hasAnyRole("ADMIN", "ANALYST", "VIEWER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/cases").hasAnyRole("ADMIN", "ANALYST")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/alerts/*/status", "/api/v1/cases/*/status")
                            .hasAnyRole("ADMIN", "ANALYST")
                        .requestMatchers("/api/v1/**").hasRole("ADMIN")
                        .anyRequest().denyAll());
        return http.build();
    }

    private static void problem(HttpServletRequest request, HttpServletResponse response,
                                int status, String title, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        if (status == 401) {
            response.setHeader("WWW-Authenticate", "Basic realm=\"Sentinel\", charset=\"UTF-8\"");
        }
        // Fixed fields only: do not interpolate attacker-controlled URLs into JSON.
        response.getWriter().write("{\"type\":\"about:blank\",\"status\":" + status
                + ",\"title\":\"" + title + "\",\"detail\":\"" + detail + "\"}");
    }
}
