package com.example.coa.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
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
import org.springframework.web.multipart.MultipartFile;

import com.example.coa.common.ApiException;
import com.example.coa.security.AuthenticatedUser;
import com.example.coa.service.parse.OutlineParseEngine;
import com.example.coa.service.parse.OutlineParseEngine.AssessItemDraftSuggestion;
import com.example.coa.service.parse.OutlineParseEngine.CourseInfo;
import com.example.coa.service.parse.OutlineParseEngine.ObjectiveAssessMappingSuggestion;
import com.example.coa.service.parse.OutlineParseEngine.ObjectiveDraftSuggestion;
import com.example.coa.service.parse.OutlineParseEngine.ParsedOutlineDraft;
import com.example.coa.service.parse.OutlineParseEngine.SourceSegment;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional
public class InMemoryCoaService {

    private static final Logger log = LoggerFactory.getLogger(InMemoryCoaService.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> MAP_LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};
    private static final HttpStatus UNPROCESSABLE_STATUS = HttpStatus.UNPROCESSABLE_CONTENT;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JdbcTemplate plainJdbcTemplate;
    private final ObjectMapper objectMapper;
    private final OutlineParseEngine outlineParseEngine;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public InMemoryCoaService(
        NamedParameterJdbcTemplate jdbcTemplate,
        JdbcTemplate plainJdbcTemplate,
        ObjectMapper objectMapper,
        OutlineParseEngine outlineParseEngine
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.plainJdbcTemplate = plainJdbcTemplate;
        this.objectMapper = objectMapper;
        this.outlineParseEngine = outlineParseEngine;
        ensureParseImportSchema();
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

        List<Map<String, Object>> quickLinks = List.of(
            map("label", "教学目标管理", "route", "/objectives/list"),
            map("label", "数据采集", "route", "/collect/grades"),
            map("label", "达成度核算与报告", "route", "/analysis/calculation")
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

    public Map<String, Object> uploadParseFile(Long courseId, String semester, MultipartFile file, Long outlineId) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, 400, "请先上传课程大纲文件。");
        }
        if (file.getSize() > 20L * 1024 * 1024) {
            throw new ApiException(UNPROCESSABLE_STATUS, 400, "上传文件不能超过20MB。");
        }

        String sourceFileName = defaultString(file.getOriginalFilename(), "course-outline.docx");
        validateParseFileName(sourceFileName);

