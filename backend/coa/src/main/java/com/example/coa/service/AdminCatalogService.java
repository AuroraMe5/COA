package com.example.coa.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.example.coa.common.ApiException;

@Service
@Transactional
public class AdminCatalogService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AdminCatalogService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> listColleges(String keyword, String status) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder("""
            SELECT
                c.id,
                c.college_name,
                c.college_code,
                c.status,
                c.created_at,
                c.updated_at,
                (SELECT COUNT(*) FROM base_major m WHERE m.college_id = c.id AND m.status = 1) AS major_count,
                (SELECT COUNT(*) FROM base_course bc WHERE bc.college_id = c.id AND bc.status = 1) AS course_count,
                (SELECT COUNT(*) FROM sys_user u WHERE u.college_id = c.id AND u.status = 1) AS teacher_count
            FROM base_college c
            WHERE c.status IN (0, 1)
            """);
        appendKeyword(sql, params, keyword, "c.college_name", "c.college_code");
        appendStatus(sql, params, status, "c.status");
        sql.append(" ORDER BY c.status DESC, c.id ASC");

        List<Map<String, Object>> items = jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> collegeRow(rs));
        return map("items", items, "summary", catalogSummary("base_college"));
    }

    public Map<String, Object> createCollege(Map<String, Object> payload) {
        String code = requireText(payload.get("code"), "学院编码不能为空。");
        String name = requireText(payload.get("name"), "学院名称不能为空。");
        validateMaxLength(code, 20, "学院编码不能超过 20 个字符。");
        validateMaxLength(name, 100, "学院名称不能超过 100 个字符。");
        ensureCodeAvailable("base_college", "college_code", code, null, "学院编码已存在。");

        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
            INSERT INTO base_college (college_name, college_code, status)
            VALUES (:name, :code, :status)
            """, new MapSqlParameterSource()
            .addValue("name", name)
            .addValue("code", code)
            .addValue("status", statusFromPayload(payload, 1)), keyHolder);
        return map("item", requireCollege(keyHolder.getKey().longValue()));
    }

    public Map<String, Object> updateCollege(Long id, Map<String, Object> payload) {
        requireCollege(id);
        String code = requireText(payload.get("code"), "学院编码不能为空。");
        String name = requireText(payload.get("name"), "学院名称不能为空。");
        validateMaxLength(code, 20, "学院编码不能超过 20 个字符。");
        validateMaxLength(name, 100, "学院名称不能超过 100 个字符。");
        ensureCodeAvailable("base_college", "college_code", code, id, "学院编码已存在。");

        jdbcTemplate.update("""
            UPDATE base_college
            SET college_name = :name,
                college_code = :code,
                status = :status,
                updated_at = NOW()
            WHERE id = :id
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("name", name)
            .addValue("code", code)
            .addValue("status", statusFromPayload(payload, 1)));
        return map("item", requireCollege(id));
    }

    public Map<String, Object> deleteCollege(Long id) {
        requireCollege(id);
        softDelete("base_college", id);
        return map("success", true);
    }

    public Map<String, Object> listMajors(String keyword, String status, Long collegeId) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder("""
            SELECT
                m.id,
                m.major_name,
                m.major_code,
                m.college_id,
                c.college_name,
                m.status,
                m.created_at,
                m.updated_at,
                (SELECT COUNT(*) FROM base_class bc WHERE bc.major_id = m.id AND bc.status = 1) AS class_count,
                (SELECT COUNT(*) FROM base_course course WHERE course.major_id = m.id AND course.status = 1) AS course_count
            FROM base_major m
            LEFT JOIN base_college c ON c.id = m.college_id
            WHERE m.status IN (0, 1)
            """);
        appendKeyword(sql, params, keyword, "m.major_name", "m.major_code", "c.college_name");
        appendStatus(sql, params, status, "m.status");
        if (collegeId != null && collegeId > 0) {
            sql.append(" AND m.college_id = :collegeId ");
            params.addValue("collegeId", collegeId);
        }
        sql.append(" ORDER BY m.status DESC, m.id ASC");

        List<Map<String, Object>> items = jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> majorRow(rs));
        return map(
            "items", items,
            "summary", catalogSummary("base_major"),
            "colleges", activeColleges()
        );
    }

    public Map<String, Object> createMajor(Map<String, Object> payload) {
        String code = requireText(payload.get("code"), "专业编码不能为空。");
        String name = requireText(payload.get("name"), "专业名称不能为空。");
        Long collegeId = requireLong(payload.get("collegeId"), "请选择所属学院。");
        validateMaxLength(code, 20, "专业编码不能超过 20 个字符。");
        validateMaxLength(name, 100, "专业名称不能超过 100 个字符。");
        requireActiveCollege(collegeId);
        ensureCodeAvailable("base_major", "major_code", code, null, "专业编码已存在。");

        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
            INSERT INTO base_major (college_id, major_name, major_code, status)
            VALUES (:collegeId, :name, :code, :status)
            """, new MapSqlParameterSource()
            .addValue("collegeId", collegeId)
            .addValue("name", name)
            .addValue("code", code)
            .addValue("status", statusFromPayload(payload, 1)), keyHolder);
        return map("item", requireMajor(keyHolder.getKey().longValue()));
    }

    public Map<String, Object> updateMajor(Long id, Map<String, Object> payload) {
        requireMajor(id);
        String code = requireText(payload.get("code"), "专业编码不能为空。");
        String name = requireText(payload.get("name"), "专业名称不能为空。");
        Long collegeId = requireLong(payload.get("collegeId"), "请选择所属学院。");
        validateMaxLength(code, 20, "专业编码不能超过 20 个字符。");
        validateMaxLength(name, 100, "专业名称不能超过 100 个字符。");
        requireActiveCollege(collegeId);
        ensureCodeAvailable("base_major", "major_code", code, id, "专业编码已存在。");

        jdbcTemplate.update("""
            UPDATE base_major
            SET college_id = :collegeId,
                major_name = :name,
                major_code = :code,
                status = :status,
                updated_at = NOW()
            WHERE id = :id
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("collegeId", collegeId)
            .addValue("name", name)
            .addValue("code", code)
            .addValue("status", statusFromPayload(payload, 1)));
        return map("item", requireMajor(id));
    }

    public Map<String, Object> deleteMajor(Long id) {
        requireMajor(id);
        softDelete("base_major", id);
        return map("success", true);
    }

    public Map<String, Object> listSemesters(String keyword, String status) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder("""
            SELECT
                s.id,
                s.semester_code,
                s.semester_name,
                s.school_year,
                s.term_no,
                s.start_date,
                s.end_date,
                s.status,
                s.created_at,
                s.updated_at,
                (SELECT COUNT(*) FROM course_teacher ct WHERE ct.semester_id = s.id AND ct.status = 1) AS course_teacher_count,
                (SELECT COUNT(*) FROM class_course cc WHERE cc.semester_id = s.id AND cc.status = 1) AS class_course_count,
                (SELECT COUNT(*) FROM outline_main o WHERE o.semester_id = s.id) AS outline_count
            FROM base_semester s
            WHERE s.status IN (0, 1)
            """);
        appendKeyword(sql, params, keyword, "s.semester_code", "s.semester_name", "s.school_year");
        appendStatus(sql, params, status, "s.status");
        sql.append(" ORDER BY s.school_year DESC, s.term_no DESC, s.id DESC");

        List<Map<String, Object>> items = jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> semesterRow(rs));
        return map("items", items, "summary", catalogSummary("base_semester"));
    }

    public Map<String, Object> createSemester(Map<String, Object> payload) {
        String code = requireText(payload.get("code"), "学期编码不能为空。");
        String name = requireText(payload.get("name"), "学期名称不能为空。");
        String schoolYear = requireText(payload.get("schoolYear"), "学年不能为空。");
        int termNo = termNo(payload.get("termNo"));
        validateMaxLength(code, 20, "学期编码不能超过 20 个字符。");
        validateMaxLength(name, 50, "学期名称不能超过 50 个字符。");
        validateMaxLength(schoolYear, 20, "学年不能超过 20 个字符。");
        ensureCodeAvailable("base_semester", "semester_code", code, null, "学期编码已存在。");

        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
            INSERT INTO base_semester (
                semester_code, semester_name, school_year, term_no, start_date, end_date, status
            ) VALUES (
                :code, :name, :schoolYear, :termNo, :startDate, :endDate, :status
            )
            """, new MapSqlParameterSource()
            .addValue("code", code)
            .addValue("name", name)
            .addValue("schoolYear", schoolYear)
            .addValue("termNo", termNo)
            .addValue("startDate", nullableText(payload.get("startDate")))
            .addValue("endDate", nullableText(payload.get("endDate")))
            .addValue("status", statusFromPayload(payload, 1)), keyHolder);
        return map("item", requireSemester(keyHolder.getKey().longValue()));
    }

    public Map<String, Object> updateSemester(Long id, Map<String, Object> payload) {
        requireSemester(id);
        String code = requireText(payload.get("code"), "学期编码不能为空。");
        String name = requireText(payload.get("name"), "学期名称不能为空。");
        String schoolYear = requireText(payload.get("schoolYear"), "学年不能为空。");
        int termNo = termNo(payload.get("termNo"));
        validateMaxLength(code, 20, "学期编码不能超过 20 个字符。");
        validateMaxLength(name, 50, "学期名称不能超过 50 个字符。");
        validateMaxLength(schoolYear, 20, "学年不能超过 20 个字符。");
        ensureCodeAvailable("base_semester", "semester_code", code, id, "学期编码已存在。");

        jdbcTemplate.update("""
            UPDATE base_semester
            SET semester_code = :code,
                semester_name = :name,
                school_year = :schoolYear,
                term_no = :termNo,
                start_date = :startDate,
                end_date = :endDate,
                status = :status,
                updated_at = NOW()
            WHERE id = :id
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("code", code)
            .addValue("name", name)
            .addValue("schoolYear", schoolYear)
            .addValue("termNo", termNo)
            .addValue("startDate", nullableText(payload.get("startDate")))
            .addValue("endDate", nullableText(payload.get("endDate")))
            .addValue("status", statusFromPayload(payload, 1)));
        return map("item", requireSemester(id));
    }

    public Map<String, Object> deleteSemester(Long id) {
        requireSemester(id);
        softDelete("base_semester", id);
        return map("success", true);
    }

    private Map<String, Object> requireCollege(Long id) {
        Map<String, Object> item = jdbcTemplate.query("""
            SELECT id, college_name, college_code, status, created_at, updated_at,
                   0 AS major_count, 0 AS course_count, 0 AS teacher_count
            FROM base_college
            WHERE id = :id
              AND status IN (0, 1)
            LIMIT 1
            """, new MapSqlParameterSource("id", id), rs -> rs.next() ? collegeRow(rs) : null);
        if (item == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, 404, "学院不存在。");
        }
        return item;
    }

    private Map<String, Object> requireMajor(Long id) {
        Map<String, Object> item = jdbcTemplate.query("""
            SELECT m.id, m.major_name, m.major_code, m.college_id, c.college_name,
                   m.status, m.created_at, m.updated_at,
                   0 AS class_count, 0 AS course_count
            FROM base_major m
            LEFT JOIN base_college c ON c.id = m.college_id
            WHERE m.id = :id
              AND m.status IN (0, 1)
            LIMIT 1
            """, new MapSqlParameterSource("id", id), rs -> rs.next() ? majorRow(rs) : null);
        if (item == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, 404, "专业不存在。");
        }
        return item;
    }

    private Map<String, Object> requireSemester(Long id) {
        Map<String, Object> item = jdbcTemplate.query("""
            SELECT s.id, s.semester_code, s.semester_name, s.school_year, s.term_no,
                   s.start_date, s.end_date, s.status, s.created_at, s.updated_at,
                   0 AS course_teacher_count, 0 AS class_course_count, 0 AS outline_count
            FROM base_semester s
            WHERE s.id = :id
              AND s.status IN (0, 1)
            LIMIT 1
            """, new MapSqlParameterSource("id", id), rs -> rs.next() ? semesterRow(rs) : null);
        if (item == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, 404, "学期不存在。");
        }
        return item;
    }

    private void requireActiveCollege(Long id) {
        long exists = count("""
            SELECT COUNT(*)
            FROM base_college
            WHERE id = :id
              AND status = 1
            """, new MapSqlParameterSource("id", id));
        if (exists == 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, 400, "所属学院不存在或已停用。");
        }
    }

    private void ensureCodeAvailable(String table, String column, String code, Long currentId, String message) {
        long exists = count("""
            SELECT COUNT(*)
            FROM %s
            WHERE %s = :code
              AND (:currentId IS NULL OR id <> :currentId)
            """.formatted(table, column), new MapSqlParameterSource()
            .addValue("code", code)
            .addValue("currentId", currentId));
        if (exists > 0) {
            throw new ApiException(HttpStatus.CONFLICT, 409, message);
        }
    }

    private void softDelete(String table, Long id) {
        jdbcTemplate.update("""
            UPDATE %s
            SET status = 0,
                updated_at = NOW()
            WHERE id = :id
            """.formatted(table), new MapSqlParameterSource("id", id));
    }

    private List<Map<String, Object>> activeColleges() {
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

    private Map<String, Object> catalogSummary(String table) {
        return map(
            "total", count("SELECT COUNT(*) FROM " + table + " WHERE status IN (0, 1)", new MapSqlParameterSource()),
            "enabled", count("SELECT COUNT(*) FROM " + table + " WHERE status = 1", new MapSqlParameterSource()),
            "disabled", count("SELECT COUNT(*) FROM " + table + " WHERE status = 0", new MapSqlParameterSource())
        );
    }

    private void appendKeyword(StringBuilder sql, MapSqlParameterSource params, String keyword, String... columns) {
        String text = defaultString(keyword, "");
        if (!StringUtils.hasText(text) || columns.length == 0) {
            return;
        }
        sql.append(" AND (");
        for (int index = 0; index < columns.length; index++) {
            if (index > 0) {
                sql.append(" OR ");
            }
            sql.append(columns[index]).append(" LIKE :keyword");
        }
        sql.append(") ");
        params.addValue("keyword", "%" + text + "%");
    }

    private void appendStatus(StringBuilder sql, MapSqlParameterSource params, String status, String column) {
        String text = defaultString(status, "all");
        if ("enabled".equalsIgnoreCase(text)) {
            sql.append(" AND ").append(column).append(" = 1 ");
        } else if ("disabled".equalsIgnoreCase(text)) {
            sql.append(" AND ").append(column).append(" = 0 ");
        }
    }

    private Map<String, Object> collegeRow(ResultSet rs) throws SQLException {
        return map(
            "id", rs.getLong("id"),
            "name", rs.getString("college_name"),
            "code", rs.getString("college_code"),
            "status", rs.getInt("status"),
            "majorCount", rs.getLong("major_count"),
            "courseCount", rs.getLong("course_count"),
            "teacherCount", rs.getLong("teacher_count"),
            "createdAt", formatTime(rs.getTimestamp("created_at")),
            "updatedAt", formatTime(rs.getTimestamp("updated_at"))
        );
    }

    private Map<String, Object> majorRow(ResultSet rs) throws SQLException {
        return map(
            "id", rs.getLong("id"),
            "name", rs.getString("major_name"),
            "code", rs.getString("major_code"),
            "collegeId", nullableLong(rs.getObject("college_id")),
            "collegeName", defaultString(rs.getString("college_name"), ""),
            "status", rs.getInt("status"),
            "classCount", rs.getLong("class_count"),
            "courseCount", rs.getLong("course_count"),
            "createdAt", formatTime(rs.getTimestamp("created_at")),
            "updatedAt", formatTime(rs.getTimestamp("updated_at"))
        );
    }

    private Map<String, Object> semesterRow(ResultSet rs) throws SQLException {
        return map(
            "id", rs.getLong("id"),
            "code", rs.getString("semester_code"),
            "name", rs.getString("semester_name"),
            "schoolYear", rs.getString("school_year"),
            "termNo", rs.getInt("term_no"),
            "startDate", dateText(rs.getObject("start_date")),
            "endDate", dateText(rs.getObject("end_date")),
            "status", rs.getInt("status"),
            "courseTeacherCount", rs.getLong("course_teacher_count"),
            "classCourseCount", rs.getLong("class_course_count"),
            "outlineCount", rs.getLong("outline_count"),
            "createdAt", formatTime(rs.getTimestamp("created_at")),
            "updatedAt", formatTime(rs.getTimestamp("updated_at"))
        );
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

    private int termNo(Object value) {
        int termNo = intValue(value);
        if (termNo != 1 && termNo != 2) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, 400, "学期序号只能为 1 或 2。");
        }
        return termNo;
    }

    private void validateMaxLength(String value, int maxLength, String message) {
        if (value != null && value.length() > maxLength) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, 400, message);
        }
    }

    private String requireText(Object value, String message) {
        String text = defaultString(value, "");
        if (!StringUtils.hasText(text)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, 400, message);
        }
        return text;
    }

    private Long requireLong(Object value, String message) {
        Long number = nullableLong(value);
        if (number == null || number <= 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, 400, message);
        }
        return number;
    }

    private String nullableText(Object value) {
        String text = defaultString(value, "");
        return StringUtils.hasText(text) ? text : null;
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

    private long count(String sql, MapSqlParameterSource params) {
        Long value = jdbcTemplate.query(sql, params, rs -> rs.next() ? rs.getLong(1) : 0L);
        return value == null ? 0L : value;
    }

    private String defaultString(Object value, String defaultValue) {
        String text = value == null ? "" : String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : defaultValue;
    }

    private String formatTime(Timestamp value) {
        return value == null ? "" : value.toLocalDateTime().format(TIME_FORMATTER);
    }

    private String dateText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Map<String, Object> map(Object... values) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }
}
