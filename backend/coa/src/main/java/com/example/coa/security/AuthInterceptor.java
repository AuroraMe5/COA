package com.example.coa.security;

import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.example.coa.common.ApiException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String CURRENT_USER_ATTR = "CURRENT_USER";

    private final SessionService sessionService;

    public AuthInterceptor(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, 10003, "missing or invalid token");
        }

        String token = authorization.substring("Bearer ".length()).trim();
        AuthenticatedUser user = sessionService.resolveAccessToken(token);
        if (Objects.isNull(user)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, 10003, "token expired or invalid");
        }

        request.setAttribute(CURRENT_USER_ATTR, user);
        return true;
    }
}
