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

    public Map<String, Object> getCourseDetail(Long courseId, String semester) {
        long currentCourseId = normalizeCourseId(courseId);
        String currentSemester = normalizeSemester(semester);
        Long outlineId = findOutlineId(currentCourseId, currentSemester);

        Map<String, Object> course = getCourseMetaById(currentCourseId);
        Map<String, Object> outline = outlineId == null ? new LinkedHashMap<>() : getOutlineById(outlineId);
        List<Map<String, Object>> objectives = outlineId == null ? new ArrayList<>() : objectiveList(outlineId);
        List<Map<String, Object>> assessItems = outlineId == null ? new ArrayList<>() : assessItemListByOutline(outlineId);
        List<Map<String, Object>> mappingRows = outlineId == null ? new ArrayList<>() : buildMappingRows(objectives, assessItems);
        Map<String, Object> latestParsedCourse = latestParsedCourseInfo(currentCourseId, currentSemester);
        List<Map<String, Object>> teachingContents = courseTeachingContentList(currentCourseId, currentSemester);
        if (!teachingContents.isEmpty() || !latestParsedCourse.containsKey("teachingContents")) {
            latestParsedCourse.put("teachingContents", teachingContents);
        }
        List<String> teacherNames = courseTeacherNames(currentCourseId, currentSemester);

        double objectiveWeight = objectives.stream()
            .mapToDouble(item -> defaultDouble(item.get("weight"), 0D))
            .sum();
        double assessWeight = assessItems.stream()
            .mapToDouble(item -> defaultDouble(item.get("weight"), 0D))
            .sum();

        Map<String, Object> result = referenceData();
        result.put("currentCourseId", currentCourseId);
        result.put("currentSemester", currentSemester);
        result.put("course", course);
        result.put("outline", outline);
        result.put("objectives", objectives);
        result.put("assessItems", assessItems);
        result.put("mappingRows", mappingRows);
        result.put("latestParsedCourse", latestParsedCourse);
        result.put("teacherNames", teacherNames);
        result.put("summary", map(
            "objectiveCount", objectives.size(),
            "objectiveWeight", round2(objectiveWeight),
            "assessItemCount", assessItems.size(),
            "assessWeight", round2(assessWeight),
            "mappingRowCount", mappingRows.size(),
            "teacherNames", teacherNames,
            "teachingContentCount", listSize(latestParsedCourse.get("teachingContents")),
            "assessmentDetailCount", listSize(latestParsedCourse.get("assessmentDetails"))
        ));
        return result;
    }

    public Map<String, Object> updateCourse(Long courseId, Map<String, Object> payload) {
        long id = longValue(courseId);
        applyCourseOverrides(id, payload, List.of(
            "courseCode",
            "courseNameZh",
            "courseNameEn",
            "courseType",
            "targetStudents",
            "teachingLanguage",
            "collegeName",
            "hours",
            "credits",
            "prerequisiteCourse",
            "courseOwner"
        ));
        return getCourseDetail(id, defaultString(payload.get("semester"), normalizeSemester(null)));
    }

    public Map<String, Object> updateCourseTeachingContents(Long courseId, String semester, Map<String, Object> payload) {
        long id = longValue(courseId);
        String semesterCode = normalizeSemester(semester);
        long semesterId = requireSemesterId(semesterCode);
        List<Map<String, Object>> teachingContents = normalizeTeachingContents(listOfMap(payload.get("teachingContents")));
        replaceCourseTeachingContents(id, semesterId, teachingContents);

        Map<String, Object> result = getCourseDetail(id, semesterCode);
        result.put("savedTeachingContents", teachingContents.size());
        return result;
    }

    public Map<String, Object> updateCourseAssessItems(Long courseId, String semester, Map<String, Object> payload) {
        long id = longValue(courseId);
        String semesterCode = normalizeSemester(semester);
        long outlineId = ensureOutline(id, semesterCode);
        List<Map<String, Object>> assessItems = normalizeAssessItems(listOfMap(payload.get("assessItems")));
        validateConfirmedWeightTotal(assessItems, "考核项", true);
        replaceAssessItemsSafely(outlineId, assessItems);

        Map<String, Object> result = getCourseDetail(id, semesterCode);
        result.put("savedAssessItems", assessItems.size());
        return result;
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
            todos.add(map("id", 2, "text", "有已解析的成绩批次等待确认导入。", "route", "/collect/grades/manage", "level", "medium"));
        }

        List<Map<String, Object>> quickLinks = List.of(
            map("label", "教学目标管理", "route", "/objectives/list"),
            map("label", "数据采集", "route", "/collect/grades/manage"),
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
        boolean overwriteObjectives = overwrite || booleanValue(payload.get("overwriteObjectives"));
        boolean overwriteAssessItems = overwrite || booleanValue(payload.get("overwriteAssessItems"));
        boolean overwriteMappings = overwrite || booleanValue(payload.get("overwriteMappings"));
        boolean overwriteTeachingContents = overwrite || booleanValue(payload.get("overwriteTeachingContents"));
        boolean anyOverwrite = overwrite || overwriteObjectives || overwriteAssessItems || overwriteMappings || overwriteTeachingContents;
        long semesterId = longValue(taskRow.get("semester_id"));
        long teacherId = longValue(taskRow.get("teacher_id"));
        String semesterCode = queryString("SELECT semester_code FROM base_semester WHERE id = :id", params("id", semesterId));
        long fallbackCourseId = longValue(taskRow.get("course_id"));
        Map<String, Object> parsedCourse = readMap(taskRow.get("parsed_course_json"));
        Map<String, Object> mergedCourseInfo = mergeCourseInfo(parsedCourse, mapOfObject(payload.get("courseInfo")), fallbackCourseId);
        String courseImportMode = defaultString(payload.get("courseImportMode"), "current");
        Long targetCourseId = nullableLong(payload.get("targetCourseId"));
        List<String> overwriteCourseFields = listOfString(payload.get("overwriteCourseFields"));
        anyOverwrite = anyOverwrite || !overwriteCourseFields.isEmpty();
        Map<String, Object> reviewConfirmations = mapOfObject(payload.get("reviewConfirmations"));
        Map<String, Object> reviewOverwrite = mapOfObject(payload.get("reviewOverwrite"));
        mergeExplicitReviewOverwrite(reviewOverwrite, payload);
        if (!reviewConfirmations.isEmpty()) {
            mergedCourseInfo.put("reviewConfirmations", reviewConfirmations);
        }
        if (!reviewOverwrite.isEmpty()) {
            mergedCourseInfo.put("reviewOverwrite", reviewOverwrite);
        }

        long courseId;
        if ("new".equalsIgnoreCase(courseImportMode)) {
            courseId = createCourseFromParseImport(mergedCourseInfo, fallbackCourseId, semesterId, teacherId);
        } else {
            courseId = targetCourseId == null ? fallbackCourseId : targetCourseId;
            applyCourseOverrides(courseId, mergedCourseInfo, overwriteCourseFields);
        }
        ensureCourseTeacher(courseId, teacherId, semesterId);
        long outlineId = ensureOutline(courseId, semesterCode);
        if ("overwrite".equalsIgnoreCase(courseImportMode)) {
            Map<String, Object> existingCourseInfo = latestParsedCourseInfo(courseId, semesterCode);
            List<Map<String, Object>> existingTeachingContents = courseTeachingContentList(courseId, semesterCode);
            if (!existingTeachingContents.isEmpty()) {
                existingCourseInfo.put("teachingContents", existingTeachingContents);
            }
            mergedCourseInfo = applyParsedSectionOverwritePolicy(mergedCourseInfo, existingCourseInfo, reviewOverwrite);
        }
        List<Map<String, Object>> submittedTeachingContents = normalizeTeachingContents(listOfMap(mergedCourseInfo.get("teachingContents")));

        boolean useAllObjectiveDrafts = booleanValue(reviewConfirmations.get("objectives"));
        String objectiveConfirmFilter = useAllObjectiveDrafts ? "  AND is_confirmed <> 2\n" : "  AND is_confirmed = 1\n";
        List<Map<String, Object>> objectiveDrafts = jdbcTemplate.query("""
            SELECT *
            FROM parse_objective_draft
            WHERE parse_task_id = :parseTaskId
            """ + objectiveConfirmFilter + """
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

        boolean useAllAssessDrafts = booleanValue(reviewConfirmations.get("assessItems"));
        String assessConfirmFilter = useAllAssessDrafts ? "  AND is_confirmed <> 2\n" : "  AND is_confirmed = 1\n";
        List<Map<String, Object>> assessDrafts = jdbcTemplate.query("""
            SELECT *
            FROM parse_assess_item_draft
            WHERE parse_task_id = :parseTaskId
            """ + assessConfirmFilter + """
            ORDER BY sort_order ASC, id ASC
            """, params("parseTaskId", parseTaskId), (rs, rowNum) -> map(
            "itemName", defaultString(rs.getString("item_name_final"), rs.getString("item_name_suggest")),
            "itemType", defaultString(rs.getString("item_type_final"), rs.getString("item_type_suggest")),
            "weight", rs.getObject("weight_final") == null ? rs.getBigDecimal("weight_suggest") : rs.getBigDecimal("weight_final")
        ));

        validateConfirmedWeightTotal(assessDrafts, "考核项", false);

        if (overwrite) {
            clearObjectiveImportData(outlineId);
        } else {
            if (overwriteMappings || overwriteObjectives || overwriteAssessItems) {
                clearObjectiveAssessMappings(outlineId);
            }
            if (overwriteObjectives) {
                clearObjectiveImportData(outlineId);
            }
        }

        int importedObjectiveCount = upsertObjectives(outlineId, objectiveDrafts);

        if (assessDrafts.isEmpty()) {
            ensureDefaultAssessItems(outlineId);
        } else if (overwrite || overwriteAssessItems) {
            replaceAssessItemsSafely(outlineId, assessDrafts);
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
            mappingList,
            overwriteMappings || overwriteObjectives || overwriteAssessItems || overwrite
        );
        int importedTeachingContentCount = 0;
        if (!submittedTeachingContents.isEmpty()) {
            boolean noExistingTeachingContent = count("""
                SELECT COUNT(*)
                FROM course_teaching_content
                WHERE course_id = :courseId
                  AND semester_id = :semesterId
                """, new MapSqlParameterSource()
                .addValue("courseId", courseId)
                .addValue("semesterId", semesterId)) == 0;
            if (overwriteTeachingContents || noExistingTeachingContent) {
                replaceCourseTeachingContents(courseId, semesterId, submittedTeachingContents);
                importedTeachingContentCount = submittedTeachingContents.size();
            }
        }
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
            .addValue("overwriteMode", anyOverwrite ? 1 : 0)
            .addValue("courseId", courseId)
            .addValue("outlineId", outlineId)
            .addValue("parsedCourseJson", writeJson(mergedCourseInfo)));

        return map(
            "courseId", courseId,
            "outlineId", outlineId,
            "courseName", defaultString(mergedCourseInfo.get("courseNameZh"), defaultString(mergedCourseInfo.get("courseName"), "")),
            "importedObjectives", importedObjectiveCount,
            "importedAssessItems", assessDrafts.isEmpty() ? assessItemListByOutline(outlineId).size() : assessDrafts.size(),
            "importedMappings", importedMappingCount,
            "importedTeachingContents", importedTeachingContentCount
        );
    }

    public Map<String, Object> getClasses(String keyword) {
        String text = defaultString(keyword, "");
        StringBuilder sql = new StringBuilder("""
            SELECT
                bc.id,
                bc.class_code,
                bc.class_name,
                bc.major_id,
                bm.major_name,
                bc.grade_year,
                bc.student_count,
                bc.status
            FROM base_class bc
            LEFT JOIN base_major bm ON bm.id = bc.major_id
            WHERE bc.status = 1
            """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (StringUtils.hasText(text)) {
            sql.append(" AND (bc.class_code LIKE :keyword OR bc.class_name LIKE :keyword OR bm.major_name LIKE :keyword)");
            params.addValue("keyword", "%" + text.trim() + "%");
        }
        sql.append(" ORDER BY bc.grade_year DESC, bc.class_code ASC, bc.id ASC");
        List<Map<String, Object>> rows = jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> classMap(rs));
        return map("items", rows, "total", rows.size());
    }

    public Map<String, Object> saveClass(Long id, Map<String, Object> payload) {
        String classCode = defaultString(payload.get("classCode"), "").trim();
        String className = defaultString(payload.get("className"), "").trim();
        Long majorId = nullableLong(payload.get("majorId"));
        String gradeYear = defaultString(payload.get("gradeYear"), "").trim();
        if (!StringUtils.hasText(classCode)) {
            throw new ApiException(UNPROCESSABLE_STATUS, 400, "班级编码不能为空。");
        }
        if (!StringUtils.hasText(className)) {
            throw new ApiException(UNPROCESSABLE_STATUS, 400, "班级名称不能为空。");
        }
        if (id == null) {
            GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update("""
                INSERT INTO base_class (class_code, class_name, major_id, grade_year, student_count, status)
                VALUES (:classCode, :className, :majorId, :gradeYear, 0, 1)
                """, new MapSqlParameterSource()
                .addValue("classCode", classCode)
                .addValue("className", className)
                .addValue("majorId", majorId)
                .addValue("gradeYear", gradeYear), keyHolder);
            id = keyHolder.getKey().longValue();
        } else {
            jdbcTemplate.update("""
                UPDATE base_class
                SET class_code = :classCode,
                    class_name = :className,
                    major_id = :majorId,
                    grade_year = :gradeYear,
                    updated_at = NOW()
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("classCode", classCode)
                .addValue("className", className)
                .addValue("majorId", majorId)
                .addValue("gradeYear", gradeYear));
        }
        refreshClassStudentCount(id);
        return map("success", true, "item", getClassById(id));
    }

    public Map<String, Object> getClassStudents(Long classId, String keyword) {
        if (classId == null) {
            return map("items", List.of(), "total", 0);
        }
        String text = defaultString(keyword, "");
        StringBuilder sql = new StringBuilder("""
            SELECT
                s.id,
                s.student_no,
                s.student_name,
                s.gender,
                s.class_id,
                bc.class_name,
                s.major_id,
                bm.major_name,
                s.phone,
                s.email,
                s.status
            FROM base_student s
            LEFT JOIN base_class bc ON bc.id = s.class_id
            LEFT JOIN base_major bm ON bm.id = s.major_id
            WHERE s.status = 1
              AND s.class_id = :classId
            """);
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("classId", classId);
        if (StringUtils.hasText(text)) {
            sql.append(" AND (s.student_no LIKE :keyword OR s.student_name LIKE :keyword)");
            params.addValue("keyword", "%" + text.trim() + "%");
        }
        sql.append(" ORDER BY s.student_no ASC, s.id ASC");
        List<Map<String, Object>> rows = jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> studentMap(rs));
        return map("items", rows, "total", rows.size());
    }

    public Map<String, Object> uploadStudents(Long classId, MultipartFile file) {
        if (classId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, 400, "导入学生时必须选择班级。");
        }
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, 400, "请先上传学生信息文件。");
        }
        validateGradeFileName(defaultString(file.getOriginalFilename(), "students.csv"));
        Map<String, Object> classRow = getClassById(classId);
        Long majorId = nullableLong(classRow.get("majorId"));
        try {
            StudentImportResult result = parseStudentFile(file, defaultString(file.getOriginalFilename(), ""), classId, majorId);
            for (StudentImportRow row : result.rows()) {
                if (!row.valid()) {
                    continue;
                }
                upsertStudent(row.studentNo(), row.studentName(), row.gender(), classId, majorId, row.phone(), row.email());
            }
            refreshClassStudentCount(classId);
            return map(
                "success", true,
                "importedCount", result.validRows(),
                "skippedCount", result.errorRows(),
                "errors", result.errors(),
                "students", getClassStudents(classId, "").get("items")
            );
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, 400, "学生信息文件读取失败，请检查文件是否损坏。");
        }
    }

    public Map<String, Object> saveStudent(Long id, Map<String, Object> payload) {
        Long classId = nullableLong(payload.get("classId"));
        String studentNo = defaultString(payload.get("studentNo"), "").trim();
        String studentName = defaultString(payload.get("studentName"), "").trim();
        String gender = defaultString(payload.get("gender"), "").trim();
        Long majorId = nullableLong(payload.get("majorId"));
        String phone = defaultString(payload.get("phone"), "").trim();
        String email = defaultString(payload.get("email"), "").trim();
        if (!StringUtils.hasText(studentNo)) {
            throw new ApiException(UNPROCESSABLE_STATUS, 400, "学号不能为空。");
        }
        if (!StringUtils.hasText(studentName)) {
            throw new ApiException(UNPROCESSABLE_STATUS, 400, "姓名不能为空。");
        }
        if (majorId == null && classId != null) {
            majorId = nullableLong(getClassById(classId).get("majorId"));
        }
        if (id == null) {
            id = upsertStudent(studentNo, studentName, gender, classId, majorId, phone, email);
        } else {
            jdbcTemplate.update("""
                UPDATE base_student
                SET student_no = :studentNo,
                    student_name = :studentName,
                    gender = :gender,
                    class_id = :classId,
                    major_id = :majorId,
                    phone = :phone,
                    email = :email,
                    status = 1,
                    updated_at = NOW()
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("studentNo", studentNo)
                .addValue("studentName", studentName)
                .addValue("gender", gender)
                .addValue("classId", classId)
                .addValue("majorId", majorId)
                .addValue("phone", phone)
                .addValue("email", email));
        }
        if (classId != null) {
            refreshClassStudentCount(classId);
        }
        linkStudentGrades(id, studentNo, classId);
        return map("success", true, "item", getStudentById(id));
    }

    public Map<String, Object> deleteStudent(Long id) {
        Map<String, Object> student = getStudentById(id);
        Long classId = nullableLong(student.get("classId"));
        jdbcTemplate.update("UPDATE base_student SET status = 0, updated_at = NOW() WHERE id = :id", params("id", id));
        if (classId != null) {
            refreshClassStudentCount(classId);
        }
        return map("success", true);
    }

    public Map<String, Object> getClassCourses(Long classId, String semester) {
        StringBuilder sql = new StringBuilder("""
            SELECT
                cc.id,
                cc.class_id,
                bc.class_name,
                cc.course_id,
                c.course_code,
                c.course_name,
                cc.semester_id,
                bs.semester_code,
                cc.teacher_id,
                u.real_name AS teacher_name,
                cc.status
            FROM class_course cc
            JOIN base_class bc ON bc.id = cc.class_id
            JOIN base_course c ON c.id = cc.course_id
            JOIN base_semester bs ON bs.id = cc.semester_id
            LEFT JOIN sys_user u ON u.id = cc.teacher_id
            WHERE cc.status = 1
            """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (classId != null) {
            sql.append(" AND cc.class_id = :classId");
            params.addValue("classId", classId);
        }
        if (StringUtils.hasText(semester)) {
            sql.append(" AND bs.semester_code = :semester");
            params.addValue("semester", semester);
        }
        sql.append(" ORDER BY bs.semester_code DESC, bc.class_code ASC, c.course_code ASC");
        List<Map<String, Object>> rows = jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> classCourseMap(rs));
        return map("items", rows, "total", rows.size());
    }

    public Map<String, Object> saveClassCourse(Map<String, Object> payload) {
        long classId = longValue(payload.get("classId"));
        long courseId = longValue(payload.get("courseId"));
        String semester = defaultString(payload.get("semester"), "");
        long semesterId = requireSemesterId(semester);
        Long teacherId = nullableLong(payload.get("teacherId"));
        if (teacherId == null) {
            teacherId = resolveTeacherId(courseId, semesterId);
        }
        jdbcTemplate.update("""
            INSERT INTO class_course (class_id, course_id, semester_id, teacher_id, status)
            VALUES (:classId, :courseId, :semesterId, :teacherId, 1)
            ON DUPLICATE KEY UPDATE
                teacher_id = VALUES(teacher_id),
                status = 1,
                updated_at = NOW()
            """, new MapSqlParameterSource()
            .addValue("classId", classId)
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId)
            .addValue("teacherId", teacherId));
        return getClassCourses(classId, semester);
    }

    public Map<String, Object> deleteClassCourse(Long id) {
        jdbcTemplate.update("UPDATE class_course SET status = 0, updated_at = NOW() WHERE id = :id", params("id", id));
        return map("success", true);
    }

    public Map<String, Object> getAssessmentContents(Long courseId, String semester) {
        long currentCourseId = normalizeCourseId(courseId);
        String currentSemester = normalizeSemester(semester);
        List<Map<String, Object>> assessItems = currentCourseAssessItems(currentCourseId, currentSemester);
        if (assessItems.isEmpty()) {
            return map("assessItems", List.of(), "contents", List.of(), "warnings", List.of("当前课程学期尚未配置考核项。"));
        }
        ensureDefaultAssessContents(assessItems);
        List<Map<String, Object>> contents = activeAssessContents(assessItems.stream().map(item -> longValue(item.get("id"))).toList());
        Map<Long, List<Map<String, Object>>> contentsByItem = contents.stream()
            .collect(Collectors.groupingBy(
                item -> longValue(item.get("assessItemId")),
                LinkedHashMap::new,
                Collectors.toCollection(ArrayList::new)
            ));

        List<Map<String, Object>> groupedItems = assessItems.stream().map(item -> {
            long assessItemId = longValue(item.get("id"));
            List<Map<String, Object>> itemContents = contentsByItem.getOrDefault(assessItemId, List.of());
            double contentWeight = itemContents.stream().mapToDouble(content -> doubleValue(content.get("weight"))).sum();
            return map(
                "id", assessItemId,
                "itemName", item.get("itemName"),
                "itemType", item.get("itemType"),
                "itemTypeName", item.get("itemTypeName"),
                "weight", item.get("weight"),
                "contentWeight", round2(contentWeight),
                "contents", itemContents
            );
        }).toList();

        return map(
            "assessItems", groupedItems,
            "contents", contents,
            "warnings", assessmentContentWarnings(groupedItems)
        );
    }

    public Map<String, Object> saveAssessmentContents(Map<String, Object> payload) {
        long courseId = longValue(payload.get("courseId"));
        String semester = defaultString(payload.get("semester"), "");
        List<Map<String, Object>> assessItems = currentCourseAssessItems(courseId, semester);
        if (assessItems.isEmpty()) {
            throw new ApiException(UNPROCESSABLE_STATUS, 400, "当前课程学期尚未配置考核项，无法维护考核内容。");
        }
        Map<Long, Map<String, Object>> assessById = assessItems.stream()
            .collect(Collectors.toMap(item -> longValue(item.get("id")), item -> item, (left, right) -> left, LinkedHashMap::new));
        List<Map<String, Object>> contents = listOfMap(payload.get("contents"));
        if (contents.isEmpty()) {
            throw new ApiException(UNPROCESSABLE_STATUS, 400, "至少需要保留 1 条考核内容。");
        }

        Map<Long, Double> weightByAssess = new LinkedHashMap<>();
        for (Map<String, Object> row : contents) {
            long assessItemId = longValue(row.get("assessItemId"));
            if (!assessById.containsKey(assessItemId)) {
                throw new ApiException(UNPROCESSABLE_STATUS, 400, "考核内容关联的考核项不存在。");
            }
            String contentName = defaultString(row.get("contentName"), "").trim();
            if (!StringUtils.hasText(contentName)) {
                throw new ApiException(UNPROCESSABLE_STATUS, 400, "考核内容不能为空。");
            }
            double weight = round2(defaultDouble(row.get("weight"), 0D));
            if (weight < 0D) {
                throw new ApiException(UNPROCESSABLE_STATUS, 400, "考核内容权重不能为负数。");
            }
            weightByAssess.merge(assessItemId, weight, Double::sum);
        }
        Set<Long> keepIds = new LinkedHashSet<>();
        int order = 1;
        for (Map<String, Object> row : contents) {
            long assessItemId = longValue(row.get("assessItemId"));
            Long id = nullableLong(row.get("id"));
            String contentNo = defaultString(row.get("contentNo"), "").trim();
            if (!StringUtils.hasText(contentNo)) {
                contentNo = String.valueOf(order);
            }
            String contentName = defaultString(row.get("contentName"), "").trim();
            String contentType = normalizeAssessContentType(defaultString(row.get("contentType"), ""));
            double weight = round2(defaultDouble(row.get("weight"), 0D));
            int sortOrder = defaultInt(row.get("sortOrder"), order);
            MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("assessItemId", assessItemId)
                .addValue("contentNo", contentNo)
                .addValue("contentName", contentName)
                .addValue("contentType", contentType)
                .addValue("weight", weight)
                .addValue("sortOrder", sortOrder);
            if (id == null) {
                jdbcTemplate.update("""
                    INSERT INTO assess_content (
                        assess_item_id, content_no, content_name, content_type, weight, sort_order, status
                    ) VALUES (
                        :assessItemId, :contentNo, :contentName, :contentType, :weight, :sortOrder, 1
                    )
                    ON DUPLICATE KEY UPDATE
                        content_name = VALUES(content_name),
                        content_type = VALUES(content_type),
                        weight = VALUES(weight),
                        sort_order = VALUES(sort_order),
                        status = 1,
                        updated_at = NOW()
                    """, params);
                id = queryLong("""
                    SELECT id
                    FROM assess_content
                    WHERE assess_item_id = :assessItemId
                      AND content_no = :contentNo
                    """, params);
            } else {
                jdbcTemplate.update("""
                    UPDATE assess_content
                    SET assess_item_id = :assessItemId,
                        content_no = :contentNo,
                        content_name = :contentName,
                        content_type = :contentType,
                        weight = :weight,
                        sort_order = :sortOrder,
                        status = 1,
                        updated_at = NOW()
                    WHERE id = :id
                    """, params);
            }
            keepIds.add(id);
            order++;
        }

        retireRemovedAssessContents(assessById.keySet(), keepIds);
        return getAssessmentContents(courseId, semester);
    }

    public Map<String, Object> uploadGradeFile(Long courseId, Long classId, Long assessItemId, String semester, MultipartFile file) {
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
        List<Map<String, Object>> targetAssessItems = assessItemId == null
            ? assessItems
            : assessItems.stream().filter(item -> longValue(item.get("id")) == assessItemId).toList();
        if (targetAssessItems.isEmpty()) {
            throw new ApiException(UNPROCESSABLE_STATUS, 400, "未找到本次导入关联的考核项。");
        }
        ensureDefaultAssessContents(targetAssessItems);
        List<Map<String, Object>> targetContents = assessItemId == null
            ? activeAssessContents(targetAssessItems.stream().map(item -> longValue(item.get("id"))).toList())
            : activeAssessContentsByItem(assessItemId);
        long batchAssessItemId = assessItemId == null ? longValue(assessItems.get(0).get("id")) : assessItemId;
        Long batchAssessContentId = targetContents.size() == 1 ? longValue(targetContents.get(0).get("id")) : null;
        String batchNo = "GRADE-" + System.currentTimeMillis();

        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
            INSERT INTO grade_import_batch (
                batch_no, course_id, class_id, assess_item_id, assess_content_id, teacher_id, semester_id, source_file_name,
                status, total_rows, valid_rows, error_rows, import_mode
            ) VALUES (
                :batchNo, :courseId, :classId, :assessItemId, :assessContentId, :teacherId, :semesterId, :fileName,
                'PARSING', 12, 10, 2, 'valid_only'
            )
            """, new MapSqlParameterSource()
            .addValue("batchNo", batchNo)
            .addValue("courseId", currentCourseId)
            .addValue("classId", classId)
            .addValue("assessItemId", batchAssessItemId)
            .addValue("assessContentId", batchAssessContentId)
            .addValue("teacherId", teacherId)
            .addValue("semesterId", semesterId)
            .addValue("fileName", fileName), keyHolder);
        long batchId = keyHolder.getKey().longValue();

        try {
            GradeImportResult result = targetContents.isEmpty()
                ? parseGradeFile(file, fileName, targetAssessItems)
                : parseGradeContentFile(file, fileName, targetContents);
            persistGradeImportRows(batchId, currentCourseId, classId, semesterId, teacherId, result.rows());
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
                sg.assess_content_id,
                ai.item_name,
                ac.content_name,
                sg.score,
                sg.max_score,
                sg.valid_flag,
                sg.error_message
            FROM student_grade sg
            JOIN assess_item ai ON ai.id = sg.assess_item_id
            LEFT JOIN assess_content ac ON ac.id = sg.assess_content_id
            WHERE sg.import_batch_id = :batchId
            ORDER BY sg.id ASC
            """, params("batchId", longValue(batch.get("id"))), (rs, rowNum) -> map(
            "row", rowNum + 1,
            "gradeId", rs.getLong("id"),
            "studentId", rs.getString("student_no"),
            "name", rs.getString("student_name"),
            "assessItemId", rs.getLong("assess_item_id"),
            "assessContentId", nullableLong(rs.getObject("assess_content_id")),
            "assessItemName", defaultString(rs.getString("content_name"), rs.getString("item_name")),
            "parentAssessItemName", rs.getString("item_name"),
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
        Map<String, Map<String, Object>> columns = new LinkedHashMap<>();
        for (Map<String, Object> item : preview) {
            String columnKey = gradeColumnKey(item);
            if (columns.containsKey(columnKey)) {
                continue;
            }
            columns.put(columnKey, map(
                "columnKey", columnKey,
                "assessItemId", item.get("assessItemId"),
                "assessContentId", item.get("assessContentId"),
                "assessItemName", item.get("assessItemName"),
                "parentAssessItemName", item.get("parentAssessItemName"),
                "maxScore", item.get("maxScore")
            ));
        }
        return new ArrayList<>(columns.values());
    }

    private String gradeColumnKey(Map<String, Object> item) {
        Long assessContentId = nullableLong(item.get("assessContentId"));
        return longValue(item.get("assessItemId")) + ":" + (assessContentId == null ? 0L : assessContentId);
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
            Map<String, Map<String, Object>> itemByColumnKey = new LinkedHashMap<>();
            for (Map<String, Object> item : items) {
                itemByColumnKey.put(gradeColumnKey(item), item);
            }

            List<Map<String, Object>> cells = new ArrayList<>();
            boolean valid = true;
            List<String> errors = new ArrayList<>();
            for (Map<String, Object> column : columns) {
                String columnKey = defaultString(column.get("columnKey"), "");
                Map<String, Object> item = itemByColumnKey.get(columnKey);
                boolean cellValid = item != null && booleanValue(item.get("valid"));
                String errorMsg = item == null ? "missing grade cell" : defaultString(item.get("errorMsg"), "有效");
                if (!cellValid) {
                    valid = false;
                    errors.add(defaultString(column.get("assessItemName"), "") + "：" + errorMsg);
                }
                cells.add(map(
                    "columnKey", columnKey,
                    "gradeId", item == null ? null : item.get("gradeId"),
                    "assessItemId", column.get("assessItemId"),
                    "assessContentId", column.get("assessContentId"),
                    "assessItemName", column.get("assessItemName"),
                    "parentAssessItemName", column.get("parentAssessItemName"),
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
                    "columnKey", longValue(item.get("id")) + ":0",
                    "assessItemId", item.get("id"),
                    "assessContentId", null,
                    "assessItemName", item.get("itemName"),
                    "parentAssessItemName", item.get("itemName"),
                    "maxScore", item.get("weight")
                ))
                .toList();
            if (!matchedItems.isEmpty()) {
                return matchedItems;
            }
            return importedItems.stream()
                .filter(item -> String.valueOf(item.get("assessItemId")).equals(assessItemId))
                .map(item -> map(
                    "columnKey", longValue(item.get("assessItemId")) + ":0",
                    "assessItemId", item.get("assessItemId"),
                    "assessContentId", null,
                    "assessItemName", item.get("itemName"),
                    "parentAssessItemName", item.get("itemName"),
                    "maxScore", item.get("weight")
                ))
                .distinct()
                .toList();
        }

        if (!assessItems.isEmpty()) {
            return assessItems.stream().map(item -> map(
                "columnKey", longValue(item.get("id")) + ":0",
                "assessItemId", item.get("id"),
                "assessContentId", null,
                "assessItemName", item.get("itemName"),
                "parentAssessItemName", item.get("itemName"),
                "maxScore", item.get("weight")
            )).toList();
        }

        return buildGradePreviewColumns(importedItems.stream().map(item -> map(
            "assessItemId", item.get("assessItemId"),
            "assessContentId", null,
            "assessItemName", item.get("itemName"),
            "parentAssessItemName", item.get("itemName"),
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
                    "columnKey", defaultString(column.get("columnKey"), assessItemId + ":0"),
                    "gradeId", item == null ? null : item.get("gradeId"),
                    "assessItemId", assessItemId,
                    "assessContentId", column.get("assessContentId"),
                    "assessItemName", column.get("assessItemName"),
                    "parentAssessItemName", column.get("parentAssessItemName"),
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

    private List<Map<String, Object>> queryImportedGradeComponents(
        long courseId,
        long semesterId,
        String assessItemId,
        String classId,
        String keyword
    ) {
        StringBuilder sql = new StringBuilder("""
            SELECT
                sg.id,
                sg.student_no,
                sg.student_name,
                sg.class_id,
                bc.class_name,
                sg.score,
                sg.max_score,
                ai.id AS assess_item_id,
                ai.item_name,
                ac.id AS assess_content_id,
                ac.content_name,
                ac.content_no,
                ac.content_type,
                sg.valid_flag
            FROM student_grade sg
            JOIN grade_import_batch gb ON gb.id = sg.import_batch_id
            JOIN assess_item ai ON ai.id = sg.assess_item_id
            JOIN assess_content ac ON ac.id = sg.assess_content_id
            LEFT JOIN base_class bc ON bc.id = sg.class_id
            WHERE gb.status = 'CONFIRMED'
              AND sg.valid_flag = 1
              AND sg.assess_content_id IS NOT NULL
              AND sg.course_id = :courseId
              AND sg.semester_id = :semesterId
            """);
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId);
        appendTextFilter(sql, params, "assessItemId", assessItemId, "sg.assess_item_id = :assessItemId");
        appendTextFilter(sql, params, "classId", classId, "sg.class_id = :classId");
        if (StringUtils.hasText(keyword)) {
            sql.append(" AND (sg.student_no LIKE :keyword OR sg.student_name LIKE :keyword)");
            params.addValue("keyword", "%" + keyword.trim() + "%");
        }
        sql.append(" ORDER BY sg.student_no ASC, ai.sort_order ASC, ac.sort_order ASC, ac.id ASC, sg.id ASC");
        return jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> map(
            "id", rs.getLong("id"),
            "gradeId", rs.getLong("id"),
            "studentNo", rs.getString("student_no"),
            "studentId", rs.getString("student_no"),
            "studentName", rs.getString("student_name"),
            "name", rs.getString("student_name"),
            "classId", nullableLong(rs.getObject("class_id")),
            "className", defaultString(rs.getString("class_name"), ""),
            "score", rs.getBigDecimal("score"),
            "maxScore", rs.getBigDecimal("max_score"),
            "assessItemId", rs.getLong("assess_item_id"),
            "assessContentId", rs.getLong("assess_content_id"),
            "assessItemName", rs.getString("content_name"),
            "parentAssessItemName", rs.getString("item_name"),
            "contentNo", rs.getString("content_no"),
            "contentType", rs.getString("content_type"),
            "contentTypeName", assessContentTypeName(rs.getString("content_type")),
            "validFlag", rs.getInt("valid_flag")
        ));
    }

    private List<Map<String, Object>> gradeComponentManageColumns(long courseId, String semester, String assessItemId) {
        List<Map<String, Object>> assessItems = currentCourseAssessItems(courseId, semester);
        if (StringUtils.hasText(assessItemId)) {
            assessItems = assessItems.stream()
                .filter(item -> String.valueOf(item.get("id")).equals(assessItemId))
                .toList();
        }
        ensureDefaultAssessContents(assessItems);
        return activeAssessContents(assessItems.stream().map(item -> longValue(item.get("id"))).toList()).stream()
            .map(content -> map(
                "columnKey", longValue(content.get("assessItemId")) + ":" + longValue(content.get("id")),
                "assessItemId", content.get("assessItemId"),
                "assessContentId", content.get("id"),
                "assessItemName", content.get("contentName"),
                "parentAssessItemName", content.get("assessItemName"),
                "contentNo", content.get("contentNo"),
                "contentType", content.get("contentType"),
                "contentTypeName", content.get("contentTypeName"),
                "maxScore", content.get("weight")
            ))
            .toList();
    }

    private List<Map<String, Object>> buildCombinedGradeManageRows(
        List<Map<String, Object>> componentItems,
        List<Map<String, Object>> componentColumns,
        List<Map<String, Object>> summaryRows,
        List<Map<String, Object>> summaryColumns
    ) {
        Map<String, Map<String, Object>> baseRows = new LinkedHashMap<>();
        for (Map<String, Object> row : summaryRows) {
            String key = defaultString(row.get("studentNo"), "") + "|" + defaultString(row.get("studentName"), "");
            baseRows.put(key, new LinkedHashMap<>(row));
        }
        for (Map<String, Object> item : componentItems) {
            String key = defaultString(item.get("studentNo"), "") + "|" + defaultString(item.get("studentName"), "");
            baseRows.computeIfAbsent(key, ignored -> map(
                "studentNo", item.get("studentNo"),
                "studentName", item.get("studentName"),
                "valid", true,
                "errorMsg", "有效"
            ));
        }

        Map<String, List<Map<String, Object>>> componentsByStudent = componentItems.stream()
            .collect(Collectors.groupingBy(
                item -> defaultString(item.get("studentNo"), "") + "|" + defaultString(item.get("studentName"), ""),
                LinkedHashMap::new,
                Collectors.toCollection(ArrayList::new)
            ));

        List<Map<String, Object>> rows = new ArrayList<>();
        int rowIndex = 1;
        for (Map.Entry<String, Map<String, Object>> entry : baseRows.entrySet()) {
            Map<String, Object> row = entry.getValue();
            List<Map<String, Object>> summaryCells = listOfMap(row.get("cells"));
            if (summaryCells.isEmpty()) {
                summaryCells = summaryColumns.stream().map(column -> map(
                    "columnKey", defaultString(column.get("columnKey"), longValue(column.get("assessItemId")) + ":0"),
                    "gradeId", null,
                    "assessItemId", column.get("assessItemId"),
                    "assessContentId", null,
                    "assessItemName", column.get("assessItemName"),
                    "parentAssessItemName", column.get("parentAssessItemName"),
                    "score", "",
                    "maxScore", column.get("maxScore"),
                    "valid", true,
                    "errorMsg", "有效"
                )).toList();
            }

            Map<String, Map<String, Object>> componentByColumn = new LinkedHashMap<>();
            for (Map<String, Object> item : componentsByStudent.getOrDefault(entry.getKey(), List.of())) {
                componentByColumn.put(gradeColumnKey(item), item);
            }
            List<Map<String, Object>> componentCells = new ArrayList<>();
            for (Map<String, Object> column : componentColumns) {
                String columnKey = defaultString(column.get("columnKey"), "");
                Map<String, Object> item = componentByColumn.get(columnKey);
                componentCells.add(map(
                    "columnKey", columnKey,
                    "gradeId", item == null ? null : item.get("gradeId"),
                    "assessItemId", column.get("assessItemId"),
                    "assessContentId", column.get("assessContentId"),
                    "assessItemName", column.get("assessItemName"),
                    "parentAssessItemName", column.get("parentAssessItemName"),
                    "contentType", column.get("contentType"),
                    "contentTypeName", column.get("contentTypeName"),
                    "score", item == null ? "" : item.get("score"),
                    "maxScore", item == null ? column.get("maxScore") : item.get("maxScore"),
                    "valid", true,
                    "errorMsg", "有效"
                ));
            }

            double totalScore = summaryCells.stream().mapToDouble(cell -> defaultDouble(cell.get("score"), 0D)).sum();
            row.put("row", rowIndex++);
            row.put("cells", summaryCells);
            row.put("summaryCells", summaryCells);
            row.put("componentCells", componentCells);
            row.put("totalScore", round2(totalScore));
            row.put("gradeLevel", gradeLevel(totalScore));
            rows.add(row);
        }
        return rows;
    }

    private String gradeLevel(double score) {
        if (score >= 90D) return "优";
        if (score >= 80D) return "良";
        if (score >= 70D) return "中等";
        if (score >= 60D) return "及格";
        return "不及格";
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
              AND sg.assess_content_id IS NULL
              AND sg.student_no = :studentNo
            ORDER BY sg.id DESC
            LIMIT 1
            """, new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId)
            .addValue("assessItemId", assessItemId)
            .addValue("studentNo", studentNo), rs -> rs.next() ? rs.getLong("id") : null);
    }

    private Long findConfirmedComponentGradeId(long courseId, long semesterId, long assessItemId, long assessContentId, String studentNo) {
        return jdbcTemplate.query("""
            SELECT sg.id
            FROM student_grade sg
            JOIN grade_import_batch gb ON gb.id = sg.import_batch_id
            WHERE gb.status = 'CONFIRMED'
              AND sg.valid_flag = 1
              AND sg.course_id = :courseId
              AND sg.semester_id = :semesterId
              AND sg.assess_item_id = :assessItemId
              AND sg.assess_content_id = :assessContentId
              AND sg.student_no = :studentNo
            ORDER BY sg.id DESC
            LIMIT 1
            """, new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId)
            .addValue("assessItemId", assessItemId)
            .addValue("assessContentId", assessContentId)
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
            SELECT id, student_no, assess_item_id, assess_content_id
            FROM student_grade
            WHERE import_batch_id = :batchId
              AND valid_flag = 1
            ORDER BY id ASC
            """, params("batchId", batchId), (rs, rowNum) -> map(
            "id", rs.getLong("id"),
            "studentNo", rs.getString("student_no"),
            "assessItemId", rs.getLong("assess_item_id"),
            "assessContentId", nullableLong(rs.getObject("assess_content_id"))
        ));
        if (batchRows.isEmpty()) return;

        Set<String> confirmedKeys = new java.util.HashSet<>(jdbcTemplate.query("""
            SELECT sg.student_no, sg.assess_item_id, sg.assess_content_id
            FROM student_grade sg
            JOIN grade_import_batch gb ON gb.id = sg.import_batch_id
            WHERE sg.course_id = :courseId
              AND sg.semester_id = :semesterId
              AND sg.valid_flag = 1
              AND gb.status = 'CONFIRMED'
            """, new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId),
            (rs, rowNum) -> rs.getString("student_no")
                + "|" + rs.getLong("assess_item_id")
                + "|" + defaultString(nullableLong(rs.getObject("assess_content_id")), "0")
        ));

        Set<String> seenInBatch = new java.util.HashSet<>();
        for (Map<String, Object> row : batchRows) {
            String key = defaultString(row.get("studentNo"), "")
                + "|" + longValue(row.get("assessItemId"))
                + "|" + defaultString(row.get("assessContentId"), "0");
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

    private void syncAggregatedGradesForBatch(long batchId) {
        List<Map<String, Object>> affectedRows = jdbcTemplate.query("""
            SELECT DISTINCT
                sg.course_id,
                sg.class_id,
                sg.student_id,
                sg.assess_item_id,
                sg.semester_id,
                sg.student_no,
                sg.student_name,
                sg.created_by
            FROM student_grade sg
            WHERE sg.import_batch_id = :batchId
              AND sg.valid_flag = 1
              AND sg.assess_content_id IS NOT NULL
            """, params("batchId", batchId), (rs, rowNum) -> map(
            "courseId", rs.getLong("course_id"),
            "classId", nullableLong(rs.getObject("class_id")),
            "studentId", nullableLong(rs.getObject("student_id")),
            "assessItemId", rs.getLong("assess_item_id"),
            "semesterId", rs.getLong("semester_id"),
            "studentNo", rs.getString("student_no"),
            "studentName", rs.getString("student_name"),
            "createdBy", nullableLong(rs.getObject("created_by"))
        ));
        for (Map<String, Object> row : affectedRows) {
            syncAggregatedGrade(
                longValue(row.get("courseId")),
                nullableLong(row.get("classId")),
                nullableLong(row.get("studentId")),
                longValue(row.get("assessItemId")),
                longValue(row.get("semesterId")),
                batchId,
                defaultString(row.get("studentNo"), ""),
                defaultString(row.get("studentName"), ""),
                nullableLong(row.get("createdBy"))
            );
        }
    }

    private void syncAggregatedGrade(
        long courseId,
        Long classId,
        Long studentId,
        long assessItemId,
        long semesterId,
        long batchId,
        String studentNo,
        String studentName,
        Long createdBy
    ) {
        if (!StringUtils.hasText(studentNo)) {
            return;
        }
        Map<String, Object> totals = requireMap("""
            SELECT
                COALESCE(SUM(sg.score), 0) AS score,
                COALESCE(SUM(sg.max_score), 0) AS max_score
            FROM student_grade sg
            JOIN grade_import_batch gb ON gb.id = sg.import_batch_id
            WHERE gb.status = 'CONFIRMED'
              AND sg.valid_flag = 1
              AND sg.assess_content_id IS NOT NULL
              AND sg.course_id = :courseId
              AND sg.semester_id = :semesterId
              AND sg.assess_item_id = :assessItemId
              AND sg.student_no = :studentNo
            """, new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId)
            .addValue("assessItemId", assessItemId)
            .addValue("studentNo", studentNo));
        double score = round2(doubleValue(totals.get("score")));
        double maxScore = round2(queryDouble("""
            SELECT weight
            FROM assess_item
            WHERE id = :assessItemId
            """, params("assessItemId", assessItemId)));
        if (maxScore <= 0D) {
            maxScore = round2(doubleValue(totals.get("max_score")));
        }
        Long existingId = findConfirmedGradeId(courseId, semesterId, assessItemId, studentNo);
        if (existingId == null) {
            jdbcTemplate.update("""
                INSERT INTO student_grade (
                    course_id, class_id, student_id, assess_item_id, assess_content_id, semester_id, import_batch_id,
                    student_no, student_name, score, max_score, valid_flag, error_message, created_by
                ) VALUES (
                    :courseId, :classId, :studentId, :assessItemId, NULL, :semesterId, :batchId,
                    :studentNo, :studentName, :score, :maxScore, 1, NULL, :createdBy
                )
                """, new MapSqlParameterSource()
                .addValue("courseId", courseId)
                .addValue("classId", classId)
                .addValue("studentId", studentId)
                .addValue("assessItemId", assessItemId)
                .addValue("semesterId", semesterId)
                .addValue("batchId", batchId)
                .addValue("studentNo", studentNo)
                .addValue("studentName", studentName)
                .addValue("score", score)
                .addValue("maxScore", maxScore)
                .addValue("createdBy", createdBy));
        } else {
            jdbcTemplate.update("""
                UPDATE student_grade
                SET class_id = COALESCE(:classId, class_id),
                    student_id = COALESCE(:studentId, student_id),
                    student_name = :studentName,
                    score = :score,
                    max_score = :maxScore,
                    valid_flag = 1,
                    error_message = NULL,
                    updated_at = NOW()
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", existingId)
                .addValue("classId", classId)
                .addValue("studentId", studentId)
                .addValue("studentName", studentName)
                .addValue("score", score)
                .addValue("maxScore", maxScore));
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
        syncAggregatedGradesForBatch(id);
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
        String classId = defaultString(filters.get("classId"), "");

        StringBuilder sql = new StringBuilder("""
            SELECT
                sg.id,
                sg.student_no,
                sg.student_name,
                sg.class_id,
                bc.class_name,
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
            LEFT JOIN base_class bc ON bc.id = sg.class_id
            WHERE gb.status = 'CONFIRMED'
              AND sg.valid_flag = 1
              AND sg.assess_content_id IS NULL
              AND sg.course_id = :courseId
              AND sg.semester_id = :semesterId
            """);
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId);
        appendTextFilter(sql, params, "assessItemId", assessItemId, "sg.assess_item_id = :assessItemId");
        appendTextFilter(sql, params, "classId", classId, "sg.class_id = :classId");
        if (StringUtils.hasText(keyword)) {
            sql.append(" AND (sg.student_no LIKE :keyword OR sg.student_name LIKE :keyword)");
            params.addValue("keyword", "%" + keyword.trim() + "%");
        }
        sql.append(" ORDER BY sg.student_no ASC, ai.sort_order ASC, ai.id ASC, sg.id ASC");

        List<Map<String, Object>> items = jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> map(
            "id", rs.getLong("id"),
            "studentNo", rs.getString("student_no"),
            "studentName", rs.getString("student_name"),
            "classId", nullableLong(rs.getObject("class_id")),
            "className", defaultString(rs.getString("class_name"), ""),
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
        List<Map<String, Object>> summaryRows = buildGradeManageRows(items.stream().map(item -> map(
            "row", 0,
            "gradeId", item.get("id"),
            "studentId", item.get("studentNo"),
            "name", item.get("studentName"),
            "assessItemId", item.get("assessItemId"),
            "assessContentId", null,
            "assessItemName", item.get("itemName"),
            "parentAssessItemName", item.get("itemName"),
            "score", item.get("score"),
            "maxScore", item.get("maxScore"),
            "valid", true,
            "errorMsg", "有效"
        )).toList(), columns);

        List<Map<String, Object>> componentItems = queryImportedGradeComponents(courseId, semesterId, assessItemId, classId, keyword);
        List<Map<String, Object>> componentColumns = gradeComponentManageColumns(courseId, semester, assessItemId);
        List<Map<String, Object>> rows = buildCombinedGradeManageRows(componentItems, componentColumns, summaryRows, columns);

        return map(
            "items", items,
            "componentItems", componentItems,
            "columns", columns,
            "summaryColumns", columns,
            "componentColumns", componentColumns,
            "rows", rows,
            "total", rows.size()
        );
    }

    public Map<String, Object> saveImportedGradeRow(Map<String, Object> payload) {
        long courseId = longValue(payload.get("courseId"));
        Long classId = nullableLong(payload.get("classId"));
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
        Long studentId = findStudentId(studentNo);
        if (studentId == null && classId != null) {
            Long majorId = nullableLong(getClassById(classId).get("majorId"));
            studentId = upsertStudent(studentNo, studentName, "", classId, majorId, "", "");
        }
        Long resolvedClassId = classId != null ? classId : findStudentClassId(studentNo);

        long batchId = ensureManualGradeBatch(courseId, semesterId, teacherId);
        List<Map<String, Object>> componentCells = listOfMap(payload.get("componentCells"));
        if (!componentCells.isEmpty()) {
            Set<Long> affectedAssessItems = new LinkedHashSet<>();
            for (Map<String, Object> cell : componentCells) {
                long assessItemId = longValue(cell.get("assessItemId"));
                Long assessContentId = nullableLong(cell.get("assessContentId"));
                if (assessContentId == null) {
                    continue;
                }
                double maxScore = defaultDouble(cell.get("maxScore"), 100D);
                ScoreParseResult score = parseComponentScore(defaultString(cell.get("score"), ""), 0D, maxScore);
                if (!score.valid()) {
                    throw new ApiException(UNPROCESSABLE_STATUS, 400, defaultString(cell.get("assessItemName"), "成绩") + "：" + score.errorMessage());
                }
                Long gradeId = nullableLong(cell.get("gradeId"));
                if (gradeId == null) {
                    gradeId = findConfirmedComponentGradeId(courseId, semesterId, assessItemId, assessContentId, studentNo);
                }
                if (gradeId == null) {
                    jdbcTemplate.update("""
                        INSERT INTO student_grade (
                            course_id, class_id, student_id, assess_item_id, assess_content_id, semester_id, import_batch_id, student_no, student_name,
                            score, max_score, valid_flag, error_message, created_by
                        ) VALUES (
                            :courseId, :classId, :studentId, :assessItemId, :assessContentId, :semesterId, :batchId, :studentNo, :studentName,
                            :score, :maxScore, 1, NULL, :createdBy
                        )
                        """, new MapSqlParameterSource()
                        .addValue("courseId", courseId)
                        .addValue("classId", resolvedClassId)
                        .addValue("studentId", studentId)
                        .addValue("assessItemId", assessItemId)
                        .addValue("assessContentId", assessContentId)
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
                            class_id = :classId,
                            student_id = :studentId,
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
                        .addValue("classId", resolvedClassId)
                        .addValue("studentId", studentId)
                        .addValue("score", round2(score.score()))
                        .addValue("maxScore", round2(maxScore)));
                }
                affectedAssessItems.add(assessItemId);
            }
            for (Long assessItemId : affectedAssessItems) {
                syncAggregatedGrade(courseId, resolvedClassId, studentId, assessItemId, semesterId, batchId, studentNo, studentName, teacherId);
            }
            refreshGradeBatchCounts(batchId);
            return map("success", true);
        }
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
                        course_id, class_id, student_id, assess_item_id, assess_content_id, semester_id, import_batch_id, student_no, student_name,
                        score, max_score, valid_flag, error_message, created_by
                    ) VALUES (
                        :courseId, :classId, :studentId, :assessItemId, NULL, :semesterId, :batchId, :studentNo, :studentName,
                        :score, :maxScore, 1, NULL, :createdBy
                    )
                    """, new MapSqlParameterSource()
                    .addValue("courseId", courseId)
                    .addValue("classId", resolvedClassId)
                    .addValue("studentId", studentId)
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
                        class_id = :classId,
                        student_id = :studentId,
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
                    .addValue("classId", resolvedClassId)
                    .addValue("studentId", studentId)
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
        Map<String, Object> dataSummary = buildAchievementDataSummary(currentCourseId, semesterId, outlineId);
        double overallAchievement = doubleValue(overall.get("achieve_value"));
        double thresholdValue = doubleValue(config.get("threshold_value"));

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
            "overallAchievement", overallAchievement,
            "results", results,
            "dataSummary", dataSummary,
            "smartAnalysis", buildAchievementSmartAnalysis(results, dataSummary, overallAchievement, thresholdValue),
            "chartData", buildAchievementChartData(results, dataSummary)
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
            "assessItems", catalogAssessItems(),
            "classes", catalogClasses(),
            "majors", catalogMajors()
        );
    }

    private List<Map<String, Object>> catalogMajors() {
        return plainJdbcTemplate.query("""
            SELECT m.id, m.major_name, m.major_code, m.college_id, c.college_name
            FROM base_major m
            LEFT JOIN base_college c ON c.id = m.college_id
            WHERE m.status = 1
            ORDER BY m.id ASC
            """, (rs, rowNum) -> map(
            "id", rs.getLong("id"),
            "name", rs.getString("major_name"),
            "code", rs.getString("major_code"),
            "collegeId", nullableLong(rs.getObject("college_id")),
            "collegeName", defaultString(rs.getString("college_name"), "")
        ));
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

    private List<Map<String, Object>> catalogClasses() {
        return plainJdbcTemplate.query("""
            SELECT
                bc.id,
                bc.class_code,
                bc.class_name,
                bc.major_id,
                bm.major_name,
                bc.grade_year,
                bc.student_count,
                bc.status
            FROM base_class bc
            LEFT JOIN base_major bm ON bm.id = bc.major_id
            WHERE bc.status = 1
            ORDER BY bc.grade_year DESC, bc.class_code ASC, bc.id ASC
            """, (rs, rowNum) -> classMap(rs));
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

    private Map<String, Object> latestParsedCourseInfo(long courseId, String semesterCode) {
        Long semesterId = findSemesterId(semesterCode);
        if (semesterId == null) {
            return new LinkedHashMap<>();
        }

        List<Map<String, Object>> rows = jdbcTemplate.query("""
            SELECT task_no, parsed_course_json
            FROM parse_task
            WHERE course_id = :courseId
              AND semester_id = :semesterId
              AND parsed_course_json IS NOT NULL
              AND parsed_course_json <> ''
            ORDER BY updated_at DESC, id DESC
            LIMIT 20
            """, new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId), (rs, rowNum) -> {
            Map<String, Object> courseInfo = readMap(rs.getString("parsed_course_json"));
            courseInfo.put("taskId", rs.getString("task_no"));
            return courseInfo;
        });

        return rows.stream()
            .max(Comparator
                .comparingInt(this::parsedCourseQualityScore)
                .thenComparing(item -> defaultString(item.get("taskId"), "")))
            .orElseGet(LinkedHashMap::new);
    }

    private int parsedCourseQualityScore(Map<String, Object> courseInfo) {
        int score = 0;
        for (Map<String, Object> item : listOfMap(courseInfo.get("teachingContents"))) {
            score += StringUtils.hasText(defaultString(item.get("title"), "")) ? 1 : 0;
            score += StringUtils.hasText(defaultString(item.get("relatedObjectives"), "")) ? 10 : 0;
            score += StringUtils.hasText(defaultString(item.get("requirements"), "")) ? 10 : 0;
        }
        score += listSize(courseInfo.get("assessmentDetails"));
        score += listSize(courseInfo.get("assessmentStandards"));
        return score;
    }

    private List<Map<String, Object>> normalizeTeachingContents(List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String title = defaultString(row.get("title"), "");
            String requirements = defaultString(row.get("requirements"), "");
            String relatedObjectives = defaultString(row.get("relatedObjectives"), "");
            String teachingMethod = defaultString(row.get("teachingMethod"), "");
            String sourceText = defaultString(row.get("sourceText"), defaultString(row.get("originalText"), ""));
            if (!StringUtils.hasText(title)
                && !StringUtils.hasText(requirements)
                && !StringUtils.hasText(relatedObjectives)
                && !StringUtils.hasText(teachingMethod)) {
                continue;
            }
            result.add(map(
                "title", title,
                "lectureHours", nullableBigDecimal(row.get("lectureHours")),
                "practiceHours", nullableBigDecimal(row.get("practiceHours")),
                "teachingMethod", teachingMethod,
                "relatedObjectives", relatedObjectives,
                "requirements", requirements,
                "sourceText", sourceText
            ));
        }
        return result;
    }

    private List<Map<String, Object>> normalizeAssessItems(List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String itemName = defaultString(row.get("itemName"), defaultString(row.get("name"), ""));
            String itemType = defaultString(row.get("itemType"), "normal");
            if (!StringUtils.hasText(itemName)) {
                throw new ApiException(UNPROCESSABLE_STATUS, 400, "考核项名称不能为空。");
            }
            result.add(map(
                "id", nullableLong(row.get("id")),
                "itemName", itemName,
                "itemType", itemType,
                "weight", BigDecimal.valueOf(round2(doubleValue(row.get("weight")))),
                "maxScore", Optional.ofNullable(nullableBigDecimal(row.get("maxScore"))).orElse(BigDecimal.valueOf(100))
            ));
        }
        return result;
    }

    private void replaceCourseTeachingContents(long courseId, long semesterId, List<Map<String, Object>> teachingContents) {
        jdbcTemplate.update("""
            DELETE FROM course_teaching_content
            WHERE course_id = :courseId
              AND semester_id = :semesterId
            """, new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId));

        int sortOrder = 1;
        for (Map<String, Object> item : teachingContents) {
            jdbcTemplate.update("""
                INSERT INTO course_teaching_content (
                    course_id,
                    semester_id,
                    title,
                    lecture_hours,
                    practice_hours,
                    teaching_method,
                    related_objectives,
                    requirements,
                    source_text,
                    sort_order
                ) VALUES (
                    :courseId,
                    :semesterId,
                    :title,
                    :lectureHours,
                    :practiceHours,
                    :teachingMethod,
                    :relatedObjectives,
                    :requirements,
                    :sourceText,
                    :sortOrder
                )
                """, new MapSqlParameterSource()
                .addValue("courseId", courseId)
                .addValue("semesterId", semesterId)
                .addValue("title", defaultString(item.get("title"), "教学内容" + sortOrder))
                .addValue("lectureHours", nullableBigDecimal(item.get("lectureHours")))
                .addValue("practiceHours", nullableBigDecimal(item.get("practiceHours")))
                .addValue("teachingMethod", defaultString(item.get("teachingMethod"), ""))
                .addValue("relatedObjectives", defaultString(item.get("relatedObjectives"), ""))
                .addValue("requirements", defaultString(item.get("requirements"), ""))
                .addValue("sourceText", defaultString(item.get("sourceText"), ""))
                .addValue("sortOrder", sortOrder++));
        }
    }

    private List<String> courseTeacherNames(long courseId, String semesterCode) {
        Long semesterId = findSemesterId(semesterCode);
        if (semesterId == null) {
            return new ArrayList<>();
        }

        return jdbcTemplate.query("""
            SELECT u.real_name
            FROM course_teacher ct
            JOIN sys_user u ON u.id = ct.teacher_id
            WHERE ct.course_id = :courseId
              AND ct.semester_id = :semesterId
              AND ct.status = 1
              AND u.status = 1
            ORDER BY ct.id ASC
            """, new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId), (rs, rowNum) -> defaultString(rs.getString("real_name"), ""));
    }

    private List<Map<String, Object>> courseTeachingContentList(long courseId, String semesterCode) {
        Long semesterId = findSemesterId(semesterCode);
        if (semesterId == null) {
            return new ArrayList<>();
        }

        return jdbcTemplate.query("""
            SELECT
                id,
                title,
                lecture_hours,
                practice_hours,
                teaching_method,
                related_objectives,
                requirements,
                source_text,
                sort_order
            FROM course_teaching_content
            WHERE course_id = :courseId
              AND semester_id = :semesterId
            ORDER BY sort_order ASC, id ASC
            """, new MapSqlParameterSource()
            .addValue("courseId", courseId)
            .addValue("semesterId", semesterId), (rs, rowNum) -> map(
            "id", rs.getLong("id"),
            "title", defaultString(rs.getString("title"), ""),
            "lectureHours", rs.getObject("lecture_hours") == null ? "" : rs.getBigDecimal("lecture_hours"),
            "practiceHours", rs.getObject("practice_hours") == null ? "" : rs.getBigDecimal("practice_hours"),
            "teachingMethod", defaultString(rs.getString("teaching_method"), ""),
            "relatedObjectives", defaultString(rs.getString("related_objectives"), ""),
            "requirements", defaultString(rs.getString("requirements"), ""),
            "sourceText", defaultString(rs.getString("source_text"), ""),
            "sortOrder", rs.getInt("sort_order")
        ));
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
                "gradReqId", defaultString(rs.getString("grad_req_id"), ""),
                "gradReqDesc", defaultString(rs.getString("grad_req_desc"), ""),
                "relationLevel", defaultString(rs.getString("relation_level"), ""),
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
        ensureColumn("base_course", "credits",             "DECIMAL(3,1) NULL");
        ensureColumn("base_course", "prerequisite_course", "VARCHAR(255) NULL");
        ensureColumn("base_course", "course_owner",        "VARCHAR(100) NULL");
        ensureColumnDefinition("base_course", "course_type", "VARCHAR(100) NULL", "varchar(100)", "YES", null, null);
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
        ensureColumnDefinition("teach_objective", "relation_level", "VARCHAR(4) NOT NULL DEFAULT 'H'", "varchar(4)", "NO", "H", "'H'");
        ensureColumnDefinition("intelligent_suggestion", "suggestion_source", "VARCHAR(30) NOT NULL DEFAULT 'RULE'", "varchar(30)", "NO", "RULE", "'RULE'");
        ensureCourseTeachingContentTable();
        ensureCollectSchema();
        migrateParsedTeachingContentsToTable();
    }

    private void ensureCollectSchema() {
        try {
            plainJdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS base_class (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    class_code VARCHAR(50) NOT NULL,
                    class_name VARCHAR(100) NOT NULL,
                    major_id BIGINT NULL,
                    grade_year VARCHAR(20) NULL,
                    student_count INT NOT NULL DEFAULT 0,
                    status TINYINT NOT NULL DEFAULT 1,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_base_class_code (class_code),
                    KEY idx_base_class_major (major_id),
                    CONSTRAINT fk_base_class_major FOREIGN KEY (major_id) REFERENCES base_major (id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='班级表'
                """);
            plainJdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS base_student (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    student_no VARCHAR(30) NOT NULL,
                    student_name VARCHAR(50) NOT NULL,
                    gender VARCHAR(10) NULL,
                    class_id BIGINT NULL,
                    major_id BIGINT NULL,
                    phone VARCHAR(20) NULL,
                    email VARCHAR(100) NULL,
                    status TINYINT NOT NULL DEFAULT 1,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_base_student_no (student_no),
                    KEY idx_base_student_class (class_id),
                    KEY idx_base_student_major (major_id),
                    CONSTRAINT fk_base_student_class FOREIGN KEY (class_id) REFERENCES base_class (id) ON DELETE SET NULL,
                    CONSTRAINT fk_base_student_major FOREIGN KEY (major_id) REFERENCES base_major (id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学生基础信息表'
                """);
            plainJdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS class_course (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    class_id BIGINT NOT NULL,
                    course_id BIGINT NOT NULL,
                    semester_id BIGINT NOT NULL,
                    teacher_id BIGINT NULL,
                    status TINYINT NOT NULL DEFAULT 1,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_class_course_scope (class_id, course_id, semester_id),
                    KEY idx_class_course_course (course_id, semester_id),
                    KEY idx_class_course_teacher (teacher_id),
                    CONSTRAINT fk_class_course_class FOREIGN KEY (class_id) REFERENCES base_class (id) ON DELETE CASCADE,
                    CONSTRAINT fk_class_course_course FOREIGN KEY (course_id) REFERENCES base_course (id) ON DELETE CASCADE,
                    CONSTRAINT fk_class_course_semester FOREIGN KEY (semester_id) REFERENCES base_semester (id),
                    CONSTRAINT fk_class_course_teacher FOREIGN KEY (teacher_id) REFERENCES sys_user (id) ON DELETE SET NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='班级开课表'
                """);
            ensureColumn("grade_import_batch", "class_id", "BIGINT NULL");
            ensureColumn("grade_import_batch", "assess_content_id", "BIGINT NULL");
            ensureColumn("student_grade", "class_id", "BIGINT NULL");
            ensureColumn("student_grade", "student_id", "BIGINT NULL");
            ensureColumn("student_grade", "assess_content_id", "BIGINT NULL");
            ensureAssessmentContentTable();
            ensureIndex("grade_import_batch", "idx_grade_import_class", List.of("class_id", "semester_id"));
            ensureIndex("grade_import_batch", "idx_grade_import_content", List.of("assess_content_id"));
            ensureIndex("student_grade", "idx_student_grade_class", List.of("class_id", "semester_id"));
            ensureIndex("student_grade", "idx_student_grade_content", List.of("assess_content_id"));
            ensureIndex("student_grade", "fk_student_grade_student", List.of("student_id"));
            ensureForeignKey("grade_import_batch", "fk_grade_import_class", "class_id", "base_class", "id", "ON DELETE SET NULL");
            ensureForeignKey("grade_import_batch", "fk_grade_import_content", "assess_content_id", "assess_content", "id", "ON DELETE SET NULL");
            ensureForeignKey("student_grade", "fk_student_grade_class", "class_id", "base_class", "id", "ON DELETE SET NULL");
            ensureForeignKey("student_grade", "fk_student_grade_student", "student_id", "base_student", "id", "ON DELETE SET NULL");
            ensureForeignKey("student_grade", "fk_student_grade_content", "assess_content_id", "assess_content", "id", "ON DELETE SET NULL");
        } catch (Exception e) {
            log.warn("Schema migration failed for collect tables: {}", e.getMessage());
        }
    }

    private void ensureAssessmentContentTable() {
        plainJdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS assess_content (
                id BIGINT NOT NULL AUTO_INCREMENT,
                assess_item_id BIGINT NOT NULL,
                content_no VARCHAR(20) NOT NULL,
                content_name VARCHAR(120) NOT NULL,
                content_type VARCHAR(20) NOT NULL DEFAULT 'assignment',
                weight DECIMAL(6,2) NOT NULL DEFAULT 0.00,
                sort_order INT NOT NULL DEFAULT 0,
                status TINYINT NOT NULL DEFAULT 1,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                UNIQUE KEY uk_assess_content_no (assess_item_id, content_no),
                KEY idx_assess_content_item (assess_item_id, status, sort_order),
                CONSTRAINT fk_assess_content_item FOREIGN KEY (assess_item_id) REFERENCES assess_item (id) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='考核内容及方式表'
            """);
    }

    private void ensureCourseTeachingContentTable() {
        try {
            plainJdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS course_teaching_content (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    course_id BIGINT NOT NULL,
                    semester_id BIGINT NOT NULL,
                    title VARCHAR(255) NOT NULL,
                    lecture_hours DECIMAL(5,1) NULL,
                    practice_hours DECIMAL(5,1) NULL,
                    teaching_method VARCHAR(100) NULL,
                    related_objectives VARCHAR(255) NULL,
                    requirements TEXT NULL,
                    source_text TEXT NULL,
                    sort_order INT NOT NULL DEFAULT 0,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    KEY idx_course_teaching_scope (course_id, semester_id, sort_order),
                    CONSTRAINT fk_course_teaching_course FOREIGN KEY (course_id) REFERENCES base_course (id) ON DELETE CASCADE,
                    CONSTRAINT fk_course_teaching_semester FOREIGN KEY (semester_id) REFERENCES base_semester (id) ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程教学内容表'
                """);
        } catch (Exception e) {
            log.warn("Schema migration failed for course_teaching_content: {}", e.getMessage());
        }
    }

    private void migrateParsedTeachingContentsToTable() {
        try {
            List<Map<String, Object>> tasks = jdbcTemplate.query("""
                SELECT course_id, semester_id, parsed_course_json
                FROM parse_task
                WHERE parsed_course_json IS NOT NULL
                  AND parsed_course_json <> ''
                ORDER BY updated_at DESC, id DESC
                """, new MapSqlParameterSource(), (rs, rowNum) -> map(
                "courseId", rs.getLong("course_id"),
                "semesterId", rs.getLong("semester_id"),
                "courseInfo", readMap(rs.getString("parsed_course_json"))
            ));

            Map<String, Map<String, Object>> bestByScope = new LinkedHashMap<>();
            for (Map<String, Object> task : tasks) {
                Map<String, Object> courseInfo = mapOfObject(task.get("courseInfo"));
                if (listSize(courseInfo.get("teachingContents")) == 0) {
                    continue;
                }
                String key = task.get("courseId") + ":" + task.get("semesterId");
                Map<String, Object> current = bestByScope.get(key);
                if (current == null || parsedCourseQualityScore(courseInfo) > parsedCourseQualityScore(mapOfObject(current.get("courseInfo")))) {
                    bestByScope.put(key, task);
                }
            }

            for (Map<String, Object> task : bestByScope.values()) {
                long courseId = longValue(task.get("courseId"));
                long semesterId = longValue(task.get("semesterId"));
                long existing = count("""
                    SELECT COUNT(*)
                    FROM course_teaching_content
                    WHERE course_id = :courseId
                      AND semester_id = :semesterId
                    """, new MapSqlParameterSource()
                    .addValue("courseId", courseId)
                    .addValue("semesterId", semesterId));
                if (existing > 0) {
                    continue;
                }
                Map<String, Object> courseInfo = mapOfObject(task.get("courseInfo"));
                replaceCourseTeachingContents(courseId, semesterId, normalizeTeachingContents(listOfMap(courseInfo.get("teachingContents"))));
            }
        } catch (Exception e) {
            log.warn("Teaching content migration skipped: {}", e.getMessage());
        }
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

    private void ensureColumnDefinition(
        String table,
        String column,
        String definition,
        String expectedType,
        String expectedNullable,
        String expectedDefault,
        String fillNullSql
    ) {
        try {
            String dbName = plainJdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
            Map<String, Object> meta = plainJdbcTemplate.query("""
                SELECT COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = ?
                  AND TABLE_NAME = ?
                  AND COLUMN_NAME = ?
                """, rs -> rs.next() ? map(
                "columnType", rs.getString("COLUMN_TYPE"),
                "isNullable", rs.getString("IS_NULLABLE"),
                "columnDefault", rs.getString("COLUMN_DEFAULT")
            ) : Map.of(), dbName, table, column);
            if (meta.isEmpty()) {
                return;
            }
            boolean typeChanged = !expectedType.equalsIgnoreCase(defaultString(meta.get("columnType"), ""));
            boolean nullableChanged = !expectedNullable.equalsIgnoreCase(defaultString(meta.get("isNullable"), ""));
            boolean defaultChanged = !defaultString(expectedDefault, "").equals(defaultString(meta.get("columnDefault"), ""));
            if (typeChanged || nullableChanged || defaultChanged) {
                if (StringUtils.hasText(fillNullSql)) {
                    plainJdbcTemplate.execute("UPDATE `" + table + "` SET `" + column + "` = " + fillNullSql + " WHERE `" + column + "` IS NULL");
                }
                plainJdbcTemplate.execute("ALTER TABLE `" + table + "` MODIFY COLUMN `" + column + "` " + definition);
                log.info("Schema migration: modified column {}.{}", table, column);
            }
        } catch (Exception e) {
            log.warn("Schema migration failed for {}.{} definition: {}", table, column, e.getMessage());
        }
    }

    private void ensureIndex(String table, String indexName, List<String> columns) {
        try {
            String dbName = plainJdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
            Integer count = plainJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND INDEX_NAME = ?",
                Integer.class, dbName, table, indexName);
            if (count == null || count == 0) {
                String columnSql = columns.stream()
                    .map(column -> "`" + column + "`")
                    .collect(Collectors.joining(", "));
                plainJdbcTemplate.execute("ALTER TABLE `" + table + "` ADD INDEX `" + indexName + "` (" + columnSql + ")");
                log.info("Schema migration: added index {}.{}", table, indexName);
            }
        } catch (Exception e) {
            log.warn("Schema migration failed for index {}.{}: {}", table, indexName, e.getMessage());
        }
    }

    private void ensureForeignKey(
        String table,
        String constraintName,
        String column,
        String referencedTable,
        String referencedColumn,
        String action
    ) {
        try {
            String dbName = plainJdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
            Integer count = plainJdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.TABLE_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = ?
                  AND TABLE_NAME = ?
                  AND CONSTRAINT_NAME = ?
                  AND CONSTRAINT_TYPE = 'FOREIGN KEY'
                """, Integer.class, dbName, table, constraintName);
            if (count == null || count == 0) {
                plainJdbcTemplate.execute("ALTER TABLE `" + table + "` ADD CONSTRAINT `" + constraintName + "` FOREIGN KEY (`"
                    + column + "`) REFERENCES `" + referencedTable + "` (`" + referencedColumn + "`) " + action);
                log.info("Schema migration: added foreign key {}.{}", table, constraintName);
            }
        } catch (Exception e) {
            log.warn("Schema migration failed for foreign key {}.{}: {}", table, constraintName, e.getMessage());
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

    public Map<String, Object> updateParseCourseInfo(String taskId, Map<String, Object> payload) {
        long parseTaskId = resolveParseTaskId(taskId);
        Map<String, Object> existing = jdbcTemplate.query("""
            SELECT parsed_course_json
            FROM parse_task
            WHERE id = :id
            """, params("id", parseTaskId), rs -> rs.next() ? readMap(rs.getString("parsed_course_json")) : new LinkedHashMap<>());

        LinkedHashMap<String, Object> merged = new LinkedHashMap<>(existing);
        merged.putAll(payload == null ? Map.of() : payload);

        jdbcTemplate.update("""
            UPDATE parse_task
            SET parsed_course_json = :courseInfoJson,
                updated_at = NOW()
            WHERE id = :id
            """, new MapSqlParameterSource()
            .addValue("id", parseTaskId)
            .addValue("courseInfoJson", writeJson(merged)));

        return map("success", true, "parsedCourse", merged);
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
        merged.put("teachingContents", courseInfoValue(submittedCourse, parsedCourse, "teachingContents", new ArrayList<>()));
        merged.put("assessmentDetails", courseInfoValue(submittedCourse, parsedCourse, "assessmentDetails", new ArrayList<>()));
        merged.put("assessmentStandards", courseInfoValue(submittedCourse, parsedCourse, "assessmentStandards", new ArrayList<>()));
        merged.put("assessmentPolicy", courseInfoValue(submittedCourse, parsedCourse, "assessmentPolicy", new LinkedHashMap<>()));
        merged.put("collegeId", nullableLong(currentCourse.get("collegeId")));
        merged.put("majorId", nullableLong(currentCourse.get("majorId")));
        return merged;
    }

    private void mergeExplicitReviewOverwrite(Map<String, Object> reviewOverwrite, Map<String, Object> payload) {
        Map<String, String> keyMap = Map.of(
            "teachingContents", "overwriteTeachingContents",
            "assessmentPolicy", "overwriteAssessmentPolicy",
            "assessmentDetails", "overwriteAssessmentDetails",
            "assessmentStandards", "overwriteAssessmentStandards",
            "objectives", "overwriteObjectives",
            "assessItems", "overwriteAssessItems",
            "mapping", "overwriteMappings"
        );
        keyMap.forEach((sectionKey, payloadKey) -> {
            if (payload.containsKey(payloadKey)) {
                reviewOverwrite.put(sectionKey, booleanValue(payload.get(payloadKey)));
            }
        });
    }

    private Map<String, Object> applyParsedSectionOverwritePolicy(
        Map<String, Object> submitted,
        Map<String, Object> existing,
        Map<String, Object> reviewOverwrite
    ) {
        if (existing == null || existing.isEmpty()) {
            return submitted;
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>(submitted);
        preserveExistingParsedSection(result, existing, reviewOverwrite, "teachingContents");
        preserveExistingParsedSection(result, existing, reviewOverwrite, "assessmentPolicy");
        preserveExistingParsedSection(result, existing, reviewOverwrite, "assessmentDetails");
        preserveExistingParsedSection(result, existing, reviewOverwrite, "assessmentStandards");
        return result;
    }

    private void preserveExistingParsedSection(
        Map<String, Object> result,
        Map<String, Object> existing,
        Map<String, Object> reviewOverwrite,
        String key
    ) {
        if (booleanValue(reviewOverwrite.get(key))) {
            return;
        }
        Object existingValue = existing.get(key);
        if (hasStructuredCourseSection(existingValue)) {
            result.put(key, existingValue);
        }
    }

    private boolean hasStructuredCourseSection(Object value) {
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(item -> StringUtils.hasText(defaultString(item, "")));
        }
        return StringUtils.hasText(defaultString(value, ""));
    }

    private Object courseInfoValue(Map<String, Object> submittedCourse, Map<String, Object> parsedCourse, String key, Object defaultValue) {
        if (submittedCourse.containsKey(key)) {
            return submittedCourse.get(key);
        }
        if (parsedCourse.containsKey(key)) {
            return parsedCourse.get(key);
        }
        return defaultValue;
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
        clearObjectiveImportData(outlineId);
        clearAssessImportData(outlineId);
    }

    private void clearObjectiveAssessMappings(long outlineId) {
        jdbcTemplate.update("""
            DELETE FROM obj_assess_map
            WHERE objective_id IN (
                SELECT id FROM teach_objective WHERE outline_id = :outlineId
            )
            """, params("outlineId", outlineId));
    }

    private void clearObjectiveImportData(long outlineId) {
        clearObjectiveAssessMappings(outlineId);
        jdbcTemplate.update("""
            DELETE FROM obj_decompose
            WHERE objective_id IN (
                SELECT id FROM teach_objective WHERE outline_id = :outlineId
            )
            """, params("outlineId", outlineId));
        jdbcTemplate.update("DELETE FROM teach_objective WHERE outline_id = :outlineId", params("outlineId", outlineId));
    }

    private void clearAssessImportData(long outlineId) {
        clearObjectiveAssessMappings(outlineId);
        deleteUnretainedAssessItemsSafely(outlineId, Set.of());
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

    private void replaceAssessItemsSafely(long outlineId, List<Map<String, Object>> assessDrafts) {
        List<Map<String, Object>> existingItems = assessItemListByOutline(outlineId);
        Set<Long> retainedIds = new LinkedHashSet<>();
        int sortOrder = 1;
        for (Map<String, Object> item : assessDrafts) {
            Long existingId = findReusableAssessItemId(existingItems, retainedIds, item);
            if (existingId == null) {
                GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
                jdbcTemplate.update("""
                    INSERT INTO assess_item (
                        outline_id, item_name, item_type, weight, max_score, sort_order
                    ) VALUES (
                        :outlineId, :itemName, :itemType, :weight, :maxScore, :sortOrder
                    )
                    """, new MapSqlParameterSource()
                    .addValue("outlineId", outlineId)
                    .addValue("itemName", item.get("itemName"))
                    .addValue("itemType", item.get("itemType"))
                    .addValue("weight", item.get("weight"))
                    .addValue("maxScore", Optional.ofNullable(nullableBigDecimal(item.get("maxScore"))).orElse(BigDecimal.valueOf(100)))
                    .addValue("sortOrder", sortOrder++), keyHolder);
                retainedIds.add(keyHolder.getKey().longValue());
                continue;
            }

            retainedIds.add(existingId);
            jdbcTemplate.update("""
                UPDATE assess_item
                SET item_name = :itemName,
                    item_type = :itemType,
                    weight = :weight,
                    max_score = :maxScore,
                    sort_order = :sortOrder,
                    updated_at = NOW()
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", existingId)
                .addValue("itemName", item.get("itemName"))
                .addValue("itemType", item.get("itemType"))
                .addValue("weight", item.get("weight"))
                .addValue("maxScore", Optional.ofNullable(nullableBigDecimal(item.get("maxScore"))).orElse(BigDecimal.valueOf(100)))
                .addValue("sortOrder", sortOrder++));
        }

        deleteUnretainedAssessItemsSafely(outlineId, retainedIds);
    }

    private Long findReusableAssessItemId(List<Map<String, Object>> existingItems, Set<Long> retainedIds, Map<String, Object> draft) {
        Long draftId = nullableLong(draft.get("id"));
        if (draftId != null) {
            for (Map<String, Object> item : existingItems) {
                long id = longValue(item.get("id"));
                if (!retainedIds.contains(id) && id == draftId) {
                    return id;
                }
            }
        }

        String draftName = normalizeKey(defaultString(draft.get("itemName"), ""));
        for (Map<String, Object> item : existingItems) {
            long id = longValue(item.get("id"));
            if (!retainedIds.contains(id) && normalizeKey(defaultString(item.get("itemName"), "")).equals(draftName)) {
                return id;
            }
        }

        String draftType = defaultString(draft.get("itemType"), "");
        for (Map<String, Object> item : existingItems) {
            long id = longValue(item.get("id"));
            if (!retainedIds.contains(id)
                && StringUtils.hasText(draftType)
                && draftType.equals(defaultString(item.get("itemType"), ""))) {
                return id;
            }
        }

        return existingItems.stream()
            .map(item -> longValue(item.get("id")))
            .filter(id -> !retainedIds.contains(id))
            .findFirst()
            .orElse(null);
    }

    private void deleteUnretainedAssessItemsSafely(long outlineId, Set<Long> retainedIds) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("outlineId", outlineId);
        String retainedFilter = "";
        if (!retainedIds.isEmpty()) {
            retainedFilter = " AND id NOT IN (:retainedIds) ";
            params.addValue("retainedIds", retainedIds);
        }

        jdbcTemplate.update("""
            DELETE FROM assess_item
            WHERE outline_id = :outlineId
            """ + retainedFilter + """
              AND id NOT IN (SELECT DISTINCT assess_item_id FROM grade_import_batch WHERE assess_item_id IS NOT NULL)
              AND id NOT IN (SELECT DISTINCT assess_item_id FROM student_grade WHERE assess_item_id IS NOT NULL)
              AND id NOT IN (SELECT DISTINCT assess_item_id FROM achieve_result_detail WHERE assess_item_id IS NOT NULL)
            """, params);

        jdbcTemplate.update("""
            UPDATE assess_item
            SET weight = 0,
                sort_order = CASE WHEN sort_order < 1000 THEN 1000 + sort_order ELSE sort_order END,
                updated_at = NOW()
            WHERE outline_id = :outlineId
            """ + retainedFilter + """
              AND (
                id IN (SELECT DISTINCT assess_item_id FROM grade_import_batch WHERE assess_item_id IS NOT NULL)
                OR id IN (SELECT DISTINCT assess_item_id FROM student_grade WHERE assess_item_id IS NOT NULL)
                OR id IN (SELECT DISTINCT assess_item_id FROM achieve_result_detail WHERE assess_item_id IS NOT NULL)
              )
            """, params);
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
            SELECT id, item_name, item_type, weight, max_score, sort_order
            FROM assess_item
            WHERE outline_id = :outlineId
              AND sort_order < 1000
            ORDER BY sort_order ASC, id ASC
            """, params("outlineId", outlineId), (rs, rowNum) -> map(
            "id", rs.getLong("id"),
            "outlineId", outlineId,
            "itemName", rs.getString("item_name"),
            "itemType", defaultString(rs.getString("item_type"), ""),
            "itemTypeName", assessItemTypeName(rs.getString("item_type")),
            "weight", rs.getBigDecimal("weight"),
            "maxScore", rs.getBigDecimal("max_score"),
            "sortOrder", rs.getInt("sort_order")
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
        List<Map<String, Object>> mappingSuggestions,
        boolean replaceExisting
    ) {
        if (mappingSuggestions.isEmpty()) {
            return 0;
        }

        List<Map<String, Object>> objectives = objectiveList(outlineId);
        List<Map<String, Object>> assessItems = assessItemListByOutline(outlineId);
        if (objectives.isEmpty() || assessItems.isEmpty()) {
            return 0;
        }

        if (replaceExisting) {
            clearObjectiveAssessMappings(outlineId);
        }

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
                ON DUPLICATE KEY UPDATE
                    contribution_weight = :contributionWeight,
                    updated_at = NOW()
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

    private Map<String, Object> classMap(ResultSet rs) throws SQLException {
        return map(
            "id", rs.getLong("id"),
            "classCode", rs.getString("class_code"),
            "className", rs.getString("class_name"),
            "majorId", nullableLong(rs.getObject("major_id")),
            "majorName", defaultString(rs.getString("major_name"), ""),
            "gradeYear", defaultString(rs.getString("grade_year"), ""),
            "studentCount", rs.getInt("student_count"),
            "status", rs.getInt("status")
        );
    }

    private Map<String, Object> studentMap(ResultSet rs) throws SQLException {
        return map(
            "id", rs.getLong("id"),
            "studentNo", rs.getString("student_no"),
            "studentName", rs.getString("student_name"),
            "gender", defaultString(rs.getString("gender"), ""),
            "classId", nullableLong(rs.getObject("class_id")),
            "className", defaultString(rs.getString("class_name"), ""),
            "majorId", nullableLong(rs.getObject("major_id")),
            "majorName", defaultString(rs.getString("major_name"), ""),
            "phone", defaultString(rs.getString("phone"), ""),
            "email", defaultString(rs.getString("email"), ""),
            "status", rs.getInt("status")
        );
    }

    private Map<String, Object> classCourseMap(ResultSet rs) throws SQLException {
        return map(
            "id", rs.getLong("id"),
            "classId", rs.getLong("class_id"),
            "className", rs.getString("class_name"),
            "courseId", rs.getLong("course_id"),
            "courseCode", rs.getString("course_code"),
            "courseName", rs.getString("course_name"),
            "semesterId", rs.getLong("semester_id"),
            "semester", rs.getString("semester_code"),
            "teacherId", nullableLong(rs.getObject("teacher_id")),
            "teacherName", defaultString(rs.getString("teacher_name"), ""),
            "status", rs.getInt("status")
        );
    }

    private Map<String, Object> getClassById(Long id) {
        return requireMap("""
            SELECT
                bc.id,
                bc.class_code,
                bc.class_name,
                bc.major_id,
                bm.major_name,
                bc.grade_year,
                bc.student_count,
                bc.status
            FROM base_class bc
            LEFT JOIN base_major bm ON bm.id = bc.major_id
            WHERE bc.id = :id
            """, params("id", id), this::classMap);
    }

    private Map<String, Object> getStudentById(Long id) {
        return requireMap("""
            SELECT
                s.id,
                s.student_no,
                s.student_name,
                s.gender,
                s.class_id,
                bc.class_name,
                s.major_id,
                bm.major_name,
                s.phone,
                s.email,
                s.status
            FROM base_student s
            LEFT JOIN base_class bc ON bc.id = s.class_id
            LEFT JOIN base_major bm ON bm.id = s.major_id
            WHERE s.id = :id
            """, params("id", id), this::studentMap);
    }

    private Long findStudentId(String studentNo) {
        if (!StringUtils.hasText(studentNo)) return null;
        return jdbcTemplate.query("""
            SELECT id FROM base_student
            WHERE student_no = :studentNo
              AND status = 1
            LIMIT 1
            """, params("studentNo", studentNo), rs -> rs.next() ? rs.getLong("id") : null);
    }

    private Long findStudentClassId(String studentNo) {
        if (!StringUtils.hasText(studentNo)) return null;
        return jdbcTemplate.query("""
            SELECT class_id FROM base_student
            WHERE student_no = :studentNo
              AND status = 1
            LIMIT 1
            """, params("studentNo", studentNo), rs -> rs.next() ? nullableLong(rs.getObject("class_id")) : null);
    }

    private long upsertStudent(
        String studentNo,
        String studentName,
        String gender,
        Long classId,
        Long majorId,
        String phone,
        String email
    ) {
        Long existingId = findStudentId(studentNo);
        if (existingId == null) {
            GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update("""
                INSERT INTO base_student (
                    student_no, student_name, gender, class_id, major_id, phone, email, status
                ) VALUES (
                    :studentNo, :studentName, :gender, :classId, :majorId, :phone, :email, 1
                )
                """, new MapSqlParameterSource()
                .addValue("studentNo", studentNo)
                .addValue("studentName", studentName)
                .addValue("gender", gender)
                .addValue("classId", classId)
                .addValue("majorId", majorId)
                .addValue("phone", phone)
                .addValue("email", email), keyHolder);
            existingId = keyHolder.getKey().longValue();
        } else {
            jdbcTemplate.update("""
                UPDATE base_student
                SET student_name = :studentName,
                    gender = :gender,
                    class_id = :classId,
                    major_id = :majorId,
                    phone = :phone,
                    email = :email,
                    status = 1,
                    updated_at = NOW()
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", existingId)
                .addValue("studentName", studentName)
                .addValue("gender", gender)
                .addValue("classId", classId)
                .addValue("majorId", majorId)
                .addValue("phone", phone)
                .addValue("email", email));
        }
        if (classId != null) {
            refreshClassStudentCount(classId);
        }
        linkStudentGrades(existingId, studentNo, classId);
        return existingId;
    }

    private void linkStudentGrades(Long studentId, String studentNo, Long classId) {
        if (studentId == null || !StringUtils.hasText(studentNo)) return;
        jdbcTemplate.update("""
            UPDATE student_grade
            SET student_id = :studentId,
                class_id = COALESCE(class_id, :classId),
                updated_at = NOW()
            WHERE student_no = :studentNo
            """, new MapSqlParameterSource()
            .addValue("studentId", studentId)
            .addValue("classId", classId)
            .addValue("studentNo", studentNo));
    }

    private void refreshClassStudentCount(Long classId) {
        if (classId == null) return;
        jdbcTemplate.update("""
            UPDATE base_class bc
            SET student_count = (
                SELECT COUNT(*)
                FROM base_student s
                WHERE s.class_id = bc.id
                  AND s.status = 1
            ),
            updated_at = NOW()
            WHERE bc.id = :classId
            """, params("classId", classId));
    }

    private List<Map<String, Object>> currentCourseAssessItems(long courseId, String semesterCode) {
        Long outlineId = findOutlineId(courseId, semesterCode);
        if (outlineId == null) {
            return List.of();
        }
        return assessItemListByOutline(outlineId);
    }

    private void ensureDefaultAssessContents(List<Map<String, Object>> assessItems) {
        for (Map<String, Object> item : assessItems) {
            long assessItemId = longValue(item.get("id"));
            long count = count("""
                SELECT COUNT(*)
                FROM assess_content
                WHERE assess_item_id = :assessItemId
                  AND status = 1
                """, params("assessItemId", assessItemId));
            if (count > 0) {
                continue;
            }
            jdbcTemplate.update("""
                INSERT INTO assess_content (
                    assess_item_id, content_no, content_name, content_type, weight, sort_order, status
                ) VALUES (
                    :assessItemId, '1', :contentName, :contentType, :weight, :sortOrder, 1
                )
                ON DUPLICATE KEY UPDATE
                    content_name = VALUES(content_name),
                    content_type = VALUES(content_type),
                    weight = VALUES(weight),
                    sort_order = VALUES(sort_order),
                    status = 1,
                    updated_at = NOW()
                """, new MapSqlParameterSource()
                .addValue("assessItemId", assessItemId)
                .addValue("contentName", defaultString(item.get("itemName"), "考核内容"))
                .addValue("contentType", inferAssessContentType(item))
                .addValue("weight", round2(doubleValue(item.get("weight"))))
                .addValue("sortOrder", defaultInt(item.get("sortOrder"), 0)));
        }
    }

    private List<Map<String, Object>> activeAssessContents(List<Long> assessItemIds) {
        if (assessItemIds == null || assessItemIds.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query("""
            SELECT
                ac.id,
                ac.assess_item_id,
                ai.item_name,
                ai.weight AS assess_item_weight,
                ac.content_no,
                ac.content_name,
                ac.content_type,
                ac.weight,
                ac.sort_order
            FROM assess_content ac
            JOIN assess_item ai ON ai.id = ac.assess_item_id
            WHERE ac.status = 1
              AND ac.assess_item_id IN (:assessItemIds)
            ORDER BY ai.sort_order ASC, ai.id ASC, ac.sort_order ASC, ac.id ASC
            """, new MapSqlParameterSource("assessItemIds", assessItemIds), (rs, rowNum) -> map(
            "id", rs.getLong("id"),
            "assessItemId", rs.getLong("assess_item_id"),
            "assessItemName", rs.getString("item_name"),
            "assessItemWeight", rs.getBigDecimal("assess_item_weight"),
            "contentNo", rs.getString("content_no"),
            "contentName", rs.getString("content_name"),
            "contentType", rs.getString("content_type"),
            "contentTypeName", assessContentTypeName(rs.getString("content_type")),
            "weight", rs.getBigDecimal("weight"),
            "maxScore", rs.getBigDecimal("weight"),
            "sortOrder", rs.getInt("sort_order")
        ));
    }

    private List<Map<String, Object>> activeAssessContentsByItem(long assessItemId) {
        return activeAssessContents(List.of(assessItemId));
    }

    private List<String> assessmentContentWarnings(List<Map<String, Object>> assessItems) {
        List<String> warnings = new ArrayList<>();
        for (Map<String, Object> item : assessItems) {
            double expected = round2(doubleValue(item.get("weight")));
            double actual = round2(doubleValue(item.get("contentWeight")));
            if (expected > 0D && Math.abs(expected - actual) > 0.01D) {
                warnings.add(defaultString(item.get("itemName"), "考核项") + "的内容权重合计为 "
                    + formatNumber(actual, 2) + "，应为 " + formatNumber(expected, 2) + "。");
            }
        }
        return warnings;
    }

    private void retireRemovedAssessContents(Set<Long> assessItemIds, Set<Long> keepIds) {
        if (assessItemIds == null || assessItemIds.isEmpty()) {
            return;
        }
        List<Long> existingIds = jdbcTemplate.query("""
            SELECT id
            FROM assess_content
            WHERE assess_item_id IN (:assessItemIds)
              AND status = 1
            """, new MapSqlParameterSource("assessItemIds", assessItemIds), (rs, rowNum) -> rs.getLong("id"));
        for (Long id : existingIds) {
            if (keepIds.contains(id)) {
                continue;
            }
            long referenced = count("""
                SELECT
                    (SELECT COUNT(*) FROM student_grade WHERE assess_content_id = :id)
                  + (SELECT COUNT(*) FROM grade_import_batch WHERE assess_content_id = :id)
                """, params("id", id));
            if (referenced > 0) {
                jdbcTemplate.update("""
                    UPDATE assess_content
                    SET status = 0,
                        updated_at = NOW()
                    WHERE id = :id
                    """, params("id", id));
            } else {
                jdbcTemplate.update("DELETE FROM assess_content WHERE id = :id", params("id", id));
            }
        }
    }

    private String normalizeAssessContentType(String type) {
        String value = defaultString(type, "").trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "experiment", "practice", "lab" -> "experiment";
            case "exam", "final", "test" -> "exam";
            default -> "assignment";
        };
    }

    private String inferAssessContentType(Map<String, Object> assessItem) {
        String type = defaultString(assessItem.get("itemType"), "");
        String name = defaultString(assessItem.get("itemName"), "");
        String normalized = normalizeGradeHeader(type + name);
        if (containsAnyNormalized(normalized, List.of("实验", "实践", "上机", "项目", "practice", "lab"))) {
            return "experiment";
        }
        if (containsAnyNormalized(normalized, List.of("期末", "考试", "测试", "综合报告", "final", "exam", "test"))) {
            return "exam";
        }
        return "assignment";
    }

    private String assessContentTypeName(String type) {
        return switch (normalizeAssessContentType(type)) {
            case "experiment" -> "实验";
            case "exam" -> "考核";
            default -> "作业";
        };
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
                    null,
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

    private GradeImportResult parseGradeContentFile(
        MultipartFile file,
        String fileName,
        List<Map<String, Object>> contents
    ) throws IOException {
        String lower = fileName.toLowerCase(Locale.ROOT);
        List<List<String>> rows = lower.endsWith(".csv") ? readCsvRows(file) : readWorkbookRowsForContents(file, contents);
        if (rows.isEmpty()) {
            throw new ApiException(UNPROCESSABLE_STATUS, 400, "成绩文件为空，请检查表头和数据行。");
        }

        List<String> headers = rows.get(0);
        Map<Integer, GradeContentColumn> gradeColumns = resolveGradeContentColumns(headers, contents);
        if (gradeColumns.isEmpty()) {
            throw new ApiException(UNPROCESSABLE_STATUS, 400, "未在成绩文件中找到与考核内容匹配的成绩列。");
        }

        List<GradeImportRow> imports = new ArrayList<>();
        for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            if (row.stream().allMatch(cell -> !StringUtils.hasText(cell))) {
                continue;
            }
            String studentNo = cellAt(row, 0);
            String studentName = cellAt(row, 1);
            for (Map.Entry<Integer, GradeContentColumn> entry : gradeColumns.entrySet()) {
                GradeContentColumn column = entry.getValue();
                String rawScore = cellAt(row, entry.getKey());
                ScoreParseResult score = parseComponentScore(rawScore, column.inputMaxScore(), column.weight());
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
                    column.assessContentId(),
                    score.score(),
                    column.weight(),
                    !StringUtils.hasText(error),
                    error
                ));
            }
        }

        int validRows = (int) imports.stream().filter(GradeImportRow::valid).count();
        return new GradeImportResult(imports, imports.size(), validRows, imports.size() - validRows);
    }

    private StudentImportResult parseStudentFile(
        MultipartFile file,
        String fileName,
        Long classId,
        Long majorId
    ) throws IOException {
        String lower = defaultString(fileName, "").toLowerCase(Locale.ROOT);
        List<List<String>> rows = lower.endsWith(".csv") ? readCsvRows(file) : readStudentWorkbookRows(file);
        if (rows.isEmpty()) {
            throw new ApiException(UNPROCESSABLE_STATUS, 400, "学生信息文件为空，请检查表头和数据行。");
        }

        int headerIndex = findStudentHeaderIndex(rows);
        List<String> header = rows.get(headerIndex);
        Map<String, Integer> columns = resolveStudentColumns(header);
        if (!columns.containsKey("studentNo") || !columns.containsKey("studentName")) {
            throw new ApiException(UNPROCESSABLE_STATUS, 400, "学生信息文件必须包含学号和姓名列。");
        }

        List<StudentImportRow> imports = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (int rowIndex = headerIndex + 1; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            if (row.stream().allMatch(cell -> !StringUtils.hasText(cell))) {
                continue;
            }
            String studentNo = cellAt(row, columns.get("studentNo"));
            String studentName = cellAt(row, columns.get("studentName"));
            String gender = columns.containsKey("gender") ? cellAt(row, columns.get("gender")) : "";
            String phone = columns.containsKey("phone") ? cellAt(row, columns.get("phone")) : "";
            String email = columns.containsKey("email") ? cellAt(row, columns.get("email")) : "";
            String error = "";
            if (!StringUtils.hasText(studentNo)) {
                error = "学号不能为空";
            } else if (!StringUtils.hasText(studentName)) {
                error = "姓名不能为空";
            }
            boolean valid = !StringUtils.hasText(error);
            if (!valid) {
                errors.add("第 " + (rowIndex + 1) + " 行：" + error);
            }
            imports.add(new StudentImportRow(
                rowIndex + 1,
                studentNo,
                studentName,
                gender,
                classId,
                majorId,
                phone,
                email,
                valid,
                error
            ));
        }

        int validRows = (int) imports.stream().filter(StudentImportRow::valid).count();
        return new StudentImportResult(imports, validRows, imports.size() - validRows, errors);
    }

    private List<List<String>> readStudentWorkbookRows(MultipartFile file) throws IOException {
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            if (workbook.getNumberOfSheets() == 0) {
                return List.of();
            }
            List<List<String>> bestRows = readSheetRows(workbook.getSheetAt(0), formatter);
            int bestScore = studentHeaderScore(bestRows);
            for (int sheetIndex = 1; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                List<List<String>> rows = readSheetRows(workbook.getSheetAt(sheetIndex), formatter);
                int score = studentHeaderScore(rows);
                if (score > bestScore) {
                    bestScore = score;
                    bestRows = rows;
                }
            }
            return bestRows;
        }
    }

    private int findStudentHeaderIndex(List<List<String>> rows) {
        int bestIndex = 0;
        int bestScore = -1;
        int inspectRows = Math.min(rows.size(), 20);
        for (int index = 0; index < inspectRows; index++) {
            int score = studentHeaderScore(List.of(rows.get(index)));
            if (score > bestScore) {
                bestScore = score;
                bestIndex = index;
            }
        }
        return bestIndex;
    }

    private int studentHeaderScore(List<List<String>> rows) {
        int best = 0;
        int inspectRows = Math.min(rows.size(), 20);
        for (int index = 0; index < inspectRows; index++) {
            Map<String, Integer> columns = resolveStudentColumns(rows.get(index));
            int score = columns.size() * 20;
            if (columns.containsKey("studentNo")) score += 80;
            if (columns.containsKey("studentName")) score += 80;
            best = Math.max(best, score);
        }
        return best;
    }

    private Map<String, Integer> resolveStudentColumns(List<String> headers) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int index = 0; index < headers.size(); index++) {
            String header = normalizeGradeHeader(headers.get(index));
            if (!columns.containsKey("studentNo") && (header.contains("学号") || header.contains("学生编号"))) {
                columns.put("studentNo", index);
            } else if (!columns.containsKey("studentName") && (header.contains("姓名") || header.contains("学生姓名"))) {
                columns.put("studentName", index);
            } else if (!columns.containsKey("gender") && header.contains("性别")) {
                columns.put("gender", index);
            } else if (!columns.containsKey("phone") && (header.contains("手机") || header.contains("电话"))) {
                columns.put("phone", index);
            } else if (!columns.containsKey("email") && (header.contains("邮箱") || header.contains("email"))) {
                columns.put("email", index);
            }
        }
        return columns;
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

    private List<List<String>> readWorkbookRowsForContents(MultipartFile file, List<Map<String, Object>> contents) throws IOException {
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            SheetSelection best = null;
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                List<List<String>> rows = readSheetRows(sheet, formatter);
                SheetSelection selection = selectGradeContentSheet(sheet.getSheetName(), rows, contents);
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

    private SheetSelection selectGradeContentSheet(String sheetName, List<List<String>> rows, List<Map<String, Object>> contents) {
        SheetSelection best = null;
        int inspectRows = Math.min(rows.size(), 20);
        for (int rowIndex = 0; rowIndex < inspectRows; rowIndex++) {
            List<String> header = rows.get(rowIndex);
            if (header.isEmpty()) {
                continue;
            }
            Map<Integer, GradeContentColumn> columns = resolveGradeContentColumns(header, contents);
            if (columns.isEmpty()) {
                continue;
            }
            String first = normalizeGradeHeader(cellAt(header, 0));
            String second = normalizeGradeHeader(cellAt(header, 1));
            int score = columns.values().stream().mapToInt(GradeContentColumn::quality).sum() + columns.size() * 20;
            if (first.contains("学号") || first.contains("工号")) {
                score += 80;
            }
            if (second.contains("姓名") || second.contains("学生")) {
                score += 60;
            }
            String normalizedSheet = normalizeGradeHeader(sheetName);
            if (normalizedSheet.contains("学生成绩")) {
                score += 100;
            } else if (normalizedSheet.contains("成绩") || normalizedSheet.contains("作业") || normalizedSheet.contains("实验") || normalizedSheet.contains("试卷")) {
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

    private Map<Integer, GradeContentColumn> resolveGradeContentColumns(List<String> headers, List<Map<String, Object>> contents) {
        Map<Long, GradeContentColumn> bestByContent = new LinkedHashMap<>();
        for (int index = 2; index < headers.size(); index++) {
            String header = defaultString(headers.get(index), "");
            GradeContentColumn matched = matchAssessContentByHeader(index, header, contents);
            if (matched == null) {
                continue;
            }
            GradeContentColumn existing = bestByContent.get(matched.assessContentId());
            if (existing == null || matched.quality() > existing.quality()) {
                bestByContent.put(matched.assessContentId(), matched);
            }
        }
        if (bestByContent.isEmpty()) {
            int count = Math.min(Math.max(headers.size() - 2, 0), contents.size());
            for (int offset = 0; offset < count; offset++) {
                Map<String, Object> content = contents.get(offset);
                int index = offset + 2;
                double inputMaxScore = defaultDouble(extractExplicitMaxScore(cellAt(headers, index)), 100D);
                bestByContent.put(longValue(content.get("id")), new GradeContentColumn(
                    index,
                    longValue(content.get("assessItemId")),
                    longValue(content.get("id")),
                    inputMaxScore,
                    Math.max(doubleValue(content.get("weight")), 0D),
                    10
                ));
            }
        }
        return bestByContent.values().stream()
            .sorted(Comparator.comparingInt(GradeContentColumn::index))
            .collect(Collectors.toMap(
                GradeContentColumn::index,
                item -> item,
                (left, right) -> left,
                LinkedHashMap::new
            ));
    }

    private GradeContentColumn matchAssessContentByHeader(int index, String header, List<Map<String, Object>> contents) {
        int bestQuality = 0;
        Map<String, Object> best = null;
        for (Map<String, Object> content : contents) {
            int quality = gradeContentHeaderMatchQuality(header, content);
            if (quality > bestQuality) {
                bestQuality = quality;
                best = content;
            }
        }
        if (best == null || bestQuality <= 0) {
            return null;
        }
        double inputMaxScore = defaultDouble(extractExplicitMaxScore(header), 0D);
        return new GradeContentColumn(
            index,
            longValue(best.get("assessItemId")),
            longValue(best.get("id")),
            inputMaxScore,
            Math.max(doubleValue(best.get("weight")), 0D),
            bestQuality
        );
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

    private int gradeContentHeaderMatchQuality(String header, Map<String, Object> content) {
        String normalizedHeader = normalizeGradeHeader(header);
        if (!StringUtils.hasText(normalizedHeader)) {
            return 0;
        }
        String normalizedName = normalizeGradeHeader(stringValue(content.get("contentName")));
        String normalizedType = normalizeGradeHeader(assessContentTypeName(stringValue(content.get("contentType"))));
        List<String> ordinalTokens = contentOrdinalTokens(stringValue(content.get("contentNo")));
        int quality = 0;
        if (StringUtils.hasText(normalizedName)) {
            if (normalizedHeader.equals(normalizedName)) {
                quality += 300;
            } else if (normalizedHeader.contains(normalizedName) || normalizedName.contains(normalizedHeader)) {
                quality += 230;
            }
        }
        for (String token : ordinalTokens) {
            String normalizedToken = normalizeGradeHeader(token);
            if (!StringUtils.hasText(normalizedToken)) {
                continue;
            }
            boolean nameCarriesOrdinal = StringUtils.hasText(normalizedName) && normalizedName.contains(normalizedToken);
            if (nameCarriesOrdinal && normalizedHeader.equals(normalizedToken)) {
                quality += 140;
            } else if (normalizedHeader.contains(normalizedType + normalizedToken)
                || (nameCarriesOrdinal && normalizedHeader.contains(normalizedToken))
                || (nameCarriesOrdinal && normalizedHeader.contains("第" + normalizedToken))) {
                quality += 120;
            }
        }
        if (StringUtils.hasText(normalizedType) && normalizedHeader.contains(normalizedType)) {
            quality += 35;
        }
        if (extractExplicitMaxScore(header) != null) {
            quality += 30;
        }
        return quality;
    }

    private List<String> contentOrdinalTokens(String value) {
        String text = defaultString(value, "").trim();
        List<String> tokens = new ArrayList<>();
        if (StringUtils.hasText(text)) {
            tokens.add(text);
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+").matcher(text);
        if (matcher.find()) {
            int number = Integer.parseInt(matcher.group());
            tokens.add(String.valueOf(number));
            if (number >= 1 && number <= 10) {
                tokens.add(List.of("", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十").get(number));
                tokens.add(List.of("", "①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩").get(number));
            }
        }
        return tokens.stream().filter(StringUtils::hasText).distinct().toList();
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

    private ScoreParseResult parseComponentScore(String rawValue, double inputMaxScore, double targetMaxScore) {
        if (!StringUtils.hasText(rawValue)) {
            return new ScoreParseResult(0D, false, "成绩不能为空");
        }
        double target = targetMaxScore > 0D ? targetMaxScore : 100D;
        String normalized = rawValue.trim().replace("%", "").replace("％", "");
        try {
            double raw = Double.parseDouble(normalized);
            if (raw < 0D) {
                return new ScoreParseResult(raw, false, "成绩不能为负数");
            }
            double score;
            if (inputMaxScore > 0D && Math.abs(inputMaxScore - target) > 0.000001D) {
                if (raw > inputMaxScore) {
                    return new ScoreParseResult(raw, false, "成绩超出 0-" + formatNumber(inputMaxScore, 2));
                }
                score = inputMaxScore > 0D ? raw / inputMaxScore * target : raw;
            } else if (raw <= target) {
                score = raw;
            } else if (raw <= 100D && target < 100D) {
                score = raw / 100D * target;
            } else {
                return new ScoreParseResult(raw, false, "成绩超出 0-" + formatNumber(target, 2));
            }
            if (score > target + 0.000001D) {
                return new ScoreParseResult(score, false, "折算成绩超出 0-" + formatNumber(target, 2));
            }
            return new ScoreParseResult(round2(score), true, "");
        } catch (NumberFormatException exception) {
            return new ScoreParseResult(0D, false, "成绩不是有效数字");
        }
    }

    private void persistGradeImportRows(
        long batchId,
        long courseId,
        Long classId,
        long semesterId,
        long teacherId,
        List<GradeImportRow> rows
    ) {
        for (GradeImportRow row : rows) {
            Long studentId = findStudentId(row.studentNo());
            Long resolvedClassId = classId != null ? classId : findStudentClassId(row.studentNo());
            jdbcTemplate.update("""
                INSERT INTO student_grade (
                    course_id, class_id, student_id, assess_item_id, assess_content_id, semester_id, import_batch_id, student_no, student_name,
                    score, max_score, valid_flag, error_message, created_by
                ) VALUES (
                    :courseId, :classId, :studentId, :assessItemId, :assessContentId, :semesterId, :batchId, :studentNo, :studentName,
                    :score, :maxScore, :validFlag, :errorMessage, :createdBy
                )
                """, new MapSqlParameterSource()
                .addValue("courseId", courseId)
                .addValue("classId", resolvedClassId)
                .addValue("studentId", studentId)
                .addValue("assessItemId", row.assessItemId())
                .addValue("assessContentId", row.assessContentId())
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
              AND sg.assess_content_id IS NULL
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
              AND sg.assess_content_id IS NULL
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
              AND sg.assess_content_id IS NULL
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

    private Map<String, Object> buildAchievementSmartAnalysis(
        List<Map<String, Object>> results,
        Map<String, Object> dataSummary,
        double overallAchievement,
        double thresholdValue
    ) {
        double threshold = thresholdValue > 0D ? thresholdValue : 0.7D;
        int totalObjectives = results.size();
        long achievedCount = results.stream().filter(item -> booleanValue(item.get("isAchieved"))).count();
        List<Map<String, Object>> assessItems = listOfMap(dataSummary.get("assessItems"));
        List<String> warnings = listOfString(dataSummary.get("warnings"));

        List<Map<String, Object>> weakObjectives = results.stream()
            .filter(item -> !booleanValue(item.get("isAchieved")) || doubleValue(item.get("achieveValue")) < threshold)
            .sorted(Comparator.comparingDouble(item -> doubleValue(item.get("achieveValue"))))
            .limit(5)
            .map(item -> {
                double value = doubleValue(item.get("achieveValue"));
                return map(
                    "objectiveId", item.get("objectiveId"),
                    "objCode", defaultString(item.get("objCode"), ""),
                    "objContent", trimText(defaultString(item.get("objContent"), ""), 80),
                    "achieveValue", round4(value),
                    "gap", round4(Math.max(0D, threshold - value)),
                    "level", achievementLevel(value)
                );
            })
            .toList();

        List<Map<String, Object>> weakAssessItems = assessItems.stream()
            .filter(item -> longValue(item.get("confirmedRows")) == 0L || doubleValue(item.get("avgRate")) < 0.7D)
            .sorted(Comparator
                .comparingLong((Map<String, Object> item) -> longValue(item.get("confirmedRows")) == 0L ? 0L : 1L)
                .thenComparingDouble(item -> doubleValue(item.get("avgRate"))))
            .limit(5)
            .map(item -> map(
                "assessItemId", item.get("assessItemId"),
                "itemName", defaultString(item.get("itemName"), ""),
                "itemTypeName", defaultString(item.get("itemTypeName"), "其他"),
                "avgRate", round4(doubleValue(item.get("avgRate"))),
                "confirmedRows", longValue(item.get("confirmedRows")),
                "pendingRows", longValue(item.get("pendingRows"))
            ))
            .toList();

        String summary;
        if (totalObjectives == 0) {
            summary = "尚未形成课程目标达成度核算结果。请先完成课程目标、考核项、成绩导入与核算，系统将据此生成薄弱目标、薄弱考核项和改进建议。";
        } else {
            String tail = weakObjectives.isEmpty()
                ? "所有已核算课程目标均达到当前阈值，后续可重点关注评价数据的持续积累与跨学期对比。"
                : "其中" + weakObjectives.get(0).get("objCode") + "相对薄弱，建议优先复核相关教学内容、考核任务和过程反馈。";
            summary = "本次核算覆盖" + totalObjectives + "个课程目标，" + achievedCount + "个达到阈值，课程整体达成度为"
                + formatNumber(overallAchievement, 3) + "。" + tail;
        }

        List<String> highlights = new ArrayList<>();
        if (totalObjectives > 0) {
            highlights.add("课程目标达成率为" + achievedCount + "/" + totalObjectives + "，整体达成度"
                + (overallAchievement >= threshold ? "达到" : "低于") + "设定阈值" + formatNumber(threshold, 2) + "。");
        }
        if (!weakObjectives.isEmpty()) {
            Map<String, Object> weakest = weakObjectives.get(0);
            highlights.add(weakest.get("objCode") + "最低，达成度为" + formatNumber(doubleValue(weakest.get("achieveValue")), 3)
                + "，距离阈值差" + formatNumber(doubleValue(weakest.get("gap")), 3) + "。");
        }
        if (!weakAssessItems.isEmpty()) {
            Map<String, Object> weakestItem = weakAssessItems.get(0);
            String itemName = defaultString(weakestItem.get("itemName"), "未命名考核项");
            long confirmedRows = longValue(weakestItem.get("confirmedRows"));
            highlights.add(confirmedRows == 0L
                ? itemName + "暂无已确认成绩，当前核算无法体现该考核项表现。"
                : itemName + "平均得分率为" + formatNumber(doubleValue(weakestItem.get("avgRate")), 3) + "，是需要关注的考核环节。");
        }
        if (!warnings.isEmpty()) {
            highlights.add(warnings.get(0));
        }
        if (highlights.isEmpty()) {
            highlights.add("当前数据完整性较好，达成度结果可用于报告生成和持续改进跟踪。");
        }

        List<String> actions = new ArrayList<>();
        if (longValue(dataSummary.get("pendingGradeRows")) > 0L) {
            actions.add("先确认待写入成绩数据，避免未确认成绩影响核算口径。");
        }
        if (longValue(dataSummary.get("mappingCount")) == 0L && longValue(dataSummary.get("objectiveCount")) > 0L) {
            actions.add("补充目标-考核项支撑矩阵，明确每个考核项对课程目标的贡献权重。");
        }
        if (!weakObjectives.isEmpty()) {
            actions.add("围绕" + weakObjectives.get(0).get("objCode") + "梳理薄弱知识点，增加课堂练习、案例讲解和阶段反馈。");
        }
        if (!weakAssessItems.isEmpty()) {
            actions.add("复核" + weakAssessItems.get(0).get("itemName") + "的任务难度、评分标准和训练覆盖度。");
        }
        if (actions.isEmpty()) {
            actions.add("保持当前教学组织和考核结构，并在下一轮课程中继续积累对比数据。");
            actions.add("结合学生明细定位个体差异，对临界学生开展针对性辅导。");
        }

        String riskLevel = "info";
        if (totalObjectives > 0 && overallAchievement < threshold) {
            riskLevel = "danger";
        } else if (!weakObjectives.isEmpty() || !weakAssessItems.isEmpty() || !warnings.isEmpty()) {
            riskLevel = "warning";
        } else if (totalObjectives > 0) {
            riskLevel = "success";
        }

        return map(
            "summary", summary,
            "riskLevel", riskLevel,
            "thresholdValue", threshold,
            "achievedCount", achievedCount,
            "objectiveCount", totalObjectives,
            "highlights", highlights,
            "weakObjectives", weakObjectives,
            "weakAssessItems", weakAssessItems,
            "actions", actions
        );
    }

    private Map<String, Object> buildAchievementChartData(List<Map<String, Object>> results, Map<String, Object> dataSummary) {
        List<Map<String, Object>> assessItems = listOfMap(dataSummary.get("assessItems"));
        return map(
            "objectiveBars", results.stream()
                .map(item -> map(
                    "name", defaultString(item.get("objCode"), ""),
                    "value", round4(doubleValue(item.get("achieveValue"))),
                    "achieved", booleanValue(item.get("isAchieved"))
                ))
                .toList(),
            "componentBars", buildDimensionData(results),
            "assessRates", assessItems.stream()
                .map(item -> map(
                    "name", defaultString(item.get("itemName"), ""),
                    "typeName", defaultString(item.get("itemTypeName"), "其他"),
                    "value", round4(doubleValue(item.get("avgRate"))),
                    "weight", doubleValue(item.get("weight"))
                ))
                .toList(),
            "gradeCoverage", List.of(
                map("name", "已确认成绩", "value", longValue(dataSummary.get("confirmedGradeRows"))),
                map("name", "待确认成绩", "value", longValue(dataSummary.get("pendingGradeRows")))
            )
        );
    }

    private String achievementLevel(double value) {
        if (value >= 0.9D) {
            return "优秀";
        }
        if (value >= 0.8D) {
            return "良好";
        }
        if (value >= 0.7D) {
            return "中等";
        }
        if (value >= 0.6D) {
            return "基本达成";
        }
        return "未达成";
    }

    private String trimText(String text, int maxLength) {
        String normalized = defaultString(text, "").replaceAll("\\s+", "");
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength)) + "…";
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
               AND sg.assess_content_id IS NULL
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

    private record GradeContentColumn(int index, long assessItemId, long assessContentId, double inputMaxScore, double weight, int quality) {
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
        Long assessContentId,
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

    private record StudentImportRow(
        int rowNumber,
        String studentNo,
        String studentName,
        String gender,
        Long classId,
        Long majorId,
        String phone,
        String email,
        boolean valid,
        String errorMessage
    ) {
    }

    private record StudentImportResult(
        List<StudentImportRow> rows,
        int validRows,
        int errorRows,
        List<String> errors
    ) {
    }
}
