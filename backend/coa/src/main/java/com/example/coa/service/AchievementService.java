package com.example.coa.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AchievementService {

    private final InMemoryCoaService delegate;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AchievementService(InMemoryCoaService delegate, NamedParameterJdbcTemplate jdbcTemplate) {
        this.delegate = delegate;
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> getAchievementResults(Long courseId, String semester, Long classId) {
        return delegate.getAchievementCalculation(courseId, semester, classId);
    }

    public Map<String, Object> runAchievementCalculation(Map<String, Object> payload) {
        return delegate.runAchievementCalculation(payload);
    }

    public Map<String, Object> getAchievementContentMapping(Long courseId, String semester) {
        return delegate.getAchievementContentMapping(courseId, semester);
    }

    public Map<String, Object> saveAchievementContentMapping(Map<String, Object> payload) {
        return delegate.saveAchievementContentMapping(payload);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getGradeDistribution(Long outlineId, Long semesterId) {
        List<Map<String, Object>> scores = getStudentTotalScores(outlineId, semesterId, null);
        int total = scores.size();
        Map<String, Object> distribution = new LinkedHashMap<>();
        distribution.put("优", bucket(scores, total, 90, 100.000001));
        distribution.put("良", bucket(scores, total, 80, 90));
        distribution.put("中", bucket(scores, total, 70, 80));
        distribution.put("及格", bucket(scores, total, 60, 70));
        distribution.put("不及格", bucket(scores, total, 0, 60));
        return distribution;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getComponentStats(Long outlineId, Long semesterId) {
        return jdbcTemplate.query("""
            SELECT
                COALESCE(ai.item_type, 'other') AS item_type,
                AVG(sg.score / NULLIF(sg.max_score, 0) * ai.weight) AS avg_score,
                MAX(sg.score / NULLIF(sg.max_score, 0) * ai.weight) AS max_score,
                MIN(sg.score / NULLIF(sg.max_score, 0) * ai.weight) AS min_score,
                AVG(CASE WHEN sg.score / NULLIF(sg.max_score, 0) >= 0.6 THEN 1 ELSE 0 END) AS pass_rate
            FROM student_grade sg
            JOIN grade_import_batch gb ON gb.id = sg.import_batch_id
            JOIN assess_item ai ON ai.id = sg.assess_item_id
            WHERE ai.outline_id = :outlineId
              AND sg.semester_id = :semesterId
              AND sg.valid_flag = 1
              AND sg.assess_content_id IS NULL
              AND gb.status = 'CONFIRMED'
            GROUP BY COALESCE(ai.item_type, 'other')
            ORDER BY MIN(ai.sort_order), item_type
            """, new MapSqlParameterSource()
            .addValue("outlineId", outlineId)
            .addValue("semesterId", semesterId), (rs, rowNum) -> Map.of(
            "type", rs.getString("item_type"),
            "typeName", assessItemTypeName(rs.getString("item_type")),
            "avgScore", round2(rs.getDouble("avg_score")),
            "maxScore", round2(rs.getDouble("max_score")),
            "minScore", round2(rs.getDouble("min_score")),
            "passRate", round4(rs.getDouble("pass_rate"))
        ));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getStudentTotalScores(Long outlineId, Long semesterId, Long calcRuleId) {
        return jdbcTemplate.query("""
            SELECT
                sg.student_no,
                MAX(sg.student_name) AS student_name,
                SUM(CASE WHEN ai.item_type IN ('normal', 'mid', 'report') THEN sg.score / NULLIF(sg.max_score, 0) * ai.weight ELSE 0 END) AS normal_score,
                SUM(CASE WHEN ai.item_type = 'practice' THEN sg.score / NULLIF(sg.max_score, 0) * ai.weight ELSE 0 END) AS practice_score,
                SUM(CASE WHEN ai.item_type = 'final' THEN sg.score / NULLIF(sg.max_score, 0) * ai.weight ELSE 0 END) AS final_score,
                SUM(sg.score / NULLIF(sg.max_score, 0) * ai.weight) AS total_score
            FROM student_grade sg
            JOIN grade_import_batch gb ON gb.id = sg.import_batch_id
            JOIN assess_item ai ON ai.id = sg.assess_item_id
            WHERE ai.outline_id = :outlineId
              AND sg.semester_id = :semesterId
              AND sg.valid_flag = 1
              AND sg.assess_content_id IS NULL
              AND gb.status = 'CONFIRMED'
            GROUP BY sg.student_no
            ORDER BY sg.student_no ASC
            """, new MapSqlParameterSource()
            .addValue("outlineId", outlineId)
            .addValue("semesterId", semesterId), (rs, rowNum) -> {
            double totalScore = round2(rs.getDouble("total_score"));
            return Map.of(
                "studentNo", rs.getString("student_no"),
                "studentName", rs.getString("student_name") == null ? "" : rs.getString("student_name"),
                "normalScore", round2(rs.getDouble("normal_score")),
                "practiceScore", round2(rs.getDouble("practice_score")),
                "finalScore", round2(rs.getDouble("final_score")),
                "totalScore", totalScore,
                "gradeLevel", gradeLevel(totalScore)
            );
        });
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getPerStudentObjectiveScores(Long calcRuleId) {
        return jdbcTemplate.query("""
            SELECT
                ar.objective_id,
                t.obj_code,
                ar.achieve_value
            FROM achieve_result ar
            JOIN teach_objective t ON t.id = ar.objective_id
            WHERE ar.calc_rule_id = :calcRuleId
              AND ar.status = 1
              AND ar.objective_id IS NOT NULL
            ORDER BY t.sort_order ASC, t.id ASC
            """, new MapSqlParameterSource("calcRuleId", calcRuleId), (rs, rowNum) -> Map.of(
            "objectiveId", rs.getLong("objective_id"),
            "objectiveCode", rs.getString("obj_code"),
            "classAverageAchievement", round4(rs.getDouble("achieve_value"))
        ));
    }

    private Map<String, Object> bucket(List<Map<String, Object>> scores, int total, double min, double max) {
        long count = scores.stream()
            .mapToDouble(item -> number(item.get("totalScore")))
            .filter(score -> score >= min && score < max)
            .count();
        return Map.of("count", count, "pct", total == 0 ? 0D : round4((double) count / total));
    }

    private String assessItemTypeName(String type) {
        return switch (type == null ? "" : type) {
            case "normal" -> "平时";
            case "mid" -> "期中";
            case "final" -> "期末";
            case "practice" -> "实践";
            case "report" -> "报告";
            default -> "其他";
        };
    }

    private String gradeLevel(double score) {
        if (score >= 90D) {
            return "优";
        }
        if (score >= 80D) {
            return "良";
        }
        if (score >= 70D) {
            return "中";
        }
        if (score >= 60D) {
            return "及格";
        }
        return "不及格";
    }

    private double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(value));
    }

    private double round2(double value) {
        return Math.round(value * 100D) / 100D;
    }

    private double round4(double value) {
        return Math.round(value * 10000D) / 10000D;
    }
}
