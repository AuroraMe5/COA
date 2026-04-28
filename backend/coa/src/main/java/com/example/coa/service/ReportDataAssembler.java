package com.example.coa.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.example.coa.common.ApiException;

import org.springframework.http.HttpStatus;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
public class ReportDataAssembler {

    private static final Logger log = LoggerFactory.getLogger(ReportDataAssembler.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JdbcTemplate plainJdbcTemplate;
    private final ObjectMapper objectMapper;

    public ReportDataAssembler(
        NamedParameterJdbcTemplate jdbcTemplate,
        JdbcTemplate plainJdbcTemplate,
        ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.plainJdbcTemplate = plainJdbcTemplate;
        this.objectMapper = objectMapper;
        ensureReportSchema();
    }

    public ReportContext assemble(Long outlineId, Long calcRuleId) {
        if (outlineId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, 400, "outlineId is required");
        }

        Map<String, Object> outline = requireMap("""
            SELECT
                o.id AS outline_id,
                o.course_id,
                o.teacher_id,
                o.semester_id,
                o.version,
                o.overview,
                o.target_source,
                o.updated_at,
                c.course_code,
                c.course_name,
                c.credits,
                c.hours,
                c.course_type,
                bc.college_name,
                bm.major_name,
                s.semester_code,
                s.semester_name,
                s.school_year,
                s.term_no,
                u.real_name AS teacher_name
            FROM outline_main o
            JOIN base_course c ON c.id = o.course_id
            JOIN base_semester s ON s.id = o.semester_id
            LEFT JOIN base_college bc ON bc.id = c.college_id
            LEFT JOIN base_major bm ON bm.id = c.major_id
            LEFT JOIN sys_user u ON u.id = o.teacher_id
            WHERE o.id = :outlineId
            """, params("outlineId", outlineId));

        long courseId = longValue(outline.get("course_id"));
        long semesterId = longValue(outline.get("semester_id"));
        Long effectiveCalcRuleId = calcRuleId != null ? calcRuleId : latestCalcRuleId(courseId, semesterId);
        CalcRuleInfo ruleInfo = loadCalcRule(effectiveCalcRuleId);

        ReportContext ctx = new ReportContext();
        ctx.outlineId = outlineId;
        ctx.calcRuleId = effectiveCalcRuleId;
        ctx.thresholdValue = ruleInfo.thresholdValue;
        ctx.generatedAt = LocalDateTime.now().format(TIME_FORMATTER);
        ctx.courseInfo = new CourseInfo(
            courseId,
            text(outline.get("course_code")),
            text(outline.get("course_name")),
            nullableInteger(outline.get("hours")),
            nullableDouble(outline.get("credits")),
            text(outline.get("course_type")),
            text(outline.get("major_name")),
            text(outline.get("college_name")),
            text(outline.get("teacher_name"))
        );
        ctx.outlineInfo = new OutlineInfo(
            outlineId,
            semesterId,
            text(outline.get("semester_code")),
            text(outline.get("semester_name")),
            text(outline.get("school_year")),
            intValue(outline.get("term_no")),
            text(outline.get("version")),
            text(outline.get("overview")),
            text(outline.get("target_source")),
            text(outline.get("major_name")),
            text(outline.get("teacher_name")),
            LocalDate.now().format(DATE_FORMATTER)
        );

        ctx.objectives = loadObjectives(outlineId);
        ctx.assessItems = loadAssessItems(outlineId);
        ctx.objAssessMaps = loadObjAssessMaps(outlineId);
        ctx.objAssessContentMaps = loadObjAssessContentMaps(outlineId);
        Map<Long, AchievementResultRow> resultRows = loadAchievementResults(courseId, semesterId, effectiveCalcRuleId);
        ctx.objectiveAchievements = buildObjectiveAchievements(
            ctx.objectives,
            resultRows,
            ruleInfo.thresholdValue,
            objectiveContentTargetScores(ctx.objAssessContentMaps)
        );
        ctx.overallAchievement = loadOverallAchievement(courseId, semesterId, effectiveCalcRuleId);

        StudentReportData studentData = buildStudentReportData(ctx, semesterId, ruleInfo);
        ctx.gradeDistribution = studentData.gradeDistribution;
        ctx.componentStats = studentData.componentStats;
        ctx.objectiveScoreDistributions = studentData.objectiveScoreDistributions;
        ctx.objectiveBandAchievements = studentData.objectiveBandAchievements;
        ctx.weakStudents = studentData.weakStudents;
        ctx.studentDetails = studentData.studentDetails;
        ctx.studentScoreSummaries = studentData.studentScoreSummaries;
        ctx.studentCount = studentData.studentScoreSummaries.size();
        ctx.gradReqAchievements = buildGradReqAchievements(ctx.objectives, ctx.objectiveAchievements);
        ctx.existingSuggestions = loadSuggestions(courseId, semesterId);
        return ctx;
    }

    private List<ObjectiveInfo> loadObjectives(Long outlineId) {
        return jdbcTemplate.query("""
            SELECT
                id,
                obj_code,
                obj_content,
                obj_type,
                weight,
                sort_order,
                grad_req_id,
                grad_req_desc,
                relation_level
            FROM teach_objective
            WHERE outline_id = :outlineId
            ORDER BY sort_order ASC, id ASC
            """, params("outlineId", outlineId), (rs, rowNum) -> new ObjectiveInfo(
            rs.getLong("id"),
            rs.getString("obj_code"),
            rs.getString("obj_content"),
            rs.getInt("obj_type"),
            rs.getDouble("weight"),
            defaultString(rs.getString("grad_req_id"), ""),
            defaultString(rs.getString("grad_req_desc"), ""),
            defaultString(rs.getString("relation_level"), "H"),
            rs.getInt("sort_order")
        ));
    }

    private List<AssessItemInfo> loadAssessItems(Long outlineId) {
        return jdbcTemplate.query("""
            SELECT id, item_name, item_type, weight, max_score, sort_order
            FROM assess_item
            WHERE outline_id = :outlineId
            ORDER BY sort_order ASC, id ASC
            """, params("outlineId", outlineId), (rs, rowNum) -> new AssessItemInfo(
            rs.getLong("id"),
            rs.getString("item_name"),
            defaultString(rs.getString("item_type"), "other"),
            assessItemTypeName(rs.getString("item_type")),
            rs.getDouble("weight"),
            rs.getDouble("max_score"),
            rs.getInt("sort_order")
        ));
    }