        long currentCourseId = normalizeCourseId(courseId);
        String currentSemester = normalizeSemester(semester);
        long semesterId = requireSemesterId(currentSemester);
        long teacherId = resolveTeacherId(currentCourseId, semesterId);
        long currentOutlineId = outlineId == null ? ensureOutline(currentCourseId, currentSemester) : outlineId;
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
            .addValue("outlineId", currentOutlineId)
            .addValue("fileName", sourceFileName), keyHolder);
        long parseTaskId = keyHolder.getKey().longValue();

        try {
            ParsedOutlineDraft parsed = outlineParseEngine.parse(sourceFileName, file.getBytes());
            persistParsedOutlineDraft(parseTaskId, parsed);
            persistParsedCourseInfo(parseTaskId, parsed.courseInfo());
            persistParsedMappingInfo(parseTaskId, parsed.mappings());
            persistParsedMappingMatrix(parseTaskId, parsed.objAssessMatrix());
            refreshParseTaskCounts(parseTaskId);
            jdbcTemplate.update("""
                UPDATE parse_task
                SET status = 'DONE',
                    finished_at = NOW(),
                    updated_at = NOW(),
                    error_code = NULL,
                    error_message = NULL
                WHERE id = :id
                """, params("id", parseTaskId));
            return map(
                "taskId", taskNo,
                "status", "PARSING",
                "objExtractCount", parsed.objectives().size(),
                "assessExtractCount", parsed.assessItems().size(),
                "mappingExtractCount", parsed.mappings().size(),
                "existingObjectiveCount", count("SELECT COUNT(*) FROM teach_objective WHERE outline_id = " + currentOutlineId),
                "existingAssessCount", count("SELECT COUNT(*) FROM assess_item WHERE outline_id = " + currentOutlineId),
                "parsedCourse", courseInfoToMap(parsed.courseInfo())
            );
        } catch (IOException exception) {
            markParseTaskFailed(parseTaskId, "FILE_READ_ERROR", "课程大纲文件读取失败，请重新上传后重试。");
            throw new ApiException(HttpStatus.BAD_REQUEST, 400, "课程大纲文件读取失败，请重新上传后重试。");
        } catch (IllegalArgumentException exception) {
            markParseTaskFailed(parseTaskId, "PARSE_ERROR", exception.getMessage());
            return map("taskId", taskNo, "status", "FAILED", "message", exception.getMessage());
        } catch (RuntimeException exception) {
            markParseTaskFailed(parseTaskId, "PARSE_ERROR", defaultString(exception.getMessage(), "智能解析失败，请稍后重试。"));
            throw exception;
        }
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
                pt.parsed_course_json,
                pt.parsed_mapping_json,
                pt.obj_assess_matrix_json,
                pt.error_message,
                bs.semester_code,
                pt.created_at
            FROM parse_task pt
            JOIN base_semester bs ON bs.id = pt.semester_id
            WHERE pt.task_no = :taskNo
            """, params("taskNo", taskId));
        long parseTaskId = longValue(taskRow.get("id"));
        List<Map<String, Object>> objectives = listParseObjectives(parseTaskId);
        List<Map<String, Object>> assessItems = listParseAssessItems(parseTaskId);
        long courseId = longValue(taskRow.get("course_id"));
        long outlineId = longValue(taskRow.get("outline_id"));

        return map(
            "taskId", taskRow.get("task_no"),
            "courseId", courseId,
            "outlineId", outlineId,
            "semester", taskRow.get("semester_code"),
            "fileName", taskRow.get("source_file_name"),
            "status", taskRow.get("status"),
            "errorMessage", taskRow.get("error_message"),
            "objExtractCount", taskRow.get("obj_extract_count"),
            "assessExtractCount", taskRow.get("assess_extract_count"),
            "parsedCourse", readMap(taskRow.get("parsed_course_json")),
            "mappingSuggestions", readMapList(taskRow.get("parsed_mapping_json")),
            "mappingMatrix", readMap(taskRow.get("obj_assess_matrix_json")),
            "currentCourse", getCourseMetaById(courseId),
            "objectiveWeightSummary", buildParseWeightSummary(objectives),
            "assessWeightSummary", buildParseWeightSummary(assessItems),
            "existingObjectiveCount", count("SELECT COUNT(*) FROM teach_objective WHERE outline_id = :outlineId", params("outlineId", outlineId)),
            "existingAssessCount", count("SELECT COUNT(*) FROM assess_item WHERE outline_id = :outlineId", params("outlineId", outlineId)),
            "objectives", objectives,
            "assessItems", assessItems,
            "originalSections", buildParseSourceSections(objectives, assessItems)
        );
    }

    public Map<String, Object> updateParseDraft(Long id, Map<String, Object> payload) {
        jdbcTemplate.update("""
            UPDATE parse_objective_draft
            SET obj_code_suggest = :objCode,
                obj_content_final = :content,
                obj_type_final = :objType,
                weight_final = :weight,
                grad_req_id_final = :gradReqId,
                grad_req_desc_final = :gradReqDesc,
                relation_level_final = :relationLevel,
                is_confirmed = :isConfirmed,
                updated_at = NOW()
            WHERE id = :id
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("objCode", defaultString(payload.get("objCodeSuggest"), ""))
            .addValue("content", defaultString(payload.get("objContentFinal"), ""))
            .addValue("objType", intValue(payload.get("objTypeFinal")))
            .addValue("weight", round2(doubleValue(payload.get("weightFinal"))))
            .addValue("gradReqId", defaultString(payload.get("gradReqIdFinal"), defaultString(payload.get("gradReqIdSuggest"), "")))
            .addValue("gradReqDesc", defaultString(payload.get("gradReqDescFinal"), defaultString(payload.get("gradReqDescSuggest"), "")))
            .addValue("relationLevel", defaultString(payload.get("relationLevelFinal"), defaultString(payload.get("relationLevelSuggest"), "")))
            .addValue("isConfirmed", intValue(payload.get("isConfirmed"))));
        return map("success", true);
    }

    public Map<String, Object> createParseDraft(String taskId, Map<String, Object> payload) {
        long parseTaskId = resolveParseTaskId(taskId);
        int nextSortOrder = nextSortOrder("parse_objective_draft", "parse_task_id", parseTaskId);
        jdbcTemplate.update("""
            INSERT INTO parse_objective_draft (
                parse_task_id, obj_code_suggest, obj_content_suggest, obj_type_suggest, weight_suggest,
                grad_req_id_suggest, grad_req_desc_suggest, relation_level_suggest,
                obj_content_final, obj_type_final, weight_final, confidence_score, confidence_level,
                grad_req_id_final, grad_req_desc_final, relation_level_final,
                original_text, is_confirmed, sort_order
            ) VALUES (
                :parseTaskId, :objCode, :content, :objType, :weight,
                :gradReqId, :gradReqDesc, :relationLevel,
                :content, :objType, :weight, :confidenceScore, :confidenceLevel,
                :gradReqId, :gradReqDesc, :relationLevel,
                :originalText, :isConfirmed, :sortOrder
            )
            """, new MapSqlParameterSource()
            .addValue("parseTaskId", parseTaskId)
            .addValue("objCode", defaultString(payload.get("objCodeSuggest"), "OBJ-" + nextSortOrder))
            .addValue("content", defaultString(payload.get("objContentFinal"), ""))
            .addValue("objType", defaultInt(payload.get("objTypeFinal"), 1))
            .addValue("weight", round2(defaultDouble(payload.get("weightFinal"), 0D)))
            .addValue("gradReqId", defaultString(payload.get("gradReqIdFinal"), ""))
            .addValue("gradReqDesc", defaultString(payload.get("gradReqDescFinal"), ""))
            .addValue("relationLevel", defaultString(payload.get("relationLevelFinal"), ""))
            .addValue("confidenceScore", round4(defaultDouble(payload.get("confidenceScore"), 0.5D)))
            .addValue("confidenceLevel", defaultString(payload.get("confidenceLevel"), "LOW"))
            .addValue("originalText", defaultString(payload.get("originalText"), "教师手动补充"))
            .addValue("isConfirmed", defaultInt(payload.get("isConfirmed"), 1))
            .addValue("sortOrder", nextSortOrder));
        refreshParseTaskCounts(parseTaskId);
        return map("success", true);
    }

    public Map<String, Object> deleteParseDraft(Long id) {
        Long parseTaskId = jdbcTemplate.query("""
            SELECT parse_task_id
            FROM parse_objective_draft
            WHERE id = :id
            """, params("id", id), rs -> rs.next() ? rs.getLong("parse_task_id") : null);
        int affected = jdbcTemplate.update("DELETE FROM parse_objective_draft WHERE id = :id", params("id", id));
        if (affected == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, 404, "目标草稿不存在");
        }
        if (parseTaskId != null) {
            refreshParseTaskCounts(parseTaskId);
        }
        return map("success", true);
    }

    public Map<String, Object> updateParseAssessDraft(Long id, Map<String, Object> payload) {
        jdbcTemplate.update("""
            UPDATE parse_assess_item_draft
            SET item_name_final = :itemName,
                item_type_final = :itemType,
                weight_final = :weight,
                is_confirmed = :isConfirmed,
                updated_at = NOW()
            WHERE id = :id
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("itemName", defaultString(payload.get("itemNameFinal"), ""))
            .addValue("itemType", defaultString(payload.get("itemTypeFinal"), "normal"))
            .addValue("weight", round2(doubleValue(payload.get("weightFinal"))))
            .addValue("isConfirmed", intValue(payload.get("isConfirmed"))));
        return map("success", true);
    }

    public Map<String, Object> createParseAssessDraft(String taskId, Map<String, Object> payload) {
        long parseTaskId = resolveParseTaskId(taskId);
        int nextSortOrder = nextSortOrder("parse_assess_item_draft", "parse_task_id", parseTaskId);
        jdbcTemplate.update("""
            INSERT INTO parse_assess_item_draft (
                parse_task_id, item_name_suggest, item_type_suggest, weight_suggest,
                item_name_final, item_type_final, weight_final, confidence_score, confidence_level,
                original_text, is_confirmed, sort_order
            ) VALUES (
                :parseTaskId, :itemName, :itemType, :weight,
                :itemName, :itemType, :weight, :confidenceScore, :confidenceLevel,
                :originalText, :isConfirmed, :sortOrder
            )
            """, new MapSqlParameterSource()
            .addValue("parseTaskId", parseTaskId)
            .addValue("itemName", defaultString(payload.get("itemNameFinal"), "考核项" + nextSortOrder))
            .addValue("itemType", defaultString(payload.get("itemTypeFinal"), "normal"))
            .addValue("weight", round2(defaultDouble(payload.get("weightFinal"), 0D)))
            .addValue("confidenceScore", round4(defaultDouble(payload.get("confidenceScore"), 0.5D)))
            .addValue("confidenceLevel", defaultString(payload.get("confidenceLevel"), "LOW"))
            .addValue("originalText", defaultString(payload.get("originalText"), "教师手动补充"))
            .addValue("isConfirmed", defaultInt(payload.get("isConfirmed"), 1))
            .addValue("sortOrder", nextSortOrder));
        refreshParseTaskCounts(parseTaskId);
        return map("success", true);
    }

    public Map<String, Object> deleteParseAssessDraft(Long id) {
        Long parseTaskId = jdbcTemplate.query("""
            SELECT parse_task_id
            FROM parse_assess_item_draft
            WHERE id = :id
            """, params("id", id), rs -> rs.next() ? rs.getLong("parse_task_id") : null);
        int affected = jdbcTemplate.update("DELETE FROM parse_assess_item_draft WHERE id = :id", params("id", id));
        if (affected == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, 404, "考核项草稿不存在");
        }
        if (parseTaskId != null) {
            refreshParseTaskCounts(parseTaskId);
        }
        return map("success", true);
    }

    public Map<String, Object> confirmParseTask(String taskId, Map<String, Object> payload) {
        Map<String, Object> taskRow = requireMap("""
            SELECT id, course_id, outline_id, semester_id, teacher_id,
                   parsed_course_json, parsed_mapping_json, obj_assess_matrix_json
            FROM parse_task
            WHERE task_no = :taskNo
            """, params("taskNo", taskId));

        long parseTaskId = longValue(taskRow.get("id"));
        boolean overwrite = booleanValue(payload.get("overwrite"));
        long semesterId = longValue(taskRow.get("semester_id"));
        long teacherId = longValue(taskRow.get("teacher_id"));
        String semesterCode = queryString("SELECT semester_code FROM base_semester WHERE id = :id", params("id", semesterId));
        long fallbackCourseId = longValue(taskRow.get("course_id"));
        Map<String, Object> parsedCourse = readMap(taskRow.get("parsed_course_json"));
        Map<String, Object> mergedCourseInfo = mergeCourseInfo(parsedCourse, mapOfObject(payload.get("courseInfo")), fallbackCourseId);
        String courseImportMode = defaultString(payload.get("courseImportMode"), "current");
        Long targetCourseId = nullableLong(payload.get("targetCourseId"));
        List<String> overwriteCourseFields = listOfString(payload.get("overwriteCourseFields"));

        long courseId;
        if ("new".equalsIgnoreCase(courseImportMode)) {
            courseId = createCourseFromParseImport(mergedCourseInfo, fallbackCourseId, semesterId, teacherId);
        } else {
            courseId = targetCourseId == null ? fallbackCourseId : targetCourseId;
            applyCourseOverrides(courseId, mergedCourseInfo, overwriteCourseFields);
        }
        ensureCourseTeacher(courseId, teacherId, semesterId);
        long outlineId = ensureOutline(courseId, semesterCode);

        List<Map<String, Object>> objectiveDrafts = jdbcTemplate.query("""
            SELECT *
            FROM parse_objective_draft
            WHERE parse_task_id = :parseTaskId
              AND is_confirmed = 1
            ORDER BY sort_order ASC, id ASC
            """, params("parseTaskId", parseTaskId), (rs, rowNum) -> map(
            "objCode", defaultString(rs.getString("obj_code_suggest"), "OBJ-" + (rowNum + 1)),
            "objContent", defaultString(rs.getString("obj_content_final"), rs.getString("obj_content_suggest")),
            "objType", rs.getObject("obj_type_final") == null ? rs.getInt("obj_type_suggest") : rs.getInt("obj_type_final"),
            "weight", rs.getObject("weight_final") == null ? rs.getBigDecimal("weight_suggest") : rs.getBigDecimal("weight_final"),
            "gradReqId", defaultString(rs.getString("grad_req_id_final"), defaultString(rs.getString("grad_req_id_suggest"), "")),
            "gradReqDesc", defaultString(rs.getString("grad_req_desc_final"), defaultString(rs.getString("grad_req_desc_suggest"), "")),
            "relationLevel", defaultString(rs.getString("relation_level_final"), defaultString(rs.getString("relation_level_suggest"), "H"))
        ));

        validateConfirmedWeightTotal(objectiveDrafts, "课程目标", true);

        List<Map<String, Object>> assessDrafts = jdbcTemplate.query("""
            SELECT *
            FROM parse_assess_item_draft
            WHERE parse_task_id = :parseTaskId
              AND is_confirmed = 1
            ORDER BY sort_order ASC, id ASC
            """, params("parseTaskId", parseTaskId), (rs, rowNum) -> map(
            "itemName", defaultString(rs.getString("item_name_final"), rs.getString("item_name_suggest")),
            "itemType", defaultString(rs.getString("item_type_final"), rs.getString("item_type_suggest")),
            "weight", rs.getObject("weight_final") == null ? rs.getBigDecimal("weight_suggest") : rs.getBigDecimal("weight_final")
        ));

        validateConfirmedWeightTotal(assessDrafts, "考核项", false);

        if (overwrite) {
            clearOutlineImportData(outlineId);
        }

        int importedObjectiveCount = upsertObjectives(outlineId, objectiveDrafts);

        if (assessDrafts.isEmpty()) {
            ensureDefaultAssessItems(outlineId);
        } else {
            upsertAssessItems(outlineId, assessDrafts);
        }
        Map<String, Object> matrixJson = readMap(taskRow.get("obj_assess_matrix_json"));
        List<Map<String, Object>> mappingList = !matrixJson.isEmpty()
            ? matrixJsonToMappingList(matrixJson, assessDrafts)
            : readMapList(taskRow.get("parsed_mapping_json"));
        int importedMappingCount = applyParsedObjectiveAssessMappings(
            outlineId,
            objectiveDrafts,
            mappingList
        );
        jdbcTemplate.update("""
            UPDATE parse_task
            SET status = 'CONFIRMED',
                overwrite_mode = :overwriteMode,
                course_id = :courseId,
                outline_id = :outlineId,
                parsed_course_json = :parsedCourseJson,
                updated_at = NOW()
            WHERE id = :id
            """, new MapSqlParameterSource()
            .addValue("id", parseTaskId)
            .addValue("overwriteMode", overwrite ? 1 : 0)
            .addValue("courseId", courseId)
            .addValue("outlineId", outlineId)
            .addValue("parsedCourseJson", writeJson(mergedCourseInfo)));

        return map(
            "courseId", courseId,
            "outlineId", outlineId,
            "courseName", defaultString(mergedCourseInfo.get("courseNameZh"), defaultString(mergedCourseInfo.get("courseName"), "")),
            "importedObjectives", importedObjectiveCount,
            "importedAssessItems", assessDrafts.isEmpty() ? assessItemListByOutline(outlineId).size() : assessDrafts.size(),
            "importedMappings", importedMappingCount
        );
    }

    public Map<String, Object> uploadGradeFile(Long courseId, Long assessItemId, String semester, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, 400, "请先上传成绩文件。");
        }
        String fileName = defaultString(file.getOriginalFilename(), "grade-import.csv");
        validateGradeFileName(fileName);

        long currentCourseId = normalizeCourseId(courseId);
        String currentSemester = normalizeSemester(semester);
        long semesterId = requireSemesterId(currentSemester);
        long teacherId = resolveTeacherId(currentCourseId, semesterId);
        List<Map<String, Object>> assessItems = currentCourseAssessItems(currentCourseId, currentSemester);
        if (assessItems.isEmpty()) {
            throw new ApiException(UNPROCESSABLE_STATUS, 400, "当前课程尚未配置考核项，请先在教学目标管理中维护考核项。");
        }
        long batchAssessItemId = assessItemId == null ? longValue(assessItems.get(0).get("id")) : assessItemId;
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
            .addValue("assessItemId", batchAssessItemId)
            .addValue("teacherId", teacherId)
            .addValue("semesterId", semesterId)
            .addValue("fileName", fileName), keyHolder);
        long batchId = keyHolder.getKey().longValue();

        try {
            GradeImportResult result = parseGradeFile(file, fileName, assessItems);
            persistGradeImportRows(batchId, currentCourseId, semesterId, teacherId, result.rows());
            markDuplicateGradeRows(batchId, currentCourseId, semesterId);
            refreshGradeBatchCounts(batchId);
            jdbcTemplate.update("""
                UPDATE grade_import_batch SET status = 'PARSED', updated_at = NOW() WHERE id = :id
                """, params("id", batchId));
            Map<String, Object> finalCounts = requireMap("""
                SELECT valid_rows, error_rows FROM grade_import_batch WHERE id = :id
                """, params("id", batchId));
            return map(
                "batchId", batchNo,
                "status", "PARSED",
                "validRows", intValue(finalCounts.get("valid_rows")),
                "errorRows", intValue(finalCounts.get("error_rows"))
            );
        } catch (IOException exception) {
            jdbcTemplate.update("""
                UPDATE grade_import_batch
                SET status = 'FAILED',
                    error_rows = 1,
                    updated_at = NOW()
                WHERE id = :id
                """, params("id", batchId));
            throw new ApiException(HttpStatus.BAD_REQUEST, 400, "成绩文件读取失败，请检查文件是否损坏。");
        }
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
                sg.id,
                sg.student_no,
                sg.student_name,
                sg.assess_item_id,
                ai.item_name,
                sg.score,
                sg.max_score,
                sg.valid_flag,
                sg.error_message
            FROM student_grade sg
            JOIN assess_item ai ON ai.id = sg.assess_item_id
            WHERE sg.import_batch_id = :batchId
            ORDER BY sg.id ASC
            """, params("batchId", longValue(batch.get("id"))), (rs, rowNum) -> map(
            "row", rowNum + 1,
            "gradeId", rs.getLong("id"),
            "studentId", rs.getString("student_no"),
            "name", rs.getString("student_name"),
            "assessItemId", rs.getLong("assess_item_id"),
            "assessItemName", rs.getString("item_name"),
            "score", rs.getBigDecimal("score"),
            "maxScore", rs.getBigDecimal("max_score"),
            "valid", rs.getInt("valid_flag") == 1,
            "errorMsg", defaultString(rs.getString("error_message"), "有效")
        ));
        List<Map<String, Object>> previewColumns = buildGradePreviewColumns(preview);
        List<Map<String, Object>> previewRows = buildGradePreviewRows(preview, previewColumns);

        return map(
            "batchId", batch.get("batch_no"),
            "fileName", batch.get("source_file_name"),
            "status", batch.get("status"),
            "validRows", batch.get("valid_rows"),
            "errorRows", batch.get("error_rows"),
            "previewColumns", previewColumns,
            "previewRows", previewRows,
            "preview", preview
        );
    }

    public Map<String, Object> updateGradePreviewRow(String batchId, Map<String, Object> payload) {
        long currentBatchId = resolveGradeBatchId(batchId);
        String studentNo = defaultString(payload.get("studentNo"), "");
        String studentName = defaultString(payload.get("studentName"), "");
        List<Map<String, Object>> cells = listOfMap(payload.get("cells"));

        for (Map<String, Object> cell : cells) {
            long gradeId = longValue(cell.get("gradeId"));
            double maxScore = defaultDouble(cell.get("maxScore"), 100D);
            ScoreParseResult score = parseScore(defaultString(cell.get("score"), ""), maxScore);
            String error = "";
            if (!StringUtils.hasText(studentNo)) {
                error = "学号不能为空";
            } else if (!StringUtils.hasText(studentName)) {
                error = "姓名不能为空";
            } else if (!score.valid()) {
                error = score.errorMessage();
            }

            jdbcTemplate.update("""
                UPDATE student_grade
                SET student_no = :studentNo,
                    student_name = :studentName,
                    score = :score,
                    valid_flag = :validFlag,
                    error_message = :errorMessage,
                    updated_at = NOW()
                WHERE id = :id
                  AND import_batch_id = :batchId
                """, new MapSqlParameterSource()
                .addValue("id", gradeId)
                .addValue("batchId", currentBatchId)
                .addValue("studentNo", studentNo)
                .addValue("studentName", studentName)
                .addValue("score", round2(score.score()))
                .addValue("validFlag", StringUtils.hasText(error) ? 0 : 1)
                .addValue("errorMessage", StringUtils.hasText(error) ? error : null));
        }

        refreshGradeBatchCounts(currentBatchId);
        return getGradeBatchPreview(batchId);
    }

    private List<Map<String, Object>> buildGradePreviewColumns(List<Map<String, Object>> preview) {
        Map<Long, Map<String, Object>> columns = new LinkedHashMap<>();
        for (Map<String, Object> item : preview) {
            long assessItemId = longValue(item.get("assessItemId"));
            if (columns.containsKey(assessItemId)) {
                continue;
            }
            columns.put(assessItemId, map(
                "assessItemId", assessItemId,
                "assessItemName", item.get("assessItemName"),
                "maxScore", item.get("maxScore")
            ));
        }
        return new ArrayList<>(columns.values());
    }

    private List<Map<String, Object>> buildGradePreviewRows(
        List<Map<String, Object>> preview,
        List<Map<String, Object>> columns
    ) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int columnCount = Math.max(columns.size(), 1);
        int rowIndex = 1;
        for (int start = 0; start < preview.size(); start += columnCount) {
            List<Map<String, Object>> items = preview.subList(start, Math.min(start + columnCount, preview.size()));
            Map<String, Object> first = items.get(0);
            Map<Long, Map<String, Object>> itemByAssessId = new LinkedHashMap<>();
            for (Map<String, Object> item : items) {
                itemByAssessId.put(longValue(item.get("assessItemId")), item);
            }

            List<Map<String, Object>> cells = new ArrayList<>();
            boolean valid = true;
            List<String> errors = new ArrayList<>();
            for (Map<String, Object> column : columns) {
                long assessItemId = longValue(column.get("assessItemId"));
                Map<String, Object> item = itemByAssessId.get(assessItemId);
                boolean cellValid = item != null && booleanValue(item.get("valid"));
                String errorMsg = item == null ? "missing grade cell" : defaultString(item.get("errorMsg"), "有效");
                if (!cellValid) {
                    valid = false;
                    errors.add(defaultString(column.get("assessItemName"), "") + "：" + errorMsg);
                }
                cells.add(map(
                    "gradeId", item == null ? null : item.get("gradeId"),
                    "assessItemId", assessItemId,
                    "assessItemName", column.get("assessItemName"),
                    "score", item == null ? "" : item.get("score"),
                    "maxScore", item == null ? column.get("maxScore") : item.get("maxScore"),
                    "valid", cellValid,
                    "errorMsg", errorMsg
                ));
            }

            rows.add(map(
                "row", rowIndex++,
                "studentNo", first.get("studentId"),
                "studentName", first.get("name"),
                "valid", valid,
                "errorMsg", valid ? "有效" : String.join("；", errors),
                "cells", cells
            ));
        }
        return rows;
    }

    private List<Map<String, Object>> gradeManageColumns(
        long courseId,
        String semester,
        String assessItemId,
        List<Map<String, Object>> importedItems
    ) {
        List<Map<String, Object>> assessItems = currentCourseAssessItems(courseId, semester);
        if (StringUtils.hasText(assessItemId)) {
            List<Map<String, Object>> matchedItems = assessItems.stream()
                .filter(item -> String.valueOf(item.get("id")).equals(assessItemId))
                .map(item -> map(
                    "assessItemId", item.get("id"),
                    "assessItemName", item.get("itemName"),
                    "maxScore", 100
                ))
                .toList();
            if (!matchedItems.isEmpty()) {
                return matchedItems;
            }
            return importedItems.stream()
                .filter(item -> String.valueOf(item.get("assessItemId")).equals(assessItemId))
                .map(item -> map("assessItemId", item.get("assessItemId"), "assessItemName", item.get("itemName"), "maxScore", item.get("maxScore")))
                .distinct()
                .toList();
        }

        if (!assessItems.isEmpty()) {
            return assessItems.stream().map(item -> map(
                "assessItemId", item.get("id"),
                "assessItemName", item.get("itemName"),
                "maxScore", 100
            )).toList();
        }

        return buildGradePreviewColumns(importedItems.stream().map(item -> map(
            "assessItemId", item.get("assessItemId"),
            "assessItemName", item.get("itemName"),
            "maxScore", item.get("maxScore")
        )).toList());
    }

    private List<Map<String, Object>> buildGradeManageRows(
        List<Map<String, Object>> preview,
        List<Map<String, Object>> columns
    ) {
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> item : preview) {
            String key = defaultString(item.get("studentId"), "") + "|" + defaultString(item.get("name"), "");
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(item);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        int rowIndex = 1;
        for (List<Map<String, Object>> items : grouped.values()) {
            Map<String, Object> first = items.get(0);
            Map<Long, Map<String, Object>> itemByAssessId = new LinkedHashMap<>();
            for (Map<String, Object> item : items) {
                itemByAssessId.put(longValue(item.get("assessItemId")), item);
            }

            List<Map<String, Object>> cells = new ArrayList<>();
            for (Map<String, Object> column : columns) {
                long assessItemId = longValue(column.get("assessItemId"));
                Map<String, Object> item = itemByAssessId.get(assessItemId);
                cells.add(map(
                    "gradeId", item == null ? null : item.get("gradeId"),
                    "assessItemId", assessItemId,
                    "assessItemName", column.get("assessItemName"),
                    "score", item == null ? "" : item.get("score"),
                    "maxScore", item == null ? column.get("maxScore") : item.get("maxScore"),
                    "valid", true,
                    "errorMsg", "有效"
                ));
            }

            rows.add(map(
                "row", rowIndex++,
                "studentNo", first.get("studentId"),
                "studentName", first.get("name"),
                "valid", true,
                "errorMsg", "有效",
                "cells", cells
            ));
        }
        return rows;
    }

    private Long findConfirmedGradeId(long courseId, long semesterId, long assessItemId, String studentNo) {
        return jdbcTemplate.query("""
            SELECT sg.id
            FROM student_grade sg
            JOIN grade_import_batch gb ON gb.id = sg.import_batch_id
            WHERE gb.status = 'CONFIRMED'
              AND sg.valid_flag = 1
              AND sg.course_id = :courseId
              AND sg.semester_id = :semesterId
              AND sg.assess_item_id = :assessItemId
              AND sg.student_no = :studentNo
            ORDER BY sg.id DESC
            LIMIT 1
            """, new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId)
            .addValue("assessItemId", assessItemId)
            .addValue("studentNo", studentNo), rs -> rs.next() ? rs.getLong("id") : null);
    }

    private long ensureManualGradeBatch(long courseId, long semesterId, long teacherId) {
        Long batchId = jdbcTemplate.query("""
            SELECT id
            FROM grade_import_batch
            WHERE course_id = :courseId
              AND semester_id = :semesterId
              AND status = 'CONFIRMED'
              AND import_mode = 'manual'
            ORDER BY id DESC
            LIMIT 1
            """, new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId), rs -> rs.next() ? rs.getLong("id") : null);
        if (batchId != null) {
            return batchId;
        }

        List<Map<String, Object>> assessItems = currentCourseAssessItems(courseId, queryString(
            "SELECT semester_code FROM base_semester WHERE id = :id",
            params("id", semesterId)
        ));
        long assessItemId = assessItems.isEmpty() ? 0L : longValue(assessItems.get(0).get("id"));
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
            INSERT INTO grade_import_batch (
                batch_no, course_id, assess_item_id, teacher_id, semester_id, source_file_name,
                status, total_rows, valid_rows, error_rows, import_mode, confirmed_at
            ) VALUES (
                :batchNo, :courseId, :assessItemId, :teacherId, :semesterId, 'manual-entry',
                'CONFIRMED', 0, 0, 0, 'manual', NOW()
            )
            """, new MapSqlParameterSource()
            .addValue("batchNo", "GRADE-MANUAL-" + System.currentTimeMillis())
            .addValue("courseId", courseId)
            .addValue("assessItemId", assessItemId)
            .addValue("teacherId", teacherId)
            .addValue("semesterId", semesterId), keyHolder);
        return keyHolder.getKey().longValue();
    }

    private long resolveGradeBatchId(String batchNo) {
        return queryLong("""
            SELECT id
            FROM grade_import_batch
            WHERE batch_no = :batchNo
            """, params("batchNo", batchNo));
    }

    private void refreshGradeBatchCounts(long batchId) {
        Map<String, Object> counts = requireMap("""
            SELECT
                COUNT(*) AS total_rows,
                SUM(CASE WHEN valid_flag = 1 THEN 1 ELSE 0 END) AS valid_rows,
                SUM(CASE WHEN valid_flag = 0 THEN 1 ELSE 0 END) AS error_rows
            FROM student_grade
            WHERE import_batch_id = :batchId
            """, params("batchId", batchId));
        jdbcTemplate.update("""
            UPDATE grade_import_batch
            SET total_rows = :totalRows,
                valid_rows = :validRows,
                error_rows = :errorRows,
                updated_at = NOW()
            WHERE id = :batchId
            """, new MapSqlParameterSource()
            .addValue("batchId", batchId)
            .addValue("totalRows", intValue(counts.get("total_rows")))
            .addValue("validRows", intValue(counts.get("valid_rows")))
            .addValue("errorRows", intValue(counts.get("error_rows"))));
    }

    private void markDuplicateGradeRows(long batchId, long courseId, long semesterId) {
        List<Map<String, Object>> batchRows = jdbcTemplate.query("""
            SELECT id, student_no, assess_item_id
            FROM student_grade
            WHERE import_batch_id = :batchId
              AND valid_flag = 1
            ORDER BY id ASC
            """, params("batchId", batchId), (rs, rowNum) -> map(
            "id", rs.getLong("id"),
            "studentNo", rs.getString("student_no"),
            "assessItemId", rs.getLong("assess_item_id")
        ));
        if (batchRows.isEmpty()) return;

        Set<String> confirmedKeys = new java.util.HashSet<>(jdbcTemplate.query("""
            SELECT sg.student_no, sg.assess_item_id
            FROM student_grade sg
            JOIN grade_import_batch gb ON gb.id = sg.import_batch_id
            WHERE sg.course_id = :courseId
              AND sg.semester_id = :semesterId
              AND sg.valid_flag = 1
              AND gb.status = 'CONFIRMED'
            """, new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId),
            (rs, rowNum) -> rs.getString("student_no") + "|" + rs.getLong("assess_item_id")
        ));

        Set<String> seenInBatch = new java.util.HashSet<>();
        for (Map<String, Object> row : batchRows) {
            String key = defaultString(row.get("studentNo"), "") + "|" + longValue(row.get("assessItemId"));
            boolean isDuplicate = confirmedKeys.contains(key) || !seenInBatch.add(key);
            if (isDuplicate) {
                jdbcTemplate.update("""
                    UPDATE student_grade
                    SET valid_flag = 0,
                        error_message = '与已有成绩重复，导入失败',
                        updated_at = NOW()
                    WHERE id = :id
                    """, params("id", longValue(row.get("id"))));
            }
        }
    }

    public Map<String, Object> confirmGradeBatch(String batchId) {
        Map<String, Object> batch = requireMap("""
            SELECT id, course_id, semester_id, valid_rows, error_rows
            FROM grade_import_batch
            WHERE batch_no = :batchNo
            """, params("batchNo", batchId));
        long id = longValue(batch.get("id"));
        jdbcTemplate.update("""
            UPDATE grade_import_batch
            SET status = 'CONFIRMED', confirmed_at = NOW(), updated_at = NOW()
            WHERE id = :id
            """, params("id", id));
        return map(
            "success", true,
            "importedCount", intValue(batch.get("valid_rows")),
            "skippedCount", intValue(batch.get("error_rows"))
        );
    }

    public Map<String, Object> getImportedGrades(Map<String, String> filters) {
        Long courseId = nullableLong(filters.get("courseId"));
        String semester = defaultString(filters.get("semester"), "");
        if (courseId == null || !StringUtils.hasText(semester)) {
            return map("items", List.of(), "columns", List.of(), "rows", List.of(), "total", 0);
        }

        Long semesterId = findSemesterId(semester);
        if (semesterId == null) {
            return map("items", List.of(), "columns", List.of(), "rows", List.of(), "total", 0);
        }

        String keyword = defaultString(filters.get("keyword"), "");
        String assessItemId = defaultString(filters.get("assessItemId"), "");

        StringBuilder sql = new StringBuilder("""
            SELECT
                sg.id,
                sg.student_no,
                sg.student_name,
                sg.score,
                sg.max_score,
                c.course_name,
                c.course_code,
                ai.id AS assess_item_id,
                ai.item_name,
                bs.semester_code,
                sg.valid_flag
            FROM student_grade sg
            JOIN grade_import_batch gb ON gb.id = sg.import_batch_id
            JOIN base_course c ON c.id = sg.course_id
            JOIN assess_item ai ON ai.id = sg.assess_item_id
            JOIN base_semester bs ON bs.id = sg.semester_id
            WHERE gb.status = 'CONFIRMED'
              AND sg.valid_flag = 1
              AND sg.course_id = :courseId
              AND sg.semester_id = :semesterId
            """);
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId);
        appendTextFilter(sql, params, "assessItemId", assessItemId, "sg.assess_item_id = :assessItemId");
        if (StringUtils.hasText(keyword)) {
            sql.append(" AND (sg.student_no LIKE :keyword OR sg.student_name LIKE :keyword)");
            params.addValue("keyword", "%" + keyword.trim() + "%");
        }
        sql.append(" ORDER BY sg.student_no ASC, ai.sort_order ASC, ai.id ASC, sg.id ASC");

        List<Map<String, Object>> items = jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> map(
            "id", rs.getLong("id"),
            "studentNo", rs.getString("student_no"),
            "studentName", rs.getString("student_name"),
            "score", rs.getBigDecimal("score"),
            "maxScore", rs.getBigDecimal("max_score"),
            "assessItemId", rs.getLong("assess_item_id"),
            "courseName", rs.getString("course_name"),
            "courseCode", rs.getString("course_code"),
            "itemName", rs.getString("item_name"),
            "semester", rs.getString("semester_code"),
            "validFlag", rs.getInt("valid_flag")
        ));

        List<Map<String, Object>> columns = gradeManageColumns(courseId, semester, assessItemId, items);
        List<Map<String, Object>> rows = buildGradeManageRows(items.stream().map(item -> map(
            "row", 0,
            "gradeId", item.get("id"),
            "studentId", item.get("studentNo"),
            "name", item.get("studentName"),
            "assessItemId", item.get("assessItemId"),
            "assessItemName", item.get("itemName"),
            "score", item.get("score"),
            "maxScore", item.get("maxScore"),
            "valid", true,
            "errorMsg", "有效"
        )).toList(), columns);

        return map("items", items, "columns", columns, "rows", rows, "total", rows.size());
    }

    public Map<String, Object> saveImportedGradeRow(Map<String, Object> payload) {
        long courseId = longValue(payload.get("courseId"));
        String semester = defaultString(payload.get("semester"), "");
        long semesterId = requireSemesterId(semester);
        long teacherId = resolveTeacherId(courseId, semesterId);
        String studentNo = defaultString(payload.get("studentNo"), "").trim();
        String studentName = defaultString(payload.get("studentName"), "").trim();
        if (!StringUtils.hasText(studentNo)) {
            throw new ApiException(UNPROCESSABLE_STATUS, 400, "学号不能为空。");
        }
        if (!StringUtils.hasText(studentName)) {
            throw new ApiException(UNPROCESSABLE_STATUS, 400, "姓名不能为空。");
        }

        long batchId = ensureManualGradeBatch(courseId, semesterId, teacherId);
        List<Map<String, Object>> cells = listOfMap(payload.get("cells"));
        for (Map<String, Object> cell : cells) {
            long assessItemId = longValue(cell.get("assessItemId"));
            double maxScore = defaultDouble(cell.get("maxScore"), 100D);
            ScoreParseResult score = parseScore(defaultString(cell.get("score"), ""), maxScore);
            if (!score.valid()) {
                throw new ApiException(UNPROCESSABLE_STATUS, 400, defaultString(cell.get("assessItemName"), "成绩") + "：" + score.errorMessage());
            }
            Long gradeId = nullableLong(cell.get("gradeId"));
            if (gradeId == null) {
                Long existingId = findConfirmedGradeId(courseId, semesterId, assessItemId, studentNo);
                if (existingId != null) {
                    gradeId = existingId;
                }
            }

            if (gradeId == null) {
                jdbcTemplate.update("""
                    INSERT INTO student_grade (
                        course_id, assess_item_id, semester_id, import_batch_id, student_no, student_name,
                        score, max_score, valid_flag, error_message, created_by
                    ) VALUES (
                        :courseId, :assessItemId, :semesterId, :batchId, :studentNo, :studentName,
                        :score, :maxScore, 1, NULL, :createdBy
                    )
                    """, new MapSqlParameterSource()
                    .addValue("courseId", courseId)
                    .addValue("assessItemId", assessItemId)
                    .addValue("semesterId", semesterId)
                    .addValue("batchId", batchId)
                    .addValue("studentNo", studentNo)
                    .addValue("studentName", studentName)
                    .addValue("score", round2(score.score()))
                    .addValue("maxScore", round2(maxScore))
                    .addValue("createdBy", teacherId));
            } else {
                jdbcTemplate.update("""
                    UPDATE student_grade
                    SET student_no = :studentNo,
                        student_name = :studentName,
                        score = :score,
                        max_score = :maxScore,
                        valid_flag = 1,
                        error_message = NULL,
                        updated_at = NOW()
                    WHERE id = :id
                      AND course_id = :courseId
                      AND semester_id = :semesterId
                    """, new MapSqlParameterSource()
                    .addValue("id", gradeId)
                    .addValue("courseId", courseId)
                    .addValue("semesterId", semesterId)
                    .addValue("studentNo", studentNo)
                    .addValue("studentName", studentName)
                    .addValue("score", round2(score.score()))
                    .addValue("maxScore", round2(maxScore)));
            }
        }
        refreshGradeBatchCounts(batchId);
        return map("success", true);
    }

    public Map<String, Object> deleteImportedGradeRow(Long courseId, String semester, String studentNo, Long assessItemId) {
        if (courseId == null || !StringUtils.hasText(semester) || !StringUtils.hasText(studentNo)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, 400, "删除成绩时必须提供课程、学期和学号。");
        }
        long semesterId = requireSemesterId(semester);
        String assessFilter = assessItemId == null ? "" : " AND sg.assess_item_id = :assessItemId";
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId)
            .addValue("studentNo", studentNo)
            .addValue("assessItemId", assessItemId);
        List<Long> batchIds = jdbcTemplate.query("""
            SELECT DISTINCT sg.import_batch_id
            FROM student_grade sg
            JOIN grade_import_batch gb ON gb.id = sg.import_batch_id
            WHERE gb.status = 'CONFIRMED'
              AND sg.course_id = :courseId
              AND sg.semester_id = :semesterId
              AND sg.student_no = :studentNo
            """ + assessFilter, params, (rs, rowNum) -> rs.getLong("import_batch_id"));
        int deleted = jdbcTemplate.update("""
            DELETE sg
            FROM student_grade sg
            JOIN grade_import_batch gb ON gb.id = sg.import_batch_id
            WHERE gb.status = 'CONFIRMED'
              AND sg.course_id = :courseId
              AND sg.semester_id = :semesterId
              AND sg.student_no = :studentNo
            """ + assessFilter, params);
        for (Long batchId : batchIds) {
            refreshGradeBatchCounts(batchId);
        }
        return map("success", true, "deletedCount", deleted);
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
        Long outlineId = findOutlineId(currentCourseId, currentSemester);

        Map<String, Object> config = currentCalcRule();
        Map<String, Object> configJson = readMap(config.get("config_json"));
        @SuppressWarnings("unchecked")
        Map<String, Object> customThresholds = configJson.get("customThresholds") instanceof Map
            ? (Map<String, Object>) configJson.get("customThresholds") : Map.of();
        boolean retakeEnabled = Boolean.TRUE.equals(configJson.get("retakeEnabled"));

        List<Map<String, Object>> results = achievementResults(currentCourseId, semesterId, false);
        Map<String, Object> overall = achievementOverall(currentCourseId, semesterId);

        List<Map<String, Object>> objectives = outlineId != null ? jdbcTemplate.query("""
            SELECT id, obj_code
            FROM teach_objective
            WHERE outline_id = :outlineId
            ORDER BY sort_order ASC, id ASC
            """, params("outlineId", outlineId), (rs, rowNum) -> map(
            "id", rs.getLong("id"),
            "objCode", rs.getString("obj_code")
        )) : List.of();

        Map<String, Object> result = referenceData();
        result.put("currentCourseId", currentCourseId);
        result.put("currentSemester", currentSemester);
        result.put("outlineId", outlineId);
        result.put("objectives", objectives);
        result.put("record", map(
            "config", map(
                "calcRuleId", config.get("id"),
                "calcMethod", config.get("calc_method"),
                "thresholdValue", config.get("threshold_value"),
                "passThreshold", config.get("pass_threshold"),
                "customThresholds", customThresholds,
                "retakeEnabled", retakeEnabled
            ),
            "generatedAt", defaultString(overall.get("calc_time"), ""),
            "overallAchievement", doubleValue(overall.get("achieve_value")),
            "results", results,
            "dataSummary", buildAchievementDataSummary(currentCourseId, semesterId, outlineId)
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
        boolean retakeEnabled = Boolean.TRUE.equals(payload.get("retakeEnabled"));
        @SuppressWarnings("unchecked")
        Map<String, Object> customThresholds = payload.get("customThresholds") instanceof Map
            ? (Map<String, Object>) payload.get("customThresholds") : Map.of();

        long calcRuleId = ensureCalcRule(calcMethod, thresholdValue, passThreshold, customThresholds, retakeEnabled);
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
        Map<Long, Map<String, Double>> itemStudentRates = perStudentItemRates(outlineId, semesterId, retakeEnabled);
        Map<Long, Double> itemAvgRates = computeAvgRates(itemStudentRates);
        double overallAchievement = 0D;

        for (Map<String, Object> objective : objectives) {
            long objectiveId = longValue(objective.get("id"));
            List<Map<String, Object>> mappings = mappingWeights(objectiveId);
            if (mappings.isEmpty()) {
                mappings = defaultMappingWeights(outlineId);
            }

            double achieveValue;
            boolean achieved;
            double normal = 0D;
            double mid = 0D;
            double fin = 0D;

            if ("threshold".equals(calcMethod)) {
                Map<String, Double> studentValues = computeStudentObjectiveValues(mappings, itemStudentRates);
                long passCount = studentValues.values().stream().filter(v -> v >= passThreshold).count();
                long total = studentValues.size();
                achieveValue = total > 0 ? round4((double) passCount / total) : 0D;
                achieved = achieveValue >= thresholdValue;
            } else {
                Map<String, Double> studentValues = computeStudentObjectiveValues(mappings, itemStudentRates);
                achieveValue = studentValues.isEmpty() ? 0D : round4(
                    studentValues.values().stream().mapToDouble(Double::doubleValue).average().orElse(0D)
                );
                double objThreshold = "custom".equals(calcMethod)
                    ? doubleValue(customThresholds.getOrDefault(String.valueOf(objectiveId), thresholdValue))
                    : thresholdValue;
                achieved = achieveValue >= objThreshold;

                for (Map<String, Object> mapping : mappings) {
                    long assessItemId = longValue(mapping.get("assessItemId"));
                    double contribution = doubleValue(mapping.get("contributionWeight")) / 100D;
                    double rate = itemAvgRates.getOrDefault(assessItemId, 0D);
                    String itemType = stringValue(mapping.get("itemType")).toLowerCase(Locale.ROOT);
                    if (itemType.contains("平时") || itemType.contains("regular") || itemType.contains("normal")) {
                        normal += rate * contribution;
                    } else if (itemType.contains("期中") || itemType.contains("mid")) {
                        mid += rate * contribution;
                    } else {
                        fin += rate * contribution;
                    }
                }
                normal = round4(normal);
                mid = round4(mid);
                fin = round4(fin);
            }

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
        return getAchievementCalculation(courseId, semesterCode);
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
            "isAllAchieved", !objectives.isEmpty() && objectives.stream().allMatch(item -> booleanValue(item.get("isAchieved"))),
            "objectives", objectives,
            "compareCourses", compareCourses,
            "radarData", map("indicators", indicators, "values", values),
            "dimensionData", buildDimensionData(objectives),
            "weakObjectives", objectives.stream()
                .filter(item -> !booleanValue(item.get("isAchieved")))
                .sorted((left, right) -> Double.compare(doubleValue(left.get("achieveValue")), doubleValue(right.get("achieveValue"))))
                .toList(),
            "dataSummary", buildAchievementDataSummary(currentCourseId, semesterId, findOutlineId(currentCourseId, currentSemester)),
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

        return map("measureId", keyHolder.getKey().longValue(), "redirectUrl", "/analysis/calculation");
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
                c.course_name_en,
                c.course_type,
                c.target_students,
                c.teaching_language,
                c.hours,
                c.credits,
                c.prerequisite_course,
                c.course_owner,
                c.college_id,
                bc.college_name,
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
            LEFT JOIN base_college bc ON bc.id = c.college_id
            WHERE c.status = 1
            ORDER BY c.id ASC
            """, (rs, rowNum) -> map(
            "id", rs.getLong("id"),
            "code", rs.getString("course_code"),
            "name", rs.getString("course_name"),
            "nameEn", defaultString(rs.getString("course_name_en"), ""),
            "courseType", defaultString(rs.getString("course_type"), ""),
            "targetStudents", defaultString(rs.getString("target_students"), ""),
            "teachingLanguage", defaultString(rs.getString("teaching_language"), ""),
            "hours", rs.getObject("hours") == null ? null : rs.getInt("hours"),
            "credits", rs.getBigDecimal("credits"),
            "prerequisiteCourse", defaultString(rs.getString("prerequisite_course"), ""),
            "courseOwner", defaultString(rs.getString("course_owner"), ""),
            "collegeId", nullableLong(rs.getObject("college_id")),
            "collegeName", defaultString(rs.getString("college_name"), ""),
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
                o.course_id,
                bs.semester_code
            FROM assess_item ai
            JOIN outline_main o ON o.id = ai.outline_id
            JOIN base_semester bs ON bs.id = o.semester_id
            ORDER BY o.course_id ASC, bs.semester_code DESC, ai.sort_order ASC, ai.id ASC
            """, (rs, rowNum) -> map(
            "id", rs.getLong("id"),
            "outlineId", rs.getLong("outline_id"),
            "courseId", rs.getLong("course_id"),
            "semester", rs.getString("semester_code"),
            "itemName", rs.getString("item_name"),
            "itemType", defaultString(rs.getString("item_type"), ""),
            "itemTypeName", assessItemTypeName(rs.getString("item_type")),
            "weight", rs.getBigDecimal("weight")
        ));
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

    private int nextSortOrder(String tableName, String foreignKeyName, long foreignKeyValue) {
        return jdbcTemplate.query("""
            SELECT COALESCE(MAX(sort_order), 0) + 1 AS next_sort
            FROM %s
            WHERE %s = :foreignKeyValue
            """.formatted(tableName, foreignKeyName), params("foreignKeyValue", foreignKeyValue), rs -> rs.next() ? rs.getInt("next_sort") : 1);
    }

    private void validateParseFileName(String fileName) {
        String lower = defaultString(fileName, "").toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".doc") || lower.endsWith(".docx") || lower.endsWith(".pdf"))) {
            throw new ApiException(UNPROCESSABLE_STATUS, 400, "仅支持上传 doc、docx 或 pdf 格式的课程大纲文件。");
        }
    }

    private void ensureParseImportSchema() {
        ensureColumn("base_course", "course_name_en",      "VARCHAR(255) NULL");
        ensureColumn("base_course", "course_type",         "VARCHAR(100) NULL");
        ensureColumn("base_course", "target_students",     "VARCHAR(255) NULL");
        ensureColumn("base_course", "teaching_language",   "VARCHAR(100) NULL");
        ensureColumn("base_course", "hours",               "INT NULL");
        ensureColumn("base_course", "credits",             "DECIMAL(4,1) NULL");
        ensureColumn("base_course", "prerequisite_course", "VARCHAR(255) NULL");
        ensureColumn("base_course", "course_owner",        "VARCHAR(100) NULL");
        ensureColumn("parse_task",  "parsed_course_json",   "TEXT NULL");
        ensureColumn("parse_task",  "parsed_mapping_json",  "TEXT NULL");
        ensureColumn("parse_task",  "obj_assess_matrix_json", "TEXT NULL");
        ensureColumn("parse_objective_draft", "grad_req_id_suggest", "VARCHAR(20) NULL");
        ensureColumn("parse_objective_draft", "grad_req_desc_suggest", "VARCHAR(500) NULL");
        ensureColumn("parse_objective_draft", "relation_level_suggest", "VARCHAR(4) NULL");
        ensureColumn("parse_objective_draft", "grad_req_id_final", "VARCHAR(20) NULL");
        ensureColumn("parse_objective_draft", "grad_req_desc_final", "VARCHAR(500) NULL");
        ensureColumn("parse_objective_draft", "relation_level_final", "VARCHAR(4) NULL");
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
        } catch (Exception e) {
            log.warn("Schema migration failed for {}.{}: {}", table, column, e.getMessage());
        }
    }

    private void persistParsedCourseInfo(long parseTaskId, CourseInfo courseInfo) {
        jdbcTemplate.update("""
            UPDATE parse_task
            SET parsed_course_json = :parsedCourseJson,
                updated_at = NOW()
            WHERE id = :id
            """, new MapSqlParameterSource()
            .addValue("id", parseTaskId)
            .addValue("parsedCourseJson", writeJson(courseInfoToMap(courseInfo))));
    }

    private void persistParsedMappingInfo(long parseTaskId, List<ObjectiveAssessMappingSuggestion> mappings) {
        List<Map<String, Object>> rows = mappings.stream()
            .map(item -> map(
                "objectiveCode", item.objectiveCode(),
                "objectiveNumber", item.objectiveNumber(),
                "assessItemName", item.assessItemName(),
                "assessItemType", item.assessItemType(),
                "contributionWeight", item.contributionWeight(),
                "confidenceScore", item.confidenceScore(),
                "confidenceLevel", item.confidenceLevel(),
                "originalText", item.originalText()
            ))
            .toList();
        jdbcTemplate.update("""
            UPDATE parse_task
            SET parsed_mapping_json = :parsedMappingJson,
                updated_at = NOW()
            WHERE id = :id
            """, new MapSqlParameterSource()
            .addValue("id", parseTaskId)
            .addValue("parsedMappingJson", writeJson(rows)));
    }

    private void persistParsedMappingMatrix(long parseTaskId, Object matrix) {
        if (matrix == null) return;
        jdbcTemplate.update("""
            UPDATE parse_task
            SET obj_assess_matrix_json = :matrixJson, updated_at = NOW()
            WHERE id = :id
            """, new MapSqlParameterSource()
            .addValue("id", parseTaskId)
            .addValue("matrixJson", writeJson(matrix)));
    }

    public Map<String, Object> updateParseMappingMatrix(String taskId, Map<String, Object> payload) {
        long parseTaskId = resolveParseTaskId(taskId);
        jdbcTemplate.update("""
            UPDATE parse_task
            SET obj_assess_matrix_json = :matrixJson, updated_at = NOW()
            WHERE id = :id
            """, new MapSqlParameterSource()
            .addValue("id", parseTaskId)
            .addValue("matrixJson", writeJson(payload)));
        return map("success", true);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> matrixJsonToMappingList(Map<String, Object> matrixJson, List<Map<String, Object>> assessDrafts) {
        if (matrixJson == null) return List.of();
        List<String> methodNames = (List<String>) matrixJson.get("methodNames");
        List<String> methodTypes = (List<String>) matrixJson.get("methodTypes");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) matrixJson.get("rows");
        if (methodNames == null || rows == null || methodNames.isEmpty()) return List.of();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String objectiveCode = defaultString(row.get("objectiveCode"), "");
            int objectiveNumber = defaultInt(row.get("objectiveNumber"), 0);
            List<Number> proportions = (List<Number>) row.get("proportions");
            if (proportions == null) continue;

            double rowTotalWeight = defaultDouble(row.get("totalWeight"), 0D);
            if (rowTotalWeight > 0D) {
                for (int i = 0; i < methodNames.size() && i < proportions.size(); i++) {
                    Number p = proportions.get(i);
                    double proportion = p == null ? 0 : p.doubleValue();
                    double contributionWeight = proportion / rowTotalWeight * 100D;
                    if (contributionWeight <= 0D) continue;
                    String methodType = (methodTypes != null && i < methodTypes.size()) ? methodTypes.get(i) : "";
                    result.add(map(
                        "objectiveCode", objectiveCode,
                        "objectiveNumber", objectiveNumber,
                        "assessItemName", methodNames.get(i),
                        "assessItemType", methodType,
                        "contributionWeight", round2(contributionWeight)
                    ));
                }
                continue;
            }

            List<Double> weightedParts = new ArrayList<>();
            double weightedTotal = 0D;
            for (int i = 0; i < methodNames.size() && i < proportions.size(); i++) {
                Number p = proportions.get(i);
                double proportion = p == null ? 0 : p.doubleValue();
                double assessWeight = assessDraftWeightForMethod(
                    assessDrafts,
                    methodNames.get(i),
                    methodTypes != null && i < methodTypes.size() ? methodTypes.get(i) : ""
                );
                double weightedPart = proportion * assessWeight;
                weightedParts.add(weightedPart);
                weightedTotal += weightedPart;
            }
            if (weightedTotal <= 0D) {
                continue;
            }

            for (int i = 0; i < methodNames.size() && i < proportions.size(); i++) {
                double contributionWeight = weightedParts.get(i) / weightedTotal * 100D;
                if (contributionWeight <= 0) continue;
                String methodType = (methodTypes != null && i < methodTypes.size()) ? methodTypes.get(i) : "";
                result.add(map(
                    "objectiveCode", objectiveCode,
                    "objectiveNumber", objectiveNumber,
                    "assessItemName", methodNames.get(i),
                    "assessItemType", methodType,
                    "contributionWeight", round2(contributionWeight)
                ));
            }
        }
        return result;
    }

    private double assessDraftWeightForMethod(List<Map<String, Object>> assessDrafts, String methodName, String methodType) {
        for (Map<String, Object> draft : assessDrafts) {
            if (StringUtils.hasText(methodType) && methodType.equals(defaultString(draft.get("itemType"), ""))) {
                return defaultDouble(draft.get("weight"), 100D);
            }
        }
        String normalizedMethod = normalizeKey(methodName);
        for (Map<String, Object> draft : assessDrafts) {
            String normalizedName = normalizeKey(defaultString(draft.get("itemName"), ""));
            if (StringUtils.hasText(normalizedMethod)
                && (normalizedName.contains(normalizedMethod) || normalizedMethod.contains(normalizedName))) {
                return defaultDouble(draft.get("weight"), 100D);
            }
        }
        return 100D;
    }

    private Map<String, Object> courseInfoToMap(CourseInfo courseInfo) {
        if (courseInfo == null) {
            return new LinkedHashMap<>();
        }
        return map(
            "courseCode", defaultString(courseInfo.courseCode(), ""),
            "courseNameZh", defaultString(courseInfo.courseNameZh(), ""),
            "courseNameEn", defaultString(courseInfo.courseNameEn(), ""),
            "courseType", defaultString(courseInfo.courseType(), ""),
            "targetStudents", defaultString(courseInfo.targetStudents(), ""),
            "teachingLanguage", defaultString(courseInfo.teachingLanguage(), ""),
            "collegeName", defaultString(courseInfo.collegeName(), ""),
            "hours", courseInfo.hours(),
            "credits", courseInfo.credits(),
            "prerequisiteCourse", defaultString(courseInfo.prerequisiteCourse(), ""),
            "courseOwner", defaultString(courseInfo.courseOwner(), ""),
            "teachingContents", courseInfo.teachingContents(),
            "assessmentDetails", courseInfo.assessmentDetails(),
            "assessmentStandards", courseInfo.assessmentStandards(),
            "assessmentPolicy", courseInfo.assessmentPolicy()
        );
    }

    private void persistParsedOutlineDraft(long parseTaskId, ParsedOutlineDraft parsed) {
        jdbcTemplate.update("DELETE FROM parse_assess_item_draft WHERE parse_task_id = :parseTaskId", params("parseTaskId", parseTaskId));
        jdbcTemplate.update("DELETE FROM parse_objective_draft WHERE parse_task_id = :parseTaskId", params("parseTaskId", parseTaskId));

        int objectiveOrder = 1;
        for (ObjectiveDraftSuggestion suggestion : parsed.objectives()) {
            jdbcTemplate.update("""
                INSERT INTO parse_objective_draft (
                    parse_task_id, obj_code_suggest, obj_content_suggest, obj_type_suggest, weight_suggest,
                    grad_req_id_suggest, grad_req_desc_suggest, relation_level_suggest,
                    obj_content_final, obj_type_final, weight_final, confidence_score, confidence_level,
                    grad_req_id_final, grad_req_desc_final, relation_level_final,
                    original_text, is_confirmed, sort_order
                ) VALUES (
                    :parseTaskId, :objCode, :objContent, :objType, :weight,
                    :gradReqId, :gradReqDesc, :relationLevel,
                    :objContent, :objType, :weight, :confidenceScore, :confidenceLevel,
                    :gradReqId, :gradReqDesc, :relationLevel,
                    :originalText, 0, :sortOrder
                )
                """, new MapSqlParameterSource()
                .addValue("parseTaskId", parseTaskId)
                .addValue("objCode", suggestion.code())
                .addValue("objContent", suggestion.content())
                .addValue("objType", suggestion.objType())
                .addValue("weight", suggestion.weight())
                .addValue("gradReqId", defaultString(suggestion.gradReqId(), ""))
                .addValue("gradReqDesc", defaultString(suggestion.gradReqDesc(), ""))
                .addValue("relationLevel", defaultString(suggestion.relationLevel(), ""))
                .addValue("confidenceScore", suggestion.confidenceScore())
                .addValue("confidenceLevel", suggestion.confidenceLevel())
                .addValue("originalText", suggestion.originalText())
                .addValue("sortOrder", objectiveOrder++));
        }

        int assessOrder = 1;
        for (AssessItemDraftSuggestion suggestion : parsed.assessItems()) {
            jdbcTemplate.update("""
                INSERT INTO parse_assess_item_draft (
                    parse_task_id, item_name_suggest, item_type_suggest, weight_suggest,
                    item_name_final, item_type_final, weight_final, confidence_score, confidence_level,
                    original_text, is_confirmed, sort_order
                ) VALUES (
                    :parseTaskId, :itemName, :itemType, :weight,
                    :itemName, :itemType, :weight, :confidenceScore, :confidenceLevel,
                    :originalText, 0, :sortOrder
                )
                """, new MapSqlParameterSource()
                .addValue("parseTaskId", parseTaskId)
                .addValue("itemName", suggestion.itemName())
                .addValue("itemType", suggestion.itemType())
                .addValue("weight", suggestion.weight())
                .addValue("confidenceScore", suggestion.confidenceScore())
                .addValue("confidenceLevel", suggestion.confidenceLevel())
                .addValue("originalText", suggestion.originalText())
                .addValue("sortOrder", assessOrder++));
        }
    }

    private void refreshParseTaskCounts(long parseTaskId) {
        jdbcTemplate.update("""
            UPDATE parse_task
            SET obj_extract_count = (
                    SELECT COUNT(*)
                    FROM parse_objective_draft
                    WHERE parse_task_id = :parseTaskId
                ),
                assess_extract_count = (
                    SELECT COUNT(*)
                    FROM parse_assess_item_draft
                    WHERE parse_task_id = :parseTaskId
                ),
                updated_at = NOW()
            WHERE id = :parseTaskId
            """, params("parseTaskId", parseTaskId));
    }

    private void markParseTaskFailed(long parseTaskId, String errorCode, String errorMessage) {
        jdbcTemplate.update("""
            UPDATE parse_task
            SET status = 'FAILED',
                error_code = :errorCode,
                error_message = :errorMessage,
                finished_at = NOW(),
                updated_at = NOW()
            WHERE id = :id
            """, new MapSqlParameterSource()
            .addValue("id", parseTaskId)
            .addValue("errorCode", errorCode)
            .addValue("errorMessage", defaultString(errorMessage, "智能解析失败")));
    }

    private List<Map<String, Object>> listParseObjectives(long parseTaskId) {
        return jdbcTemplate.query("""
            SELECT
                id,
                obj_code_suggest,
                obj_content_suggest,
                obj_type_suggest,
                weight_suggest,
                grad_req_id_suggest,
                grad_req_desc_suggest,
                relation_level_suggest,
                obj_content_final,
                obj_type_final,
                weight_final,
                grad_req_id_final,
                grad_req_desc_final,
                relation_level_final,
                confidence_score,
                confidence_level,
                original_text,
                is_confirmed
            FROM parse_objective_draft
            WHERE parse_task_id = :parseTaskId
            ORDER BY sort_order ASC, id ASC
            """, params("parseTaskId", parseTaskId), (rs, rowNum) -> map(
            "id", rs.getLong("id"),
            "objCodeSuggest", rs.getString("obj_code_suggest"),
            "objContentSuggest", rs.getString("obj_content_suggest"),
            "objTypeSuggest", rs.getInt("obj_type_suggest"),
            "weightSuggest", rs.getBigDecimal("weight_suggest"),
            "gradReqIdSuggest", defaultString(rs.getString("grad_req_id_suggest"), ""),
            "gradReqDescSuggest", defaultString(rs.getString("grad_req_desc_suggest"), ""),
            "relationLevelSuggest", defaultString(rs.getString("relation_level_suggest"), ""),
            "objContentFinal", defaultString(rs.getString("obj_content_final"), rs.getString("obj_content_suggest")),
            "objTypeFinal", rs.getObject("obj_type_final") == null ? rs.getInt("obj_type_suggest") : rs.getInt("obj_type_final"),
            "weightFinal", rs.getObject("weight_final") == null ? rs.getBigDecimal("weight_suggest") : rs.getBigDecimal("weight_final"),
            "gradReqIdFinal", defaultString(rs.getString("grad_req_id_final"), defaultString(rs.getString("grad_req_id_suggest"), "")),
            "gradReqDescFinal", defaultString(rs.getString("grad_req_desc_final"), defaultString(rs.getString("grad_req_desc_suggest"), "")),
            "relationLevelFinal", defaultString(rs.getString("relation_level_final"), defaultString(rs.getString("relation_level_suggest"), "")),
            "confidenceScore", rs.getBigDecimal("confidence_score"),
            "confidenceLevel", rs.getString("confidence_level"),
            "originalText", rs.getString("original_text"),
            "isConfirmed", rs.getInt("is_confirmed")
        ));
    }

    private List<Map<String, Object>> listParseAssessItems(long parseTaskId) {
        return jdbcTemplate.query("""
            SELECT
                id,
                item_name_suggest,
                item_type_suggest,
                weight_suggest,
                item_name_final,
                item_type_final,
                weight_final,
                confidence_score,
                confidence_level,
                original_text,
                is_confirmed
            FROM parse_assess_item_draft
            WHERE parse_task_id = :parseTaskId
            ORDER BY sort_order ASC, id ASC
            """, params("parseTaskId", parseTaskId), (rs, rowNum) -> map(
            "id", rs.getLong("id"),
            "itemNameSuggest", rs.getString("item_name_suggest"),
            "itemTypeSuggest", rs.getString("item_type_suggest"),
            "weightSuggest", rs.getBigDecimal("weight_suggest"),
            "itemNameFinal", defaultString(rs.getString("item_name_final"), rs.getString("item_name_suggest")),
            "itemTypeFinal", defaultString(rs.getString("item_type_final"), rs.getString("item_type_suggest")),
            "weightFinal", rs.getObject("weight_final") == null ? rs.getBigDecimal("weight_suggest") : rs.getBigDecimal("weight_final"),
            "confidenceScore", rs.getBigDecimal("confidence_score"),
            "confidenceLevel", rs.getString("confidence_level"),
            "originalText", rs.getString("original_text"),
            "isConfirmed", rs.getInt("is_confirmed")
        ));
    }

    private List<Map<String, Object>> buildParseSourceSections(List<Map<String, Object>> objectives, List<Map<String, Object>> assessItems) {
        List<Map<String, Object>> sections = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> item : objectives) {
            sections.add(map("id", "obj-" + item.get("id"), "label", "目标原文" + index++, "text", item.get("originalText")));
        }
        for (Map<String, Object> item : assessItems) {
            sections.add(map("id", "assess-" + item.get("id"), "label", "考核原文" + index++, "text", item.get("originalText")));
        }
        return sections;
    }

    private Map<String, Object> buildParseWeightSummary(List<Map<String, Object>> items) {
        double totalWeight = items.stream()
            .filter(item -> intValue(item.get("isConfirmed")) != 2)
            .mapToDouble(item -> doubleValue(item.get("weightFinal")))
            .sum();
        double confirmedWeight = items.stream()
            .filter(item -> intValue(item.get("isConfirmed")) == 1)
            .mapToDouble(item -> doubleValue(item.get("weightFinal")))
            .sum();
        long confirmedCount = items.stream().filter(item -> intValue(item.get("isConfirmed")) == 1).count();
        return map(
            "draftTotal", round2(totalWeight),
            "confirmedTotal", round2(confirmedWeight),
            "confirmedCount", confirmedCount,
            "confirmedValid", confirmedCount == 0 || Math.abs(confirmedWeight - 100D) <= 0.01D
        );
    }

    private void validateConfirmedWeightTotal(List<Map<String, Object>> drafts, String label, boolean required) {
        if (drafts.isEmpty()) {
            if (required) {
                throw new ApiException(UNPROCESSABLE_STATUS, 400, label + "至少要确认 1 条，才能写入系统。");
            }
            return;
        }

        double total = drafts.stream().mapToDouble(item -> doubleValue(item.get("weight"))).sum();
        if (Math.abs(total - 100D) > 0.01D) {
            throw new ApiException(
                UNPROCESSABLE_STATUS,
                400,
                label + "的已确认权重合计必须等于 100，当前合计为 " + formatNumber(total, 2)
            );
        }
    }

    private Map<String, Object> mergeCourseInfo(Map<String, Object> parsedCourse, Map<String, Object> submittedCourse, long fallbackCourseId) {
        Map<String, Object> currentCourse = getCourseMetaById(fallbackCourseId);
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
        merged.put("courseCode", defaultString(submittedCourse.get("courseCode"), defaultString(parsedCourse.get("courseCode"), defaultString(currentCourse.get("courseCode"), ""))));
        merged.put("courseNameZh", defaultString(submittedCourse.get("courseNameZh"), defaultString(parsedCourse.get("courseNameZh"), defaultString(currentCourse.get("courseNameZh"), defaultString(currentCourse.get("courseName"), "")))));
        merged.put("courseNameEn", defaultString(submittedCourse.get("courseNameEn"), defaultString(parsedCourse.get("courseNameEn"), defaultString(currentCourse.get("courseNameEn"), ""))));
        merged.put("courseType", defaultString(submittedCourse.get("courseType"), defaultString(parsedCourse.get("courseType"), defaultString(currentCourse.get("courseType"), ""))));
        merged.put("targetStudents", defaultString(submittedCourse.get("targetStudents"), defaultString(parsedCourse.get("targetStudents"), defaultString(currentCourse.get("targetStudents"), ""))));
        merged.put("teachingLanguage", defaultString(submittedCourse.get("teachingLanguage"), defaultString(parsedCourse.get("teachingLanguage"), defaultString(currentCourse.get("teachingLanguage"), ""))));
        merged.put("collegeName", defaultString(submittedCourse.get("collegeName"), defaultString(parsedCourse.get("collegeName"), defaultString(currentCourse.get("collegeName"), ""))));
        merged.put("hours", defaultInt(submittedCourse.get("hours"), defaultInt(parsedCourse.get("hours"), defaultInt(currentCourse.get("hours"), 0))));
        merged.put("credits", defaultDouble(submittedCourse.get("credits"), defaultDouble(parsedCourse.get("credits"), defaultDouble(currentCourse.get("credits"), 0D))));
        merged.put("prerequisiteCourse", defaultString(submittedCourse.get("prerequisiteCourse"), defaultString(parsedCourse.get("prerequisiteCourse"), defaultString(currentCourse.get("prerequisiteCourse"), ""))));
        merged.put("courseOwner", defaultString(submittedCourse.get("courseOwner"), defaultString(parsedCourse.get("courseOwner"), defaultString(currentCourse.get("courseOwner"), ""))));
        merged.put("collegeId", nullableLong(currentCourse.get("collegeId")));
        merged.put("majorId", nullableLong(currentCourse.get("majorId")));
        return merged;
    }

    private Map<String, Object> getCourseMetaById(long courseId) {
        return requireMap("""
            SELECT
                c.id,
                c.course_code,
                c.course_name,
                c.course_name_en,
                c.course_type,
                c.target_students,
                c.teaching_language,
                c.hours,
                c.credits,
                c.prerequisite_course,
                c.course_owner,
                c.college_id,
                c.major_id,
                bc.college_name
            FROM base_course c
            LEFT JOIN base_college bc ON bc.id = c.college_id
            WHERE c.id = :id
            """, params("id", courseId), rs -> map(
            "id", rs.getLong("id"),
            "courseCode", defaultString(rs.getString("course_code"), ""),
            "courseName", defaultString(rs.getString("course_name"), ""),
            "courseNameZh", defaultString(rs.getString("course_name"), ""),
            "courseNameEn", defaultString(rs.getString("course_name_en"), ""),
            "courseType", defaultString(rs.getString("course_type"), ""),
            "targetStudents", defaultString(rs.getString("target_students"), ""),
            "teachingLanguage", defaultString(rs.getString("teaching_language"), ""),
            "hours", rs.getObject("hours") == null ? null : rs.getInt("hours"),
            "credits", rs.getBigDecimal("credits"),
            "prerequisiteCourse", defaultString(rs.getString("prerequisite_course"), ""),
            "courseOwner", defaultString(rs.getString("course_owner"), ""),
            "collegeId", nullableLong(rs.getObject("college_id")),
            "majorId", nullableLong(rs.getObject("major_id")),
            "collegeName", defaultString(rs.getString("college_name"), "")
        ));
    }

    private void applyCourseOverrides(long courseId, Map<String, Object> courseInfo, List<String> overwriteCourseFields) {
        if (overwriteCourseFields.isEmpty()) {
            return;
        }

        Map<String, Object> currentCourse = getCourseMetaById(courseId);
        String courseCode = overwriteCourseFields.contains("courseCode")
            ? defaultString(courseInfo.get("courseCode"), defaultString(currentCourse.get("courseCode"), ""))
            : defaultString(currentCourse.get("courseCode"), "");
        String courseNameZh = overwriteCourseFields.contains("courseNameZh")
            ? defaultString(courseInfo.get("courseNameZh"), defaultString(currentCourse.get("courseNameZh"), ""))
            : defaultString(currentCourse.get("courseNameZh"), "");
        if (!StringUtils.hasText(courseNameZh)) {
            throw new ApiException(UNPROCESSABLE_STATUS, 400, "课程名称不能为空。");
        }
        validateCourseCodeUnique(courseCode, courseId);

        long collegeId = overwriteCourseFields.contains("collegeName")
            ? resolveCollegeId(defaultString(courseInfo.get("collegeName"), ""), courseId)
            : nullableLong(currentCourse.get("collegeId")) == null ? resolveCollegeId("", courseId) : longValue(currentCourse.get("collegeId"));

        jdbcTemplate.update("""
            UPDATE base_course
            SET course_code = :courseCode,
                course_name = :courseName,
                course_name_en = :courseNameEn,
                course_type = :courseType,
                target_students = :targetStudents,
                teaching_language = :teachingLanguage,
                hours = :hours,
                credits = :credits,
                prerequisite_course = :prerequisiteCourse,
                course_owner = :courseOwner,
                college_id = :collegeId,
                updated_at = NOW()
            WHERE id = :id
            """, new MapSqlParameterSource()
            .addValue("id", courseId)
            .addValue("courseCode", courseCode)
            .addValue("courseName", courseNameZh)
            .addValue("courseNameEn", overwriteCourseFields.contains("courseNameEn") ? defaultString(courseInfo.get("courseNameEn"), "") : defaultString(currentCourse.get("courseNameEn"), ""))
            .addValue("courseType", overwriteCourseFields.contains("courseType") ? defaultString(courseInfo.get("courseType"), "") : defaultString(currentCourse.get("courseType"), ""))
            .addValue("targetStudents", overwriteCourseFields.contains("targetStudents") ? defaultString(courseInfo.get("targetStudents"), "") : defaultString(currentCourse.get("targetStudents"), ""))
            .addValue("teachingLanguage", overwriteCourseFields.contains("teachingLanguage") ? defaultString(courseInfo.get("teachingLanguage"), "") : defaultString(currentCourse.get("teachingLanguage"), ""))
            .addValue("hours", overwriteCourseFields.contains("hours") ? nullableInteger(courseInfo.get("hours")) : nullableInteger(currentCourse.get("hours")))
            .addValue("credits", overwriteCourseFields.contains("credits") ? nullableBigDecimal(courseInfo.get("credits")) : nullableBigDecimal(currentCourse.get("credits")))
            .addValue("prerequisiteCourse", overwriteCourseFields.contains("prerequisiteCourse") ? defaultString(courseInfo.get("prerequisiteCourse"), "") : defaultString(currentCourse.get("prerequisiteCourse"), ""))
            .addValue("courseOwner", overwriteCourseFields.contains("courseOwner") ? defaultString(courseInfo.get("courseOwner"), "") : defaultString(currentCourse.get("courseOwner"), ""))
            .addValue("collegeId", collegeId));
    }

    private long createCourseFromParseImport(Map<String, Object> courseInfo, long fallbackCourseId, long semesterId, long teacherId) {
        String courseCode = defaultString(courseInfo.get("courseCode"), "");
        String courseNameZh = defaultString(courseInfo.get("courseNameZh"), "");
        if (!StringUtils.hasText(courseCode)) {
            throw new ApiException(UNPROCESSABLE_STATUS, 400, "新建课程时必须提供课程代码。");
        }
        if (!StringUtils.hasText(courseNameZh)) {
            throw new ApiException(UNPROCESSABLE_STATUS, 400, "新建课程时必须提供课程名称。");
        }
        validateCourseCodeUnique(courseCode, null);

        Map<String, Object> fallbackCourse = getCourseMetaById(fallbackCourseId);
        long collegeId = resolveCollegeId(defaultString(courseInfo.get("collegeName"), ""), fallbackCourseId);
        Long majorId = nullableLong(fallbackCourse.get("majorId"));

        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
            INSERT INTO base_course (
                course_code, course_name, course_name_en, credits, hours, course_type,
                college_id, major_id, target_students, teaching_language, prerequisite_course, course_owner, status
            ) VALUES (
                :courseCode, :courseName, :courseNameEn, :credits, :hours, :courseType,
                :collegeId, :majorId, :targetStudents, :teachingLanguage, :prerequisiteCourse, :courseOwner, 1
            )
            """, new MapSqlParameterSource()
            .addValue("courseCode", courseCode)
            .addValue("courseName", courseNameZh)
            .addValue("courseNameEn", defaultString(courseInfo.get("courseNameEn"), ""))
            .addValue("credits", nullableBigDecimal(courseInfo.get("credits")))
            .addValue("hours", nullableInteger(courseInfo.get("hours")))
            .addValue("courseType", defaultString(courseInfo.get("courseType"), defaultString(fallbackCourse.get("courseType"), "")))
            .addValue("collegeId", collegeId)
            .addValue("majorId", majorId)
            .addValue("targetStudents", defaultString(courseInfo.get("targetStudents"), ""))
            .addValue("teachingLanguage", defaultString(courseInfo.get("teachingLanguage"), ""))
            .addValue("prerequisiteCourse", defaultString(courseInfo.get("prerequisiteCourse"), ""))
            .addValue("courseOwner", defaultString(courseInfo.get("courseOwner"), "")), keyHolder);

        long courseId = keyHolder.getKey().longValue();
        ensureCourseTeacher(courseId, teacherId, semesterId);
        return courseId;
    }

    private void ensureCourseTeacher(long courseId, long teacherId, long semesterId) {
        long exists = count("""
            SELECT COUNT(*)
            FROM course_teacher
            WHERE course_id = :courseId
              AND teacher_id = :teacherId
              AND semester_id = :semesterId
            """, new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("teacherId", teacherId)
            .addValue("semesterId", semesterId));
        if (exists > 0) {
            return;
        }
        jdbcTemplate.update("""
            INSERT INTO course_teacher (course_id, teacher_id, semester_id, status)
            VALUES (:courseId, :teacherId, :semesterId, 1)
            """, new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("teacherId", teacherId)
            .addValue("semesterId", semesterId));
    }

    private void validateCourseCodeUnique(String courseCode, Long currentCourseId) {
        if (!StringUtils.hasText(courseCode)) {
            throw new ApiException(UNPROCESSABLE_STATUS, 400, "课程代码不能为空。");
        }
        long exists = count("""
            SELECT COUNT(*)
            FROM base_course
            WHERE course_code = :courseCode
              AND (:currentCourseId IS NULL OR id <> :currentCourseId)
            """, new MapSqlParameterSource()
            .addValue("courseCode", courseCode)
            .addValue("currentCourseId", currentCourseId));
        if (exists > 0) {
            throw new ApiException(UNPROCESSABLE_STATUS, 400, "课程代码已存在，请修改后再保存。");
        }
    }

    private long resolveCollegeId(String collegeName, long fallbackCourseId) {
        if (StringUtils.hasText(collegeName)) {
            Long matched = jdbcTemplate.query("""
                SELECT id
                FROM base_college
                WHERE college_name = :collegeName
                LIMIT 1
                """, params("collegeName", collegeName), rs -> rs.next() ? rs.getLong("id") : null);
            if (matched != null) {
                return matched;
            }
        }

        Long fallbackCollegeId = jdbcTemplate.query("""
            SELECT college_id
            FROM base_course
            WHERE id = :id
            """, params("id", fallbackCourseId), rs -> rs.next() ? nullableLong(rs.getObject("college_id")) : null);
        if (fallbackCollegeId != null) {
            return fallbackCollegeId;
        }
        return queryLong("SELECT id FROM base_college ORDER BY id ASC LIMIT 1", new MapSqlParameterSource());
    }

    private void clearOutlineImportData(long outlineId) {
        jdbcTemplate.update("""
            DELETE FROM obj_assess_map
            WHERE objective_id IN (
                SELECT id FROM teach_objective WHERE outline_id = :outlineId
            )
            """, params("outlineId", outlineId));
        jdbcTemplate.update("""
            DELETE FROM obj_decompose
            WHERE objective_id IN (
                SELECT id FROM teach_objective WHERE outline_id = :outlineId
            )
            """, params("outlineId", outlineId));
        jdbcTemplate.update("DELETE FROM teach_objective WHERE outline_id = :outlineId", params("outlineId", outlineId));
        jdbcTemplate.update("DELETE FROM assess_item WHERE outline_id = :outlineId", params("outlineId", outlineId));
    }

    private long resolveParseTaskId(String taskId) {
        return queryLong("""
            SELECT id
            FROM parse_task
            WHERE task_no = :taskNo
            """, params("taskNo", taskId));
    }

    private int upsertObjectives(long outlineId, List<Map<String, Object>> objectiveDrafts) {
        int sortOrder = 1;
        int affectedCount = 0;
        for (Map<String, Object> draft : objectiveDrafts) {
            String objCode = defaultString(draft.get("objCode"), "OBJ-" + sortOrder);
            Long existingId = jdbcTemplate.query("""
                SELECT id
                FROM teach_objective
                WHERE outline_id = :outlineId
                  AND obj_code = :objCode
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("outlineId", outlineId)
                .addValue("objCode", objCode), rs -> rs.next() ? rs.getLong("id") : null);

            long objectiveId;
            if (existingId == null) {
                GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
                jdbcTemplate.update("""
                    INSERT INTO teach_objective (
                        outline_id, obj_code, obj_content, obj_type, weight, sort_order,
                        grad_req_id, grad_req_desc, relation_level
                    ) VALUES (
                        :outlineId, :objCode, :objContent, :objType, :weight, :sortOrder,
                        :gradReqId, :gradReqDesc, :relationLevel
                    )
                    """, new MapSqlParameterSource()
                    .addValue("outlineId", outlineId)
                    .addValue("objCode", objCode)
                    .addValue("objContent", draft.get("objContent"))
                    .addValue("objType", draft.get("objType"))
                    .addValue("weight", draft.get("weight"))
                    .addValue("sortOrder", sortOrder)
                    .addValue("gradReqId", defaultString(draft.get("gradReqId"), ""))
                    .addValue("gradReqDesc", defaultString(draft.get("gradReqDesc"), ""))
                    .addValue("relationLevel", defaultString(draft.get("relationLevel"), "H")), keyHolder);
                objectiveId = keyHolder.getKey().longValue();
            } else {
                objectiveId = existingId;
                jdbcTemplate.update("""
                    UPDATE teach_objective
                    SET obj_content = :objContent,
                        obj_type = :objType,
                        weight = :weight,
                        sort_order = :sortOrder,
                        grad_req_id = :gradReqId,
                        grad_req_desc = :gradReqDesc,
                        relation_level = :relationLevel,
                        updated_at = NOW()
                    WHERE id = :id
                    """, new MapSqlParameterSource()
                    .addValue("id", objectiveId)
                    .addValue("objContent", draft.get("objContent"))
                    .addValue("objType", draft.get("objType"))
                    .addValue("weight", draft.get("weight"))
                    .addValue("sortOrder", sortOrder)
                    .addValue("gradReqId", defaultString(draft.get("gradReqId"), ""))
                    .addValue("gradReqDesc", defaultString(draft.get("gradReqDesc"), ""))
                    .addValue("relationLevel", defaultString(draft.get("relationLevel"), "H")));
            }

            jdbcTemplate.update("DELETE FROM obj_decompose WHERE objective_id = :objectiveId", params("objectiveId", objectiveId));
            jdbcTemplate.update("""
                INSERT INTO obj_decompose (
                    objective_id, point_code, point_content, point_type, weight, sort_order
                ) VALUES (
                    :objectiveId, :pointCode, :pointContent, :pointType, :weight, 1
                )
                """, new MapSqlParameterSource()
                .addValue("objectiveId", objectiveId)
                .addValue("pointCode", objCode + "-1")
                .addValue("pointContent", draft.get("objContent"))
                .addValue("pointType", draft.get("objType"))
                .addValue("weight", 100));

            sortOrder++;
            affectedCount++;
        }
        return affectedCount;
    }

    private void upsertAssessItems(long outlineId, List<Map<String, Object>> assessDrafts) {
        int sortOrder = 1;
        for (Map<String, Object> item : assessDrafts) {
            long existingCount = count("""
                SELECT COUNT(*)
                FROM assess_item
                WHERE outline_id = :outlineId
                  AND item_name = :itemName
                """, new MapSqlParameterSource()
                .addValue("outlineId", outlineId)
                .addValue("itemName", item.get("itemName")));

            if (existingCount > 0) {
                jdbcTemplate.update("""
                    UPDATE assess_item
                    SET item_type = :itemType,
                        weight = :weight,
                        sort_order = :sortOrder,
                        updated_at = NOW()
                    WHERE outline_id = :outlineId
                      AND item_name = :itemName
                    """, new MapSqlParameterSource()
                    .addValue("outlineId", outlineId)
                    .addValue("itemName", item.get("itemName"))
                    .addValue("itemType", item.get("itemType"))
                    .addValue("weight", item.get("weight"))
                    .addValue("sortOrder", sortOrder++));
                continue;
            }

            jdbcTemplate.update("""
                INSERT INTO assess_item (
                    outline_id, item_name, item_type, weight, max_score, sort_order
                ) VALUES (
                    :outlineId, :itemName, :itemType, :weight, 100, :sortOrder
                )
                """, new MapSqlParameterSource()
                .addValue("outlineId", outlineId)
                .addValue("itemName", item.get("itemName"))
                .addValue("itemType", item.get("itemType"))
                .addValue("weight", item.get("weight"))
                .addValue("sortOrder", sortOrder++));
        }
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
            SELECT id, item_name, item_type, weight, max_score
            FROM assess_item
            WHERE outline_id = :outlineId
            ORDER BY sort_order ASC, id ASC
            """, params("outlineId", outlineId), (rs, rowNum) -> map(
            "id", rs.getLong("id"),
            "outlineId", outlineId,
            "itemName", rs.getString("item_name"),
            "itemType", defaultString(rs.getString("item_type"), ""),
            "weight", rs.getBigDecimal("weight"),
            "maxScore", rs.getBigDecimal("max_score")
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

    @SuppressWarnings("unused")
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
            map("name", "平时成绩", "type", "normal", "weight", 30),
            map("name", "期中成绩", "type", "mid", "weight", 30),
            map("name", "期末成绩", "type", "final", "weight", 40)
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

    private int applyParsedObjectiveAssessMappings(
        long outlineId,
        List<Map<String, Object>> objectiveDrafts,
        List<Map<String, Object>> mappingSuggestions
    ) {
        if (mappingSuggestions.isEmpty()) {
            return 0;
        }

        List<Map<String, Object>> objectives = objectiveList(outlineId);
        List<Map<String, Object>> assessItems = assessItemListByOutline(outlineId);
        if (objectives.isEmpty() || assessItems.isEmpty()) {
            return 0;
        }

        jdbcTemplate.update("""
            DELETE FROM obj_assess_map
            WHERE objective_id IN (
                SELECT id FROM teach_objective WHERE outline_id = :outlineId
            )
            """, params("outlineId", outlineId));

        int inserted = 0;
        for (Map<String, Object> suggestion : mappingSuggestions) {
            Long objectiveId = resolveObjectiveIdForMapping(objectives, objectiveDrafts, suggestion);
            Long assessItemId = resolveAssessItemIdForMapping(assessItems, suggestion);
            double contribution = defaultDouble(suggestion.get("contributionWeight"), 0D);
            if (objectiveId == null || assessItemId == null || contribution <= 0D) {
                continue;
            }

            jdbcTemplate.update("""
                INSERT INTO obj_assess_map (
                    objective_id, assess_item_id, contribution_weight
                ) VALUES (
                    :objectiveId, :assessItemId, :contributionWeight
                )
                """, new MapSqlParameterSource()
                .addValue("objectiveId", objectiveId)
                .addValue("assessItemId", assessItemId)
                .addValue("contributionWeight", round2(contribution)));
            inserted++;
        }
        return inserted;
    }

    private Long resolveObjectiveIdForMapping(
        List<Map<String, Object>> objectives,
        List<Map<String, Object>> objectiveDrafts,
        Map<String, Object> suggestion
    ) {
        String code = defaultString(suggestion.get("objectiveCode"), "");
        for (Map<String, Object> objective : objectives) {
            if (code.equalsIgnoreCase(defaultString(objective.get("objCode"), ""))) {
                return longValue(objective.get("id"));
            }
        }

        int objectiveNumber = defaultInt(suggestion.get("objectiveNumber"), 0);
        if (objectiveNumber >= 1 && objectiveNumber <= objectives.size()) {
            return longValue(objectives.get(objectiveNumber - 1).get("id"));
        }

        String content = defaultString(suggestion.get("objectiveContent"), "");
        if (StringUtils.hasText(content)) {
            String normalizedContent = normalizeKey(content);
            for (Map<String, Object> objective : objectives) {
                String current = normalizeKey(defaultString(objective.get("objContent"), ""));
                if (current.contains(normalizedContent) || normalizedContent.contains(current)) {
                    return longValue(objective.get("id"));
                }
            }
        }

        if (!objectiveDrafts.isEmpty() && objectiveNumber >= 1 && objectiveNumber <= objectiveDrafts.size()) {
            String draftCode = defaultString(objectiveDrafts.get(objectiveNumber - 1).get("objCode"), "");
            for (Map<String, Object> objective : objectives) {
                if (draftCode.equalsIgnoreCase(defaultString(objective.get("objCode"), ""))) {
                    return longValue(objective.get("id"));
                }
            }
        }
        return null;
    }

    private Long resolveAssessItemIdForMapping(List<Map<String, Object>> assessItems, Map<String, Object> suggestion) {
        String type = defaultString(suggestion.get("assessItemType"), "");
        for (Map<String, Object> item : assessItems) {
            if (StringUtils.hasText(type) && type.equals(defaultString(item.get("itemType"), ""))) {
                return longValue(item.get("id"));
            }
        }

        String name = normalizeKey(defaultString(suggestion.get("assessItemName"), ""));
        for (Map<String, Object> item : assessItems) {
            String current = normalizeKey(defaultString(item.get("itemName"), ""));
            if (StringUtils.hasText(name) && (current.contains(name) || name.contains(current))) {
                return longValue(item.get("id"));
            }
        }
        return null;
    }

    private String normalizeKey(String value) {
        return defaultString(value, "")
            .replaceAll("[\\s_\\-—:：/\\\\+＋（）()，,。.;；]+", "")
            .toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unused")
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

    @SuppressWarnings("unused")
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

    private void validateGradeFileName(String fileName) {
        String lower = defaultString(fileName, "").toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".xls") || lower.endsWith(".xlsx") || lower.endsWith(".csv"))) {
            throw new ApiException(UNPROCESSABLE_STATUS, 400, "仅支持上传 xls、xlsx 或 csv 格式的成绩文件。");
        }
    }

    private List<Map<String, Object>> currentCourseAssessItems(long courseId, String semesterCode) {
        Long outlineId = findOutlineId(courseId, semesterCode);
        if (outlineId == null) {
            return List.of();
        }
        return assessItemListByOutline(outlineId);
    }

    private GradeImportResult parseGradeFile(
        MultipartFile file,
        String fileName,
        List<Map<String, Object>> assessItems
    ) throws IOException {
        String lower = fileName.toLowerCase(Locale.ROOT);
        List<List<String>> rows = lower.endsWith(".csv") ? readCsvRows(file) : readWorkbookRows(file, assessItems);
        if (rows.isEmpty()) {
            throw new ApiException(UNPROCESSABLE_STATUS, 400, "成绩文件为空，请检查表头和数据行。");
        }

        List<String> headers = rows.get(0);
        Map<Integer, GradeColumn> gradeColumns = resolveGradeColumns(headers, assessItems);
        if (gradeColumns.isEmpty()) {
            throw new ApiException(UNPROCESSABLE_STATUS, 400, "未在成绩文件中找到与当前课程考核项匹配的成绩列。");
        }

        List<GradeImportRow> imports = new ArrayList<>();
        for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            if (row.stream().allMatch(cell -> !StringUtils.hasText(cell))) {
                continue;
            }
            String studentNo = cellAt(row, 0);
            String studentName = cellAt(row, 1);
            for (Map.Entry<Integer, GradeColumn> entry : gradeColumns.entrySet()) {
                GradeColumn column = entry.getValue();
                String rawScore = cellAt(row, entry.getKey());
                ScoreParseResult score = parseScore(rawScore, column.maxScore());
                String error = "";
                if (!StringUtils.hasText(studentNo)) {
                    error = "学号不能为空";
                } else if (!StringUtils.hasText(studentName)) {
                    error = "姓名不能为空";
                } else if (!score.valid()) {
                    error = score.errorMessage();
                }
                imports.add(new GradeImportRow(
                    rowIndex + 1,
                    studentNo,
                    studentName,
                    column.assessItemId(),
                    score.score(),
                    column.maxScore(),
                    !StringUtils.hasText(error),
                    error
                ));
            }
        }

        int validRows = (int) imports.stream().filter(GradeImportRow::valid).count();
        return new GradeImportResult(imports, imports.size(), validRows, imports.size() - validRows);
    }

    private List<List<String>> readWorkbookRows(MultipartFile file, List<Map<String, Object>> assessItems) throws IOException {
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            SheetSelection best = null;
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                List<List<String>> rows = readSheetRows(sheet, formatter);
                SheetSelection selection = selectGradeSheet(sheet.getSheetName(), rows, assessItems);
                if (selection == null) {
                    continue;
                }
                if (best == null || selection.score() > best.score()) {
                    best = selection;
                }
            }
            if (best != null) {
                return best.rows().subList(best.headerIndex(), best.rows().size());
            }
            return workbook.getNumberOfSheets() == 0
                ? List.of()
                : readSheetRows(workbook.getSheetAt(0), formatter);
        }
    }

    private List<List<String>> readSheetRows(Sheet sheet, DataFormatter formatter) {
        List<List<String>> rows = new ArrayList<>();
        for (Row row : sheet) {
            int lastCell = Math.max(row.getLastCellNum(), 0);
            List<String> cells = new ArrayList<>();
            for (int index = 0; index < lastCell; index++) {
                Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                cells.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
            }
            rows.add(cells);
        }
        return rows;
    }

    private SheetSelection selectGradeSheet(String sheetName, List<List<String>> rows, List<Map<String, Object>> assessItems) {
        SheetSelection best = null;
        int inspectRows = Math.min(rows.size(), 20);
        for (int rowIndex = 0; rowIndex < inspectRows; rowIndex++) {
            List<String> header = rows.get(rowIndex);
            if (header.isEmpty()) {
                continue;
            }
            Map<Integer, GradeColumn> columns = resolveGradeColumns(header, assessItems);
            if (columns.isEmpty()) {
                continue;
            }
            String first = normalizeGradeHeader(cellAt(header, 0));
            String second = normalizeGradeHeader(cellAt(header, 1));
            int score = columns.size() * 100;
            if (first.contains("学号") || first.contains("工号")) {
                score += 80;
            }
            if (second.contains("姓名") || second.contains("学生")) {
                score += 60;
            }
            String normalizedSheet = normalizeGradeHeader(sheetName);
            if (normalizedSheet.contains("学生成绩")) {
                score += 120;
            } else if (normalizedSheet.contains("成绩")) {
                score += 60;
            }
            long nonEmptyDataRows = rows.stream()
                .skip(rowIndex + 1L)
                .filter(row -> row.stream().anyMatch(StringUtils::hasText))
                .count();
            score += (int) Math.min(nonEmptyDataRows, 50);
            SheetSelection current = new SheetSelection(sheetName, rows, rowIndex, score);
            if (best == null || current.score() > best.score()) {
                best = current;
            }
        }
        return best;
    }

    private List<List<String>> readCsvRows(MultipartFile file) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                rows.add(parseCsvLine(line));
            }
        }
        if (!rows.isEmpty() && !rows.get(0).isEmpty()) {
            rows.get(0).set(0, rows.get(0).get(0).replace("\uFEFF", ""));
        }
        return rows;
    }

    private List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char ch = line.charAt(index);
            if (ch == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    current.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                cells.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        cells.add(current.toString().trim());
        return cells;
    }

    private Map<Integer, GradeColumn> resolveGradeColumns(List<String> headers, List<Map<String, Object>> assessItems) {
        Map<Long, GradeColumnCandidate> bestByAssessItem = new LinkedHashMap<>();
        for (int index = 2; index < headers.size(); index++) {
            String header = defaultString(headers.get(index), "");
            Map<String, Object> matched = matchAssessItemByHeader(header, assessItems);
            if (matched == null) {
                continue;
            }
            long assessItemId = longValue(matched.get("id"));
            GradeColumnCandidate candidate = new GradeColumnCandidate(
                index,
                assessItemId,
                resolveMaxScore(header, matched),
                gradeHeaderMatchQuality(header, matched)
            );
            GradeColumnCandidate existing = bestByAssessItem.get(assessItemId);
            if (existing == null || candidate.quality() > existing.quality()) {
                bestByAssessItem.put(assessItemId, candidate);
            }
        }
        return bestByAssessItem.values().stream()
            .sorted(Comparator.comparingInt(GradeColumnCandidate::index))
            .collect(Collectors.toMap(
                GradeColumnCandidate::index,
                item -> new GradeColumn(item.assessItemId(), item.maxScore()),
                (left, right) -> left,
                LinkedHashMap::new
            ));
    }

    private Map<String, Object> matchAssessItemByHeader(String header, List<Map<String, Object>> assessItems) {
        String normalized = normalizeGradeHeader(header);
        for (Map<String, Object> item : assessItems) {
            if (normalized.contains(normalizeGradeHeader(stringValue(item.get("itemName"))))) {
                return item;
            }
        }
        for (Map<String, Object> item : assessItems) {
            String type = stringValue(item.get("itemType"));
            if (matchesAssessTypeHeader(normalized, type)) {
                return item;
            }
        }
        return null;
    }

    private boolean matchesAssessTypeHeader(String normalizedHeader, String itemType) {
        return switch (defaultString(itemType, "")) {
            case "normal" -> containsAnyNormalized(normalizedHeader, List.of("平时", "课堂", "作业", "讨论", "quiz", "assignment", "regular"));
            case "mid" -> containsAnyNormalized(normalizedHeader, List.of("期中", "midterm"));
            case "final" -> containsAnyNormalized(normalizedHeader, List.of("期末", "考试", "技术考试", "final", "exam"));
            case "practice" -> containsAnyNormalized(normalizedHeader, List.of("实践", "实验", "上机", "项目", "lab", "practice", "project"));
            case "report" -> containsAnyNormalized(normalizedHeader, List.of("报告", "汇报", "report", "presentation"));
            default -> false;
        };
    }

    private boolean containsAnyNormalized(String text, List<String> keywords) {
        return keywords.stream().map(this::normalizeGradeHeader).anyMatch(text::contains);
    }

    private String normalizeGradeHeader(String value) {
        return defaultString(value, "")
            .replaceAll("[（(]\\s*\\d+(?:\\.\\d+)?\\s*分?\\s*[)）]", "")
            .replaceAll("[\\s_\\-—:：/\\\\]+", "")
            .toLowerCase(Locale.ROOT);
    }

    private int gradeHeaderMatchQuality(String header, Map<String, Object> item) {
        String normalizedHeader = normalizeGradeHeader(header);
        String normalizedItemName = normalizeGradeHeader(stringValue(item.get("itemName")));
        int quality = 0;
        if (StringUtils.hasText(normalizedItemName)) {
            if (normalizedHeader.equals(normalizedItemName)) {
                quality += 300;
            } else if (normalizedHeader.contains(normalizedItemName) || normalizedItemName.contains(normalizedHeader)) {
                quality += 240;
            }
        }
        if (matchesAssessTypeHeader(normalizedHeader, stringValue(item.get("itemType")))) {
            quality += 40;
        }
        if (extractExplicitMaxScore(header) != null) {
            quality += 90;
        }
        if (containsAnyNormalized(normalizedHeader, List.of("成绩", "折算", "总评", "合计"))) {
            quality += 50;
        }
        boolean headerLooksSplitItem = normalizedHeader.matches(".*\\d+.*");
        boolean itemLooksSplitItem = normalizedItemName.matches(".*\\d+.*");
        if (headerLooksSplitItem && !itemLooksSplitItem) {
            quality -= 30;
        }
        return quality;
    }

    private double resolveMaxScore(String header, Map<String, Object> item) {
        Double explicitMaxScore = extractExplicitMaxScore(header);
        if (explicitMaxScore != null && explicitMaxScore > 0D) {
            return explicitMaxScore;
        }
        double maxScore = defaultDouble(item.get("maxScore"), 0D);
        double weight = defaultDouble(item.get("weight"), 0D);
        if (maxScore > 0D && Math.abs(maxScore - 100D) > 0.000001D) {
            return maxScore;
        }
        if (weight > 0D && weight < 100D) {
            return weight;
        }
        return maxScore > 0D ? maxScore : 100D;
    }

    private Double extractExplicitMaxScore(String header) {
        String text = defaultString(header, "");
        java.util.regex.Matcher parenMatcher = java.util.regex.Pattern
            .compile("[（(]\\s*(\\d+(?:\\.\\d+)?)\\s*(?:分|%)?\\s*[)）]")
            .matcher(text);
        if (parenMatcher.find()) {
            return Double.parseDouble(parenMatcher.group(1));
        }
        java.util.regex.Matcher dashMatcher = java.util.regex.Pattern
            .compile("[-—]\\s*(\\d+(?:\\.\\d+)?)\\s*$")
            .matcher(text);
        return dashMatcher.find() ? Double.parseDouble(dashMatcher.group(1)) : null;
    }

    private ScoreParseResult parseScore(String rawValue, double maxScore) {
        if (!StringUtils.hasText(rawValue)) {
            return new ScoreParseResult(0D, false, "成绩不能为空");
        }
        String normalized = rawValue.trim().replace("%", "").replace("％", "");
        try {
            double score = Double.parseDouble(normalized);
            if (score < 0D || score > maxScore) {
                return new ScoreParseResult(score, false, "成绩超出 0-" + formatNumber(maxScore, 2));
            }
            return new ScoreParseResult(score, true, "");
        } catch (NumberFormatException exception) {
            return new ScoreParseResult(0D, false, "成绩不是有效数字");
        }
    }

    private void persistGradeImportRows(
        long batchId,
        long courseId,
        long semesterId,
        long teacherId,
        List<GradeImportRow> rows
    ) {
        for (GradeImportRow row : rows) {
            jdbcTemplate.update("""
                INSERT INTO student_grade (
                    course_id, assess_item_id, semester_id, import_batch_id, student_no, student_name,
                    score, max_score, valid_flag, error_message, created_by
                ) VALUES (
                    :courseId, :assessItemId, :semesterId, :batchId, :studentNo, :studentName,
                    :score, :maxScore, :validFlag, :errorMessage, :createdBy
                )
                """, new MapSqlParameterSource()
                .addValue("courseId", courseId)
                .addValue("assessItemId", row.assessItemId())
                .addValue("semesterId", semesterId)
                .addValue("batchId", batchId)
                .addValue("studentNo", row.studentNo())
                .addValue("studentName", row.studentName())
                .addValue("score", round2(row.score()))
                .addValue("maxScore", round2(row.maxScore()))
                .addValue("validFlag", row.valid() ? 1 : 0)
                .addValue("errorMessage", row.valid() ? null : row.errorMessage())
                .addValue("createdBy", teacherId));
        }
    }

    private String cellAt(List<String> row, int index) {
        return index >= 0 && index < row.size() ? defaultString(row.get(index), "") : "";
    }

    @SuppressWarnings("unused")
    private String assessItemName(long assessItemId) {
        return jdbcTemplate.query("""
            SELECT item_name
            FROM assess_item
            WHERE id = :id
            """, params("id", assessItemId), rs -> rs.next() ? rs.getString("item_name") : "");
    }

    private Map<String, Object> currentCalcRule() {
        return requireMap("""
            SELECT id, calc_method, threshold_value, pass_threshold, config_json
            FROM calc_rule
            WHERE is_default = 1
            ORDER BY id DESC
            LIMIT 1
            """, new MapSqlParameterSource());
    }

    private long ensureCalcRule(String calcMethod, double thresholdValue, double passThreshold,
            Map<String, Object> customThresholds, boolean retakeEnabled) {
        Map<String, Object> current = currentCalcRule();
        jdbcTemplate.update("UPDATE calc_rule SET is_default = 0 WHERE id = :id", params("id", longValue(current.get("id"))));
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        Map<String, Object> configData = new LinkedHashMap<>();
        configData.put("source", "runtime");
        if (!customThresholds.isEmpty()) {
            configData.put("customThresholds", customThresholds);
        }
        if (retakeEnabled) {
            configData.put("retakeEnabled", true);
        }
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
            .addValue("configJson", writeJson(configData)), keyHolder);
        return keyHolder.getKey().longValue();
    }

    private Map<Long, Map<String, Double>> perStudentItemRates(long outlineId, long semesterId, boolean retakeEnabled) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
            SELECT sg.assess_item_id, sg.student_no, sg.score, sg.max_score
            FROM student_grade sg
            JOIN grade_import_batch gb ON gb.id = sg.import_batch_id
            WHERE sg.semester_id = :semesterId
              AND sg.valid_flag = 1
              AND gb.status = 'CONFIRMED'
              AND sg.assess_item_id IN (
                  SELECT id FROM assess_item WHERE outline_id = :outlineId
              )
            ORDER BY sg.assess_item_id ASC, sg.student_no ASC, sg.id ASC
            """, new MapSqlParameterSource()
            .addValue("outlineId", outlineId)
            .addValue("semesterId", semesterId), (rs, rowNum) -> map(
            "assessItemId", rs.getLong("assess_item_id"),
            "studentNo", rs.getString("student_no"),
            "score", rs.getDouble("score"),
            "maxScore", rs.getDouble("max_score")
        ));

        Map<Long, Map<String, List<double[]>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            long itemId = longValue(row.get("assessItemId"));
            String studentNo = stringValue(row.get("studentNo"));
            grouped.computeIfAbsent(itemId, k -> new LinkedHashMap<>())
                   .computeIfAbsent(studentNo, k -> new ArrayList<>())
                   .add(new double[]{ doubleValue(row.get("score")), doubleValue(row.get("maxScore")) });
        }

        Map<Long, Map<String, Double>> result = new LinkedHashMap<>();
        for (Map.Entry<Long, Map<String, List<double[]>>> entry : grouped.entrySet()) {
            long itemId = entry.getKey();
            Map<String, Double> studentRates = new LinkedHashMap<>();
            for (Map.Entry<String, List<double[]>> se : entry.getValue().entrySet()) {
                List<double[]> grades = se.getValue();
                double rate;
                if (retakeEnabled && grades.size() >= 2) {
                    double[] first = grades.get(0);
                    double originalRate = first[1] > 0 ? first[0] / first[1] : 0D;
                    double bestRetake = grades.subList(1, grades.size()).stream()
                        .mapToDouble(g -> g[1] > 0 ? g[0] / g[1] * 0.8 : 0D)
                        .max().orElse(0D);
                    rate = Math.max(originalRate, bestRetake);
                } else {
                    double[] latest = grades.get(grades.size() - 1);
                    rate = latest[1] > 0 ? latest[0] / latest[1] : 0D;
                }
                studentRates.put(se.getKey(), round4(rate));
            }
            result.put(itemId, studentRates);
        }
        return result;
    }

    private Map<Long, Double> computeAvgRates(Map<Long, Map<String, Double>> itemStudentRates) {
        Map<Long, Double> avgRates = new LinkedHashMap<>();
        for (Map.Entry<Long, Map<String, Double>> entry : itemStudentRates.entrySet()) {
            double avg = entry.getValue().values().stream().mapToDouble(Double::doubleValue).average().orElse(0D);
            avgRates.put(entry.getKey(), round4(avg));
        }
        return avgRates;
    }

    private Map<String, Double> computeStudentObjectiveValues(
            List<Map<String, Object>> mappings,
            Map<Long, Map<String, Double>> itemStudentRates) {
        Set<String> allStudents = new LinkedHashSet<>();
        for (Map<String, Object> mapping : mappings) {
            long assessItemId = longValue(mapping.get("assessItemId"));
            allStudents.addAll(itemStudentRates.getOrDefault(assessItemId, Map.of()).keySet());
        }
        Map<String, Double> studentValues = new LinkedHashMap<>();
        for (String studentNo : allStudents) {
            double achieveValue = 0D;
            for (Map<String, Object> mapping : mappings) {
                long assessItemId = longValue(mapping.get("assessItemId"));
                double contribution = doubleValue(mapping.get("contributionWeight")) / 100D;
                achieveValue += itemStudentRates.getOrDefault(assessItemId, Map.of())
                    .getOrDefault(studentNo, 0D) * contribution;
            }
            studentValues.put(studentNo, round4(achieveValue));
        }
        return studentValues;
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

    private List<Map<String, Object>> defaultMappingWeights(long outlineId) {
        List<Map<String, Object>> items = assessItemListByOutline(outlineId);
        double totalWeight = items.stream().mapToDouble(item -> doubleValue(item.get("weight"))).sum();
        double fallbackWeight = items.isEmpty() ? 0D : round2(100D / items.size());
        return items.stream().map(item -> {
            double contribution = totalWeight > 0D
                ? round2(doubleValue(item.get("weight")) * 100D / totalWeight)
                : fallbackWeight;
            return map(
                "assessItemId", item.get("id"),
                "contributionWeight", contribution,
                "itemType", item.get("itemType"),
                "mappingSource", "fallback"
            );
        }).toList();
    }

    private Map<String, Object> buildAchievementDataSummary(long courseId, Long semesterId, Long outlineId) {
        if (semesterId == null || outlineId == null) {
            return map(
                "objectiveCount", 0,
                "assessItemCount", 0,
                "mappingCount", 0,
                "confirmedGradeRows", 0,
                "pendingGradeRows", 0,
                "assessItems", List.of(),
                "warnings", List.of("当前课程学期尚未建立课程大纲，无法核算达成度。")
            );
        }

        List<Map<String, Object>> objectives = objectiveList(outlineId);
        List<Map<String, Object>> assessItems = achievementAssessItemSummary(outlineId, courseId, semesterId);
        long mappingCount = count("""
            SELECT COUNT(*)
            FROM obj_assess_map m
            JOIN teach_objective t ON t.id = m.objective_id
            WHERE t.outline_id = :outlineId
            """, params("outlineId", outlineId));
        long confirmedRows = count("""
            SELECT COUNT(*)
            FROM student_grade sg
            JOIN grade_import_batch gb ON gb.id = sg.import_batch_id
            WHERE sg.course_id = :courseId
              AND sg.semester_id = :semesterId
              AND sg.valid_flag = 1
              AND gb.status = 'CONFIRMED'
            """, new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId));
        long pendingRows = count("""
            SELECT COUNT(*)
            FROM student_grade sg
            JOIN grade_import_batch gb ON gb.id = sg.import_batch_id
            WHERE sg.course_id = :courseId
              AND sg.semester_id = :semesterId
              AND gb.status <> 'CONFIRMED'
            """, new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId));

        List<String> warnings = new ArrayList<>();
        if (objectives.isEmpty()) {
            warnings.add("当前课程学期尚未维护教学目标。");
        }
        if (assessItems.isEmpty()) {
            warnings.add("当前课程学期尚未维护考核项。");
        }
        if (mappingCount == 0 && !objectives.isEmpty() && !assessItems.isEmpty()) {
            warnings.add("尚未配置目标-考核项支撑矩阵，本次核算将按考核项总成绩权重临时分配。");
        }
        if (confirmedRows == 0 && pendingRows > 0) {
            warnings.add("检测到待确认成绩数据，请先在成绩导入页面确认写入后再核算。");
        } else if (confirmedRows == 0) {
            warnings.add("当前课程学期尚无已确认成绩数据。");
        }
        return map(
            "objectiveCount", objectives.size(),
            "assessItemCount", assessItems.size(),
            "mappingCount", mappingCount,
            "confirmedGradeRows", confirmedRows,
            "pendingGradeRows", pendingRows,
            "assessItems", assessItems,
            "warnings", warnings
        );
    }

    private List<Map<String, Object>> achievementAssessItemSummary(long outlineId, long courseId, long semesterId) {
        return jdbcTemplate.query("""
            SELECT
                ai.id,
                ai.item_name,
                ai.item_type,
                ai.weight,
                COALESCE(SUM(CASE WHEN gb.status = 'CONFIRMED' AND sg.valid_flag = 1 THEN 1 ELSE 0 END), 0) AS confirmed_rows,
                COALESCE(SUM(CASE WHEN gb.status <> 'CONFIRMED' THEN 1 ELSE 0 END), 0) AS pending_rows,
                COALESCE(AVG(CASE WHEN gb.status = 'CONFIRMED' AND sg.valid_flag = 1 THEN sg.score / NULLIF(sg.max_score, 0) END), 0) AS avg_rate
            FROM assess_item ai
            LEFT JOIN student_grade sg
                ON sg.assess_item_id = ai.id
               AND sg.course_id = :courseId
               AND sg.semester_id = :semesterId
            LEFT JOIN grade_import_batch gb ON gb.id = sg.import_batch_id
            WHERE ai.outline_id = :outlineId
            GROUP BY ai.id, ai.item_name, ai.item_type, ai.weight, ai.sort_order
            ORDER BY ai.sort_order ASC, ai.id ASC
            """, new MapSqlParameterSource()
            .addValue("outlineId", outlineId)
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId), (rs, rowNum) -> map(
            "assessItemId", rs.getLong("id"),
            "itemName", rs.getString("item_name"),
            "itemType", defaultString(rs.getString("item_type"), ""),
            "itemTypeName", assessItemTypeName(rs.getString("item_type")),
            "weight", rs.getBigDecimal("weight"),
            "confirmedRows", rs.getLong("confirmed_rows"),
            "pendingRows", rs.getLong("pending_rows"),
            "avgRate", round4(rs.getDouble("avg_rate"))
        ));
    }

    private List<Map<String, Object>> buildDimensionData(List<Map<String, Object>> objectives) {
        double normal = 0D;
        double mid = 0D;
        double fin = 0D;
        for (Map<String, Object> item : objectives) {
            normal += doubleValue(item.get("normal"));
            mid += doubleValue(item.get("mid"));
            fin += doubleValue(item.get("final"));
        }
        int divisor = Math.max(objectives.size(), 1);
        return List.of(
            map("name", "平时", "value", round4(normal / divisor)),
            map("name", "期中", "value", round4(mid / divisor)),
            map("name", "期末", "value", round4(fin / divisor))
        );
    }

    private List<Map<String, Object>> achievementResults(long courseId, Long semesterId, boolean keepNumbers) {
        if (semesterId == null) {
            return List.of();
        }
        return jdbcTemplate.query("""
            SELECT
                ar.objective_id,
                t.obj_code,
                t.obj_content,
                t.weight,
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
            .addValue("semesterId", semesterId), (rs, rowNum) -> {
            long objectiveId = rs.getLong("objective_id");
            return map(
                "objectiveId", objectiveId,
                "objCode", rs.getString("obj_code"),
                "objContent", rs.getString("obj_content"),
                "objectiveWeight", rs.getBigDecimal("weight"),
                "normal", keepNumbers ? round4(rs.getDouble("normal_score")) : rs.getBigDecimal("normal_score"),
                "mid", keepNumbers ? round4(rs.getDouble("mid_score")) : rs.getBigDecimal("mid_score"),
                "final", keepNumbers ? round4(rs.getDouble("final_score")) : rs.getBigDecimal("final_score"),
                "achieveValue", keepNumbers ? round4(rs.getDouble("achieve_value")) : rs.getBigDecimal("achieve_value"),
                "isAchieved", rs.getInt("is_achieved") == 1,
                "details", achievementResultDetails(courseId, semesterId, objectiveId)
            );
        });
    }

    private List<Map<String, Object>> achievementResultDetails(long courseId, long semesterId, long objectiveId) {
        return jdbcTemplate.query("""
            SELECT
                ai.id,
                ai.item_name,
                ai.item_type,
                ai.weight,
                d.score_rate,
                d.contribution_weight,
                d.achieve_value
            FROM achieve_result ar
            JOIN achieve_result_detail d ON d.achieve_result_id = ar.id
            JOIN assess_item ai ON ai.id = d.assess_item_id
            WHERE ar.course_id = :courseId
              AND ar.semester_id = :semesterId
              AND ar.objective_id = :objectiveId
              AND ar.status = 1
            ORDER BY ai.sort_order ASC, ai.id ASC
            """, new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId)
            .addValue("objectiveId", objectiveId), (rs, rowNum) -> map(
            "assessItemId", rs.getLong("id"),
            "itemName", rs.getString("item_name"),
            "itemType", defaultString(rs.getString("item_type"), ""),
            "itemTypeName", assessItemTypeName(rs.getString("item_type")),
            "itemWeight", rs.getBigDecimal("weight"),
            "scoreRate", round4(rs.getDouble("score_rate")),
            "contributionWeight", rs.getBigDecimal("contribution_weight"),
            "achieveValue", round4(rs.getDouble("achieve_value"))
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
                    suggestion_source, priority, title, suggestion_text, data_basis_json, is_read, is_dismissed
                ) VALUES (
                    :receiverUserId, :courseId, :objectiveId, :semesterId, :ruleId, :ruleCode,
                    'REPORT_GEN', 1, :title, :suggestionText, :dataBasisJson, 0, 0
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
                    suggestion_source, priority, title, suggestion_text, data_basis_json, is_read, is_dismissed
                ) VALUES (
                    :receiverUserId, :courseId, NULL, :semesterId, :ruleId, :ruleCode,
                    'REPORT_GEN', 3, :title, :suggestionText, :dataBasisJson, 0, 0
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

    private List<Map<String, Object>> readMapList(Object value) {
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(String.valueOf(value), MAP_LIST_TYPE);
        } catch (Exception error) {
            return new ArrayList<>();
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

    private Integer nullableInteger(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (!StringUtils.hasText(text) || "null".equalsIgnoreCase(text)) {
            return null;
        }
        return intValue(value);
    }

    private BigDecimal nullableBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).replace("%", "").trim();
        if (!StringUtils.hasText(text) || "null".equalsIgnoreCase(text)) {
            return null;
        }
        return BigDecimal.valueOf(doubleValue(value)).setScale(1, RoundingMode.HALF_UP);
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

    private int defaultInt(Object value, int defaultValue) {
        return value == null || !StringUtils.hasText(String.valueOf(value)) ? defaultValue : intValue(value);
    }

    private double defaultDouble(Object value, double defaultValue) {
        return value == null || !StringUtils.hasText(String.valueOf(value)) ? defaultValue : doubleValue(value);
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

    private record GradeColumn(long assessItemId, double maxScore) {
    }

    private record GradeColumnCandidate(int index, long assessItemId, double maxScore, int quality) {
    }

    private record SheetSelection(String sheetName, List<List<String>> rows, int headerIndex, int score) {
    }

    private record ScoreParseResult(double score, boolean valid, String errorMessage) {
    }

    private record GradeImportRow(
        int rowNumber,
        String studentNo,
        String studentName,
        long assessItemId,
        double score,
        double maxScore,
        boolean valid,
        String errorMessage
    ) {
    }

    private record GradeImportResult(
        List<GradeImportRow> rows,
        int totalRows,
        int validRows,
        int errorRows
    ) {
    }
}
