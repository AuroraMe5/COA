package com.example.coa.security;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SessionService {

    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Shanghai");

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final com.example.coa.service.InMemoryCoaService coaService;

    public SessionService(NamedParameterJdbcTemplate jdbcTemplate, com.example.coa.service.InMemoryCoaService coaService) {
        this.jdbcTemplate = jdbcTemplate;
        this.coaService = coaService;
    }

    public Map<String, Object> createSession(AuthenticatedUser user, boolean remember) {
        String accessToken = "access-" + user.id() + "-" + UUID.randomUUID();
        String refreshToken = "refresh-" + user.id() + "-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZONE_ID);
        LocalDateTime accessExpiresAt = now.plusHours(2);
        LocalDateTime refreshExpiresAt = remember ? now.plusDays(30) : now.plusDays(7);

        jdbcTemplate.update("""
            INSERT INTO sys_login_session (
                user_id, access_token, refresh_token, remember_me, expires_at, refresh_expires_at
            ) VALUES (
                :userId, :accessToken, :refreshToken, :rememberMe, :expiresAt, :refreshExpiresAt
            )
            """, new MapSqlParameterSource()
            .addValue("userId", user.id())
            .addValue("accessToken", accessToken)
            .addValue("refreshToken", refreshToken)
            .addValue("rememberMe", remember ? 1 : 0)
            .addValue("expiresAt", Timestamp.valueOf(accessExpiresAt))
            .addValue("refreshExpiresAt", Timestamp.valueOf(refreshExpiresAt)));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accessToken", accessToken);
        payload.put("refreshToken", refreshToken);
        payload.put("expiresIn", 7200);
        payload.put("remember", remember);
        payload.put("issuedAt", Instant.now().toEpochMilli());
        return payload;
    }

    public AuthenticatedUser resolveAccessToken(String token) {
        Long userId = jdbcTemplate.query("""
            SELECT user_id
            FROM sys_login_session
            WHERE access_token = :token
              AND revoked_at IS NULL
              AND expires_at > NOW()
            ORDER BY id DESC
            LIMIT 1
            """, new MapSqlParameterSource("token", token), rs -> rs.next() ? rs.getLong("user_id") : null);

        return userId == null ? null : coaService.getUserById(userId);
    }

    public AuthenticatedUser resolveRefreshToken(String token) {
        Long userId = jdbcTemplate.query("""
            SELECT user_id
            FROM sys_login_session
            WHERE refresh_token = :token
              AND revoked_at IS NULL
              AND (refresh_expires_at IS NULL OR refresh_expires_at > NOW())
            ORDER BY id DESC
            LIMIT 1
            """, new MapSqlParameterSource("token", token), rs -> rs.next() ? rs.getLong("user_id") : null);

        return userId == null ? null : coaService.getUserById(userId);
    }

    public void revoke(String accessToken) {
        jdbcTemplate.update("""
            UPDATE sys_login_session
            SET revoked_at = NOW()
            WHERE access_token = :accessToken
              AND revoked_at IS NULL
            """, new MapSqlParameterSource("accessToken", accessToken));
    }
}