    private List<ObjAssessMapInfo> loadObjAssessMaps(Long outlineId) {
        return jdbcTemplate.query("""
            SELECT
                m.objective_id,
                m.assess_item_id,
                m.contribution_weight
            FROM obj_assess_map m
            JOIN teach_objective t ON t.id = m.objective_id
            WHERE t.outline_id = :outlineId
            ORDER BY t.sort_order ASC, m.id ASC
            """, params("outlineId", outlineId), (rs, rowNum) -> new ObjAssessMapInfo(
            rs.getLong("objective_id"),
            rs.getLong("assess_item_id"),
            rs.getDouble("contribution_weight")
        ));
    }

    private List<ObjAssessContentMapInfo> loadObjAssessContentMaps(Long outlineId) {
        return jdbcTemplate.query("""
            SELECT
                m.objective_id,
                m.assess_item_id,
                m.assess_content_id,
                m.contribution_score,
                ac.content_type
            FROM obj_assess_content_map m
            JOIN teach_objective t ON t.id = m.objective_id
            JOIN assess_content ac ON ac.id = m.assess_content_id
            WHERE t.outline_id = :outlineId
              AND m.status = 1
              AND m.contribution_score > 0
              AND ac.status = 1
            ORDER BY t.sort_order ASC, t.id ASC, m.assess_item_id ASC, ac.sort_order ASC, ac.id ASC
            """, params("outlineId", outlineId), (rs, rowNum) -> new ObjAssessContentMapInfo(
            rs.getLong("objective_id"),
            rs.getLong("assess_item_id"),
            rs.getLong("assess_content_id"),
            rs.getDouble("contribution_score"),
            normalizeAssessContentType(rs.getString("content_type"))
        ));
    }

