package com.example.coa.service;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class OutlineService {

    private final InMemoryCoaService delegate;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public OutlineService(InMemoryCoaService delegate, NamedParameterJdbcTemplate jdbcTemplate) {
        this.delegate = delegate;
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> getOutlines(Long courseId, String semester) {
        return delegate.getOutlines(courseId, semester);
    }

    public Map<String, Object> saveOutline(Map<String, Object> payload) {
        return delegate.saveOutline(payload);
    }

    public Map<String, Object> publishOutline(Long id) {
        return delegate.publishOutline(id);
    }

    public Map<String, Object> getObjectives(Long courseId, String semester) {
        return delegate.getObjectives(courseId, semester);
    }

    public Map<String, Object> getObjectiveDetail(Long id) {
        return delegate.getObjectiveDetail(id);
    }

    public Map<String, Object> saveObjective(Map<String, Object> payload) {
        return delegate.saveObjective(payload);
    }

    public Map<String, Object> saveObjectiveBatch(Map<String, Object> payload) {
        return delegate.saveObjectiveBatch(payload);
    }

    public Map<String, Object> saveObjectiveWeights(Map<String, Object> payload) {
        return delegate.saveObjectiveWeights(payload);
    }

    public Map<String, Object> getObjectiveMapping(Long courseId, String semester) {
        return delegate.getObjectiveMapping(courseId, semester);
    }

    public Map<String, Object> saveObjectiveMapping(Map<String, Object> payload) {
        return delegate.saveObjectiveMapping(payload);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getOutlineByCourseSemester(Long courseId, Long semesterId) {
        return jdbcTemplate.query("""
            SELECT id, course_id, teacher_id, semester_id, version, status, overview, target_source
            FROM outline_main
            WHERE course_id = :courseId
              AND semester_id = :semesterId
            ORDER BY updated_at DESC, id DESC
            LIMIT 1
            """, new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId), rs -> rs.next() ? Map.of(
            "id", rs.getLong("id"),
            "courseId", rs.getLong("course_id"),
            "teacherId", rs.getLong("teacher_id"),
            "semesterId", rs.getLong("semester_id"),
            "version", rs.getString("version"),
            "status", rs.getInt("status"),
            "overview", rs.getString("overview") == null ? "" : rs.getString("overview"),
            "targetSource", rs.getString("target_source") == null ? "" : rs.getString("target_source")
        ) : Map.of());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getObjectivesByOutline(Long outlineId) {
        return jdbcTemplate.query("""
            SELECT id, obj_code, obj_content, obj_type, weight, sort_order
            FROM teach_objective
            WHERE outline_id = :outlineId
            ORDER BY sort_order ASC, id ASC
            """, new MapSqlParameterSource("outlineId", outlineId), (rs, rowNum) -> Map.of(
            "id", rs.getLong("id"),
            "objCode", rs.getString("obj_code"),
            "objContent", rs.getString("obj_content"),
            "objType", rs.getInt("obj_type"),
            "weight", rs.getBigDecimal("weight"),
            "sortOrder", rs.getInt("sort_order")
        ));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAssessItemsByOutline(Long outlineId) {
        return jdbcTemplate.query("""
            SELECT id, item_name, item_type, weight, max_score, sort_order
            FROM assess_item
            WHERE outline_id = :outlineId
            ORDER BY sort_order ASC, id ASC
            """, new MapSqlParameterSource("outlineId", outlineId), (rs, rowNum) -> Map.of(
            "id", rs.getLong("id"),
            "itemName", rs.getString("item_name"),
            "itemType", rs.getString("item_type") == null ? "" : rs.getString("item_type"),
            "weight", rs.getBigDecimal("weight"),
            "maxScore", rs.getBigDecimal("max_score"),
            "sortOrder", rs.getInt("sort_order")
        ));
    }
}
