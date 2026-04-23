package com.example.coa.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.example.coa.common.ApiException;
import com.example.coa.security.AuthenticatedUser;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional
public class InMemoryCoaService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};
    private static final HttpStatus UNPROCESSABLE_STATUS = HttpStatus.UNPROCESSABLE_CONTENT;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JdbcTemplate plainJdbcTemplate;
    private final ObjectMapper objectMapper;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public InMemoryCoaService(
        NamedParameterJdbcTemplate jdbcTemplate,
        JdbcTemplate plainJdbcTemplate,
        ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.plainJdbcTemplate = plainJdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public AuthenticatedUser authenticate(String username, String password) {
        Map<String, Object> userRow = findUserRowByUsername(username)
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, 10001, "用户名或密码错误"));

        if (!passwordEncoder.matches(password, stringValue(userRow.get("password_hash")))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, 10001, "用户名或密码错误");
        }

        jdbcTemplate.update("""
            UPDATE sys_user
            SET last_login_at = NOW(), updated_at = NOW()
            WHERE id = :id
            """, params("id", longValue(userRow.get("id"))));
        return toAuthenticatedUser(userRow);
    }

    public AuthenticatedUser getUserById(Long userId) {
        return findUserRowById(userId)
            .map(this::toAuthenticatedUser)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, 404, "用户不存在"));
    }

    public Map<String, Object> getReferenceCatalogs() {
        return referenceData();
    }

    public List<Map<String, Object>> getCourses() {
        return catalogCourses();
    }

    public List<String> getSemesters() {
        return catalogSemesters();
    }

    public List<Map<String, Object>> getAssessItems() {
        return catalogAssessItems();
    }

    public Map<String, Object> getDashboardData() {
        List<Map<String, Object>> courseAchievements = plainJdbcTemplate.query("""
            SELECT
                c.id AS course_id,
                c.course_name,
                COALESCE(MAX(CASE WHEN ar.objective_id IS NULL THEN ar.achieve_value END), 0) AS value
            FROM base_course c
            LEFT JOIN achieve_result ar ON ar.course_id = c.id AND ar.status = 1
            GROUP BY c.id, c.course_name
            ORDER BY c.id
            """, (rs, rowNum) -> map(
            "courseId", rs.getLong("course_id"),
            "courseName", rs.getString("course_name"),
            "value", round4(rs.getDouble("value"))
        ));

        double averageAchievement = courseAchievements.stream()
            .mapToDouble(item -> doubleValue(item.get("value")))
            .average()
            .orElse(0D);

        List<Map<String, Object>> stats = List.of(
            map("label", "本学期课程数", "value", catalogCourses().size(), "tone", "primary"),
            map("label", "已发布大纲", "value", count("SELECT COUNT(*) FROM outline_main WHERE status = 1"), "tone", "success"),
            map("label", "待确认导入", "value", count("SELECT COUNT(*) FROM grade_import_batch WHERE status <> 'CONFIRMED'"), "tone", "warning"),
            map("label", "平均达成度", "value", formatNumber(averageAchievement, 2), "tone", "secondary")
        );

        List<Map<String, Object>> todos = new ArrayList<>();
        if (count("SELECT COUNT(*) FROM parse_task WHERE status = 'PARSING'") > 0) {
            todos.add(map("id", 1, "text", "有待复核的智能解析任务。", "route", "/objectives/parse-import", "level", "high"));
        }
        if (count("SELECT COUNT(*) FROM grade_import_batch WHERE status = 'PARSED'") > 0) {
            todos.add(map("id", 2, "text", "有已解析的成绩批次等待确认导入。", "route", "/collect/grades", "level", "medium"));
        }
        if (count("SELECT COUNT(*) FROM intelligent_suggestion WHERE is_read = 0 AND is_dismissed = 0") > 0) {
            todos.add(map("id", 3, "text", "有未读的智能建议待处理。", "route", "/analysis/suggestions", "level", "normal"));
        }

        List<Map<String, Object>> quickLinks = List.of(
            map("label", "教学目标管理", "route", "/objectives/list"),
            map("label", "数据采集", "route", "/collect/grades"),
            map("label", "达成度核算", "route", "/analysis/calculation"),
            map("label", "智能建议中心", "route", "/analysis/suggestions")
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stats", stats);
        result.put("todos", todos);
        result.put("quickLinks", quickLinks);
        result.put("courseAchievements", courseAchievements);
        return result;
    }

    public Map<String, Object> getOutlines(Long courseId, String semester) {
        long currentCourseId = normalizeCourseId(courseId);
        String currentSemester = normalizeSemester(semester);
        Long semesterId = findSemesterId(currentSemester);

        List<Map<String, Object>> items = jdbcTemplate.query("""
            SELECT
                o.id,
                o.course_id,
                s.semester_code,
                o.version,
                o.status,
                o.overview,
                o.target_source,
                o.updated_at,
                (
                    SELECT COUNT(*)
                    FROM teach_objective t
                    WHERE t.outline_id = o.id
                ) AS objective_count,
                (
                    SELECT COUNT(*)
                    FROM assess_item a
                    WHERE a.outline_id = o.id
                ) AS assess_item_count
            FROM outline_main o
            JOIN base_semester s ON s.id = o.semester_id
            WHERE o.course_id = :courseId
              AND (:semesterId IS NULL OR o.semester_id = :semesterId)
            ORDER BY o.updated_at DESC, o.id DESC
            """, new MapSqlParameterSource()
            .addValue("courseId", currentCourseId)
            .addValue("semesterId", semesterId), (rs, rowNum) -> outlineMap(rs));

        Map<String, Object> result = referenceData();
        result.put("currentCourseId", currentCourseId);
        result.put("currentSemester", currentSemester);
        result.put("items", items);
        return result;
    }

    public Map<String, Object> saveOutline(Map<String, Object> payload) {
        long courseId = longValue(payload.get("courseId"));
        String semesterCode = defaultString(payload.get("semester"), normalizeSemester(null));
        long semesterId = requireSemesterId(semesterCode);
        long teacherId = resolveTeacherId(courseId, semesterId);
        String version = defaultString(payload.get("version"), "V1.0");
        int status = "PUBLISHED".equalsIgnoreCase(stringValue(payload.get("status"))) ? 1 : 0;

        Long id = nullableLong(payload.get("id"));
        if (id == null) {
            GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update("""
                INSERT INTO outline_main (
                    course_id, teacher_id, semester_id, version, status,
                    overview, target_source
                ) VALUES (
                    :courseId, :teacherId, :semesterId, :version, :status,
                    :overview, :targetSource
                )
                """, new MapSqlParameterSource()
                .addValue("courseId", courseId)
                .addValue("teacherId", teacherId)
                .addValue("semesterId", semesterId)
                .addValue("version", version)
                .addValue("status", status)
                .addValue("overview", defaultString(payload.get("overview"), ""))
                .addValue("targetSource", defaultString(payload.get("targetSource"), "")), keyHolder);
            id = keyHolder.getKey().longValue();
        } else {
            jdbcTemplate.update("""
                UPDATE outline_main
                SET course_id = :courseId,
                    teacher_id = :teacherId,
                    semester_id = :semesterId,
                    version = :version,
                    status = :status,
                    overview = :overview,
                    target_source = :targetSource,
                    updated_at = NOW()
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("courseId", courseId)
                .addValue("teacherId", teacherId)
                .addValue("semesterId", semesterId)
                .addValue("version", version)
                .addValue("status", status)
                .addValue("overview", defaultString(payload.get("overview"), ""))
                .addValue("targetSource", defaultString(payload.get("targetSource"), "")));
        }

        return getOutlineById(id);
    }

    public Map<String, Object> publishOutline(Long id) {
        Map<String, Object> outline = getOutlineRow(id);
        long outlineId = longValue(outline.get("id"));

        double objectiveWeight = queryDouble("""
            SELECT COALESCE(SUM(weight), 0)
            FROM teach_objective
            WHERE outline_id = :outlineId
            """, params("outlineId", outlineId));
        if (objectiveWeight <= 0 || Math.abs(objectiveWeight - 100D) > 0.01D) {
            throw new ApiException(UNPROCESSABLE_STATUS, 20001, "课程目标权重合计必须等于100");
        }

        double assessWeight = queryDouble("""
            SELECT COALESCE(SUM(weight), 0)
            FROM assess_item
            WHERE outline_id = :outlineId
            """, params("outlineId", outlineId));
        if (assessWeight <= 0 || Math.abs(assessWeight - 100D) > 0.01D) {
            throw new ApiException(UNPROCESSABLE_STATUS, 20002, "考核项权重合计必须等于100");
        }

        long mappingCount = count("""
            SELECT COUNT(*)
            FROM obj_assess_map m
            JOIN teach_objective t ON t.id = m.objective_id
            WHERE t.outline_id = :outlineId
            """, params("outlineId", outlineId));
        if (mappingCount == 0) {
            throw new ApiException(UNPROCESSABLE_STATUS, 20003, "发布前必须完成目标考核映射");
        }

        jdbcTemplate.update("""
            UPDATE outline_main
            SET status = 1, published_at = NOW(), updated_at = NOW()
            WHERE id = :id
            """, params("id", id));
        return getOutlineById(id);
    }

    public Map<String, Object> getObjectives(Long courseId, String semester) {
        long currentCourseId = normalizeCourseId(courseId);
        String currentSemester = normalizeSemester(semester);
        Long outlineId = findOutlineId(currentCourseId, currentSemester);

        List<Map<String, Object>> items = outlineId == null ? List.of() : objectiveList(outlineId);

        Map<String, Object> result = referenceData();
        result.put("currentCourseId", currentCourseId);
        result.put("currentSemester", currentSemester);
        result.put("items", items);
        return result;
    }

    public Map<String, Object> getObjectiveDetail(Long id) {
        Optional<Map<String, Object>> objective = findObjectiveById(id);
        if (objective.isPresent()) {
            Map<String, Object> row = objective.get();
            row.put("decompose", decomposeList(id));
            row.put("decomposeCount", listSize(row.get("decompose")));
            return row;
        }

        Map<String, Object> firstCourse = catalogCourses().stream().findFirst().orElse(map("id", 0, "outlineId", null, "semester", ""));
        return map(
            "id", null,
            "courseId", longValue(firstCourse.get("id")),
            "outlineId", nullableLong(firstCourse.get("outlineId")),
            "semester", stringValue(firstCourse.get("semester")),
            "objCode", "",
            "objContent", "",
            "objType", 1,
            "objTypeName", "知识",
            "weight", "",
            "sortOrder", 1,
            "decomposeCount", 1,
            "decompose", List.of(map("id", null, "code", "OBJ-X-1", "content", "", "typeLabel", "知识点", "weight", 100))
        );
    }

    public Map<String, Object> saveObjective(Map<String, Object> payload) {
        long courseId = longValue(payload.get("courseId"));
        String semesterCode = defaultString(payload.get("semester"), normalizeSemester(null));
        long outlineId = nullableLong(payload.get("outlineId")) != null
            ? longValue(payload.get("outlineId"))
            : ensureOutline(courseId, semesterCode);

        Long id = nullableLong(payload.get("id"));
        int objType = intValue(payload.getOrDefault("objType", 1));
        double weight = round2(doubleValue(payload.get("weight")));
        List<Map<String, Object>> decompose = listOfMap(payload.get("decompose"));

        if (id == null) {
            GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update("""
                INSERT INTO teach_objective (
                    outline_id, obj_code, obj_content, obj_type, weight, sort_order
                ) VALUES (
                    :outlineId, :objCode, :objContent, :objType, :weight, :sortOrder
                )
                """, new MapSqlParameterSource()
                .addValue("outlineId", outlineId)
                .addValue("objCode", defaultString(payload.get("objCode"), nextObjectiveCode(outlineId)))
                .addValue("objContent", defaultString(payload.get("objContent"), ""))
                .addValue("objType", objType)
                .addValue("weight", weight)
                .addValue("sortOrder", nextSortOrder(outlineId)), keyHolder);
            id = keyHolder.getKey().longValue();
        } else {
            jdbcTemplate.update("""
                UPDATE teach_objective
                SET outline_id = :outlineId,
                    obj_code = :objCode,
                    obj_content = :objContent,
                    obj_type = :objType,
                    weight = :weight,
                    updated_at = NOW()
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("outlineId", outlineId)
                .addValue("objCode", defaultString(payload.get("objCode"), nextObjectiveCode(outlineId)))
                .addValue("objContent", defaultString(payload.get("objContent"), ""))
                .addValue("objType", objType)
                .addValue("weight", weight));
            jdbcTemplate.update("DELETE FROM obj_decompose WHERE objective_id = :objectiveId", params("objectiveId", id));
        }

        saveDecompose(id, decompose);
        return getObjectiveDetail(id);
    }

    public Map<String, Object> saveObjectiveBatch(Map<String, Object> payload) {
        Long courseId = nullableLong(payload.get("courseId"));
        String semester = defaultString(payload.get("semester"), normalizeSemester(null));
        Long outlineId = nullableLong(payload.get("outlineId"));
        if (outlineId == null && courseId != null) {
            outlineId = ensureOutline(courseId, semester);
        }
        if (outlineId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, 400, "必须提供 outlineId 或 courseId");
        }

        if (booleanValue(payload.get("overwrite"))) {
            jdbcTemplate.update("DELETE FROM teach_objective WHERE outline_id = :outlineId", params("outlineId", outlineId));
        }

        List<Map<String, Object>> saved = new ArrayList<>();
        for (Map<String, Object> item : listOfMap(payload.get("objectives"))) {
            Map<String, Object> merged = new LinkedHashMap<>(item);
            merged.put("outlineId", outlineId);
            merged.put("courseId", courseId == null ? getCourseIdByOutline(outlineId) : courseId);
            merged.put("semester", semester);
            saved.add(saveObjective(merged));
        }
        return map("success", true, "count", saved.size(), "items", saved);
    }

    public Map<String, Object> getObjectiveWeights(Long courseId, String semester) {
        Map<String, Object> data = getObjectives(courseId, semester);
        data.put("objectives", data.get("items"));
        return data;
    }

    public Map<String, Object> saveObjectiveWeights(Map<String, Object> payload) {
        for (Map<String, Object> objective : listOfMap(payload.get("objectives"))) {
            long id = longValue(objective.get("id"));
            jdbcTemplate.update("""
                UPDATE teach_objective
                SET weight = :weight, updated_at = NOW()
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("weight", round2(doubleValue(objective.get("weight")))));

            jdbcTemplate.update("DELETE FROM obj_decompose WHERE objective_id = :objectiveId", params("objectiveId", id));
            saveDecompose(id, listOfMap(objective.get("decompose")));
        }
        return map("success", true);
    }

    public Map<String, Object> getObjectiveMapping(Long courseId, String semester) {
        long currentCourseId = normalizeCourseId(courseId);
        String currentSemester = normalizeSemester(semester);
        Long outlineId = findOutlineId(currentCourseId, currentSemester);

        List<Map<String, Object>> objectives = outlineId == null ? List.of() : objectiveList(outlineId);
        List<Map<String, Object>> assessItems = outlineId == null ? List.of() : assessItemListByOutline(outlineId);
        List<Map<String, Object>> rows = buildMappingRows(objectives, assessItems);

        Map<String, Object> result = referenceData();
        result.put("currentCourseId", currentCourseId);
        result.put("currentSemester", currentSemester);
        result.put("objectives", objectives);
        result.put("assessItems", assessItems);
        result.put("rows", rows);
        return result;
    }

    public Map<String, Object> saveObjectiveMapping(Map<String, Object> payload) {
        List<Map<String, Object>> rows = listOfMap(payload.get("rows"));
        List<Long> objectiveIds = rows.stream().map(row -> longValue(row.get("objectiveId"))).toList();
        if (!objectiveIds.isEmpty()) {
            jdbcTemplate.update("""
                DELETE FROM obj_assess_map
                WHERE objective_id IN (:objectiveIds)
                """, new MapSqlParameterSource("objectiveIds", objectiveIds));
        }

        for (Map<String, Object> row : rows) {
            long objectiveId = longValue(row.get("objectiveId"));
            Map<String, Object> values = mapOfObject(row.get("values"));
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                double weight = round2(doubleValue(entry.getValue()));
                if (weight <= 0D) {
                    continue;
                }
                jdbcTemplate.update("""
                    INSERT INTO obj_assess_map (objective_id, assess_item_id, contribution_weight)
                    VALUES (:objectiveId, :assessItemId, :weight)
                    """, new MapSqlParameterSource()
                    .addValue("objectiveId", objectiveId)
                    .addValue("assessItemId", Long.parseLong(entry.getKey()))
                    .addValue("weight", weight));
            }
        }
        return map("success", true);
    }

    public Map<String, Object> uploadParseFile(Long courseId, String semester, String fileName) {
        long currentCourseId = normalizeCourseId(courseId);
        String currentSemester = normalizeSemester(semester);
        long semesterId = requireSemesterId(currentSemester);
        long teacherId = resolveTeacherId(currentCourseId, semesterId);
        long outlineId = ensureOutline(currentCourseId, currentSemester);
        String taskNo = "PARSE-" + System.currentTimeMillis();

        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
            INSERT INTO parse_task (
                task_no, course_id, teacher_id, semester_id, outline_id, source_file_name,
                status, overwrite_mode
            ) VALUES (
                :taskNo, :courseId, :teacherId, :semesterId, :outlineId, :fileName,
                'PARSING', 0
            )
            """, new MapSqlParameterSource()
            .addValue("taskNo", taskNo)
            .addValue("courseId", currentCourseId)
            .addValue("teacherId", teacherId)
            .addValue("semesterId", semesterId)
            .addValue("outlineId", outlineId)
            .addValue("fileName", defaultString(fileName, "course-outline.pdf")), keyHolder);
        long parseTaskId = keyHolder.getKey().longValue();

        createDefaultParseDrafts(parseTaskId, currentCourseId);
        jdbcTemplate.update("""
            UPDATE parse_task
            SET obj_extract_count = :objCount,
                assess_extract_count = 3
            WHERE id = :id
            """, new MapSqlParameterSource()
            .addValue("id", parseTaskId)
            .addValue("objCount", 3));

        return map("taskId", taskNo, "status", "PARSING");
    }

    public Map<String, Object> getParseTaskDetail(String taskId) {
        Map<String, Object> taskRow = requireMap("""
            SELECT
                pt.id,
                pt.task_no,
                pt.course_id,
                pt.outline_id,
                pt.source_file_name,
                pt.status,
                pt.obj_extract_count,
                pt.assess_extract_count,
                bs.semester_code,
                pt.created_at
            FROM parse_task pt
            JOIN base_semester bs ON bs.id = pt.semester_id
            WHERE pt.task_no = :taskNo
            """, params("taskNo", taskId));

        if ("PARSING".equals(taskRow.get("status"))) {
            jdbcTemplate.update("""
                UPDATE parse_task
                SET status = 'DONE',
                    finished_at = NOW(),
                    updated_at = NOW()
                WHERE id = :id
                """, params("id", longValue(taskRow.get("id"))));
            taskRow.put("status", "DONE");
        }

        List<Map<String, Object>> objectives = jdbcTemplate.query("""
            SELECT
                id,
                obj_code_suggest,
                obj_content_suggest,
                obj_type_suggest,
                weight_suggest,
                obj_content_final,
                obj_type_final,
                weight_final,
                confidence_score,
                confidence_level,
                original_text,
                is_confirmed
            FROM parse_objective_draft
            WHERE parse_task_id = :parseTaskId
            ORDER BY sort_order ASC, id ASC
            """, params("parseTaskId", longValue(taskRow.get("id"))), (rs, rowNum) -> map(
            "id", rs.getLong("id"),
            "objCodeSuggest", rs.getString("obj_code_suggest"),
            "objContentSuggest", rs.getString("obj_content_suggest"),
            "objTypeSuggest", rs.getInt("obj_type_suggest"),
            "weightSuggest", rs.getBigDecimal("weight_suggest"),
            "objContentFinal", defaultString(rs.getString("obj_content_final"), rs.getString("obj_content_suggest")),
            "objTypeFinal", rs.getObject("obj_type_final") == null ? rs.getInt("obj_type_suggest") : rs.getInt("obj_type_final"),
            "weightFinal", rs.getObject("weight_final") == null ? rs.getBigDecimal("weight_suggest") : rs.getBigDecimal("weight_final"),
            "confidenceScore", rs.getBigDecimal("confidence_score"),
            "confidenceLevel", rs.getString("confidence_level"),
            "originalText", rs.getString("original_text"),
            "isConfirmed", rs.getInt("is_confirmed")
        ));

        List<Map<String, Object>> originalSections = objectives.stream()
            .map(item -> map(
                "id", item.get("id"),
                "label", item.get("objCodeSuggest"),
                "text", item.get("originalText")
            ))
            .toList();

        return map(
            "taskId", taskRow.get("task_no"),
            "courseId", taskRow.get("course_id"),
            "outlineId", taskRow.get("outline_id"),
            "semester", taskRow.get("semester_code"),
            "fileName", taskRow.get("source_file_name"),
            "status", taskRow.get("status"),
            "objExtractCount", taskRow.get("obj_extract_count"),
            "assessExtractCount", taskRow.get("assess_extract_count"),
            "objectives", objectives,
            "originalSections", originalSections
        );
    }

    public Map<String, Object> updateParseDraft(Long id, Map<String, Object> payload) {
        jdbcTemplate.update("""
            UPDATE parse_objective_draft
            SET obj_content_final = :content,
                obj_type_final = :objType,
                weight_final = :weight,
                is_confirmed = :isConfirmed,
                updated_at = NOW()
            WHERE id = :id
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("content", defaultString(payload.get("objContentFinal"), ""))
            .addValue("objType", intValue(payload.get("objTypeFinal")))
            .addValue("weight", round2(doubleValue(payload.get("weightFinal"))))
            .addValue("isConfirmed", intValue(payload.get("isConfirmed"))));
        return map("success", true);
    }

    public Map<String, Object> confirmParseTask(String taskId, Map<String, Object> payload) {
        Map<String, Object> taskRow = requireMap("""
            SELECT id, course_id, outline_id, semester_id
            FROM parse_task
            WHERE task_no = :taskNo
            """, params("taskNo", taskId));

        long parseTaskId = longValue(taskRow.get("id"));
        long outlineId = nullableLong(payload.get("outlineId")) == null ? longValue(taskRow.get("outline_id")) : longValue(payload.get("outlineId"));
        boolean overwrite = booleanValue(payload.get("overwrite"));

        List<Map<String, Object>> drafts = jdbcTemplate.query("""
            SELECT *
            FROM parse_objective_draft
            WHERE parse_task_id = :parseTaskId
              AND is_confirmed = 1
            ORDER BY sort_order ASC, id ASC
            """, params("parseTaskId", parseTaskId), (rs, rowNum) -> map(
            "id", rs.getLong("id"),
            "objCode", defaultString(rs.getString("obj_code_suggest"), "OBJ-" + (rowNum + 1)),
            "objContent", defaultString(rs.getString("obj_content_final"), rs.getString("obj_content_suggest")),
            "objType", rs.getObject("obj_type_final") == null ? rs.getInt("obj_type_suggest") : rs.getInt("obj_type_final"),
            "weight", rs.getObject("weight_final") == null ? rs.getBigDecimal("weight_suggest") : rs.getBigDecimal("weight_final")
        ));

        double totalWeight = drafts.stream().mapToDouble(item -> doubleValue(item.get("weight"))).sum();
        if (Math.abs(totalWeight - 100D) > 0.01D) {
            throw new ApiException(UNPROCESSABLE_STATUS, 400, "已确认目标的权重合计必须等于100");
        }

        if (overwrite) {
            jdbcTemplate.update("DELETE FROM teach_objective WHERE outline_id = :outlineId", params("outlineId", outlineId));
        }

        int sortOrder = 1;
        for (Map<String, Object> draft : drafts) {
            GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update("""
                INSERT INTO teach_objective (
                    outline_id, obj_code, obj_content, obj_type, weight, sort_order
                ) VALUES (
                    :outlineId, :objCode, :objContent, :objType, :weight, :sortOrder
                )
                """, new MapSqlParameterSource()
                .addValue("outlineId", outlineId)
                .addValue("objCode", draft.get("objCode"))
                .addValue("objContent", draft.get("objContent"))
                .addValue("objType", draft.get("objType"))
                .addValue("weight", draft.get("weight"))
                .addValue("sortOrder", sortOrder++), keyHolder);

            long objectiveId = keyHolder.getKey().longValue();
            jdbcTemplate.update("""
                INSERT INTO obj_decompose (
                    objective_id, point_code, point_content, point_type, weight, sort_order
                ) VALUES (
                    :objectiveId, :pointCode, :pointContent, :pointType, :weight, 1
                )
                """, new MapSqlParameterSource()
                .addValue("objectiveId", objectiveId)
                .addValue("pointCode", draft.get("objCode") + "-1")
                .addValue("pointContent", draft.get("objContent"))
                .addValue("pointType", draft.get("objType"))
                .addValue("weight", 100));
        }

        ensureDefaultAssessItems(outlineId);
        jdbcTemplate.update("""
            UPDATE parse_task
            SET status = 'CONFIRMED', overwrite_mode = :overwriteMode, updated_at = NOW()
            WHERE id = :id
            """, new MapSqlParameterSource()
            .addValue("id", parseTaskId)
            .addValue("overwriteMode", overwrite ? 1 : 0));

        return map("importedObjectives", drafts.size(), "importedAssessItems", assessItemListByOutline(outlineId).size());
    }

    public Map<String, Object> uploadGradeFile(Long courseId, Long assessItemId, String semester, String fileName) {
        long currentCourseId = normalizeCourseId(courseId);
        long currentAssessItemId = assessItemId == null ? firstAssessItemId(currentCourseId, semester) : assessItemId;
        String currentSemester = normalizeSemester(semester);
        long semesterId = requireSemesterId(currentSemester);
        long teacherId = resolveTeacherId(currentCourseId, semesterId);
        String batchNo = "GRADE-" + System.currentTimeMillis();

        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
            INSERT INTO grade_import_batch (
                batch_no, course_id, assess_item_id, teacher_id, semester_id, source_file_name,
                status, total_rows, valid_rows, error_rows, import_mode
            ) VALUES (
                :batchNo, :courseId, :assessItemId, :teacherId, :semesterId, :fileName,
                'PARSING', 12, 10, 2, 'valid_only'
            )
            """, new MapSqlParameterSource()
            .addValue("batchNo", batchNo)
            .addValue("courseId", currentCourseId)
            .addValue("assessItemId", currentAssessItemId)
            .addValue("teacherId", teacherId)
            .addValue("semesterId", semesterId)
            .addValue("fileName", defaultString(fileName, "grade-import.xlsx")), keyHolder);
        long batchId = keyHolder.getKey().longValue();

        seedGradePreviewRows(batchId, currentCourseId, currentAssessItemId, semesterId, teacherId);
        return map("batchId", batchNo, "status", "PARSING");
    }

    public Map<String, Object> getGradeBatchPreview(String batchId) {
        Map<String, Object> batch = requireMap("""
            SELECT
                gb.id,
                gb.batch_no,
                gb.source_file_name,
                gb.status,
                gb.valid_rows,
                gb.error_rows
            FROM grade_import_batch gb
            WHERE gb.batch_no = :batchNo
            """, params("batchNo", batchId));

        if ("PARSING".equals(batch.get("status"))) {
            jdbcTemplate.update("""
                UPDATE grade_import_batch
                SET status = 'PARSED', updated_at = NOW()
                WHERE id = :id
                """, params("id", longValue(batch.get("id"))));
            batch.put("status", "PARSED");
        }

        List<Map<String, Object>> preview = jdbcTemplate.query("""
            SELECT
                student_no,
                student_name,
                score,
                valid_flag,
                error_message
            FROM student_grade
            WHERE import_batch_id = :batchId
            ORDER BY id ASC
            """, params("batchId", longValue(batch.get("id"))), (rs, rowNum) -> map(
            "row", rowNum + 1,
            "studentId", rs.getString("student_no"),
            "name", rs.getString("student_name"),
            "score", rs.getBigDecimal("score"),
            "valid", rs.getInt("valid_flag") == 1,
            "errorMsg", defaultString(rs.getString("error_message"), "有效")
        ));

        return map(
            "batchId", batch.get("batch_no"),
            "fileName", batch.get("source_file_name"),
            "status", batch.get("status"),
            "validRows", batch.get("valid_rows"),
            "errorRows", batch.get("error_rows"),
            "preview", preview
        );
    }

    public Map<String, Object> confirmGradeBatch(String batchId) {
        Map<String, Object> batch = requireMap("""
            SELECT id, valid_rows, error_rows
            FROM grade_import_batch
            WHERE batch_no = :batchNo
            """, params("batchNo", batchId));

        jdbcTemplate.update("""
            UPDATE grade_import_batch
            SET status = 'CONFIRMED', confirmed_at = NOW(), updated_at = NOW()
            WHERE id = :id
            """, params("id", longValue(batch.get("id"))));
        return map(
            "success", true,
            "importedCount", intValue(batch.get("valid_rows")),
            "skippedCount", intValue(batch.get("error_rows"))
        );
    }

    public Map<String, Object> getImportedGrades(Map<String, String> filters) {
        StringBuilder sql = new StringBuilder("""
            SELECT
                sg.id,
                sg.student_no,
                sg.student_name,
                sg.score,
                c.course_name,
                ai.item_name,
                bs.semester_code,
                sg.valid_flag
            FROM student_grade sg
            JOIN base_course c ON c.id = sg.course_id
            JOIN assess_item ai ON ai.id = sg.assess_item_id
            JOIN base_semester bs ON bs.id = sg.semester_id
            WHERE 1 = 1
            """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendTextFilter(sql, params, "courseId", filters.get("courseId"), "sg.course_id = :courseId");
        appendTextFilter(sql, params, "assessItemId", filters.get("assessItemId"), "sg.assess_item_id = :assessItemId");
        appendTextFilter(sql, params, "semesterCode", filters.get("semester"), "bs.semester_code = :semesterCode");
        sql.append(" ORDER BY sg.updated_at DESC, sg.id DESC");

        List<Map<String, Object>> items = jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> map(
            "id", rs.getLong("id"),
            "studentNo", rs.getString("student_no"),
            "studentName", rs.getString("student_name"),
            "score", rs.getBigDecimal("score"),
            "courseName", rs.getString("course_name"),
            "itemName", rs.getString("item_name"),
            "semester", rs.getString("semester_code"),
            "validFlag", rs.getInt("valid_flag")
        ));
        return map("items", items, "total", items.size());
    }

    public Map<String, Object> getStudentEvaluations(Long courseId, String semester) {
        long currentCourseId = normalizeCourseId(courseId);
        String currentSemester = normalizeSemester(semester);
        Long semesterId = findSemesterId(currentSemester);

        Map<String, Object> summary = jdbcTemplate.query("""
            SELECT id, content, score, updated_at
            FROM student_eval
            WHERE course_id = :courseId
              AND semester_id = :semesterId
              AND student_no = 'SUMMARY'
            ORDER BY id DESC
            LIMIT 1
            """, new MapSqlParameterSource()
            .addValue("courseId", currentCourseId)
            .addValue("semesterId", semesterId), rs -> rs.next() ? map(
            "id", rs.getLong("id"),
            "content", rs.getString("content"),
            "score", rs.getBigDecimal("score"),
            "updatedAt", formatTime(rs.getTimestamp("updated_at"))
        ) : null);

        List<Map<String, Object>> dimensions = new ArrayList<>();
        int evaluatorCount = 0;
        List<String> comments = new ArrayList<>();
        double avgScore = 0D;
        String updatedAt = "";

        if (summary != null) {
            Map<String, Object> json = readMap(summary.get("content"));
            evaluatorCount = intValue(json.get("evaluatorCount"));
            comments = listOfString(json.get("comments"));
            avgScore = doubleValue(summary.get("score"));
            updatedAt = stringValue(summary.get("updatedAt"));
            dimensions = jdbcTemplate.query("""
                SELECT dimension_key, dimension_name, dimension_score
                FROM student_eval_dimension
                WHERE eval_id = :evalId
                ORDER BY id ASC
                """, params("evalId", longValue(summary.get("id"))), (rs, rowNum) -> map(
                "key", rs.getString("dimension_key"),
                "label", rs.getString("dimension_name"),
                "score", rs.getBigDecimal("dimension_score")
            ));
        }

        if (dimensions.isEmpty()) {
            dimensions = defaultStudentDimensions();
        }

        Map<String, Object> result = referenceData();
        result.put("currentCourseId", currentCourseId);
        result.put("currentSemester", currentSemester);
        result.put("record", map(
            "courseId", currentCourseId,
            "semester", currentSemester,
            "evaluatorCount", evaluatorCount,
            "avgScore", round2(avgScore),
            "dimensions", dimensions,
            "comments", comments,
            "updatedAt", updatedAt
        ));
        return result;
    }

    public Map<String, Object> saveStudentEvaluations(Map<String, Object> payload) {
        long courseId = longValue(payload.get("courseId"));
        String semesterCode = defaultString(payload.get("semester"), normalizeSemester(null));
        long semesterId = requireSemesterId(semesterCode);
        List<Map<String, Object>> dimensions = listOfMap(payload.get("dimensions"));
        List<String> comments = listOfString(payload.get("comments"));
        double avgScore = dimensions.stream().mapToDouble(item -> doubleValue(item.get("score"))).average().orElse(0D);
        int evaluatorCount = intValue(payload.get("evaluatorCount"));

        jdbcTemplate.update("""
            DELETE FROM student_eval
            WHERE course_id = :courseId
              AND semester_id = :semesterId
              AND student_no = 'SUMMARY'
            """, new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId));

        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
            INSERT INTO student_eval (
                course_id, semester_id, student_no, student_name, content, score
            ) VALUES (
                :courseId, :semesterId, 'SUMMARY', '系统汇总', :content, :score
            )
            """, new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId)
            .addValue("content", writeJson(map("evaluatorCount", evaluatorCount, "comments", comments)))
            .addValue("score", round2(avgScore)), keyHolder);
        long evalId = keyHolder.getKey().longValue();

        for (Map<String, Object> dimension : dimensions) {
            jdbcTemplate.update("""
                INSERT INTO student_eval_dimension (
                    eval_id, dimension_key, dimension_name, dimension_score
                ) VALUES (
                    :evalId, :dimensionKey, :dimensionName, :dimensionScore
                )
                """, new MapSqlParameterSource()
                .addValue("evalId", evalId)
                .addValue("dimensionKey", defaultString(dimension.get("key"), "dimension"))
                .addValue("dimensionName", defaultString(dimension.get("label"), "Dimension"))
                .addValue("dimensionScore", round2(doubleValue(dimension.get("score")))));
        }

        return getStudentEvaluations(courseId, semesterCode).get("record") instanceof Map<?, ?> record
            ? castMap(record)
            : Map.of();
    }

    public Map<String, Object> getSupervisorEvaluations(Long courseId, String semester) {
        long currentCourseId = normalizeCourseId(courseId);
        String currentSemester = normalizeSemester(semester);
        Long semesterId = findSemesterId(currentSemester);

        List<Map<String, Object>> records = jdbcTemplate.query("""
            SELECT
                se.id,
                u.real_name AS supervisor_name,
                se.score,
                se.content,
                se.focus_items_json,
                se.created_at
            FROM supervisor_eval se
            JOIN sys_user u ON u.id = se.supervisor_id
            WHERE se.course_id = :courseId
              AND se.semester_id = :semesterId
            ORDER BY se.created_at DESC, se.id DESC
            """, new MapSqlParameterSource()
            .addValue("courseId", currentCourseId)
            .addValue("semesterId", semesterId), (rs, rowNum) -> map(
            "id", rs.getLong("id"),
            "supervisorName", rs.getString("supervisor_name"),
            "score", rs.getBigDecimal("score"),
            "content", defaultString(rs.getString("content"), ""),
            "focusItems", readStringList(rs.getString("focus_items_json")),
            "createdAt", formatTime(rs.getTimestamp("created_at"))
        ));

        Map<String, Object> result = referenceData();
        result.put("currentCourseId", currentCourseId);
        result.put("currentSemester", currentSemester);
        result.put("records", records);
        return result;
    }

    public Map<String, Object> getTeachingReflection(Long courseId, String semester) {
        long currentCourseId = normalizeCourseId(courseId);
        String currentSemester = normalizeSemester(semester);
        Long outlineId = findOutlineId(currentCourseId, currentSemester);
        Long semesterId = findSemesterId(currentSemester);

        Map<String, Object> record = jdbcTemplate.query("""
            SELECT
                problem_summary,
                reason_analysis,
                improvement_plan,
                next_action,
                updated_at
            FROM teacher_reflection
            WHERE course_id = :courseId
              AND semester_id = :semesterId
            ORDER BY id DESC
            LIMIT 1
            """, new MapSqlParameterSource()
            .addValue("courseId", currentCourseId)
            .addValue("semesterId", semesterId), rs -> rs.next() ? map(
            "courseId", currentCourseId,
            "semester", currentSemester,
            "problemSummary", defaultString(rs.getString("problem_summary"), ""),
            "reasonAnalysis", defaultString(rs.getString("reason_analysis"), ""),
            "improvementPlan", defaultString(rs.getString("improvement_plan"), ""),
            "nextAction", defaultString(rs.getString("next_action"), ""),
            "updatedAt", formatTime(rs.getTimestamp("updated_at"))
        ) : map(
            "courseId", currentCourseId,
            "semester", currentSemester,
            "problemSummary", "",
            "reasonAnalysis", "",
            "improvementPlan", "",
            "nextAction", "",
            "updatedAt", ""
        ));

        Map<String, Object> result = referenceData();
        result.put("currentCourseId", currentCourseId);
        result.put("currentSemester", currentSemester);
        result.put("record", record);
        result.put("outlineId", outlineId);
        return result;
    }

    public Map<String, Object> saveTeachingReflection(Map<String, Object> payload) {
        long courseId = longValue(payload.get("courseId"));
        String semesterCode = defaultString(payload.get("semester"), normalizeSemester(null));
        long semesterId = requireSemesterId(semesterCode);
        long outlineId = ensureOutline(courseId, semesterCode);
        long teacherId = resolveTeacherId(courseId, semesterId);

        Long existingId = jdbcTemplate.query("""
            SELECT id
            FROM teacher_reflection
            WHERE course_id = :courseId
              AND semester_id = :semesterId
              AND teacher_id = :teacherId
            LIMIT 1
            """, new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId)
            .addValue("teacherId", teacherId), rs -> rs.next() ? rs.getLong("id") : null);

        MapSqlParameterSource sqlParams = new MapSqlParameterSource()
            .addValue("outlineId", outlineId)
            .addValue("courseId", courseId)
            .addValue("teacherId", teacherId)
            .addValue("semesterId", semesterId)
            .addValue("problemSummary", defaultString(payload.get("problemSummary"), ""))
            .addValue("reasonAnalysis", defaultString(payload.get("reasonAnalysis"), ""))
            .addValue("improvementPlan", defaultString(payload.get("improvementPlan"), ""))
            .addValue("nextAction", defaultString(payload.get("nextAction"), ""));

        if (existingId == null) {
            jdbcTemplate.update("""
                INSERT INTO teacher_reflection (
                    outline_id, course_id, teacher_id, semester_id,
                    problem_summary, reason_analysis, improvement_plan, next_action
                ) VALUES (
                    :outlineId, :courseId, :teacherId, :semesterId,
                    :problemSummary, :reasonAnalysis, :improvementPlan, :nextAction
                )
                """, sqlParams);
        } else {
            sqlParams.addValue("id", existingId);
            jdbcTemplate.update("""
                UPDATE teacher_reflection
                SET outline_id = :outlineId,
                    problem_summary = :problemSummary,
                    reason_analysis = :reasonAnalysis,
                    improvement_plan = :improvementPlan,
                    next_action = :nextAction,
                    updated_at = NOW()
                WHERE id = :id
                """, sqlParams);
        }

        return castMap(getTeachingReflection(courseId, semesterCode).get("record"));
    }

    public Map<String, Object> getAchievementCalculation(Long courseId, String semester) {
        long currentCourseId = normalizeCourseId(courseId);
        String currentSemester = normalizeSemester(semester);
        Long semesterId = findSemesterId(currentSemester);

        Map<String, Object> config = currentCalcRule();
        List<Map<String, Object>> results = achievementResults(currentCourseId, semesterId, false);
        Map<String, Object> overall = achievementOverall(currentCourseId, semesterId);

        Map<String, Object> result = referenceData();
        result.put("currentCourseId", currentCourseId);
        result.put("currentSemester", currentSemester);
        result.put("record", map(
            "config", map(
                "calcMethod", config.get("calc_method"),
                "thresholdValue", config.get("threshold_value"),
                "passThreshold", config.get("pass_threshold")
            ),
            "generatedAt", defaultString(overall.get("calc_time"), ""),
            "overallAchievement", doubleValue(overall.get("achieve_value")),
            "results", results
        ));
        return result;
    }

    public Map<String, Object> runAchievementCalculation(Map<String, Object> payload) {
        long courseId = longValue(payload.get("courseId"));
        String semesterCode = defaultString(payload.get("semester"), normalizeSemester(null));
        long semesterId = requireSemesterId(semesterCode);
        long outlineId = ensureOutline(courseId, semesterCode);

        double thresholdValue = doubleValue(payload.getOrDefault("thresholdValue", 0.7D));
        double passThreshold = doubleValue(payload.getOrDefault("passThreshold", 0.6D));
        String calcMethod = defaultString(payload.get("calcMethod"), "weighted_avg");

        long calcRuleId = ensureCalcRule(calcMethod, thresholdValue, passThreshold);
        String batchNo = "CALC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);

        jdbcTemplate.update("""
            UPDATE achieve_result
            SET status = 0, updated_at = NOW()
            WHERE course_id = :courseId
              AND semester_id = :semesterId
              AND status = 1
            """, new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId));

        List<Map<String, Object>> objectives = objectiveList(outlineId);
        Map<Long, Double> itemAvgRates = assessItemAverageRate(outlineId, semesterId);
        List<Map<String, Object>> objectiveResults = new ArrayList<>();
        double overallAchievement = 0D;

        for (Map<String, Object> objective : objectives) {
            long objectiveId = longValue(objective.get("id"));
            List<Map<String, Object>> mappings = mappingWeights(objectiveId);

            double normal = 0D;
            double mid = 0D;
            double fin = 0D;
            double achieveValue = 0D;

            for (Map<String, Object> mapping : mappings) {
                long assessItemId = longValue(mapping.get("assessItemId"));
                double contribution = doubleValue(mapping.get("contributionWeight")) / 100D;
                double rate = itemAvgRates.getOrDefault(assessItemId, 0D);
                achieveValue += rate * contribution;

                String itemType = stringValue(mapping.get("itemType")).toLowerCase(Locale.ROOT);
                if (itemType.contains("平时") || itemType.contains("regular") || itemType.contains("normal")) {
                    normal += rate * contribution;
                } else if (itemType.contains("期中") || itemType.contains("mid")) {
                    mid += rate * contribution;
                } else {
                    fin += rate * contribution;
                }
            }

            achieveValue = round4(achieveValue);
            normal = round4(normal);
            mid = round4(mid);
            fin = round4(fin);
            boolean achieved = achieveValue >= thresholdValue;

            GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update("""
                INSERT INTO achieve_result (
                    result_batch_no, course_id, objective_id, calc_rule_id, semester_id,
                    normal_score, mid_score, final_score, achieve_value, is_achieved, status
                ) VALUES (
                    :batchNo, :courseId, :objectiveId, :calcRuleId, :semesterId,
                    :normalScore, :midScore, :finalScore, :achieveValue, :isAchieved, 1
                )
                """, new MapSqlParameterSource()
                .addValue("batchNo", batchNo)
                .addValue("courseId", courseId)
                .addValue("objectiveId", objectiveId)
                .addValue("calcRuleId", calcRuleId)
                .addValue("semesterId", semesterId)
                .addValue("normalScore", normal)
                .addValue("midScore", mid)
                .addValue("finalScore", fin)
                .addValue("achieveValue", achieveValue)
                .addValue("isAchieved", achieved ? 1 : 0), keyHolder);

            long resultId = keyHolder.getKey().longValue();
            for (Map<String, Object> mapping : mappings) {
                long assessItemId = longValue(mapping.get("assessItemId"));
                double contribution = doubleValue(mapping.get("contributionWeight"));
                double rate = itemAvgRates.getOrDefault(assessItemId, 0D);
                jdbcTemplate.update("""
                    INSERT INTO achieve_result_detail (
                        achieve_result_id, assess_item_id, score_rate, contribution_weight, achieve_value
                    ) VALUES (
                        :resultId, :assessItemId, :scoreRate, :contributionWeight, :achieveValue
                    )
                    """, new MapSqlParameterSource()
                    .addValue("resultId", resultId)
                    .addValue("assessItemId", assessItemId)
                    .addValue("scoreRate", round4(rate))
                    .addValue("contributionWeight", contribution)
                    .addValue("achieveValue", round4(rate * contribution / 100D)));
            }

            objectiveResults.add(map(
                "objectiveId", objectiveId,
                "objCode", objective.get("objCode"),
                "normal", normal,
                "mid", mid,
                "final", fin,
                "achieveValue", achieveValue,
                "isAchieved", achieved
            ));
            overallAchievement += achieveValue * doubleValue(objective.get("weight")) / 100D;
        }

        overallAchievement = round4(overallAchievement);
        jdbcTemplate.update("""
            INSERT INTO achieve_result (
                result_batch_no, course_id, objective_id, calc_rule_id, semester_id,
                achieve_value, is_achieved, status
            ) VALUES (
                :batchNo, :courseId, NULL, :calcRuleId, :semesterId,
                :achieveValue, :isAchieved, 1
            )
            """, new MapSqlParameterSource()
            .addValue("batchNo", batchNo)
            .addValue("courseId", courseId)
            .addValue("calcRuleId", calcRuleId)
            .addValue("semesterId", semesterId)
            .addValue("achieveValue", overallAchievement)
            .addValue("isAchieved", overallAchievement >= thresholdValue ? 1 : 0));

        generateSuggestions(courseId, semesterId, thresholdValue);
        return castMap(getAchievementCalculation(courseId, semesterCode).get("record"));
    }

    public Map<String, Object> getCourseOverview(Long courseId, String semester) {
        long currentCourseId = normalizeCourseId(courseId);
        String currentSemester = normalizeSemester(semester);
        Long semesterId = findSemesterId(currentSemester);

        Map<String, Object> overall = achievementOverall(currentCourseId, semesterId);
        List<Map<String, Object>> objectives = achievementResults(currentCourseId, semesterId, true);

        List<Map<String, Object>> compareCourses = plainJdbcTemplate.query("""
            SELECT
                c.id AS course_id,
                c.course_name,
                COALESCE(MAX(CASE WHEN ar.objective_id IS NULL AND bs.semester_code = ? THEN ar.achieve_value END), 0) AS value
            FROM base_course c
            LEFT JOIN achieve_result ar ON ar.course_id = c.id AND ar.status = 1
            LEFT JOIN base_semester bs ON bs.id = ar.semester_id
            GROUP BY c.id, c.course_name
            ORDER BY c.id
            """, (rs, rowNum) -> map(
            "courseId", rs.getLong("course_id"),
            "courseName", rs.getString("course_name"),
            "value", round4(rs.getDouble("value"))
        ), currentSemester);

        List<Map<String, Object>> indicators = objectives.stream()
            .map(item -> map("name", item.get("objCode"), "max", 1))
            .toList();
        List<Double> values = objectives.stream()
            .map(item -> round4(doubleValue(item.get("achieveValue"))))
            .toList();

        return map(
            "referenceData", referenceData(),
            "courseId", currentCourseId,
            "semester", currentSemester,
            "overallAchievement", round4(doubleValue(overall.get("achieve_value"))),
            "isAllAchieved", objectives.stream().allMatch(item -> booleanValue(item.get("isAchieved"))),
            "objectives", objectives,
            "compareCourses", compareCourses,
            "radarData", map("indicators", indicators, "values", values),
            "suggestionSummary", buildSuggestionSummary(currentCourseId, semesterId)
        );
    }

    public List<Map<String, Object>> getTrendData(Long courseId, Long objectiveId) {
        long currentCourseId = normalizeCourseId(courseId);
        String sql = """
            SELECT
                bs.semester_code,
                ar.achieve_value
            FROM achieve_result ar
            JOIN base_semester bs ON bs.id = ar.semester_id
            WHERE ar.course_id = :courseId
              AND ar.status = 1
              AND ((:objectiveId IS NULL AND ar.objective_id IS NULL) OR ar.objective_id = :objectiveId)
            ORDER BY bs.school_year ASC, bs.term_no ASC
            """;
        return jdbcTemplate.query(sql, new MapSqlParameterSource()
            .addValue("courseId", currentCourseId)
            .addValue("objectiveId", objectiveId), (rs, rowNum) -> map(
            "semester", rs.getString("semester_code"),
            "value", round4(rs.getDouble("achieve_value"))
        ));
    }

    public Map<String, Object> getSuggestions(Map<String, String> filters) {
        StringBuilder sql = new StringBuilder("""
            SELECT
                s.id,
                s.course_id,
                c.course_name,
                s.objective_id,
                bs.semester_code,
                s.priority,
                s.title,
                s.rule_code,
                s.suggestion_text,
                s.is_read,
                s.is_dismissed,
                s.created_at
            FROM intelligent_suggestion s
            JOIN base_course c ON c.id = s.course_id
            JOIN base_semester bs ON bs.id = s.semester_id
            WHERE 1 = 1
            """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendTextFilter(sql, params, "semester", filters.get("semester"), "bs.semester_code = :semester");
        appendTextFilter(sql, params, "courseId", filters.get("courseId"), "s.course_id = :courseId");
        appendTextFilter(sql, params, "priority", filters.get("priority"), "s.priority = :priority");
        appendTextFilter(sql, params, "isRead", filters.get("isRead"), "s.is_read = :isRead");
        appendTextFilter(sql, params, "isDismissed", filters.get("isDismissed"), "s.is_dismissed = :isDismissed");
        sql.append(" ORDER BY s.priority ASC, s.created_at DESC, s.id DESC");

        List<Map<String, Object>> items = jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> map(
            "id", rs.getLong("id"),
            "courseId", rs.getLong("course_id"),
            "courseName", rs.getString("course_name"),
            "objectiveId", nullableLong(rs.getObject("objective_id")),
            "semester", rs.getString("semester_code"),
            "priority", rs.getInt("priority"),
            "priorityLabel", priorityLabel(rs.getInt("priority")),
            "title", rs.getString("title"),
            "ruleCode", rs.getString("rule_code"),
            "suggestionText", rs.getString("suggestion_text"),
            "isRead", rs.getInt("is_read"),
            "isDismissed", rs.getInt("is_dismissed"),
            "createdAt", formatTime(rs.getTimestamp("created_at"))
        ));

        return map("courses", catalogCourses(), "semesters", catalogSemesters(), "items", items);
    }

    public Map<String, Object> getSuggestionDetail(Long id) {
        return requireMap("""
            SELECT
                s.id,
                s.course_id,
                c.course_name,
                s.objective_id,
                bs.semester_code,
                s.priority,
                s.title,
                s.rule_code,
                s.suggestion_text,
                s.data_basis_json,
                s.is_read,
                s.is_dismissed,
                s.created_at
            FROM intelligent_suggestion s
            JOIN base_course c ON c.id = s.course_id
            JOIN base_semester bs ON bs.id = s.semester_id
            WHERE s.id = :id
            """, params("id", id), rs -> map(
            "id", rs.getLong("id"),
            "courseId", rs.getLong("course_id"),
            "courseName", rs.getString("course_name"),
            "objectiveId", nullableLong(rs.getObject("objective_id")),
            "semester", rs.getString("semester_code"),
            "priority", rs.getInt("priority"),
            "priorityLabel", priorityLabel(rs.getInt("priority")),
            "title", rs.getString("title"),
            "ruleCode", rs.getString("rule_code"),
            "suggestionText", rs.getString("suggestion_text"),
            "dataBasis", readMap(rs.getString("data_basis_json")),
            "isRead", rs.getInt("is_read"),
            "isDismissed", rs.getInt("is_dismissed"),
            "createdAt", formatTime(rs.getTimestamp("created_at"))
        ));
    }

    public Map<String, Object> markSuggestionRead(Long id) {
        jdbcTemplate.update("""
            UPDATE intelligent_suggestion
            SET is_read = 1, read_at = NOW(), updated_at = NOW()
            WHERE id = :id
            """, params("id", id));
        return map("success", true);
    }

    public Map<String, Object> dismissSuggestion(Long id, Map<String, Object> payload) {
        jdbcTemplate.update("""
            UPDATE intelligent_suggestion
            SET is_dismissed = 1,
                dismiss_reason = :dismissReason,
                dismissed_at = NOW(),
                updated_at = NOW()
            WHERE id = :id
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("dismissReason", defaultString(payload.get("dismissReason"), "dismissed")));
        return map("success", true);
    }

    public Map<String, Object> createMeasureFromSuggestion(Long id) {
        Map<String, Object> suggestion = getSuggestionDetail(id);
        long courseId = longValue(suggestion.get("courseId"));
        String semesterCode = stringValue(suggestion.get("semester"));
        long semesterId = requireSemesterId(semesterCode);
        long teacherId = resolveTeacherId(courseId, semesterId);

        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
            INSERT INTO improve_measure (
                suggestion_id, course_id, objective_id, teacher_id, semester_id,
                problem_desc, measure_content, expected_effect, actual_effect,
                owner_name, status, effect_summary
            ) VALUES (
                :suggestionId, :courseId, :objectiveId, :teacherId, :semesterId,
                :problemDesc, :measureContent, :expectedEffect, '',
                :ownerName, 0, ''
            )
            """, new MapSqlParameterSource()
            .addValue("suggestionId", id)
            .addValue("courseId", courseId)
            .addValue("objectiveId", nullableLong(suggestion.get("objectiveId")))
            .addValue("teacherId", teacherId)
            .addValue("semesterId", semesterId)
            .addValue("problemDesc", suggestion.get("title"))
            .addValue("measureContent", suggestion.get("suggestionText"))
            .addValue("expectedEffect", "Raise related objective achievement in the next cycle.")
            .addValue("ownerName", teacherName(teacherId)), keyHolder);

        return map("measureId", keyHolder.getKey().longValue(), "redirectUrl", "/analysis/improvements?measureId=" + keyHolder.getKey().longValue());
    }

    public List<Map<String, Object>> getSuggestionRules() {
        return jdbcTemplate.query("""
            SELECT
                id, rule_code, rule_name, rule_type, trigger_condition_json,
                suggestion_template, priority, is_enabled, sort_order, updated_at
            FROM suggestion_rule
            ORDER BY sort_order ASC, id ASC
            """, (rs, rowNum) -> map(
            "id", rs.getLong("id"),
            "ruleCode", rs.getString("rule_code"),
            "ruleName", rs.getString("rule_name"),
            "ruleType", rs.getString("rule_type"),
            "triggerConditionJson", readMap(rs.getString("trigger_condition_json")),
            "suggestionTemplate", rs.getString("suggestion_template"),
            "priority", rs.getInt("priority"),
            "isEnabled", rs.getInt("is_enabled") == 1,
            "sortOrder", rs.getInt("sort_order"),
            "updatedAt", formatTime(rs.getTimestamp("updated_at"))
        ));
    }

    public Map<String, Object> updateSuggestionRule(Long id, Map<String, Object> payload) {
        jdbcTemplate.update("""
            UPDATE suggestion_rule
            SET rule_name = :ruleName,
                rule_type = :ruleType,
                trigger_condition_json = :triggerConditionJson,
                suggestion_template = :suggestionTemplate,
                priority = :priority,
                is_enabled = :isEnabled,
                sort_order = :sortOrder,
                description = :description,
                updated_at = NOW()
            WHERE id = :id
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("ruleName", defaultString(payload.get("ruleName"), ""))
            .addValue("ruleType", defaultString(payload.get("ruleType"), "OBJECTIVE"))
            .addValue("triggerConditionJson", writeJson(payload.getOrDefault("triggerConditionJson", Map.of())))
            .addValue("suggestionTemplate", defaultString(payload.get("suggestionTemplate"), ""))
            .addValue("priority", intValue(payload.get("priority")))
            .addValue("isEnabled", booleanValue(payload.get("isEnabled")) ? 1 : 0)
            .addValue("sortOrder", intValue(payload.get("sortOrder")))
            .addValue("description", defaultString(payload.get("description"), "")));
        return map("success", true);
    }

    public Map<String, Object> triggerSuggestionRules(Map<String, Object> payload) {
        long courseId = longValue(payload.get("courseId"));
        String semesterCode = defaultString(payload.get("semester"), normalizeSemester(null));
        long semesterId = requireSemesterId(semesterCode);
        double thresholdValue = queryDouble("""
            SELECT threshold_value
            FROM calc_rule
            WHERE is_default = 1
            ORDER BY id DESC
            LIMIT 1
            """, new MapSqlParameterSource());
        int created = generateSuggestions(courseId, semesterId, thresholdValue);
        return map("success", true, "createdCount", created);
    }

    public Map<String, Object> getImprovementMeasures(Map<String, String> filters) {
        StringBuilder sql = new StringBuilder("""
            SELECT
                im.id,
                im.suggestion_id,
                im.course_id,
                im.objective_id,
                bs.semester_code,
                im.problem_desc,
                im.measure_content,
                im.expected_effect,
                im.actual_effect,
                im.owner_name,
                im.deadline,
                im.status,
                im.effect_summary,
                im.updated_at,
                s.rule_code
            FROM improve_measure im
            JOIN base_semester bs ON bs.id = im.semester_id
            LEFT JOIN intelligent_suggestion s ON s.id = im.suggestion_id
            WHERE 1 = 1
            """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendTextFilter(sql, params, "courseId", filters.get("courseId"), "im.course_id = :courseId");
        appendTextFilter(sql, params, "semester", filters.get("semester"), "bs.semester_code = :semester");
        if (StringUtils.hasText(filters.get("status"))) {
            sql.append(" AND im.status = :status ");
            params.addValue("status", statusCode(filters.get("status")));
        }
        sql.append(" ORDER BY im.updated_at DESC, im.id DESC");

        List<Map<String, Object>> items = jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> map(
            "id", rs.getLong("id"),
            "suggestionId", nullableLong(rs.getObject("suggestion_id")),
            "courseId", rs.getLong("course_id"),
            "objectiveId", nullableLong(rs.getObject("objective_id")),
            "semester", rs.getString("semester_code"),
            "problemDesc", rs.getString("problem_desc"),
            "measureContent", rs.getString("measure_content"),
            "expectedEffect", defaultString(rs.getString("expected_effect"), ""),
            "actualEffect", defaultString(rs.getString("actual_effect"), ""),
            "owner", defaultString(rs.getString("owner_name"), ""),
            "deadline", rs.getDate("deadline") == null ? "" : rs.getDate("deadline").toString(),
            "status", statusText(rs.getInt("status")),
            "effectSummary", defaultString(rs.getString("effect_summary"), ""),
            "linkedRuleCode", defaultString(rs.getString("rule_code"), ""),
            "updatedAt", formatTime(rs.getTimestamp("updated_at"))
        ));
        return map("courses", catalogCourses(), "semesters", catalogSemesters(), "items", items);
    }

    public Map<String, Object> saveImprovementMeasure(Map<String, Object> payload) {
        long courseId = longValue(payload.get("courseId"));
        String semesterCode = defaultString(payload.get("semester"), normalizeSemester(null));
        long semesterId = requireSemesterId(semesterCode);
        long teacherId = resolveTeacherId(courseId, semesterId);

        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("suggestionId", nullableLong(payload.get("suggestionId")))
            .addValue("courseId", courseId)
            .addValue("objectiveId", nullableLong(payload.get("objectiveId")))
            .addValue("teacherId", teacherId)
            .addValue("semesterId", semesterId)
            .addValue("problemDesc", defaultString(payload.get("problemDesc"), ""))
            .addValue("measureContent", defaultString(payload.get("measureContent"), ""))
            .addValue("expectedEffect", defaultString(payload.get("expectedEffect"), ""))
            .addValue("actualEffect", defaultString(payload.get("actualEffect"), ""))
            .addValue("ownerName", defaultString(payload.get("owner"), teacherName(teacherId)))
            .addValue("deadline", parseDate(payload.get("deadline")))
            .addValue("status", statusCode(defaultString(payload.get("status"), "PLANNED")))
            .addValue("effectSummary", defaultString(payload.get("effectSummary"), defaultString(payload.get("actualEffect"), "")));

        Long id = nullableLong(payload.get("id"));
        if (id == null) {
            GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update("""
                INSERT INTO improve_measure (
                    suggestion_id, course_id, objective_id, teacher_id, semester_id,
                    problem_desc, measure_content, expected_effect, actual_effect,
                    owner_name, deadline, status, effect_summary
                ) VALUES (
                    :suggestionId, :courseId, :objectiveId, :teacherId, :semesterId,
                    :problemDesc, :measureContent, :expectedEffect, :actualEffect,
                    :ownerName, :deadline, :status, :effectSummary
                )
                """, params, keyHolder);
            id = keyHolder.getKey().longValue();
        } else {
            params.addValue("id", id);
            jdbcTemplate.update("""
                UPDATE improve_measure
                SET suggestion_id = :suggestionId,
                    course_id = :courseId,
                    objective_id = :objectiveId,
                    teacher_id = :teacherId,
                    semester_id = :semesterId,
                    problem_desc = :problemDesc,
                    measure_content = :measureContent,
                    expected_effect = :expectedEffect,
                    actual_effect = :actualEffect,
                    owner_name = :ownerName,
                    deadline = :deadline,
                    status = :status,
                    effect_summary = :effectSummary,
                    updated_at = NOW()
                WHERE id = :id
                """, params);
        }
        return getImprovementMeasureById(id);
    }

    public Map<String, Object> updateImprovementEffect(Long id, Map<String, Object> payload) {
        jdbcTemplate.update("""
            UPDATE improve_measure
            SET actual_effect = :actualEffect,
                effect_summary = :effectSummary,
                updated_at = NOW()
            WHERE id = :id
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("actualEffect", defaultString(payload.get("actualEffect"), ""))
            .addValue("effectSummary", defaultString(payload.get("effectSummary"), "")));
        return getImprovementMeasureById(id);
    }

    private Optional<Map<String, Object>> findUserRowByUsername(String username) {
        return queryOptional("""
            SELECT
                u.id,
                u.username,
                u.password_hash,
                u.real_name,
                u.email,
                u.phone,
                u.college_id,
                bc.college_name,
                u.status
            FROM sys_user u
            LEFT JOIN base_college bc ON bc.id = u.college_id
            WHERE u.username = :username
            LIMIT 1
            """, params("username", username), this::userRowMapper);
    }

    private Optional<Map<String, Object>> findUserRowById(Long id) {
        return queryOptional("""
            SELECT
                u.id,
                u.username,
                u.password_hash,
                u.real_name,
                u.email,
                u.phone,
                u.college_id,
                bc.college_name,
                u.status
            FROM sys_user u
            LEFT JOIN base_college bc ON bc.id = u.college_id
            WHERE u.id = :id
            LIMIT 1
            """, params("id", id), this::userRowMapper);
    }

    private Map<String, Object> userRowMapper(ResultSet rs) throws SQLException {
        long userId = rs.getLong("id");
        return map(
            "id", userId,
            "username", rs.getString("username"),
            "password_hash", rs.getString("password_hash"),
            "real_name", rs.getString("real_name"),
            "email", rs.getString("email"),
            "phone", rs.getString("phone"),
            "college_id", nullableLong(rs.getObject("college_id")),
            "college_name", rs.getString("college_name"),
            "status", rs.getInt("status"),
            "roles", jdbcTemplate.query("""
                SELECT r.role_code
                FROM sys_user_role ur
                JOIN sys_role r ON r.id = ur.role_id
                WHERE ur.user_id = :userId
                ORDER BY r.id
                """, params("userId", userId), (roleRs, rowNum) -> roleRs.getString("role_code"))
        );
    }

    private AuthenticatedUser toAuthenticatedUser(Map<String, Object> user) {
        return new AuthenticatedUser(
            longValue(user.get("id")),
            stringValue(user.get("username")),
            stringValue(user.get("real_name")),
            stringValue(user.get("email")),
            stringValue(user.get("phone")),
            nullableLong(user.get("college_id")),
            stringValue(user.get("college_name")),
            listOfString(user.get("roles")),
            intValue(user.get("status"))
        );
    }

    private Map<String, Object> referenceData() {
        return map(
            "courses", catalogCourses(),
            "semesters", catalogSemesters(),
            "assessItems", catalogAssessItems()
        );
    }

    private List<Map<String, Object>> catalogCourses() {
        return plainJdbcTemplate.query("""
            SELECT
                c.id,
                c.course_code,
                c.course_name,
                (
                    SELECT bs.semester_code
                    FROM outline_main o
                    JOIN base_semester bs ON bs.id = o.semester_id
                    WHERE o.course_id = c.id
                    ORDER BY bs.school_year DESC, bs.term_no DESC, o.id DESC
                    LIMIT 1
                ) AS semester_code,
                (
                    SELECT o.id
                    FROM outline_main o
                    JOIN base_semester bs ON bs.id = o.semester_id
                    WHERE o.course_id = c.id
                    ORDER BY bs.school_year DESC, bs.term_no DESC, o.id DESC
                    LIMIT 1
                ) AS outline_id
            FROM base_course c
            WHERE c.status = 1
            ORDER BY c.id ASC
            """, (rs, rowNum) -> map(
            "id", rs.getLong("id"),
            "code", rs.getString("course_code"),
            "name", rs.getString("course_name"),
            "semester", defaultString(rs.getString("semester_code"), normalizeSemester(null)),
            "outlineId", nullableLong(rs.getObject("outline_id"))
        ));
    }

    private List<String> catalogSemesters() {
        return plainJdbcTemplate.query("""
            SELECT semester_code
            FROM base_semester
            WHERE status = 1
            ORDER BY school_year DESC, term_no DESC, id DESC
            """, (rs, rowNum) -> rs.getString("semester_code"));
    }

    private List<Map<String, Object>> catalogAssessItems() {
        return plainJdbcTemplate.query("""
            SELECT
                ai.id,
                ai.outline_id,
                ai.item_name,
                ai.item_type,
                ai.weight,
                o.course_id
            FROM assess_item ai
            JOIN outline_main o ON o.id = ai.outline_id
            ORDER BY o.course_id ASC, ai.sort_order ASC, ai.id ASC
            """, (rs, rowNum) -> map(
            "id", rs.getLong("id"),
            "outlineId", rs.getLong("outline_id"),
            "courseId", rs.getLong("course_id"),
            "itemName", rs.getString("item_name"),
            "itemType", defaultString(rs.getString("item_type"), ""),
            "weight", rs.getBigDecimal("weight")
        ));
    }

    private long normalizeCourseId(Long courseId) {
        if (courseId != null) {
            return courseId;
        }
        return catalogCourses().stream()
            .findFirst()
            .map(item -> longValue(item.get("id")))
            .orElse(0L);
    }

    private String normalizeSemester(String semester) {
        if (StringUtils.hasText(semester)) {
            return semester;
        }
        return catalogSemesters().stream().findFirst().orElse("");
    }

    private Long findSemesterId(String semesterCode) {
        if (!StringUtils.hasText(semesterCode)) {
            return null;
        }
        return jdbcTemplate.query("""
            SELECT id
            FROM base_semester
            WHERE semester_code = :semesterCode
            LIMIT 1
            """, params("semesterCode", semesterCode), rs -> rs.next() ? rs.getLong("id") : null);
    }

    private long requireSemesterId(String semesterCode) {
        Long id = findSemesterId(semesterCode);
        if (id == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, 400, "semester not found");
        }
        return id;
    }

    private Long findOutlineId(long courseId, String semesterCode) {
        Long semesterId = findSemesterId(semesterCode);
        if (semesterId == null) {
            return null;
        }
        return jdbcTemplate.query("""
            SELECT id
            FROM outline_main
            WHERE course_id = :courseId
              AND semester_id = :semesterId
            ORDER BY updated_at DESC, id DESC
            LIMIT 1
            """, new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId), rs -> rs.next() ? rs.getLong("id") : null);
    }

    private long ensureOutline(long courseId, String semesterCode) {
        Long existing = findOutlineId(courseId, semesterCode);
        if (existing != null) {
            return existing;
        }
        long semesterId = requireSemesterId(semesterCode);
        long teacherId = resolveTeacherId(courseId, semesterId);
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
            INSERT INTO outline_main (
                course_id, teacher_id, semester_id, version, status, overview, target_source
            ) VALUES (
                :courseId, :teacherId, :semesterId, 'V1.0', 0, '', ''
            )
            """, new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("teacherId", teacherId)
            .addValue("semesterId", semesterId), keyHolder);
        return keyHolder.getKey().longValue();
    }

    private long resolveTeacherId(long courseId, long semesterId) {
        Long teacherId = jdbcTemplate.query("""
            SELECT teacher_id
            FROM course_teacher
            WHERE course_id = :courseId
              AND semester_id = :semesterId
              AND status = 1
            ORDER BY id ASC
            LIMIT 1
            """, new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId), rs -> rs.next() ? rs.getLong("teacher_id") : null);
        if (teacherId != null) {
            return teacherId;
        }

        teacherId = jdbcTemplate.query("""
            SELECT u.id
            FROM sys_user u
            JOIN sys_user_role ur ON ur.user_id = u.id
            JOIN sys_role r ON r.id = ur.role_id
            WHERE r.role_code = 'TEACHER'
              AND u.status = 1
            ORDER BY u.id ASC
            LIMIT 1
            """, new MapSqlParameterSource(), rs -> rs.next() ? rs.getLong("id") : null);
        if (teacherId != null) {
            return teacherId;
        }
        throw new ApiException(UNPROCESSABLE_STATUS, 400, "no teacher available for current course");
    }

    private Map<String, Object> outlineMap(ResultSet rs) throws SQLException {
        return map(
            "id", rs.getLong("id"),
            "courseId", rs.getLong("course_id"),
            "semester", rs.getString("semester_code"),
            "version", rs.getString("version"),
            "status", rs.getInt("status") == 1 ? "PUBLISHED" : "DRAFT",
            "overview", defaultString(rs.getString("overview"), ""),
            "targetSource", defaultString(rs.getString("target_source"), ""),
            "objectiveCount", rs.getLong("objective_count"),
            "assessItemCount", rs.getLong("assess_item_count"),
            "updatedAt", formatTime(rs.getTimestamp("updated_at"))
        );
    }

    private Map<String, Object> getOutlineById(long id) {
        return requireMap("""
            SELECT
                o.id,
                o.course_id,
                bs.semester_code,
                o.version,
                o.status,
                o.overview,
                o.target_source,
                o.updated_at,
                (
                    SELECT COUNT(*)
                    FROM teach_objective t
                    WHERE t.outline_id = o.id
                ) AS objective_count,
                (
                    SELECT COUNT(*)
                    FROM assess_item a
                    WHERE a.outline_id = o.id
                ) AS assess_item_count
            FROM outline_main o
            JOIN base_semester bs ON bs.id = o.semester_id
            WHERE o.id = :id
            """, params("id", id), this::outlineMap);
    }

    private Map<String, Object> getOutlineRow(long id) {
        return requireMap("""
            SELECT id, course_id, semester_id, teacher_id
            FROM outline_main
            WHERE id = :id
            """, params("id", id));
    }

    private List<Map<String, Object>> objectiveList(long outlineId) {
        Map<String, Object> scope = getOutlineRow(outlineId);
        String semesterCode = queryString("""
            SELECT semester_code
            FROM base_semester
            WHERE id = :semesterId
            """, params("semesterId", longValue(scope.get("semester_id"))));

        return jdbcTemplate.query("""
            SELECT id, obj_code, obj_content, obj_type, weight, sort_order
            FROM teach_objective
            WHERE outline_id = :outlineId
            ORDER BY sort_order ASC, id ASC
            """, params("outlineId", outlineId), (rs, rowNum) -> {
            long objectiveId = rs.getLong("id");
            List<Map<String, Object>> decompose = decomposeList(objectiveId);
            return map(
                "id", objectiveId,
                "courseId", longValue(scope.get("course_id")),
                "outlineId", outlineId,
                "semester", semesterCode,
                "objCode", rs.getString("obj_code"),
                "objContent", rs.getString("obj_content"),
                "objType", rs.getInt("obj_type"),
                "objTypeName", objTypeName(rs.getInt("obj_type")),
                "weight", rs.getBigDecimal("weight"),
                "sortOrder", rs.getInt("sort_order"),
                "decomposeCount", decompose.size(),
                "decompose", decompose
            );
        });
    }

    private Optional<Map<String, Object>> findObjectiveById(long id) {
        return queryOptional("""
            SELECT
                t.id,
                t.outline_id,
                t.obj_code,
                t.obj_content,
                t.obj_type,
                t.weight,
                t.sort_order,
                o.course_id,
                bs.semester_code
            FROM teach_objective t
            JOIN outline_main o ON o.id = t.outline_id
            JOIN base_semester bs ON bs.id = o.semester_id
            WHERE t.id = :id
            """, params("id", id), rs -> map(
            "id", rs.getLong("id"),
            "courseId", rs.getLong("course_id"),
            "outlineId", rs.getLong("outline_id"),
            "semester", rs.getString("semester_code"),
            "objCode", rs.getString("obj_code"),
            "objContent", rs.getString("obj_content"),
            "objType", rs.getInt("obj_type"),
            "objTypeName", objTypeName(rs.getInt("obj_type")),
            "weight", rs.getBigDecimal("weight"),
            "sortOrder", rs.getInt("sort_order")
        ));
    }

    private List<Map<String, Object>> decomposeList(long objectiveId) {
        return jdbcTemplate.query("""
            SELECT id, point_code, point_content, point_type, weight
            FROM obj_decompose
            WHERE objective_id = :objectiveId
            ORDER BY sort_order ASC, id ASC
            """, params("objectiveId", objectiveId), (rs, rowNum) -> map(
            "id", rs.getLong("id"),
            "code", rs.getString("point_code"),
            "content", rs.getString("point_content"),
            "typeLabel", pointTypeName(rs.getInt("point_type")),
            "weight", rs.getBigDecimal("weight")
        ));
    }

    private void saveDecompose(long objectiveId, List<Map<String, Object>> items) {
        int sortOrder = 1;
        for (Map<String, Object> item : items) {
            jdbcTemplate.update("""
                INSERT INTO obj_decompose (
                    objective_id, point_code, point_content, point_type, weight, sort_order
                ) VALUES (
                    :objectiveId, :pointCode, :pointContent, :pointType, :weight, :sortOrder
                )
                """, new MapSqlParameterSource()
                .addValue("objectiveId", objectiveId)
                .addValue("pointCode", defaultString(item.get("code"), "PART-" + sortOrder))
                .addValue("pointContent", defaultString(item.get("content"), ""))
                .addValue("pointType", pointTypeCode(item.get("typeLabel")))
                .addValue("weight", round2(doubleValue(item.get("weight"))))
                .addValue("sortOrder", sortOrder++));
        }
    }

    private int nextSortOrder(long outlineId) {
        return jdbcTemplate.query("""
            SELECT COALESCE(MAX(sort_order), 0) + 1 AS next_sort
            FROM teach_objective
            WHERE outline_id = :outlineId
            """, params("outlineId", outlineId), rs -> rs.next() ? rs.getInt("next_sort") : 1);
    }

    private String nextObjectiveCode(long outlineId) {
        return "OBJ-" + nextSortOrder(outlineId);
    }

    private long getCourseIdByOutline(long outlineId) {
        return queryLong("""
            SELECT course_id
            FROM outline_main
            WHERE id = :outlineId
            """, params("outlineId", outlineId));
    }

    private List<Map<String, Object>> assessItemListByOutline(long outlineId) {
        return jdbcTemplate.query("""
            SELECT id, item_name, item_type, weight
            FROM assess_item
            WHERE outline_id = :outlineId
            ORDER BY sort_order ASC, id ASC
            """, params("outlineId", outlineId), (rs, rowNum) -> map(
            "id", rs.getLong("id"),
            "outlineId", outlineId,
            "itemName", rs.getString("item_name"),
            "itemType", defaultString(rs.getString("item_type"), ""),
            "weight", rs.getBigDecimal("weight")
        ));
    }

    private List<Map<String, Object>> buildMappingRows(List<Map<String, Object>> objectives, List<Map<String, Object>> assessItems) {
        return objectives.stream().map(objective -> {
            long objectiveId = longValue(objective.get("id"));
            Map<String, Object> values = new LinkedHashMap<>();
            for (Map<String, Object> item : assessItems) {
                long assessItemId = longValue(item.get("id"));
                Double weight = jdbcTemplate.query("""
                    SELECT contribution_weight
                    FROM obj_assess_map
                    WHERE objective_id = :objectiveId
                      AND assess_item_id = :assessItemId
                    LIMIT 1
                    """, new MapSqlParameterSource()
                    .addValue("objectiveId", objectiveId)
                    .addValue("assessItemId", assessItemId), rs -> rs.next() ? rs.getDouble("contribution_weight") : 0D);
                values.put(String.valueOf(assessItemId), round2(weight == null ? 0D : weight));
            }
            return map("objectiveId", objectiveId, "values", values);
        }).toList();
    }

    private void createDefaultParseDrafts(long parseTaskId, long courseId) {
        Map<String, Object> course = catalogCourses().stream()
            .filter(item -> longValue(item.get("id")) == courseId)
            .findFirst()
            .orElse(map("name", "课程"));

        List<Map<String, Object>> drafts = List.of(
            map("code", "OBJ-1", "content", "掌握《" + course.get("name") + "》的核心理论框架与基础知识。", "type", 1, "weight", 40, "level", "HIGH"),
            map("code", "OBJ-2", "content", "具备分析并解决《" + course.get("name") + "》实际问题的能力。", "type", 2, "weight", 35, "level", "MEDIUM"),
            map("code", "OBJ-3", "content", "在学习过程中形成规范意识、协作意识与持续改进意识。", "type", 3, "weight", 25, "level", "LOW")
        );

        int sortOrder = 1;
        for (Map<String, Object> draft : drafts) {
            jdbcTemplate.update("""
                INSERT INTO parse_objective_draft (
                    parse_task_id, obj_code_suggest, obj_content_suggest, obj_type_suggest, weight_suggest,
                    obj_content_final, obj_type_final, weight_final, confidence_score, confidence_level,
                    original_text, is_confirmed, sort_order
                ) VALUES (
                    :parseTaskId, :objCode, :objContent, :objType, :weight,
                    :objContent, :objType, :weight, :confidenceScore, :confidenceLevel,
                    :originalText, 0, :sortOrder
                )
                """, new MapSqlParameterSource()
                .addValue("parseTaskId", parseTaskId)
                .addValue("objCode", draft.get("code"))
                .addValue("objContent", draft.get("content"))
                .addValue("objType", draft.get("type"))
                .addValue("weight", draft.get("weight"))
                .addValue("confidenceScore", "HIGH".equals(draft.get("level")) ? 0.93 : "MEDIUM".equals(draft.get("level")) ? 0.78 : 0.61)
                .addValue("confidenceLevel", draft.get("level"))
                .addValue("originalText", draft.get("content"))
                .addValue("sortOrder", sortOrder++));
        }
    }

    private void ensureDefaultAssessItems(long outlineId) {
        if (!assessItemListByOutline(outlineId).isEmpty()) {
            return;
        }
        List<Map<String, Object>> items = List.of(
            map("name", "Regular Grade", "type", "normal", "weight", 30),
            map("name", "Mid Exam", "type", "mid", "weight", 30),
            map("name", "Final Exam", "type", "final", "weight", 40)
        );
        int sortOrder = 1;
        for (Map<String, Object> item : items) {
            jdbcTemplate.update("""
                INSERT INTO assess_item (
                    outline_id, item_name, item_type, weight, max_score, sort_order
                ) VALUES (
                    :outlineId, :itemName, :itemType, :weight, 100, :sortOrder
                )
                """, new MapSqlParameterSource()
                .addValue("outlineId", outlineId)
                .addValue("itemName", item.get("name"))
                .addValue("itemType", item.get("type"))
                .addValue("weight", item.get("weight"))
                .addValue("sortOrder", sortOrder++));
        }
    }

    private long firstAssessItemId(long courseId, String semesterCode) {
        Long outlineId = findOutlineId(courseId, normalizeSemester(semesterCode));
        if (outlineId == null) {
            outlineId = ensureOutline(courseId, normalizeSemester(semesterCode));
            ensureDefaultAssessItems(outlineId);
        }
        List<Map<String, Object>> items = assessItemListByOutline(outlineId);
        if (items.isEmpty()) {
            ensureDefaultAssessItems(outlineId);
            items = assessItemListByOutline(outlineId);
        }
        if (items.isEmpty()) {
            throw new ApiException(UNPROCESSABLE_STATUS, 400, "assessment items not found");
        }
        return longValue(items.get(0).get("id"));
    }

    private void seedGradePreviewRows(long batchId, long courseId, long assessItemId, long semesterId, long teacherId) {
        for (int i = 1; i <= 12; i++) {
            boolean valid = i <= 10;
            double score = valid ? 60 + (i * 3 % 35) : 0;
            jdbcTemplate.update("""
                INSERT INTO student_grade (
                    course_id, assess_item_id, semester_id, import_batch_id, student_no, student_name,
                    score, max_score, valid_flag, error_message, created_by
                ) VALUES (
                    :courseId, :assessItemId, :semesterId, :batchId, :studentNo, :studentName,
                    :score, 100, :validFlag, :errorMessage, :createdBy
                )
                """, new MapSqlParameterSource()
                .addValue("courseId", courseId)
                .addValue("assessItemId", assessItemId)
                .addValue("semesterId", semesterId)
                .addValue("batchId", batchId)
                .addValue("studentNo", "20240" + String.format("%03d", i))
                .addValue("studentName", "Student-" + i)
                .addValue("score", round2(score))
                .addValue("validFlag", valid ? 1 : 0)
                .addValue("errorMessage", valid ? null : "score out of range")
                .addValue("createdBy", teacherId));
        }
    }

    private Map<String, Object> currentCalcRule() {
        return requireMap("""
            SELECT id, calc_method, threshold_value, pass_threshold
            FROM calc_rule
            WHERE is_default = 1
            ORDER BY id DESC
            LIMIT 1
            """, new MapSqlParameterSource());
    }

    private long ensureCalcRule(String calcMethod, double thresholdValue, double passThreshold) {
        Map<String, Object> current = currentCalcRule();
        jdbcTemplate.update("UPDATE calc_rule SET is_default = 0 WHERE id = :id", params("id", longValue(current.get("id"))));
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
            INSERT INTO calc_rule (
                rule_name, calc_method, threshold_value, pass_threshold, config_json, is_default, status
            ) VALUES (
                :ruleName, :calcMethod, :thresholdValue, :passThreshold, :configJson, 1, 1
            )
            """, new MapSqlParameterSource()
            .addValue("ruleName", "Dynamic Rule " + LocalDateTime.now().format(TIME_FORMATTER))
            .addValue("calcMethod", calcMethod)
            .addValue("thresholdValue", round4(thresholdValue))
            .addValue("passThreshold", round4(passThreshold))
            .addValue("configJson", writeJson(Map.of("source", "runtime"))), keyHolder);
        return keyHolder.getKey().longValue();
    }

    private Map<Long, Double> assessItemAverageRate(long outlineId, long semesterId) {
        List<Map<String, Object>> items = assessItemListByOutline(outlineId);
        Map<Long, Double> rates = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            long assessItemId = longValue(item.get("id"));
            Double avg = jdbcTemplate.query("""
                SELECT COALESCE(AVG(score / NULLIF(max_score, 0)), 0)
                FROM student_grade
                WHERE assess_item_id = :assessItemId
                  AND semester_id = :semesterId
                  AND valid_flag = 1
                """, new MapSqlParameterSource()
                .addValue("assessItemId", assessItemId)
                .addValue("semesterId", semesterId), rs -> rs.next() ? rs.getDouble(1) : 0D);
            rates.put(assessItemId, round4(avg == null ? 0D : avg));
        }
        return rates;
    }

    private List<Map<String, Object>> mappingWeights(long objectiveId) {
        return jdbcTemplate.query("""
            SELECT
                m.assess_item_id,
                m.contribution_weight,
                ai.item_type
            FROM obj_assess_map m
            JOIN assess_item ai ON ai.id = m.assess_item_id
            WHERE m.objective_id = :objectiveId
            ORDER BY m.id ASC
            """, params("objectiveId", objectiveId), (rs, rowNum) -> map(
            "assessItemId", rs.getLong("assess_item_id"),
            "contributionWeight", rs.getBigDecimal("contribution_weight"),
            "itemType", defaultString(rs.getString("item_type"), "")
        ));
    }

    private List<Map<String, Object>> achievementResults(long courseId, Long semesterId, boolean keepNumbers) {
        if (semesterId == null) {
            return List.of();
        }
        return jdbcTemplate.query("""
            SELECT
                ar.objective_id,
                t.obj_code,
                ar.normal_score,
                ar.mid_score,
                ar.final_score,
                ar.achieve_value,
                ar.is_achieved
            FROM achieve_result ar
            JOIN teach_objective t ON t.id = ar.objective_id
            WHERE ar.course_id = :courseId
              AND ar.semester_id = :semesterId
              AND ar.status = 1
              AND ar.objective_id IS NOT NULL
            ORDER BY t.sort_order ASC, t.id ASC
            """, new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId), (rs, rowNum) -> map(
            "objectiveId", rs.getLong("objective_id"),
            "objCode", rs.getString("obj_code"),
            "normal", keepNumbers ? round4(rs.getDouble("normal_score")) : rs.getBigDecimal("normal_score"),
            "mid", keepNumbers ? round4(rs.getDouble("mid_score")) : rs.getBigDecimal("mid_score"),
            "final", keepNumbers ? round4(rs.getDouble("final_score")) : rs.getBigDecimal("final_score"),
            "achieveValue", keepNumbers ? round4(rs.getDouble("achieve_value")) : rs.getBigDecimal("achieve_value"),
            "isAchieved", rs.getInt("is_achieved") == 1
        ));
    }

    private Map<String, Object> achievementOverall(long courseId, Long semesterId) {
        if (semesterId == null) {
            return map("achieve_value", 0D, "calc_time", "");
        }
        return jdbcTemplate.query("""
            SELECT achieve_value, calc_time
            FROM achieve_result
            WHERE course_id = :courseId
              AND semester_id = :semesterId
              AND objective_id IS NULL
              AND status = 1
            ORDER BY calc_time DESC, id DESC
            LIMIT 1
            """, new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId), rs -> rs.next() ? map(
            "achieve_value", rs.getBigDecimal("achieve_value"),
            "calc_time", formatTime(rs.getTimestamp("calc_time"))
        ) : map("achieve_value", 0D, "calc_time", ""));
    }

    private int generateSuggestions(long courseId, long semesterId, double thresholdValue) {
        jdbcTemplate.update("""
            DELETE FROM intelligent_suggestion
            WHERE course_id = :courseId
              AND semester_id = :semesterId
            """, new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId));

        long teacherId = resolveTeacherId(courseId, semesterId);
        int created = 0;

        List<Map<String, Object>> objectiveResults = achievementResults(courseId, semesterId, true);
        for (Map<String, Object> item : objectiveResults) {
            double achieveValue = doubleValue(item.get("achieveValue"));
            if (achieveValue >= thresholdValue) {
                continue;
            }
            Long objectiveId = nullableLong(item.get("objectiveId"));
            String ruleCode = "R01";
            Long ruleId = findRuleId(ruleCode);
            jdbcTemplate.update("""
                INSERT INTO intelligent_suggestion (
                    receiver_user_id, course_id, objective_id, semester_id, rule_id, rule_code,
                    priority, title, suggestion_text, data_basis_json, is_read, is_dismissed
                ) VALUES (
                    :receiverUserId, :courseId, :objectiveId, :semesterId, :ruleId, :ruleCode,
                    1, :title, :suggestionText, :dataBasisJson, 0, 0
                )
                """, new MapSqlParameterSource()
                .addValue("receiverUserId", teacherId)
                .addValue("courseId", courseId)
                .addValue("objectiveId", objectiveId)
                .addValue("semesterId", semesterId)
                .addValue("ruleId", ruleId)
                .addValue("ruleCode", ruleCode)
                .addValue("title", item.get("objCode") + " 达成度低于阈值")
                .addValue("suggestionText", item.get("objCode") + " 的达成度低于设定阈值，建议针对薄弱环节强化教学支持。")
                .addValue("dataBasisJson", writeJson(map(
                    "threshold", thresholdValue,
                    "breakdown", List.of(
                        map("label", "平时成绩", "value", round4(doubleValue(item.get("normal")))),
                        map("label", "期中成绩", "value", round4(doubleValue(item.get("mid")))),
                        map("label", "期末成绩", "value", round4(doubleValue(item.get("final"))))
                    ),
                    "histDetail", getTrendData(courseId, objectiveId)
                ))));
            created++;
        }

        Map<String, Object> overall = achievementOverall(courseId, semesterId);
        if (doubleValue(overall.get("achieve_value")) >= 0.85D) {
            String ruleCode = "R07";
            Long ruleId = findRuleId(ruleCode);
            jdbcTemplate.update("""
                INSERT INTO intelligent_suggestion (
                    receiver_user_id, course_id, objective_id, semester_id, rule_id, rule_code,
                    priority, title, suggestion_text, data_basis_json, is_read, is_dismissed
                ) VALUES (
                    :receiverUserId, :courseId, NULL, :semesterId, :ruleId, :ruleCode,
                    3, :title, :suggestionText, :dataBasisJson, 0, 0
                )
                """, new MapSqlParameterSource()
                .addValue("receiverUserId", teacherId)
                .addValue("courseId", courseId)
                .addValue("semesterId", semesterId)
                .addValue("ruleId", ruleId)
                .addValue("ruleCode", ruleCode)
                .addValue("title", "整体教学效果良好")
                .addValue("suggestionText", "课程整体达成情况较好，建议总结当前做法并进行经验分享。")
                .addValue("dataBasisJson", writeJson(map("histDetail", getTrendData(courseId, null)))));
            created++;
        }
        return created;
    }

    private Long findRuleId(String ruleCode) {
        return jdbcTemplate.query("""
            SELECT id
            FROM suggestion_rule
            WHERE rule_code = :ruleCode
            LIMIT 1
            """, params("ruleCode", ruleCode), rs -> rs.next() ? rs.getLong("id") : null);
    }

    private List<String> buildSuggestionSummary(long courseId, Long semesterId) {
        if (semesterId == null) {
            return List.of("所选学期暂未生成达成度核算结果。");
        }
        List<String> suggestions = jdbcTemplate.query("""
            SELECT title
            FROM intelligent_suggestion
            WHERE course_id = :courseId
              AND semester_id = :semesterId
              AND is_dismissed = 0
            ORDER BY priority ASC, id DESC
            LIMIT 3
            """, new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId), (rs, rowNum) -> rs.getString("title"));
        if (suggestions.isEmpty()) {
            return List.of("当前尚未触发预警。可在完成成绩导入后运行达成度核算。");
        }
        return suggestions;
    }

    private Map<String, Object> getImprovementMeasureById(long id) {
        return requireMap("""
            SELECT
                im.id,
                im.suggestion_id,
                im.course_id,
                im.objective_id,
                bs.semester_code,
                im.problem_desc,
                im.measure_content,
                im.expected_effect,
                im.actual_effect,
                im.owner_name,
                im.deadline,
                im.status,
                im.effect_summary,
                im.updated_at,
                s.rule_code
            FROM improve_measure im
            JOIN base_semester bs ON bs.id = im.semester_id
            LEFT JOIN intelligent_suggestion s ON s.id = im.suggestion_id
            WHERE im.id = :id
            """, params("id", id), rs -> map(
            "id", rs.getLong("id"),
            "suggestionId", nullableLong(rs.getObject("suggestion_id")),
            "courseId", rs.getLong("course_id"),
            "objectiveId", nullableLong(rs.getObject("objective_id")),
            "semester", rs.getString("semester_code"),
            "problemDesc", rs.getString("problem_desc"),
            "measureContent", rs.getString("measure_content"),
            "expectedEffect", defaultString(rs.getString("expected_effect"), ""),
            "actualEffect", defaultString(rs.getString("actual_effect"), ""),
            "owner", defaultString(rs.getString("owner_name"), ""),
            "deadline", rs.getDate("deadline") == null ? "" : rs.getDate("deadline").toString(),
            "status", statusText(rs.getInt("status")),
            "effectSummary", defaultString(rs.getString("effect_summary"), ""),
            "linkedRuleCode", defaultString(rs.getString("rule_code"), ""),
            "updatedAt", formatTime(rs.getTimestamp("updated_at"))
        ));
    }

    private List<Map<String, Object>> defaultStudentDimensions() {
        return List.of(
            map("key", "goal_clarity", "label", "目标清晰度", "score", 0),
            map("key", "teaching_match", "label", "教学匹配度", "score", 0),
            map("key", "assessment_fairness", "label", "考核公平性", "score", 0)
        );
    }

    private String teacherName(long teacherId) {
        return queryString("SELECT real_name FROM sys_user WHERE id = :id", params("id", teacherId));
    }

    private String priorityLabel(int priority) {
        return switch (priority) {
            case 1 -> "高";
            case 2 -> "中";
            default -> "低";
        };
    }

    private String objTypeName(int type) {
        return switch (type) {
            case 1 -> "知识";
            case 2 -> "能力";
            default -> "素养";
        };
    }

    private int pointTypeCode(Object typeLabel) {
        String text = stringValue(typeLabel);
        if (text.contains("Ability") || text.contains("能力")) {
            return 2;
        }
        if (text.contains("Quality") || text.contains("素养")) {
            return 3;
        }
        return 1;
    }

    private String pointTypeName(int type) {
        return switch (type) {
            case 1 -> "知识点";
            case 2 -> "能力点";
            default -> "素养点";
        };
    }

    private int statusCode(String status) {
        return switch (defaultString(status, "PLANNED")) {
            case "IN_PROGRESS" -> 1;
            case "DONE" -> 2;
            default -> 0;
        };
    }

    private String statusText(int status) {
        return switch (status) {
            case 1 -> "IN_PROGRESS";
            case 2 -> "DONE";
            default -> "PLANNED";
        };
    }

    private void appendTextFilter(StringBuilder sql, MapSqlParameterSource params, String key, String value, String expression) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        sql.append(" AND ").append(expression).append(' ');
        params.addValue(key, value);
    }

    private MapSqlParameterSource params(String key, Object value) {
        return new MapSqlParameterSource(key, value);
    }

    private long count(String sql) {
        return count(sql, new MapSqlParameterSource());
    }

    private long count(String sql, MapSqlParameterSource params) {
        Long value = jdbcTemplate.query(sql, params, rs -> rs.next() ? rs.getLong(1) : 0L);
        return value == null ? 0L : value;
    }

    private long queryLong(String sql, MapSqlParameterSource params) {
        Long value = jdbcTemplate.query(sql, params, rs -> rs.next() ? rs.getLong(1) : null);
        if (value == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, 404, "记录不存在");
        }
        return value;
    }

    private double queryDouble(String sql, MapSqlParameterSource params) {
        Double value = jdbcTemplate.query(sql, params, rs -> rs.next() ? rs.getDouble(1) : 0D);
        return value == null ? 0D : value;
    }

    private String queryString(String sql, MapSqlParameterSource params) {
        return jdbcTemplate.query(sql, params, rs -> rs.next() ? defaultString(rs.getString(1), "") : "");
    }

    private Map<String, Object> requireMap(String sql, MapSqlParameterSource params) {
        return requireMap(sql, params, this::rowToMap);
    }

    private Map<String, Object> requireMap(String sql, MapSqlParameterSource params, RowExtractor extractor) {
        return queryOptional(sql, params, extractor)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, 404, "记录不存在"));
    }

    private Optional<Map<String, Object>> queryOptional(String sql, MapSqlParameterSource params, RowExtractor extractor) {
        return jdbcTemplate.query(sql, params, rs -> rs.next() ? Optional.of(extractor.extract(rs)) : Optional.empty());
    }

    private Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        int columnCount = rs.getMetaData().getColumnCount();
        for (int i = 1; i <= columnCount; i++) {
            row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
        }
        return row;
    }

    private Map<String, Object> readMap(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(String.valueOf(value), MAP_TYPE);
        } catch (Exception error) {
            return new LinkedHashMap<>();
        }
    }

    private List<String> readStringList(String value) {
        if (!StringUtils.hasText(value)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(value, STRING_LIST_TYPE);
        } catch (Exception error) {
            return new ArrayList<>();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            return "{}";
        }
    }

    private String formatTime(Timestamp value) {
        return value == null ? "" : value.toLocalDateTime().format(TIME_FORMATTER);
    }

    private String formatNumber(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private Map<String, Object> map(Object... values) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMap(Object value) {
        if (value == null) {
            return new ArrayList<>();
        }
        return ((List<?>) value).stream()
            .map(item -> item == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<>((Map<String, Object>) item))
            .collect(Collectors.toCollection(ArrayList::new));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapOfObject(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>((Map<String, Object>) value);
    }

    private List<String> listOfString(Object value) {
        if (value == null) {
            return new ArrayList<>();
        }
        return ((List<?>) value).stream().map(String::valueOf).collect(Collectors.toCollection(ArrayList::new));
    }

    private int listSize(Object value) {
        if (value instanceof List<?> list) {
            return list.size();
        }
        return 0;
    }

    private long longValue(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
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

    private double doubleValue(Object value) {
        if (value == null) {
            return 0D;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.doubleValue();
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String text = String.valueOf(value).replace("%", "").trim();
        if (!StringUtils.hasText(text) || "null".equalsIgnoreCase(text)) {
            return 0D;
        }
        return Double.parseDouble(text);
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

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String defaultString(Object value, String defaultValue) {
        String text = value == null ? "" : String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : defaultValue;
    }

    private double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private double round4(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private LocalDate parseDate(Object value) {
        String text = defaultString(value, "");
        return StringUtils.hasText(text) ? LocalDate.parse(text) : null;
    }

    @FunctionalInterface
    private interface RowExtractor {
        Map<String, Object> extract(ResultSet rs) throws SQLException;
    }
}


