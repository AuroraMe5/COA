package com.example.coa.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.example.coa.common.ApiException;

import jakarta.annotation.PostConstruct;

@Service
@Transactional
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String ADMIN_ROLE = "ADMIN";
    private static final String TEACHER_ROLE = "TEACHER";
    private static final String DEFAULT_ADMIN_USERNAME = "admin";
    private static final String DEFAULT_ADMIN_PASSWORD_HASH = "$2a$10$zeMmMS8JizGYIZGbBq6nPuCjbE1O5oJJawLH6Dbtk2YKpD3f9hwti";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AdminUserService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void ensureDefaultAdminAccount() {
        try {
            ensureRole(ADMIN_ROLE, "超级管理员", "拥有系统最高权限，可管理教师账号和全局数据");
            ensureRole(TEACHER_ROLE, "任课教师", "负责课程目标管理、数据采集与改进闭环");
            ensureDefaultAdminUser();
        } catch (Exception error) {
            log.warn("Default admin account bootstrap skipped: {}", error.getMessage());
        }
    }

    public Map<String, Object> listTeachers(String keyword, String status) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder("""
            SELECT
                u.id,
                u.username,
                u.real_name,
                u.email,
                u.phone,
                u.college_id,
                c.college_name,
                u.status,
                u.last_login_at,
                u.created_at,
                u.updated_at
            FROM sys_user u
            JOIN sys_user_role ur ON ur.user_id = u.id
            JOIN sys_role r ON r.id = ur.role_id
            LEFT JOIN base_college c ON c.id = u.college_id
            WHERE r.role_code = 'TEACHER'
              AND u.status IN (0, 1)
            """);

        String keywordText = defaultString(keyword, "");
        if (StringUtils.hasText(keywordText)) {
            sql.append("""
                AND (
                    u.username LIKE :keyword
                    OR u.real_name LIKE :keyword
                    OR u.email LIKE :keyword
                    OR u.phone LIKE :keyword
                    OR c.college_name LIKE :keyword
                )
                """);
            params.addValue("keyword", "%" + keywordText + "%");
        }

        String statusText = defaultString(status, "all");
        if ("enabled".equalsIgnoreCase(statusText)) {
            sql.append(" AND u.status = 1 ");
        } else if ("disabled".equalsIgnoreCase(statusText)) {
            sql.append(" AND u.status = 0 ");
        }

        sql.append(" ORDER BY u.status DESC, u.id ASC");
        List<Map<String, Object>> teachers = jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> teacherRow(rs));

        long enabled = teacherCount(1);
        long disabled = teacherCount(0);
        return map(
            "teachers", teachers,
            "summary", map(
                "total", enabled + disabled,
                "enabled", enabled,
                "disabled", disabled
            ),
            "colleges", listColleges()
        );
    }

    public Map<String, Object> createTeacher(Map<String, Object> payload) {
        String username = requireText(payload.get("username"), "用户名不能为空");
        String password = requireText(payload.get("password"), "初始密码不能为空");
        String realName = requireText(payload.get("realName"), "姓名不能为空");
        Long collegeId = nullableLong(payload.get("collegeId"));
        int status = statusFromPayload(payload, 1);

        validateAccountInput(username, password, realName);
        validateCollege(collegeId);
        ensureUsernameAvailable(username);

        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
            INSERT INTO sys_user (
                username, password_hash, real_name, email, phone, college_id, status
            ) VALUES (
                :username, :passwordHash, :realName, :email, :phone, :collegeId, :status
            )
            """, new MapSqlParameterSource()
            .addValue("username", username)
            .addValue("passwordHash", passwordEncoder.encode(password))
            .addValue("realName", realName)
            .addValue("email", nullableText(payload.get("email")))
            .addValue("phone", nullableText(payload.get("phone")))
            .addValue("collegeId", collegeId)
            .addValue("status", status), keyHolder);

        Long userId = keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
        if (userId == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, 500, "教师账号创建失败");
        }
        assignRole(userId, TEACHER_ROLE);
        return getTeacherById(userId);
    }

    public Map<String, Object> resetTeacherPassword(Long id, Map<String, Object> payload) {
        requireTeacherAccount(id);
        String password = requireText(payload.get("password"), "新密码不能为空");
        if (password.length() < 6 || password.length() > 72) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, 400, "密码长度需为 6-72 位");
        }

        jdbcTemplate.update("""
            UPDATE sys_user
            SET password_hash = :passwordHash,
                updated_at = NOW()
            WHERE id = :id
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("passwordHash", passwordEncoder.encode(password)));
        revokeUserSessions(id);
        return getTeacherById(id);
    }

    public Map<String, Object> updateTeacherStatus(Long id, Map<String, Object> payload) {
        requireTeacherAccount(id);
        int status = statusFromPayload(payload, 1);
        jdbcTemplate.update("""
            UPDATE sys_user
            SET status = :status,
                updated_at = NOW()
            WHERE id = :id
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("status", status));
        if (status == 0) {
            revokeUserSessions(id);
        }
        return getTeacherById(id);
    }

    public Map<String, Object> deleteTeacher(Long id) {
        Map<String, Object> teacher = requireTeacherAccount(id);
        if (hasRole(id, ADMIN_ROLE)) {
            throw new ApiException(HttpStatus.FORBIDDEN, 403, "不能删除管理员账号");
        }

        jdbcTemplate.update("""
            UPDATE sys_user
            SET username = :deletedUsername,
                status = -1,
                updated_at = NOW()
            WHERE id = :id
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("deletedUsername", deletedUsername(id, stringValue(teacher.get("username")))));
        revokeUserSessions(id);
        return map("success", true);
    }

    private void ensureRole(String roleCode, String roleName, String description) {
        int updated = jdbcTemplate.update("""
            UPDATE sys_role
            SET role_name = :roleName,
                description = :description,
                updated_at = NOW()
            WHERE role_code = :roleCode
            """, new MapSqlParameterSource()
            .addValue("roleCode", roleCode)
            .addValue("roleName", roleName)
            .addValue("description", description));
        if (updated > 0) {
            return;
        }

        jdbcTemplate.update("""
            INSERT INTO sys_role (role_name, role_code, description)
            VALUES (:roleName, :roleCode, :description)
            """, new MapSqlParameterSource()
            .addValue("roleCode", roleCode)
            .addValue("roleName", roleName)
            .addValue("description", description));
    }

    private void ensureDefaultAdminUser() {
        Long adminId = jdbcTemplate.query("""
            SELECT id
            FROM sys_user
            WHERE username = :username
            LIMIT 1
            """, new MapSqlParameterSource("username", DEFAULT_ADMIN_USERNAME),
            rs -> rs.next() ? rs.getLong("id") : null);

        if (adminId == null) {
            GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update("""
                INSERT INTO sys_user (
                    username, password_hash, real_name, email, phone, college_id, status
                ) VALUES (
                    :username, :passwordHash, :realName, :email, :phone, NULL, 1
                )
                """, new MapSqlParameterSource()
                .addValue("username", DEFAULT_ADMIN_USERNAME)
                .addValue("passwordHash", DEFAULT_ADMIN_PASSWORD_HASH)
                .addValue("realName", "超级管理员")
                .addValue("email", "admin@coa.local")
                .addValue("phone", ""), keyHolder);
            adminId = keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
        } else {
            jdbcTemplate.update("""
                UPDATE sys_user
                SET real_name = CASE WHEN real_name IS NULL OR real_name = '' THEN '超级管理员' ELSE real_name END,
                    status = 1,
                    updated_at = NOW()
                WHERE id = :id
                """, new MapSqlParameterSource("id", adminId));
        }

        if (adminId != null) {
            assignRole(adminId, ADMIN_ROLE);
        }
    }

    private void assignRole(Long userId, String roleCode) {
        jdbcTemplate.update("""
            INSERT INTO sys_user_role (user_id, role_id)
            SELECT :userId, r.id
            FROM sys_role r
            WHERE r.role_code = :roleCode
              AND NOT EXISTS (
                  SELECT 1
                  FROM sys_user_role ur
                  WHERE ur.user_id = :userId
                    AND ur.role_id = r.id
              )
            """, new MapSqlParameterSource()
            .addValue("userId", userId)
            .addValue("roleCode", roleCode));
    }

    private Map<String, Object> getTeacherById(Long id) {
        return jdbcTemplate.query("""
            SELECT
                u.id,
                u.username,
                u.real_name,
                u.email,
                u.phone,
                u.college_id,
                c.college_name,
                u.status,
                u.last_login_at,
                u.created_at,
                u.updated_at
            FROM sys_user u
            JOIN sys_user_role ur ON ur.user_id = u.id
            JOIN sys_role r ON r.id = ur.role_id
            LEFT JOIN base_college c ON c.id = u.college_id
            WHERE u.id = :id
              AND r.role_code = 'TEACHER'
              AND u.status IN (0, 1)
            LIMIT 1
            """, new MapSqlParameterSource("id", id),
            rs -> rs.next() ? teacherRow(rs) : null);
    }

    private Map<String, Object> requireTeacherAccount(Long id) {
        Map<String, Object> teacher = getTeacherById(id);
        if (teacher == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, 404, "教师账号不存在");
        }
        return teacher;
    }

    private boolean hasRole(Long userId, String roleCode) {
        return count("""
            SELECT COUNT(*)
            FROM sys_user_role ur
            JOIN sys_role r ON r.id = ur.role_id
            WHERE ur.user_id = :userId
              AND r.role_code = :roleCode
            """, new MapSqlParameterSource()
            .addValue("userId", userId)
            .addValue("roleCode", roleCode)) > 0;
    }

    private void revokeUserSessions(Long userId) {
        jdbcTemplate.update("""
            UPDATE sys_login_session
            SET revoked_at = NOW()
            WHERE user_id = :userId
              AND revoked_at IS NULL
            """, new MapSqlParameterSource("userId", userId));
    }

    private long teacherCount(int status) {
        return count("""
            SELECT COUNT(*)
            FROM sys_user u
            JOIN sys_user_role ur ON ur.user_id = u.id
            JOIN sys_role r ON r.id = ur.role_id
            WHERE r.role_code = 'TEACHER'
              AND u.status = :status
            """, new MapSqlParameterSource("status", status));
    }

    private List<Map<String, Object>> listColleges() {
        return jdbcTemplate.query("""
            SELECT id, college_name, college_code
            FROM base_college
            WHERE status = 1
            ORDER BY id ASC
            """, new MapSqlParameterSource(), (rs, rowNum) -> map(
            "id", rs.getLong("id"),
            "name", rs.getString("college_name"),
            "code", rs.getString("college_code")
        ));
    }

    private Map<String, Object> teacherRow(ResultSet rs) throws SQLException {
        return map(
            "id", rs.getLong("id"),
            "username", rs.getString("username"),
            "realName", rs.getString("real_name"),
            "email", defaultString(rs.getString("email"), ""),
            "phone", defaultString(rs.getString("phone"), ""),
            "collegeId", nullableLong(rs.getObject("college_id")),
            "collegeName", defaultString(rs.getString("college_name"), ""),
            "status", rs.getInt("status"),
            "lastLoginAt", formatTime(rs.getTimestamp("last_login_at")),
            "createdAt", formatTime(rs.getTimestamp("created_at")),
            "updatedAt", formatTime(rs.getTimestamp("updated_at"))
        );
    }

    private void validateAccountInput(String username, String password, String realName) {
        if (username.length() > 50) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, 400, "用户名不能超过 50 个字符");
        }
        if (realName.length() > 50) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, 400, "姓名不能超过 50 个字符");
        }
        if (password.length() < 6 || password.length() > 72) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, 400, "密码长度需为 6-72 位");
        }
    }

    private void ensureUsernameAvailable(String username) {
        long exists = count("""
            SELECT COUNT(*)
            FROM sys_user
            WHERE username = :username
            """, new MapSqlParameterSource("username", username));
        if (exists > 0) {
            throw new ApiException(HttpStatus.CONFLICT, 409, "用户名已存在");
        }
    }

    private void validateCollege(Long collegeId) {
        if (collegeId == null) {
            return;
        }
        long exists = count("SELECT COUNT(*) FROM base_college WHERE id = :id", new MapSqlParameterSource("id", collegeId));
        if (exists == 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, 400, "所属学院不存在");
        }
    }

    private int statusFromPayload(Map<String, Object> payload, int defaultStatus) {
        if (payload.containsKey("enabled")) {
            return booleanValue(payload.get("enabled")) ? 1 : 0;
        }
        if (!payload.containsKey("status")) {
            return defaultStatus;
        }
        return intValue(payload.get("status")) == 0 ? 0 : 1;
    }

    private String deletedUsername(Long id, String username) {
        String prefix = "deleted_" + id + "_";
        int maxSourceLength = Math.max(1, 50 - prefix.length());
        String source = defaultString(username, "user");
        if (source.length() > maxSourceLength) {
            source = source.substring(0, maxSourceLength);
        }
        return prefix + source;
    }

    private long count(String sql, MapSqlParameterSource params) {
        Long value = jdbcTemplate.query(sql, params, rs -> rs.next() ? rs.getLong(1) : 0L);
        return value == null ? 0L : value;
    }

    private Long nullableLong(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (!StringUtils.hasText(text) || "null".equalsIgnoreCase(text)) {
            return null;
        }
        return Long.parseLong(text);
    }

    private int intValue(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private boolean booleanValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() == 1;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private String requireText(Object value, String message) {
        String text = defaultString(value, "");
        if (!StringUtils.hasText(text)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, 400, message);
        }
        return text;
    }

    private String nullableText(Object value) {
        String text = defaultString(value, "");
        return StringUtils.hasText(text) ? text : null;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String defaultString(Object value, String defaultValue) {
        String text = value == null ? "" : String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : defaultValue;
    }

    private String formatTime(Timestamp value) {
        return value == null ? "" : value.toLocalDateTime().format(TIME_FORMATTER);
    }

    private Map<String, Object> map(Object... values) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }
}