    private Map<Long, AchievementResultRow> loadAchievementResults(long courseId, long semesterId, Long calcRuleId) {
        List<AchievementResultRow> rows = jdbcTemplate.query("""
            SELECT objective_id, normal_score, mid_score, final_score, achieve_value, is_achieved
            FROM achieve_result
            WHERE course_id = :courseId
              AND semester_id = :semesterId
              AND status = 1
              AND objective_id IS NOT NULL
              AND (:calcRuleId IS NULL OR calc_rule_id = :calcRuleId)
            ORDER BY calc_time DESC, id DESC
            """, new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId)
            .addValue("calcRuleId", calcRuleId), (rs, rowNum) -> new AchievementResultRow(
            rs.getLong("objective_id"),
            rs.getDouble("normal_score"),
            rs.getDouble("mid_score"),
            rs.getDouble("final_score"),
            rs.getDouble("achieve_value"),
            rs.getInt("is_achieved") == 1
        ));
        Map<Long, AchievementResultRow> result = new LinkedHashMap<>();
        for (AchievementResultRow row : rows) {
            result.putIfAbsent(row.objectiveId, row);
        }
        return result;
    }

    private List<ObjectiveAchievement> buildObjectiveAchievements(
        List<ObjectiveInfo> objectives,
        Map<Long, AchievementResultRow> resultRows,
        double thresholdValue,
        Map<Long, Double> objectiveTargetScores
    ) {
        List<ObjectiveAchievement> result = new ArrayList<>();
        for (ObjectiveInfo objective : objectives) {
            AchievementResultRow row = resultRows.get(objective.id);
            double achievement = row == null ? 0D : row.achievement;
            double totalScore = objectiveTargetScores.getOrDefault(objective.id, objective.weight);
            result.add(new ObjectiveAchievement(
                objective.id,
                objective.code,
                objective.content,
                objective.gradReqId,
                objective.gradReqDesc,
                totalScore,
                round2(totalScore * achievement),
                round4(achievement),
                row != null && row.achieved,
                judgement(achievement, thresholdValue),
                row == null ? 0D : row.normal,
                row == null ? 0D : row.mid,
                row == null ? 0D : row.fin
            ));
        }
        return result;
    }

    private double loadOverallAchievement(long courseId, long semesterId, Long calcRuleId) {
        return jdbcTemplate.query("""
            SELECT achieve_value
            FROM achieve_result
            WHERE course_id = :courseId
              AND semester_id = :semesterId
              AND status = 1
              AND objective_id IS NULL
              AND (:calcRuleId IS NULL OR calc_rule_id = :calcRuleId)
            ORDER BY calc_time DESC, id DESC
            LIMIT 1
            """, new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId)
            .addValue("calcRuleId", calcRuleId), rs -> rs.next() ? round4(rs.getDouble("achieve_value")) : 0D);
    }

    private StudentReportData buildStudentReportData(ReportContext ctx, long semesterId, CalcRuleInfo ruleInfo) {
        Map<Long, AssessItemInfo> assessById = ctx.assessItems.stream()
            .collect(Collectors.toMap(item -> item.id, item -> item, (left, right) -> left, LinkedHashMap::new));
        Map<String, String> studentNames = loadStudentNames(ctx.outlineId, semesterId);

        StudentReportData data = new StudentReportData();
        if (!ctx.objAssessContentMaps.isEmpty()) {
            Map<Long, Map<String, Double>> contentStudentRates = computeContentStudentRates(ctx.outlineId, semesterId, ruleInfo.retakeEnabled);
            data.studentScoreSummaries = buildStudentScoreSummariesFromContents(ctx.objAssessContentMaps, contentStudentRates, studentNames);
            data.gradeDistribution = buildDistribution(data.studentScoreSummaries.stream()
                .map(item -> item.totalScore)
                .toList());
            data.componentStats = buildComponentStatsFromContents(ctx.objAssessContentMaps, contentStudentRates);
            buildObjectiveStudentDetailsFromContents(ctx, contentStudentRates, studentNames, data);
            data.objectiveBandAchievements = buildObjectiveBandAchievements(ctx.objectives, data.studentDetails, data.studentScoreSummaries);
            return data;
        }

        Map<Long, Map<String, Double>> itemStudentRates = computeItemStudentRates(ctx.outlineId, semesterId, ruleInfo.retakeEnabled);
        data.studentScoreSummaries = buildStudentScoreSummaries(assessById, itemStudentRates, studentNames);
        data.gradeDistribution = buildDistribution(data.studentScoreSummaries.stream()
            .map(item -> item.totalScore)
            .toList());
        data.componentStats = buildComponentStats(ctx.assessItems, itemStudentRates);
        buildObjectiveStudentDetails(ctx, itemStudentRates, studentNames, data);
        data.objectiveBandAchievements = buildObjectiveBandAchievements(ctx.objectives, data.studentDetails, data.studentScoreSummaries);
        return data;
    }

    private Map<Long, Map<String, Double>> computeItemStudentRates(long outlineId, long semesterId, boolean retakeEnabled) {
        List<GradeRow> rows = jdbcTemplate.query("""
            SELECT sg.id, sg.assess_item_id, sg.student_no, sg.score, sg.max_score
            FROM student_grade sg
            JOIN grade_import_batch gb ON gb.id = sg.import_batch_id
            WHERE sg.semester_id = :semesterId
              AND sg.valid_flag = 1
              AND sg.assess_content_id IS NULL
              AND gb.status = 'CONFIRMED'
              AND sg.assess_item_id IN (
                  SELECT id FROM assess_item WHERE outline_id = :outlineId
              )
            ORDER BY sg.assess_item_id ASC, sg.student_no ASC, sg.id ASC
            """, new MapSqlParameterSource()
            .addValue("outlineId", outlineId)
            .addValue("semesterId", semesterId), (rs, rowNum) -> new GradeRow(
            rs.getLong("id"),
            rs.getLong("assess_item_id"),
            rs.getString("student_no"),
            rs.getDouble("score"),
            rs.getDouble("max_score")
        ));

        Map<Long, Map<String, List<GradeRow>>> grouped = new LinkedHashMap<>();
        for (GradeRow row : rows) {
            grouped.computeIfAbsent(row.assessItemId, key -> new LinkedHashMap<>())
                .computeIfAbsent(row.studentNo, key -> new ArrayList<>())
                .add(row);
        }

        Map<Long, Map<String, Double>> result = new LinkedHashMap<>();
        for (Map.Entry<Long, Map<String, List<GradeRow>>> itemEntry : grouped.entrySet()) {
            Map<String, Double> studentRates = new LinkedHashMap<>();
            for (Map.Entry<String, List<GradeRow>> studentEntry : itemEntry.getValue().entrySet()) {
                List<GradeRow> grades = studentEntry.getValue();
                double rate;
                if (retakeEnabled && grades.size() >= 2) {
                    GradeRow first = grades.get(0);
                    double original = first.maxScore > 0 ? first.score / first.maxScore : 0D;
                    double bestRetake = grades.subList(1, grades.size()).stream()
                        .mapToDouble(item -> item.maxScore > 0 ? item.score / item.maxScore * 0.8D : 0D)
                        .max()
                        .orElse(0D);
                    rate = Math.max(original, bestRetake);
                } else {
                    GradeRow latest = grades.get(grades.size() - 1);
                    rate = latest.maxScore > 0 ? latest.score / latest.maxScore : 0D;
                }
                studentRates.put(studentEntry.getKey(), round4(rate));
            }
            result.put(itemEntry.getKey(), studentRates);
        }
        return result;
    }

    private Map<Long, Map<String, Double>> computeContentStudentRates(long outlineId, long semesterId, boolean retakeEnabled) {
        List<ContentGradeRow> rows = jdbcTemplate.query("""
            SELECT sg.id, sg.assess_item_id, sg.assess_content_id, sg.student_no, sg.score, sg.max_score
            FROM student_grade sg
            JOIN grade_import_batch gb ON gb.id = sg.import_batch_id
            WHERE sg.semester_id = :semesterId
              AND sg.valid_flag = 1
              AND sg.assess_content_id IS NOT NULL
              AND gb.status = 'CONFIRMED'
              AND sg.assess_item_id IN (
                  SELECT id FROM assess_item WHERE outline_id = :outlineId
              )
            ORDER BY sg.assess_content_id ASC, sg.student_no ASC, sg.id ASC
            """, new MapSqlParameterSource()
            .addValue("outlineId", outlineId)
            .addValue("semesterId", semesterId), (rs, rowNum) -> new ContentGradeRow(
            rs.getLong("id"),
            rs.getLong("assess_item_id"),
            rs.getLong("assess_content_id"),
            rs.getString("student_no"),
            rs.getDouble("score"),
            rs.getDouble("max_score")
        ));

        Map<Long, Map<String, List<ContentGradeRow>>> grouped = new LinkedHashMap<>();
        for (ContentGradeRow row : rows) {
            grouped.computeIfAbsent(row.assessContentId, key -> new LinkedHashMap<>())
                .computeIfAbsent(row.studentNo, key -> new ArrayList<>())
                .add(row);
        }

        Map<Long, Map<String, Double>> result = new LinkedHashMap<>();
        for (Map.Entry<Long, Map<String, List<ContentGradeRow>>> contentEntry : grouped.entrySet()) {
            Map<String, Double> studentRates = new LinkedHashMap<>();
            for (Map.Entry<String, List<ContentGradeRow>> studentEntry : contentEntry.getValue().entrySet()) {
                List<ContentGradeRow> grades = studentEntry.getValue();
                double rate;
                if (retakeEnabled && grades.size() >= 2) {
                    ContentGradeRow first = grades.get(0);
                    double original = first.maxScore > 0 ? first.score / first.maxScore : 0D;
                    double bestRetake = grades.subList(1, grades.size()).stream()
                        .mapToDouble(item -> item.maxScore > 0 ? item.score / item.maxScore * 0.8D : 0D)
                        .max()
                        .orElse(0D);
                    rate = Math.max(original, bestRetake);
                } else {
                    ContentGradeRow latest = grades.get(grades.size() - 1);
                    rate = latest.maxScore > 0 ? latest.score / latest.maxScore : 0D;
                }
                studentRates.put(studentEntry.getKey(), round4(rate));
            }
            result.put(contentEntry.getKey(), studentRates);
        }
        return result;
    }

    private Map<String, String> loadStudentNames(long outlineId, long semesterId) {
        return jdbcTemplate.query("""
            SELECT sg.student_no, MAX(sg.student_name) AS student_name
            FROM student_grade sg
            JOIN grade_import_batch gb ON gb.id = sg.import_batch_id
            WHERE sg.semester_id = :semesterId
              AND sg.valid_flag = 1
              AND gb.status = 'CONFIRMED'
              AND sg.assess_item_id IN (
                  SELECT id FROM assess_item WHERE outline_id = :outlineId
              )
            GROUP BY sg.student_no
            ORDER BY sg.student_no ASC
            """, new MapSqlParameterSource()
            .addValue("outlineId", outlineId)
            .addValue("semesterId", semesterId), rs -> {
            Map<String, String> result = new LinkedHashMap<>();
            while (rs.next()) {
                result.put(rs.getString("student_no"), defaultString(rs.getString("student_name"), ""));
            }
            return result;
        });
    }

    private List<StudentScoreSummary> buildStudentScoreSummaries(
        Map<Long, AssessItemInfo> assessById,
        Map<Long, Map<String, Double>> itemStudentRates,
        Map<String, String> studentNames
    ) {
        Map<String, StudentScoreSummary> summaries = new LinkedHashMap<>();
        for (Map.Entry<Long, Map<String, Double>> itemEntry : itemStudentRates.entrySet()) {
            AssessItemInfo assessItem = assessById.get(itemEntry.getKey());
            if (assessItem == null) {
                continue;
            }
            for (Map.Entry<String, Double> studentEntry : itemEntry.getValue().entrySet()) {
                StudentScoreSummary summary = summaries.computeIfAbsent(studentEntry.getKey(), studentNo ->
                    new StudentScoreSummary(studentNo, studentNames.getOrDefault(studentNo, ""))
                );
                double weightedScore = round2(studentEntry.getValue() * assessItem.weight);
                if ("practice".equals(assessItem.type)) {
                    summary.practiceScore = round2(summary.practiceScore + weightedScore);
                } else if ("final".equals(assessItem.type)) {
                    summary.finalScore = round2(summary.finalScore + weightedScore);
                } else {
                    summary.normalScore = round2(summary.normalScore + weightedScore);
                }
                summary.totalScore = round2(summary.totalScore + weightedScore);
            }
        }
        summaries.values().forEach(item -> item.gradeLevel = gradeLevel(item.totalScore));
        return summaries.values().stream()
            .sorted(Comparator.comparing(item -> item.studentNo))
            .toList();
    }

    private List<StudentScoreSummary> buildStudentScoreSummariesFromContents(
        List<ObjAssessContentMapInfo> contentMaps,
        Map<Long, Map<String, Double>> contentStudentRates,
        Map<String, String> studentNames
    ) {
        Map<Long, ContentAllocation> allocations = contentAllocations(contentMaps);
        Set<String> students = studentsFromContentRates(allocations.keySet(), contentStudentRates);
        students.addAll(studentNames.keySet());

        List<StudentScoreSummary> summaries = new ArrayList<>();
        for (String studentNo : students) {
            StudentScoreSummary summary = new StudentScoreSummary(studentNo, studentNames.getOrDefault(studentNo, ""));
            for (Map.Entry<Long, ContentAllocation> entry : allocations.entrySet()) {
                double score = contentStudentRates.getOrDefault(entry.getKey(), Map.of())
                    .getOrDefault(studentNo, 0D) * entry.getValue().score;
                String contentType = entry.getValue().contentType;
                if ("exam".equals(contentType)) {
                    summary.finalScore = round2(summary.finalScore + score);
                } else if ("experiment".equals(contentType)) {
                    summary.practiceScore = round2(summary.practiceScore + score);
                } else {
                    summary.normalScore = round2(summary.normalScore + score);
                }
                summary.totalScore = round2(summary.totalScore + score);
            }
            summary.gradeLevel = gradeLevel(summary.totalScore);
            summaries.add(summary);
        }
        return summaries.stream()
            .sorted(Comparator.comparing(item -> item.studentNo))
            .toList();
    }

    private List<ComponentStat> buildComponentStats(
        List<AssessItemInfo> assessItems,
        Map<Long, Map<String, Double>> itemStudentRates
    ) {
        Map<String, List<AssessItemInfo>> byType = assessItems.stream()
            .collect(Collectors.groupingBy(item -> item.type, LinkedHashMap::new, Collectors.toList()));
        List<ComponentStat> result = new ArrayList<>();
        for (Map.Entry<String, List<AssessItemInfo>> entry : byType.entrySet()) {
            Set<String> students = new LinkedHashSet<>();
            for (AssessItemInfo item : entry.getValue()) {
                students.addAll(itemStudentRates.getOrDefault(item.id, Map.of()).keySet());
            }
            double groupWeight = entry.getValue().stream().mapToDouble(item -> item.weight).sum();
            List<Double> scores = new ArrayList<>();
            long passCount = 0;
            for (String studentNo : students) {
                double score = 0D;
                for (AssessItemInfo item : entry.getValue()) {
                    score += itemStudentRates.getOrDefault(item.id, Map.of()).getOrDefault(studentNo, 0D) * item.weight;
                }
                score = round2(score);
                scores.add(score);
                if (groupWeight <= 0D || score / groupWeight >= 0.6D) {
                    passCount++;
                }
            }
            double avg = scores.stream().mapToDouble(Double::doubleValue).average().orElse(0D);
            double max = scores.stream().mapToDouble(Double::doubleValue).max().orElse(0D);
            double min = scores.stream().mapToDouble(Double::doubleValue).min().orElse(0D);
            result.add(new ComponentStat(
                entry.getKey(),
                assessItemTypeName(entry.getKey()),
                round2(avg),
                round2(max),
                round2(min),
                students.isEmpty() ? 0D : round4((double) passCount / students.size()),
                groupWeight
            ));
        }
        return result;
    }

    private List<ComponentStat> buildComponentStatsFromContents(
        List<ObjAssessContentMapInfo> contentMaps,
        Map<Long, Map<String, Double>> contentStudentRates
    ) {
        Map<Long, ContentAllocation> allocations = contentAllocations(contentMaps);
        Map<String, List<Map.Entry<Long, ContentAllocation>>> byType = allocations.entrySet().stream()
            .collect(Collectors.groupingBy(entry -> entry.getValue().contentType, LinkedHashMap::new, Collectors.toList()));

        List<ComponentStat> result = new ArrayList<>();
        for (Map.Entry<String, List<Map.Entry<Long, ContentAllocation>>> entry : byType.entrySet()) {
            Set<String> students = new LinkedHashSet<>();
            for (Map.Entry<Long, ContentAllocation> content : entry.getValue()) {
                students.addAll(contentStudentRates.getOrDefault(content.getKey(), Map.of()).keySet());
            }
            double groupWeight = entry.getValue().stream().mapToDouble(item -> item.getValue().score).sum();
            List<Double> scores = new ArrayList<>();
            long passCount = 0;
            for (String studentNo : students) {
                double score = 0D;
                for (Map.Entry<Long, ContentAllocation> content : entry.getValue()) {
                    score += contentStudentRates.getOrDefault(content.getKey(), Map.of()).getOrDefault(studentNo, 0D)
                        * content.getValue().score;
                }
                score = round2(score);
                scores.add(score);
                if (groupWeight <= 0D || score / groupWeight >= 0.6D) {
                    passCount++;
                }
            }
            double avg = scores.stream().mapToDouble(Double::doubleValue).average().orElse(0D);
            double max = scores.stream().mapToDouble(Double::doubleValue).max().orElse(0D);
            double min = scores.stream().mapToDouble(Double::doubleValue).min().orElse(0D);
            result.add(new ComponentStat(
                entry.getKey(),
                assessContentTypeName(entry.getKey()),
                round2(avg),
                round2(max),
                round2(min),
                students.isEmpty() ? 0D : round4((double) passCount / students.size()),
                round2(groupWeight)
            ));
        }
        return result;
    }

    private void buildObjectiveStudentDetails(
        ReportContext ctx,
        Map<Long, Map<String, Double>> itemStudentRates,
        Map<String, String> studentNames,
        StudentReportData data
    ) {
        Map<Long, List<ObjAssessMapInfo>> mappingByObjective = ctx.objAssessMaps.stream()
            .collect(Collectors.groupingBy(item -> item.objectiveId, LinkedHashMap::new, Collectors.toList()));
        Map<String, StudentAchievementDetail> detailByStudent = new LinkedHashMap<>();
        for (String studentNo : studentNames.keySet()) {
            detailByStudent.put(studentNo, new StudentAchievementDetail(studentNo, studentNames.getOrDefault(studentNo, "")));
        }

        for (ObjectiveInfo objective : ctx.objectives) {
            List<ObjAssessMapInfo> mappings = mappingByObjective.getOrDefault(objective.id, List.of());
            if (mappings.isEmpty()) {
                mappings = defaultMappings(ctx.assessItems, objective.id);
            }
            Set<String> objectiveStudents = new LinkedHashSet<>();
            for (ObjAssessMapInfo mapping : mappings) {
                objectiveStudents.addAll(itemStudentRates.getOrDefault(mapping.assessItemId, Map.of()).keySet());
            }
            List<Double> objectiveScores = new ArrayList<>();
            for (String studentNo : objectiveStudents) {
                StudentAchievementDetail detail = detailByStudent.computeIfAbsent(studentNo,
                    key -> new StudentAchievementDetail(key, studentNames.getOrDefault(key, "")));
                double achievement = 0D;
                for (ObjAssessMapInfo mapping : mappings) {
                    achievement += itemStudentRates.getOrDefault(mapping.assessItemId, Map.of())
                        .getOrDefault(studentNo, 0D) * mapping.contributionWeight / 100D;
                }
                achievement = round4(achievement);
                double score = round2(achievement * objective.weight);
                detail.objectiveScores.put(objective.code, score);
                detail.objectiveAchievements.put(objective.code, achievement);
                objectiveScores.add(score);
                if (achievement < ctx.thresholdValue) {
                    data.weakStudents.add(new WeakStudent(
                        studentNo,
                        detail.studentName,
                        objective.code,
                        objective.content,
                        achievement
                    ));
                }
            }
            data.objectiveScoreDistributions.put(objective.code, buildDistribution(objectiveScores));
        }

        data.studentDetails = detailByStudent.values().stream()
            .sorted(Comparator.comparing(item -> item.studentNo))
            .toList();
    }

    private void buildObjectiveStudentDetailsFromContents(
        ReportContext ctx,
        Map<Long, Map<String, Double>> contentStudentRates,
        Map<String, String> studentNames,
        StudentReportData data
    ) {
        Map<Long, List<ObjAssessContentMapInfo>> mappingByObjective = ctx.objAssessContentMaps.stream()
            .collect(Collectors.groupingBy(item -> item.objectiveId, LinkedHashMap::new, Collectors.toList()));
        Map<String, StudentAchievementDetail> detailByStudent = new LinkedHashMap<>();
        for (String studentNo : studentNames.keySet()) {
            detailByStudent.put(studentNo, new StudentAchievementDetail(studentNo, studentNames.getOrDefault(studentNo, "")));
        }

        for (ObjectiveInfo objective : ctx.objectives) {
            List<ObjAssessContentMapInfo> mappings = mappingByObjective.getOrDefault(objective.id, List.of());
            double targetScore = mappings.stream().mapToDouble(item -> item.contributionScore).sum();
            Set<String> objectiveStudents = new LinkedHashSet<>();
            for (ObjAssessContentMapInfo mapping : mappings) {
                objectiveStudents.addAll(contentStudentRates.getOrDefault(mapping.assessContentId, Map.of()).keySet());
            }
            if (objectiveStudents.isEmpty()) {
                objectiveStudents.addAll(studentNames.keySet());
            }

            List<Double> objectiveScores = new ArrayList<>();
            for (String studentNo : objectiveStudents) {
                StudentAchievementDetail detail = detailByStudent.computeIfAbsent(studentNo,
                    key -> new StudentAchievementDetail(key, studentNames.getOrDefault(key, "")));
                double score = 0D;
                for (ObjAssessContentMapInfo mapping : mappings) {
                    score += contentStudentRates.getOrDefault(mapping.assessContentId, Map.of())
                        .getOrDefault(studentNo, 0D) * mapping.contributionScore;
                }
                score = round2(score);
                double achievement = targetScore > 0D ? round4(score / targetScore) : 0D;
                detail.objectiveScores.put(objective.code, score);
                detail.objectiveAchievements.put(objective.code, achievement);
                objectiveScores.add(score);
                if (achievement < ctx.thresholdValue) {
                    data.weakStudents.add(new WeakStudent(
                        studentNo,
                        detail.studentName,
                        objective.code,
                        objective.content,
                        achievement
                    ));
                }
            }
            data.objectiveScoreDistributions.put(objective.code, buildDistribution(objectiveScores));
        }

        data.studentDetails = detailByStudent.values().stream()
            .sorted(Comparator.comparing(item -> item.studentNo))
            .toList();
    }

    private Map<String, Map<String, Double>> buildObjectiveBandAchievements(
        List<ObjectiveInfo> objectives,
        List<StudentAchievementDetail> studentDetails,
        List<StudentScoreSummary> scoreSummaries
    ) {
        Map<String, StudentScoreSummary> scoresByStudent = scoreSummaries.stream()
            .collect(Collectors.toMap(item -> item.studentNo, item -> item, (left, right) -> left, LinkedHashMap::new));
        Map<String, Map<String, Double>> result = new LinkedHashMap<>();
        for (ObjectiveInfo objective : objectives) {
            Map<String, List<Double>> valuesByBand = new LinkedHashMap<>();
            List.of("优", "良", "中", "及格", "不及格").forEach(key -> valuesByBand.put(key, new ArrayList<>()));
            for (StudentAchievementDetail detail : studentDetails) {
                StudentScoreSummary summary = scoresByStudent.get(detail.studentNo);
                if (summary == null || !valuesByBand.containsKey(summary.gradeLevel)) {
                    continue;
                }
                valuesByBand.get(summary.gradeLevel)
                    .add(detail.objectiveAchievements.getOrDefault(objective.code, 0D));
            }
            Map<String, Double> bandAverages = new LinkedHashMap<>();
            for (Map.Entry<String, List<Double>> entry : valuesByBand.entrySet()) {
                double avg = entry.getValue().stream()
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0D);
                bandAverages.put(entry.getKey(), round4(avg));
            }
            result.put(objective.code, bandAverages);
        }
        return result;
    }

    private Map<Long, Double> objectiveContentTargetScores(List<ObjAssessContentMapInfo> contentMaps) {
        Map<Long, Double> result = new LinkedHashMap<>();
        for (ObjAssessContentMapInfo mapping : contentMaps) {
            result.merge(mapping.objectiveId, mapping.contributionScore, Double::sum);
        }
        result.replaceAll((key, value) -> round2(value));
        return result;
    }

    private Map<Long, ContentAllocation> contentAllocations(List<ObjAssessContentMapInfo> contentMaps) {
        Map<Long, ContentAllocation> result = new LinkedHashMap<>();
        for (ObjAssessContentMapInfo mapping : contentMaps) {
            ContentAllocation existing = result.get(mapping.assessContentId);
            if (existing == null) {
                result.put(mapping.assessContentId, new ContentAllocation(
                    mapping.assessItemId,
                    mapping.contentType,
                    round2(mapping.contributionScore)
                ));
            } else {
                result.put(mapping.assessContentId, new ContentAllocation(
                    existing.assessItemId,
                    existing.contentType,
                    round2(existing.score + mapping.contributionScore)
                ));
            }
        }
        return result;
    }

    private Set<String> studentsFromContentRates(
        Set<Long> contentIds,
        Map<Long, Map<String, Double>> contentStudentRates
    ) {
        Set<String> students = new LinkedHashSet<>();
        for (Long contentId : contentIds) {
            students.addAll(contentStudentRates.getOrDefault(contentId, Map.of()).keySet());
        }
        return students;
    }

    private List<ObjAssessMapInfo> defaultMappings(List<AssessItemInfo> assessItems, Long objectiveId) {
        if (assessItems.isEmpty()) {
            return List.of();
        }
        double totalWeight = assessItems.stream().mapToDouble(item -> item.weight).sum();
        double fallback = 100D / assessItems.size();
        return assessItems.stream()
            .map(item -> new ObjAssessMapInfo(
                objectiveId,
                item.id,
                totalWeight > 0D ? round2(item.weight * 100D / totalWeight) : round2(fallback)
            ))
            .toList();
    }

    private GradeDistribution buildDistribution(List<Double> scores) {
        GradeDistribution distribution = new GradeDistribution();
        distribution.totalCount = scores.size();
        distribution.buckets.put("优", distributionBucket(scores, 90, 100.000001D));
        distribution.buckets.put("良", distributionBucket(scores, 80, 90));
        distribution.buckets.put("中", distributionBucket(scores, 70, 80));
        distribution.buckets.put("及格", distributionBucket(scores, 60, 70));
        distribution.buckets.put("不及格", distributionBucket(scores, 0, 60));
        return distribution;
    }

    private DistributionBucket distributionBucket(List<Double> scores, double min, double max) {
        long count = scores.stream().filter(score -> score >= min && score < max).count();
        return new DistributionBucket(count, scores.isEmpty() ? 0D : round4((double) count / scores.size()));
    }

    private List<GradReqAchievement> buildGradReqAchievements(
        List<ObjectiveInfo> objectives,
        List<ObjectiveAchievement> achievements
    ) {
        Map<Long, ObjectiveInfo> objectivesById = objectives.stream()
            .collect(Collectors.toMap(item -> item.id, item -> item));
        Map<String, List<ObjectiveAchievement>> grouped = achievements.stream()
            .collect(Collectors.groupingBy(item -> {
                ObjectiveInfo objective = objectivesById.get(item.objectiveId);
                return StringUtils.hasText(objective == null ? "" : objective.gradReqId)
                    ? objective.gradReqId
                    : item.objectiveCode;
            }, LinkedHashMap::new, Collectors.toList()));

        List<GradReqAchievement> result = new ArrayList<>();
        for (Map.Entry<String, List<ObjectiveAchievement>> entry : grouped.entrySet()) {
            List<String> supportingObjectives = entry.getValue().stream()
                .map(item -> item.objectiveCode)
                .toList();
            String description = entry.getValue().stream()
                .map(item -> defaultString(item.gradReqDesc, ""))
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse("由课程目标 " + String.join("、", supportingObjectives) + " 支撑。");
            double achievement = entry.getValue().stream()
                .mapToDouble(item -> item.achievement)
                .average()
                .orElse(0D);
            result.add(new GradReqAchievement(
                entry.getKey(),
                description,
                supportingObjectives,
                round4(achievement)
            ));
        }
        return result;
    }

    private List<String> loadSuggestions(long courseId, long semesterId) {
        return jdbcTemplate.query("""
            SELECT suggestion_text
            FROM intelligent_suggestion
            WHERE course_id = :courseId
              AND semester_id = :semesterId
              AND is_dismissed = 0
            ORDER BY priority ASC, id DESC
            LIMIT 6
            """, new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId), (rs, rowNum) -> rs.getString("suggestion_text"));
    }

    private Long latestCalcRuleId(long courseId, long semesterId) {
        Long resultRuleId = jdbcTemplate.query("""
            SELECT calc_rule_id
            FROM achieve_result
            WHERE course_id = :courseId
              AND semester_id = :semesterId
              AND status = 1
            ORDER BY calc_time DESC, id DESC
            LIMIT 1
            """, new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId), rs -> rs.next() ? rs.getLong("calc_rule_id") : null);
        if (resultRuleId != null) {
            return resultRuleId;
        }
        return jdbcTemplate.query("""
            SELECT id
            FROM calc_rule
            WHERE is_default = 1
            ORDER BY id DESC
            LIMIT 1
            """, new MapSqlParameterSource(), rs -> rs.next() ? rs.getLong("id") : null);
    }

    private CalcRuleInfo loadCalcRule(Long calcRuleId) {
        if (calcRuleId == null) {
            return new CalcRuleInfo(null, "weighted_avg", 0.7D, 0.6D, false);
        }
        return jdbcTemplate.query("""
            SELECT id, calc_method, threshold_value, pass_threshold, config_json
            FROM calc_rule
            WHERE id = :id
            """, params("id", calcRuleId), rs -> {
            if (!rs.next()) {
                return new CalcRuleInfo(calcRuleId, "weighted_avg", 0.7D, 0.6D, false);
            }
            Map<String, Object> config = readMap(rs.getString("config_json"));
            return new CalcRuleInfo(
                rs.getLong("id"),
                rs.getString("calc_method"),
                rs.getDouble("threshold_value"),
                rs.getDouble("pass_threshold"),
                Boolean.TRUE.equals(config.get("retakeEnabled"))
            );
        });
    }

    private void ensureReportSchema() {
        ensureColumn("teach_objective", "grad_req_id", "VARCHAR(20) NULL");
        ensureColumn("teach_objective", "grad_req_desc", "VARCHAR(500) NULL");
        ensureColumn("teach_objective", "relation_level", "VARCHAR(4) DEFAULT 'H'");
        ensureColumn("intelligent_suggestion", "suggestion_source", "VARCHAR(30) DEFAULT 'RULE'");
    }

    private void ensureColumn(String table, String column, String definition) {
        try {
            String dbName = plainJdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
            Integer count = plainJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, dbName, table, column);
            if (count == null || count == 0) {
                plainJdbcTemplate.execute("ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + definition);
                log.info("Schema migration: added column {}.{}", table, column);
            }
        } catch (Exception error) {
            log.warn("Schema migration failed for {}.{}: {}", table, column, error.getMessage());
        }
    }

    private Map<String, Object> readMap(String value) {
        if (!StringUtils.hasText(value)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (Exception error) {
            return new LinkedHashMap<>();
        }
    }

    private Map<String, Object> requireMap(String sql, MapSqlParameterSource params) {
        return jdbcTemplate.query(sql, params, rs -> {
            if (!rs.next()) {
                throw new ApiException(HttpStatus.NOT_FOUND, 404, "记录不存在");
            }
            return rowToMap(rs);
        });
    }

    private Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        int columnCount = rs.getMetaData().getColumnCount();
        for (int index = 1; index <= columnCount; index++) {
            result.put(rs.getMetaData().getColumnLabel(index), rs.getObject(index));
        }
        return result;
    }

    private MapSqlParameterSource params(String key, Object value) {
        return new MapSqlParameterSource(key, value);
    }

    private String assessItemTypeName(String type) {
        return switch (defaultString(type, "")) {
            case "normal" -> "平时";
            case "mid" -> "期中";
            case "final" -> "期末";
            case "practice" -> "实践";
            case "report" -> "报告";
            default -> "其他";
        };
    }

    private String normalizeAssessContentType(String type) {
        String value = defaultString(type, "").trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "experiment", "practice", "lab", "实验", "實驗", "实践", "實踐", "项目", "項目" -> "experiment";
            case "exam", "final", "test", "考核", "考试", "考試", "测验", "測驗", "综合", "綜合" -> "exam";
            default -> "assignment";
        };
    }

    private String assessContentTypeName(String type) {
        return switch (normalizeAssessContentType(type)) {
            case "experiment" -> "实验";
            case "exam" -> "考核";
            default -> "作业";
        };
    }

    private String judgement(double achievement, double thresholdValue) {
        if (achievement >= 0.9D) {
            return "优秀";
        }
        if (achievement >= 0.7D) {
            return "良好";
        }
        if (achievement >= Math.min(0.6D, thresholdValue)) {
            return "及格";
        }
        return "未达标";
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

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String defaultString(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null ? 0 : Integer.parseInt(String.valueOf(value));
    }

    private Integer nullableInteger(Object value) {
        return value == null ? null : intValue(value);
    }

    private Double nullableDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.doubleValue();
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    private double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private double round4(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private record GradeRow(long id, long assessItemId, String studentNo, double score, double maxScore) {
    }

    private record ContentGradeRow(
        long id,
        long assessItemId,
        long assessContentId,
        String studentNo,
        double score,
        double maxScore
    ) {
    }

    private record ContentAllocation(long assessItemId, String contentType, double score) {
    }

    private record AchievementResultRow(
        long objectiveId,
        double normal,
        double mid,
        double fin,
        double achievement,
        boolean achieved
    ) {
    }

    private record CalcRuleInfo(
        Long id,
        String calcMethod,
        double thresholdValue,
        double passThreshold,
        boolean retakeEnabled
    ) {
    }

    private static class StudentReportData {
        private GradeDistribution gradeDistribution = new GradeDistribution();
        private List<ComponentStat> componentStats = new ArrayList<>();
        private Map<String, GradeDistribution> objectiveScoreDistributions = new LinkedHashMap<>();
        private Map<String, Map<String, Double>> objectiveBandAchievements = new LinkedHashMap<>();
        private List<WeakStudent> weakStudents = new ArrayList<>();
        private List<StudentAchievementDetail> studentDetails = new ArrayList<>();
        private List<StudentScoreSummary> studentScoreSummaries = new ArrayList<>();
    }

    public static class ReportContext {
        public Long outlineId;
        public Long calcRuleId;
        public CourseInfo courseInfo;
        public OutlineInfo outlineInfo;
        public List<ObjectiveInfo> objectives = new ArrayList<>();
        public List<AssessItemInfo> assessItems = new ArrayList<>();
        public List<ObjAssessMapInfo> objAssessMaps = new ArrayList<>();
        public List<ObjAssessContentMapInfo> objAssessContentMaps = new ArrayList<>();
        public GradeDistribution gradeDistribution = new GradeDistribution();
        public List<ComponentStat> componentStats = new ArrayList<>();
        public List<ObjectiveAchievement> objectiveAchievements = new ArrayList<>();
        public double overallAchievement;
        public List<GradReqAchievement> gradReqAchievements = new ArrayList<>();
        public Map<String, GradeDistribution> objectiveScoreDistributions = new LinkedHashMap<>();
        public Map<String, Map<String, Double>> objectiveBandAchievements = new LinkedHashMap<>();
        public List<WeakStudent> weakStudents = new ArrayList<>();
        public List<StudentAchievementDetail> studentDetails = new ArrayList<>();
        public List<StudentScoreSummary> studentScoreSummaries = new ArrayList<>();
        public List<String> existingSuggestions = new ArrayList<>();
        public int studentCount;
        public double thresholdValue = 0.7D;
        public String generatedAt = "";
    }

    public static class CourseInfo {
        public final Long id;
        public final String code;
        public final String name;
        public final Integer hours;
        public final Double credits;
        public final String courseType;
        public final String department;
        public final String school;
        public final String teacher;

        public CourseInfo(Long id, String code, String name, Integer hours, Double credits,
                String courseType, String department, String school, String teacher) {
            this.id = id;
            this.code = code;
            this.name = name;
            this.hours = hours;
            this.credits = credits;
            this.courseType = courseType;
            this.department = department;
            this.school = school;
            this.teacher = teacher;
        }

        public String getName() {
            return name;
        }
    }

    public static class OutlineInfo {
        public final Long id;
        public final Long semesterId;
        public final String semester;
        public final String semesterName;
        public final String schoolYear;
        public final int termNo;
        public final String version;
        public final String overview;
        public final String targetSource;
        public final String className;
        public final String teacher;
        public final String reportDate;

        public OutlineInfo(Long id, Long semesterId, String semester, String semesterName, String schoolYear,
                int termNo, String version, String overview, String targetSource, String className,
                String teacher, String reportDate) {
            this.id = id;
            this.semesterId = semesterId;
            this.semester = semester;
            this.semesterName = semesterName;
            this.schoolYear = schoolYear;
            this.termNo = termNo;
            this.version = version;
            this.overview = overview;
            this.targetSource = targetSource;
            this.className = className;
            this.teacher = teacher;
            this.reportDate = reportDate;
        }

        public String getSemester() {
            return semester;
        }
    }

    public static class ObjectiveInfo {
        public final Long id;
        public final String code;
        public final String content;
        public final int type;
        public final double weight;
        public final String gradReqId;
        public final String gradReqDesc;
        public final String relationLevel;
        public final int sortOrder;

        public ObjectiveInfo(Long id, String code, String content, int type, double weight,
                String gradReqId, String gradReqDesc, String relationLevel, int sortOrder) {
            this.id = id;
            this.code = code;
            this.content = content;
            this.type = type;
            this.weight = weight;
            this.gradReqId = gradReqId;
            this.gradReqDesc = gradReqDesc;
            this.relationLevel = relationLevel;
            this.sortOrder = sortOrder;
        }
    }

    public static class AssessItemInfo {
        public final Long id;
        public final String name;
        public final String type;
        public final String typeName;
        public final double weight;
        public final double maxScore;
        public final int sortOrder;

        public AssessItemInfo(Long id, String name, String type, String typeName, double weight, double maxScore, int sortOrder) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.typeName = typeName;
            this.weight = weight;
            this.maxScore = maxScore;
            this.sortOrder = sortOrder;
        }
    }

    public static class ObjAssessMapInfo {
        public final Long objectiveId;
        public final Long assessItemId;
        public final double contributionWeight;

        public ObjAssessMapInfo(Long objectiveId, Long assessItemId, double contributionWeight) {
            this.objectiveId = objectiveId;
            this.assessItemId = assessItemId;
            this.contributionWeight = contributionWeight;
        }
    }

    public static class ObjAssessContentMapInfo {
        public final Long objectiveId;
        public final Long assessItemId;
        public final Long assessContentId;
        public final double contributionScore;
        public final String contentType;

        public ObjAssessContentMapInfo(
            Long objectiveId,
            Long assessItemId,
            Long assessContentId,
            double contributionScore,
            String contentType
        ) {
            this.objectiveId = objectiveId;
            this.assessItemId = assessItemId;
            this.assessContentId = assessContentId;
            this.contributionScore = contributionScore;
            this.contentType = contentType;
        }
    }

    public static class GradeDistribution {
        public int totalCount;
        public Map<String, DistributionBucket> buckets = new LinkedHashMap<>();
    }

    public static class DistributionBucket {
        public final long count;
        public final double pct;

        public DistributionBucket(long count, double pct) {
            this.count = count;
            this.pct = pct;
        }
    }

    public static class ComponentStat {
        public final String type;
        public final String typeName;
        public final double avgScore;
        public final double maxScore;
        public final double minScore;
        public final double passRate;
        public final double weight;

        public ComponentStat(String type, String typeName, double avgScore, double maxScore, double minScore,
                double passRate, double weight) {
            this.type = type;
            this.typeName = typeName;
            this.avgScore = avgScore;
            this.maxScore = maxScore;
            this.minScore = minScore;
            this.passRate = passRate;
            this.weight = weight;
        }
    }

    public static class ObjectiveAchievement {
        public final Long objectiveId;
        public final String objectiveCode;
        public final String objectiveDesc;
        public final String gradReqId;
        public final String gradReqDesc;
        public final double totalScore;
        public final double avgScore;
        public final double achievement;
        public final boolean achieved;
        public final String judgement;
        public final double normal;
        public final double mid;
        public final double fin;

        public ObjectiveAchievement(Long objectiveId, String objectiveCode, String objectiveDesc,
                String gradReqId, String gradReqDesc, double totalScore, double avgScore, double achievement,
                boolean achieved, String judgement, double normal, double mid, double fin) {
            this.objectiveId = objectiveId;
            this.objectiveCode = objectiveCode;
            this.objectiveDesc = objectiveDesc;
            this.gradReqId = gradReqId;
            this.gradReqDesc = gradReqDesc;
            this.totalScore = totalScore;
            this.avgScore = avgScore;
            this.achievement = achievement;
            this.achieved = achieved;
            this.judgement = judgement;
            this.normal = normal;
            this.mid = mid;
            this.fin = fin;
        }
    }

    public static class GradReqAchievement {
        public final String gradReqId;
        public final String gradReqDesc;
        public final List<String> supportingObjectives;
        public final double achievement;

        public GradReqAchievement(String gradReqId, String gradReqDesc, List<String> supportingObjectives, double achievement) {
            this.gradReqId = gradReqId;
            this.gradReqDesc = gradReqDesc;
            this.supportingObjectives = supportingObjectives;
            this.achievement = achievement;
        }
    }

    public static class WeakStudent {
        public final String studentNo;
        public final String studentName;
        public final String objectiveCode;
        public final String objectiveDesc;
        public final double achievement;

        public WeakStudent(String studentNo, String studentName, String objectiveCode, String objectiveDesc, double achievement) {
            this.studentNo = studentNo;
            this.studentName = studentName;
            this.objectiveCode = objectiveCode;
            this.objectiveDesc = objectiveDesc;
            this.achievement = achievement;
        }
    }

    public static class StudentAchievementDetail {
        public final String studentNo;
        public final String studentName;
        public final Map<String, Double> objectiveScores = new LinkedHashMap<>();
        public final Map<String, Double> objectiveAchievements = new LinkedHashMap<>();

        public StudentAchievementDetail(String studentNo, String studentName) {
            this.studentNo = studentNo;
            this.studentName = studentName;
        }
    }

    public static class StudentScoreSummary {
        public final String studentNo;
        public final String studentName;
        public double normalScore;
        public double practiceScore;
        public double finalScore;
        public double totalScore;
        public String gradeLevel = "";

        public StudentScoreSummary(String studentNo, String studentName) {
            this.studentNo = studentNo;
            this.studentName = studentName;
        }
    }
}
