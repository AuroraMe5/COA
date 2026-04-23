package com.example.coa.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.coa.common.ApiException;
import com.example.coa.security.AuthInterceptor;
import com.example.coa.security.AuthenticatedUser;
import com.example.coa.security.SessionService;
import com.example.coa.service.InMemoryCoaService;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final InMemoryCoaService coaService;
    private final SessionService sessionService;

    public AuthController(InMemoryCoaService coaService, SessionService sessionService) {
        this.coaService = coaService;
        this.sessionService = sessionService;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> payload) {
        AuthenticatedUser user = coaService.authenticate(
            String.valueOf(payload.get("username")),
            String.valueOf(payload.get("password"))
        );
        boolean remember = Boolean.parseBoolean(String.valueOf(payload.getOrDefault("remember", false)));
        Map<String, Object> session = sessionService.createSession(user, remember);
        session.put("userInfo", toUserInfo(user));
        return session;
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            sessionService.revoke(authorization.substring("Bearer ".length()).trim());
        }
        return Map.of("success", true);
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(@RequestBody Map<String, Object> payload) {
        String refreshToken = String.valueOf(payload.getOrDefault("refreshToken", ""));
        AuthenticatedUser user = sessionService.resolveRefreshToken(refreshToken);
        if (user == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, 10003, "refresh token is invalid");
        }
        Map<String, Object> session = sessionService.createSession(user, true);
        return Map.of(
            "accessToken", session.get("accessToken"),
            "refreshToken", session.get("refreshToken"),
            "expiresIn", session.get("expiresIn")
        );
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        AuthenticatedUser user = (AuthenticatedUser) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTR);
        return toUserInfo(user);
    }

    private Map<String, Object> toUserInfo(AuthenticatedUser user) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.id());
        result.put("username", user.username());
        result.put("realName", user.realName());
        result.put("email", user.email());
        result.put("phone", user.phone());
        result.put("collegeId", user.collegeId());
        result.put("collegeName", user.collegeName());
        result.put("roles", user.roles());
        result.put("status", user.status());
        return result;
    }
}
