package com.example.coa.service.parse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class OutlineParseEngine {

    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)");
    private static final Pattern WEIGHT_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:%|％|分)");
    private static final Pattern PERCENT_WEIGHT_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:%|％)");

    private static final Pattern EXPLICIT_OBJECTIVE_PATTERN = Pattern.compile(
        "^(?:课程目标|教学目标|学习目标|目标|course objective|course objectives|objective)\\s*"
            + "([0-9一二三四五六七八九十]+(?:\\.\\d+)*)?\\s*[.、:：)）-]?\\s*(.*)$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern NUMBERED_OBJECTIVE_PATTERN = Pattern.compile(
        "^([0-9一二三四五六七八九十]+(?:\\.\\d+)*)\\s*[.、:：)）-]\\s*(.*)$"
    );
    private static final Pattern ENGLISH_NUMBERED_OBJECTIVE_PATTERN = Pattern.compile(
        "^objective\\s*([0-9]+)\\s*[.、:：)）-]?\\s*(.*)$",
        Pattern.CASE_INSENSITIVE
    );

    private static final List<String> OBJECTIVE_SECTION_KEYWORDS = List.of(
        "课程目标", "教学目标", "学习目标", "course objectives", "teaching objectives", "learning objectives"
    );
    private static final List<String> OBJECTIVE_SECTION_EXCLUDE_KEYWORDS = List.of(
        "对应课程目标", "课程目标达成", "课程目标与毕业要求", "课程目标支撑", "毕业要求与课程目标",
        "覆盖课程目标", "重点覆盖课程目标"
    );
    private static final List<String> OBJECTIVE_SECTION_STOP_KEYWORDS = List.of(
        "课程内容", "课程教学内容", "教学内容", "教学内容设置", "教学安排", "教学进度", "考核方式", "成绩构成", "评分标准",
        "教材", "参考书", "毕业要求", "课程思政", "assessment", "grading", "contents",
        "course content", "teaching content", "schedule"
    );
    private static final List<String> OBJECTIVE_NOISE_KEYWORDS = List.of(
        "课程基本信息", "课程简介", "教学内容", "教学进度", "对应课程目标", "学时", "教学形式",
        "作业及考核要求", "章节", "参考学时", "课程思政", "考核方式", "成绩构成", "评分标准",
        "教材或参考资料", "教材", "参考资料", "毕业要求", "表", "table", "course information"
    );
    private static final List<String> OBJECTIVE_ACTION_KEYWORDS = List.of(
        "掌握", "了解", "熟悉", "理解", "认识", "知道", "能够", "学会", "运用", "应用", "分析",
        "设计", "实现", "解决", "处理", "判断", "表达", "研究", "制定", "选择", "完成",
        "培养", "形成", "树立", "增强", "提升", "具备", "发展", "提高",
        "master", "understand", "analyze", "design", "implement", "solve", "develop", "apply"
    );
    private static final List<String> SCORE_KEYWORDS = List.of(
        "成绩", "考试", "考核", "评分", "分值", "总成绩", "grade", "exam", "assessment", "evaluation", "score"
    );
    private static final List<String> STRONG_ASSESS_KEYWORDS = List.of(
        "平时成绩", "平时", "作业", "课堂", "期中", "期末", "实验", "实践", "报告",
        "regular", "assignment", "quiz", "midterm", "final", "practice", "lab", "report"
    );
    private static final List<String> ASSESS_SECTION_KEYWORDS = List.of(
        "考核方式", "成绩构成", "评分标准", "成绩比例", "assessment", "grading", "evaluation"
    );
    private static final List<String> ASSESS_STOP_KEYWORDS = List.of(
        "教材", "参考书", "毕业要求", "课程目标", "教学目标", "学习目标", "课程内容", "教学内容"
    );
    private static final List<String> ASSESS_NOISE_KEYWORDS = List.of(
        "对应课程目标", "课程目标达成", "学生学习成果", "教学内容", "章节", "参考学时"
    );

    private static final Map<Integer, List<String>> OBJECTIVE_HIGH_WEIGHT_KEYWORDS = Map.of(
        1, List.of("掌握", "了解", "熟悉", "理解", "认识", "知道", "原理", "概念", "理论", "方法", "master", "understand"),
        2, List.of("能够", "运用", "应用", "分析", "设计", "实现", "解决", "处理", "判断", "研究", "制定", "选择", "analyze", "design", "implement", "solve", "apply"),
        3, List.of("培养", "形成", "树立", "增强", "提升", "素养", "责任", "态度", "团队", "合作", "职业", "ethics", "teamwork")
    );
    private static final Map<Integer, List<String>> OBJECTIVE_MID_WEIGHT_KEYWORDS = Map.of(
        1, List.of("描述", "说明", "记忆", "归纳", "辨识", "describe", "explain"),
        2, List.of("完成", "表达", "使用", "评价", "实验", "训练", "实践", "检测", "建模", "use", "evaluate", "practice"),
        3, List.of("规范", "意识", "创新", "协作", "健康", "professional", "innovation", "health")
    );

    private static final List<AssessKeywordRule> ASSESS_KEYWORD_RULES = List.of(
        new AssessKeywordRule("平时成绩", "normal", List.of("平时成绩", "平时", "作业", "课堂", "regular", "assignment", "quiz")),
        new AssessKeywordRule("期中成绩", "mid", List.of("期中成绩", "期中考试", "期中", "midterm", "mid exam")),
        new AssessKeywordRule("期末成绩", "final", List.of("期末成绩", "期末考试", "期末", "技术考试", "技能考试", "final", "final exam", "technical exam")),
        new AssessKeywordRule("实践成绩", "practice", List.of("实践", "实验", "上机", "课程设计", "项目", "project", "practice", "lab")),
        new AssessKeywordRule("报告成绩", "report", List.of("报告", "汇报", "report", "presentation"))
    );

    private static final Map<String, List<String>> COURSE_FIELD_ALIASES = Map.ofEntries(
        Map.entry("courseCode", List.of("课程代码", "课程编号", "课程代号", "course code")),
        Map.entry("courseNameZh", List.of("课程名称（中文）", "课程名称(中文)", "课程中文名称", "中文名称", "课程名称（中/英）", "课程名称(中/英)", "课程名称")),
        Map.entry("courseNameEn", List.of("课程名称（英文）", "课程名称(英文)", "课程英文名称", "英文名称", "course name")),
        Map.entry("courseType", List.of("课程类型", "课程性质", "课程类别")),
        Map.entry("targetStudents", List.of("授课对象", "适用对象", "适用专业", "开课对象")),
        Map.entry("teachingLanguage", List.of("授课语言", "教学语言")),
        Map.entry("collegeName", List.of("开课院系", "开课单位", "承担单位", "所属学院", "开课学院")),
        Map.entry("hours", List.of("课程总学时", "总学时", "学时")),
        Map.entry("credits", List.of("学分")),
        Map.entry("prerequisiteCourse", List.of("先修课程", "先修课", "前导课程")),
        Map.entry("courseOwner", List.of("课程负责人", "负责人", "主讲教师", "任课教师", "制定人"))
    );

    public ParsedOutlineDraft parse(String fileName, byte[] fileBytes) {
        String extension = fileExtension(fileName);
        if (!Set.of("doc", "docx", "pdf").contains(extension)) {
            throw new IllegalArgumentException("仅支持上传 doc、docx 或 pdf 格式的课程大纲文件。");
        }

        List<SourceSegment> segments = switch (extension) {
            case "doc" -> extractFromDoc(fileBytes);
            case "docx" -> extractFromDocx(fileBytes);
            case "pdf" -> extractFromPdf(fileBytes);
            default -> List.of();
        };

        if (segments.isEmpty()) {
            throw new IllegalArgumentException("未从文件中提取到有效文本，请检查课程大纲文件内容。");
        }

        CourseInfo basicCourseInfo = extractCourseInfo(segments, fileName);
        List<ObjectiveCandidate> objectiveCandidates = selectPrimaryObjectiveCandidates(extractObjectiveCandidates(segments));
        if (objectiveCandidates.isEmpty()) {
            throw new IllegalArgumentException("未识别到课程目标，请检查文档中是否包含“课程目标/教学目标/学习目标”等内容。");
        }

        ObjAssessMatrix objAssessMatrix = buildObjAssessMatrix(segments);
        applyObjectiveWeightsFromMatrix(objectiveCandidates, objAssessMatrix);
        assignObjectiveWeights(objectiveCandidates);
        List<ObjectiveDraftSuggestion> objectives = buildObjectiveSuggestions(objectiveCandidates);

        List<AssessCandidate> assessCandidates = extractAssessCandidates(segments);
        mergeAssessCandidatesFromMatrix(assessCandidates, objAssessMatrix);
        alignAssessCandidatesWithMatrixNames(assessCandidates, objAssessMatrix);
        completeAssessTypes(assessCandidates);
        normalizeAssessWeights(assessCandidates);
        List<AssessItemDraftSuggestion> assessItems = assessCandidates.stream()
            .map(candidate -> new AssessItemDraftSuggestion(
                candidate.name,
                candidate.type,
                candidate.weight,
                round3(candidate.confidenceScore),
                confidenceLevel(candidate.confidenceScore),
                candidate.originalText
            ))
            .toList();
        List<ObjectiveAssessMappingSuggestion> mappings = matrixToMappings(objAssessMatrix, assessItems);
        CourseInfo courseInfo = enrichCourseInfo(
            basicCourseInfo,
            extractTeachingContents(segments),
            extractAssessmentDetails(segments, assessCandidates),
            extractAssessmentStandards(segments),
            extractAssessmentPolicy(segments)
        );

        return new ParsedOutlineDraft(objectives, assessItems, mappings, objAssessMatrix, segments, courseInfo);
    }

    private List<ObjectiveDraftSuggestion> buildObjectiveSuggestions(List<ObjectiveCandidate> candidates) {
        List<ObjectiveDraftSuggestion> objectives = new ArrayList<>();
        int objectiveIndex = 1;
        for (ObjectiveCandidate candidate : candidates) {
            ObjectiveTypePrediction prediction = classifyObjectiveType(candidate.content);
            double confidenceScore = computeObjectiveConfidence(candidate, prediction);
            objectives.add(new ObjectiveDraftSuggestion(
                "OBJ-" + objectiveIndex++,
                candidate.content,
                prediction.type(),
                candidate.weight,
                defaultIfBlank(candidate.gradReqId, ""),
                defaultIfBlank(candidate.gradReqDesc, ""),
                defaultIfBlank(candidate.relationLevel, ""),
                round3(confidenceScore),
                confidenceLevel(confidenceScore),
                candidate.originalText
            ));
        }
        return objectives;
    }

    private List<SourceSegment> extractFromDoc(byte[] fileBytes) {
        try (
            HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(fileBytes));
            WordExtractor extractor = new WordExtractor(document)
        ) {
            List<SourceSegment> segments = new ArrayList<>();
            int sortOrder = 1;
            for (String paragraph : extractor.getParagraphText()) {
                String text = sanitizeText(paragraph);
                if (StringUtils.hasText(text)) {
                    segments.add(new SourceSegment("段落" + sortOrder, text, sortOrder++));
                }
            }
            return segments;
        } catch (IOException exception) {
            throw new IllegalArgumentException("DOC 文件解析失败，请检查文件是否损坏。", exception);
        }
    }

    private List<SourceSegment> extractFromDocx(byte[] fileBytes) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(fileBytes))) {
            List<SourceSegment> segments = new ArrayList<>();
            int sortOrder = 1;

            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String text = sanitizeText(paragraph.getText());
                    if (StringUtils.hasText(text)) {
                        segments.add(new SourceSegment("段落" + sortOrder, text, sortOrder++));
                    }
                    continue;
                }

                if (element instanceof XWPFTable table) {
                    for (String rowText : flattenTable(table)) {
                        segments.add(new SourceSegment("表格" + sortOrder, rowText, sortOrder++));
                    }
                }
            }
            return segments;
        } catch (IOException exception) {
            throw new IllegalArgumentException("DOCX 文件解析失败，请检查文件是否损坏。", exception);
        }
    }

    private List<SourceSegment> extractFromPdf(byte[] fileBytes) {
        try (PDDocument document = Loader.loadPDF(fileBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);

            if (!StringUtils.hasText(sanitizeText(text))) {
                throw new IllegalArgumentException("当前 PDF 未提取到可复制文本，暂不支持扫描版 PDF，请优先上传 Word 版本。");
            }

            List<SourceSegment> segments = new ArrayList<>();
            int sortOrder = 1;
            for (String rawLine : text.split("\\r?\\n")) {
                String line = sanitizeText(rawLine);
                if (StringUtils.hasText(line)) {
                    segments.add(new SourceSegment("段落" + sortOrder, line, sortOrder++));
                }
            }

            if (segments.isEmpty()) {
                throw new IllegalArgumentException("当前 PDF 未提取到有效文本，暂不支持扫描版 PDF，请优先上传 Word 版本。");
            }
            return segments;
        } catch (IOException exception) {
            throw new IllegalArgumentException("PDF 文件解析失败，请检查文件是否损坏。", exception);
        }
    }

    private List<String> flattenTable(XWPFTable table) {
        List<String> rows = new ArrayList<>();
        table.getRows().forEach(row -> {
            List<String> cells = row.getTableCells().stream()
                .map(cell -> sanitizeText(cell.getText()))
                .filter(StringUtils::hasText)
                .toList();
            if (!cells.isEmpty()) {
                rows.add(String.join(" | ", cells));
            }
        });
        return rows;
    }

    private CourseInfo extractCourseInfo(List<SourceSegment> segments, String fileName) {
        Map<String, String> values = new LinkedHashMap<>();

        for (SourceSegment segment : segments) {
            collectCourseInfoFromText(segment.text(), values);
        }
        collectSplitHoursCredits(segments, values);

        String courseNameZh = defaultIfBlank(values.get("courseNameZh"), deriveNameFromFile(fileName));
        String courseNameEn = defaultIfBlank(values.get("courseNameEn"), "");

        if (!StringUtils.hasText(courseNameZh) && StringUtils.hasText(values.get("courseNameEn")) && containsChinese(values.get("courseNameEn"))) {
            courseNameZh = values.get("courseNameEn");
            courseNameEn = "";
        }
        if (!StringUtils.hasText(courseNameEn) && StringUtils.hasText(values.get("courseNameZh")) && !containsChinese(values.get("courseNameZh"))) {
            courseNameEn = values.get("courseNameZh");
            courseNameZh = deriveNameFromFile(fileName);
        }

        return new CourseInfo(
            defaultIfBlank(values.get("courseCode"), ""),
            defaultIfBlank(courseNameZh, ""),
            defaultIfBlank(courseNameEn, ""),
            defaultIfBlank(values.get("courseType"), ""),
            defaultIfBlank(values.get("targetStudents"), ""),
            defaultIfBlank(values.get("teachingLanguage"), ""),
            defaultIfBlank(values.get("collegeName"), ""),
            parseHours(values.get("hours")),
            parseCredits(values.get("credits")),
            defaultIfBlank(values.get("prerequisiteCourse"), ""),
            defaultIfBlank(values.get("courseOwner"), "")
        );
    }

    private CourseInfo enrichCourseInfo(
        CourseInfo base,
        List<TeachingContentInfo> teachingContents,
        List<AssessmentDetail> assessmentDetails,
        List<AssessmentStandard> assessmentStandards,
        AssessmentPolicy assessmentPolicy
    ) {
        return new CourseInfo(
            base.courseCode(),
            base.courseNameZh(),
            base.courseNameEn(),
            base.courseType(),
            base.targetStudents(),
            base.teachingLanguage(),
            base.collegeName(),
            base.hours(),
            base.credits(),
            base.prerequisiteCourse(),
            base.courseOwner(),
            teachingContents,
            assessmentDetails,
            assessmentStandards,
            assessmentPolicy
        );
    }

    private List<TeachingContentInfo> extractTeachingContents(List<SourceSegment> segments) {
        Map<String, TeachingContentInfo> result = new LinkedHashMap<>();
        List<LineEntry> lines = flattenLines(segments);
        for (LineEntry entry : lines) {
            List<String> cells = entry.cells();
            if (cells.size() >= 3 && cells.get(0).matches("\\d+")) {
                String title = cells.get(1);
                if (!looksLikeTeachingContentTitle(title)) {
                    continue;
                }
                String detailWindow = findTeachingDetailWindow(lines, title);
                String method = cells.stream()
                    .filter(cell -> containsAny(cell, List.of("讲授", "上机", "实验", "实践", "讨论")))
                    .reduce((left, right) -> right)
                    .orElse("");
                Integer hours = cells.stream()
                    .skip(2)
                    .map(this::parseFirstInteger)
                    .filter(value -> value != null && value > 0)
                    .findFirst()
                    .orElse(null);
                int lectureHours = method.contains("上机") || method.contains("实验") ? 0 : defaultInteger(hours);
                int practiceHours = method.contains("上机") || method.contains("实验") ? defaultInteger(hours) : 0;
                result.putIfAbsent(normalizeForMatch(title), new TeachingContentInfo(
                    title,
                    lectureHours == 0 ? null : lectureHours,
                    practiceHours == 0 ? null : practiceHours,
                    method,
                    defaultIfBlank(extractRelatedObjectives(entry.text()), extractRelatedObjectives(detailWindow)),
                    extractRequirementText(detailWindow),
                    defaultIfBlank(detailWindow, entry.text())
                ));
            }
        }

        if (!result.isEmpty()) {
            return new ArrayList<>(result.values());
        }

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).text();
            TeachingHeading heading = matchTeachingHeading(line);
            if (heading == null) {
                continue;
            }
            String window = collectLineWindow(lines, index, 8);
            result.putIfAbsent(normalizeForMatch(heading.title()), new TeachingContentInfo(
                heading.title(),
                heading.hours(),
                null,
                heading.title().contains("实验") ? "上机" : "讲授",
                extractRelatedObjectives(window),
                extractRequirementText(window),
                window
            ));
        }
        return new ArrayList<>(result.values());
    }

    private String findTeachingDetailWindow(List<LineEntry> lines, String title) {
        String target = normalizeTeachingTitle(title);
        if (!StringUtils.hasText(target)) {
            return "";
        }
        boolean targetIsExperiment = sanitizeText(title).startsWith("实验");
        for (int index = 0; index < lines.size(); index++) {
            TeachingHeading heading = matchTeachingHeading(lines.get(index).text());
            if (heading == null) {
                continue;
            }
            boolean headingIsExperiment = sanitizeText(heading.title()).startsWith("实验");
            if (targetIsExperiment != headingIsExperiment) {
                continue;
            }
            String current = normalizeTeachingTitle(heading.title());
            if (current.equals(target) || current.contains(target) || target.contains(current)) {
                return collectTeachingBlock(lines, index);
            }
        }
        return "";
    }

    private String collectTeachingBlock(List<LineEntry> lines, int startIndex) {
        StringBuilder builder = new StringBuilder();
        int start = Math.max(0, startIndex - 1);
        for (int index = start; index < lines.size(); index++) {
            if (index > startIndex) {
                TeachingHeading nextHeading = matchTeachingHeading(lines.get(index).text());
                if (nextHeading != null || looksLikeHeading(lines.get(index).text(), List.of("教学安排及教学方式", "考核要求与成绩评定"))) {
                    break;
                }
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(lines.get(index).text());
        }
        return builder.toString();
    }

    private List<AssessmentDetail> extractAssessmentDetails(List<SourceSegment> segments, List<AssessCandidate> candidates) {
        List<LineEntry> lines = flattenLines(segments);
        Map<String, AssessmentDetailBlock> blocksByType = extractAssessmentDetailBlocks(lines);
        List<AssessmentDetail> result = new ArrayList<>();
        for (AssessCandidate candidate : candidates) {
            AssessmentDetailBlock block = blocksByType.get(candidate.type);
            if (block != null) {
                result.add(new AssessmentDetail(
                    candidate.name,
                    round2(candidate.weight),
                    block.content(),
                    block.evaluationMethod(),
                    block.supports(),
                    block.originalText()
                ));
                continue;
            }
            int index = findAssessmentLineIndex(lines, candidate);
            String window = index >= 0 ? collectLineWindow(lines, index, 12) : candidate.originalText;
            result.add(new AssessmentDetail(
                candidate.name,
                round2(candidate.weight),
                extractBetweenLabels(window, List.of("考核内容及方式", "考核内容", "方式"), List.of("评价办法", "支撑", "考核方式", "是否设置补考")),
                extractBetweenLabels(window, List.of("评价办法"), List.of("支撑", "考核方式", "是否设置补考")),
                extractSupportText(window),
                window
            ));
        }
        return result;
    }

    private Map<String, AssessmentDetailBlock> extractAssessmentDetailBlocks(List<LineEntry> lines) {
        Map<String, MutableAssessmentDetailBlock> blocks = new LinkedHashMap<>();
        MutableAssessmentDetailBlock current = null;
        for (LineEntry line : lines) {
            List<String> cells = line.cells();
            if (cells.isEmpty()) {
                continue;
            }

            AssessKeywordRule headingRule = assessRuleForHeaderToken(cells.get(0));
            boolean startsBlock = headingRule != null
                && containsAny(cells.get(0), List.of("评定", "考核", "成绩"))
                && containsAny(line.text(), List.of("权重", "%", "％", "占课程最终成绩"));
            if (startsBlock) {
                current = new MutableAssessmentDetailBlock(headingRule.type(), headingRule.name());
                current.weight = firstWeightValue(cells, line.text());
                current.appendOriginal(line.text());
                blocks.putIfAbsent(current.type, current);
                current = blocks.get(current.type);
                continue;
            }

            if (current == null) {
                continue;
            }

            String label = normalizeForMatch(cells.get(0));
            String value = cells.size() >= 2
                ? String.join(" ", cells.subList(1, cells.size()))
                : line.text();
            if (label.contains("考核内容") || label.contains("考核方式")) {
                current.content = defaultIfBlank(current.content, sanitizeText(value));
                current.appendOriginal(line.text());
            } else if (label.contains("评价办法")) {
                current.evaluationMethod = defaultIfBlank(current.evaluationMethod, sanitizeText(value));
                current.appendOriginal(line.text());
            } else if (label.equals("支撑") || label.contains("支撑毕业要求")) {
                current.supports = defaultIfBlank(current.supports, sanitizeText(value));
                current.appendOriginal(line.text());
            } else if (label.contains("是否设置补考") || label.contains("考核方式") && containsAny(line.text(), List.of("是否设置补考"))) {
                current = null;
            }
        }

        Map<String, AssessmentDetailBlock> result = new LinkedHashMap<>();
        for (MutableAssessmentDetailBlock block : blocks.values()) {
            result.put(block.type, new AssessmentDetailBlock(
                block.name,
                block.type,
                block.weight,
                defaultIfBlank(block.content, ""),
                defaultIfBlank(block.evaluationMethod, ""),
                defaultIfBlank(block.supports, ""),
                block.originalText.toString()
            ));
        }
        return result;
    }

    private List<AssessmentStandard> extractAssessmentStandards(List<SourceSegment> segments) {
        List<LineEntry> lines = flattenLines(segments);
        List<AssessmentStandard> result = new ArrayList<>();
        List<String> fallback = new ArrayList<>();
        boolean inStandardSection = false;
        String currentMethod = "";
        for (LineEntry line : lines) {
            String text = line.text();
            if (containsAny(text, List.of("考核与评价标准", "评价标准表", "评分标准表"))) {
                inStandardSection = true;
                currentMethod = defaultIfBlank(extractAssessmentMethodFromStandardTitle(text), currentMethod);
            }
            if (!inStandardSection) {
                continue;
            }
            if (looksLikeHeading(text, List.of("教材", "参考书", "课程资源", "大纲制定"))) {
                break;
            }

            String methodFromTitle = extractAssessmentMethodFromStandardTitle(text);
            if (StringUtils.hasText(methodFromTitle)) {
                currentMethod = methodFromTitle;
            }

            AssessmentStandard row = parseAssessmentStandardRow(line.cells(), text, currentMethod);
            if (row != null) {
                if (StringUtils.hasText(row.assessmentMethod())) {
                    currentMethod = row.assessmentMethod();
                }
                result.add(row);
            } else if (containsAny(text, List.of("评价标准", "优秀", "良好", "中等", "合格", "不合格", "课程目标"))) {
                fallback.add(text);
            }
            if (result.size() >= 60) {
                break;
            }
        }
        if (result.isEmpty()) {
            for (int index = 0; index < Math.min(fallback.size(), 24); index++) {
                String text = fallback.get(index);
                result.add(new AssessmentStandard("", "", text, "", "", "", "", "", text));
            }
        }
        return result;
    }

    private AssessmentStandard parseAssessmentStandardRow(List<String> cells, String text, String currentMethod) {
        if (cells.size() < 6 || containsAny(text, List.of("评价标准", "优秀", "良好", "中等", "不合格")) && !containsAny(text, List.of("按时", "不按时", "正确", "清楚", "规范", "作业", "报告", "设计"))) {
            return null;
        }

        int objectiveIndex = -1;
        for (int index = 0; index < cells.size(); index++) {
            if (looksLikeStandardObjectiveCell(cells.get(index))) {
                objectiveIndex = index;
                break;
            }
        }
        if (objectiveIndex < 0) {
            return null;
        }

        String method = objectiveIndex > 0 ? sanitizeText(cells.get(objectiveIndex - 1)) : defaultIfBlank(currentMethod, "");
        String objective = sanitizeText(cells.get(objectiveIndex));
        int gradeStart = objectiveIndex + 1;
        if (cells.size() - gradeStart < 5) {
            return null;
        }

        String excellent = cellOrBlank(cells, gradeStart);
        String good = cellOrBlank(cells, gradeStart + 1);
        String medium = cellOrBlank(cells, gradeStart + 2);
        String pass = cellOrBlank(cells, gradeStart + 3);
        String fail = cellOrBlank(cells, gradeStart + 4);
        String scorePercent = cells.size() > gradeStart + 5 ? sanitizeScorePercent(cells.get(cells.size() - 1)) : "";
        if (!StringUtils.hasText(scorePercent) && cells.size() > gradeStart + 5) {
            scorePercent = sanitizeText(cells.get(gradeStart + 5));
        }
        if (!StringUtils.hasText(excellent) || !StringUtils.hasText(good) || !StringUtils.hasText(fail)) {
            return null;
        }
        return new AssessmentStandard(
            method,
            objective,
            excellent,
            good,
            medium,
            pass,
            fail,
            scorePercent,
            text
        );
    }

    private boolean looksLikeStandardObjectiveCell(String text) {
        String normalized = normalizeForMatch(text);
        return normalized.matches(".*课程目标\\s*\\d+.*")
            || normalized.matches(".*目标\\s*\\d+.*")
            || normalized.matches("obj[-_]?\\d+");
    }

    private String extractAssessmentMethodFromStandardTitle(String text) {
        String normalized = sanitizeText(text);
        if (!containsAny(normalized, List.of("评价标准表", "评分标准表"))) {
            return "";
        }
        normalized = normalized.replaceAll("^表\\s*\\d+\\s*", "").trim();
        normalized = normalized.replaceAll("[\\s　]*评价标准表.*$", "").trim();
        normalized = normalized.replaceAll("[\\s　]*评分标准表.*$", "").trim();
        normalized = normalized.replaceAll("^[：:、，,.\\-]+", "").trim();
        return normalized.length() <= 20 ? normalized : "";
    }

    private String cellOrBlank(List<String> cells, int index) {
        return index >= 0 && index < cells.size() ? sanitizeText(cells.get(index)) : "";
    }

    private String sanitizeScorePercent(String text) {
        Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*[%％]?").matcher(defaultIfBlank(text, ""));
        return matcher.find() ? matcher.group(1) : sanitizeText(text);
    }

    private AssessmentPolicy extractAssessmentPolicy(List<SourceSegment> segments) {
        String scoreRecordMode = "";
        String finalGradeComposition = "";
        String assessmentMode = "";
        String makeupExam = "";
        StringBuilder originalText = new StringBuilder();

        for (LineEntry line : flattenLines(segments)) {
            List<String> cells = line.cells();
            if (cells.size() < 2) {
                continue;
            }

            for (int index = 0; index + 1 < cells.size(); index += 2) {
                String label = normalizeForMatch(cells.get(index));
                String value = sanitizeText(cells.get(index + 1));
                if (!StringUtils.hasText(value)) {
                    continue;
                }

                if (label.contains("课程最终成绩记载方式") || label.contains("成绩记载方式")) {
                    scoreRecordMode = defaultIfBlank(scoreRecordMode, value);
                    appendLine(originalText, line.text());
                } else if (label.contains("课程最终成绩组成") || label.contains("成绩组成")) {
                    finalGradeComposition = defaultIfBlank(finalGradeComposition, value);
                    appendLine(originalText, line.text());
                } else if (label.equals("考核方式") || label.contains("课程考核方式")) {
                    assessmentMode = defaultIfBlank(assessmentMode, value);
                    appendLine(originalText, line.text());
                } else if (label.contains("是否设置补考") || label.contains("补考")) {
                    makeupExam = defaultIfBlank(makeupExam, value);
                    appendLine(originalText, line.text());
                }
            }
        }

        return new AssessmentPolicy(
            scoreRecordMode,
            finalGradeComposition,
            assessmentMode,
            makeupExam,
            originalText.toString()
        );
    }

    private void appendLine(StringBuilder builder, String text) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        if (builder.toString().contains(text)) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(text);
    }

    private boolean looksLikeTeachingContentTitle(String text) {
        if (!StringUtils.hasText(text) || containsAny(text, List.of("课程内容", "合计", "序号", "教学方式"))) {
            return false;
        }
        String normalized = sanitizeText(text);
        return normalized.matches("^[（(][一二三四五六七八九十]+[）)].+")
            || normalized.startsWith("实验")
            || containsAny(normalized, List.of("系统", "用户界面", "HTTP", "Servlet", "Ajax", "Web", "数据", "控件", "案例"));
    }

    private String normalizeTeachingTitle(String text) {
        String normalized = sanitizeText(text);
        normalized = normalized.replaceAll("[（(]\\s*\\d+\\s*学时\\s*[）)]", "");
        normalized = normalized.replaceAll("[,，]\\s*\\d+\\s*学时\\s*$", "");
        normalized = normalized.replaceAll("^[（(]?[一二三四五六七八九十]+[）)]", "");
        return normalizeForMatch(normalized);
    }

    private Integer parseFirstInteger(String text) {
        Matcher matcher = Pattern.compile("\\d+").matcher(defaultIfBlank(text, ""));
        return matcher.find() ? Integer.parseInt(matcher.group()) : null;
    }

    private int defaultInteger(Integer value) {
        return value == null ? 0 : value;
    }

    private String extractRelatedObjectives(String text) {
        Matcher matcher = Pattern.compile("课程目标\\s*([0-9一二三四五六七八九十、,，\\s]+)").matcher(defaultIfBlank(text, ""));
        return matcher.find() ? "课程目标" + matcher.group(1).trim() : "";
    }

    private TeachingHeading matchTeachingHeading(String text) {
        String normalized = defaultIfBlank(text, "");
        Matcher section = Pattern.compile("^[（(][一二三四五六七八九十]+[）)]\\s*(.+?)[（(]\\s*(\\d+)\\s*学时\\s*[）)]").matcher(normalized);
        if (section.find()) {
            return new TeachingHeading(section.group(1).trim(), Integer.parseInt(section.group(2)));
        }
        Matcher experiment = Pattern.compile("^(实验[一二三四五六七八九十]+[:：].+?)[（(]\\s*(\\d+)\\s*学时\\s*[）)]").matcher(normalized);
        if (experiment.find()) {
            return new TeachingHeading(experiment.group(1).trim(), Integer.parseInt(experiment.group(2)));
        }
        Matcher looseExperiment = Pattern.compile("^(实验[一二三四五六七八九十]+[:：].+?)[,，]\\s*(\\d+)\\s*学时").matcher(normalized);
        if (looseExperiment.find()) {
            return new TeachingHeading(looseExperiment.group(1).trim(), Integer.parseInt(looseExperiment.group(2)));
        }
        return null;
    }

    private String collectLineWindow(List<LineEntry> lines, int centerIndex, int radius) {
        int start = Math.max(0, centerIndex - 1);
        int end = Math.min(lines.size() - 1, centerIndex + radius);
        StringBuilder builder = new StringBuilder();
        for (int i = start; i <= end; i++) {
            if (builder.length() > 0) {
                builder.append("\n");
            }
            builder.append(lines.get(i).text());
        }
        return builder.toString();
    }

    private String extractRequirementText(String text) {
        Matcher matcher = Pattern.compile("(?s)(?:^|\\n)\\s*1[.．、]?\\s*基本要求\\s*(.+?)(?=\\n\\s*2[.．、]?\\s*重点|\\n\\s*重点[:：]|\\n\\s*2[.．、]?\\s*重点、难点|\\n\\s*3[.．、]?\\s*作业|$)")
            .matcher(defaultIfBlank(text, ""));
        if (matcher.find()) {
            return sanitizeText(matcher.group(1));
        }
        String value = extractBetweenLabels(text, List.of("基本要求"), List.of("重点", "难点", "作业", "课外学习要求"));
        return defaultIfBlank(value, "");
    }

    private int findAssessmentLineIndex(List<LineEntry> lines, AssessCandidate candidate) {
        List<String> keywords = switch (candidate.type) {
            case "normal" -> List.of("平时", "作业", "课堂");
            case "practice" -> List.of("实验", "实践", "项目");
            case "final" -> List.of("结业", "期末", "综合报告", "考试");
            case "mid" -> List.of("期中");
            default -> List.of(candidate.name);
        };
        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i).text();
            if (containsAny(text, keywords) && containsAny(collectLineWindow(lines, i, 4), List.of("权重", "考核内容", "评价办法", "支撑"))) {
                return i;
            }
        }
        return -1;
    }

    private String extractBetweenLabels(String text, List<String> starts, List<String> ends) {
        String normalized = defaultIfBlank(text, "");
        int start = -1;
        String matchedStart = "";
        for (String label : starts) {
            int index = normalized.indexOf(label);
            if (index >= 0 && (start < 0 || index < start)) {
                start = index;
                matchedStart = label;
            }
        }
        if (start < 0) {
            return "";
        }
        start += matchedStart.length();
        int end = normalized.length();
        for (String label : ends) {
            int index = normalized.indexOf(label, start);
            if (index >= 0 && index < end) {
                end = index;
            }
        }
        return sanitizeText(normalized.substring(start, end));
    }

    private String extractSupportText(String text) {
        Matcher matcher = Pattern.compile("支撑毕业要求[0-9.、，,\\s]+").matcher(defaultIfBlank(text, ""));
        return matcher.find() ? sanitizeText(matcher.group()) : "";
    }

    private void collectCourseInfoFromText(String text, Map<String, String> values) {
        if (!StringUtils.hasText(text)) {
            return;
        }

        List<String> cells = splitCells(text);
        if (cells.size() >= 2) {
            for (int index = 0; index + 1 < cells.size(); index += 2) {
                putCourseInfo(values, cells.get(index), cells.get(index + 1));
            }
        }

        for (Map.Entry<String, List<String>> entry : COURSE_FIELD_ALIASES.entrySet()) {
            for (String alias : entry.getValue()) {
                String value = matchLabeledValue(text, alias);
                if (!StringUtils.hasText(value)) {
                    continue;
                }
                assignCourseField(values, entry.getKey(), value, alias);
            }
        }
    }

    private void putCourseInfo(Map<String, String> values, String label, String rawValue) {
        for (Map.Entry<String, List<String>> entry : COURSE_FIELD_ALIASES.entrySet()) {
            if (matchesAlias(label, entry.getValue())) {
                assignCourseField(values, entry.getKey(), rawValue, label);
            }
        }
    }

    private void assignCourseField(Map<String, String> values, String field, String rawValue, String label) {
        String value = sanitizeMetadataValue(rawValue);
        if (!StringUtils.hasText(value)) {
            return;
        }

        if ("courseType".equals(field)) {
            value = normalizeCourseTypeValue(value);
            String existing = values.get(field);
            if (StringUtils.hasText(existing)) {
                if (looksLikeCheckboxCourseType(existing) && !looksLikeCheckboxCourseType(value)) {
                    values.put(field, value);
                }
                return;
            }
        }

        if ("courseNameZh".equals(field)) {
            CourseNameParts parts = splitCourseName(value);
            if (parts != null) {
                values.putIfAbsent("courseNameZh", parts.zh());
                values.putIfAbsent("courseNameEn", parts.en());
                return;
            }
        }

        if ("courseCode".equals(field) && containsAny(value, COURSE_FIELD_ALIASES.values().stream().flatMap(List::stream).toList())) {
            return;
        }

        if ("courseNameZh".equals(field) && !containsChinese(value)) {
            values.putIfAbsent("courseNameEn", value);
            return;
        }
        if ("courseNameEn".equals(field) && containsChinese(value)) {
            values.putIfAbsent("courseNameZh", value);
            return;
        }
        if ("courseNameZh".equals(field) && "课程名称".equals(label) && !containsChinese(value)) {
            values.putIfAbsent("courseNameEn", value);
            return;
        }

        values.putIfAbsent(field, value);
    }

    private String normalizeCourseTypeValue(String value) {
        String normalized = sanitizeText(value);
        if (!looksLikeCheckboxCourseType(normalized)) {
            return normalized;
        }
        String[] parts = normalized.split("[□☐]");
        for (String part : parts) {
            if (part.contains("☑") || part.contains("■") || part.contains("√") || part.contains("✓")) {
                return sanitizeText(part.replaceAll("[☑■√✓…\\.]+", ""));
            }
        }
        return normalized.replaceAll("[□☐☑■√✓…]+", " ").trim();
    }

    private boolean looksLikeCheckboxCourseType(String value) {
        return StringUtils.hasText(value) && value.matches(".*[□☐☑■√✓].*");
    }

    private CourseNameParts splitCourseName(String value) {
        String normalized = sanitizeText(value);
        String[] parts = normalized.split("\\s*/\\s*|\\s+／\\s+", 2);
        if (parts.length != 2) {
            return null;
        }
        String left = sanitizeText(parts[0]);
        String right = sanitizeText(parts[1]);
        if (!StringUtils.hasText(left) || !StringUtils.hasText(right)) {
            return null;
        }
        if (containsChinese(left) && !containsChinese(right)) {
            return new CourseNameParts(left, right);
        }
        if (!containsChinese(left) && containsChinese(right)) {
            return new CourseNameParts(right, left);
        }
        return null;
    }

    private void collectSplitHoursCredits(List<SourceSegment> segments, Map<String, String> values) {
        for (int index = 0; index < segments.size(); index++) {
            String text = segments.get(index).text();
            Matcher cnMatcher = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*学时.*?(\\d+(?:\\.\\d+)?)\\s*学分").matcher(text);
            if (cnMatcher.find()) {
                putIfUnparsed(values, "hours", cnMatcher.group(1));
                putIfUnparsed(values, "credits", cnMatcher.group(2));
                continue;
            }

            String nearby = nearbyText(segments, index, 2);
            if (!containsAny(nearby, List.of("学时", "学分", "credit", "credits", "hours"))) {
                continue;
            }
            List<Double> numbers = extractPlainNumbers(text);
            if (numbers.size() == 2 && (normalizeForMatch(text).contains("credit") || containsAny(nearby, List.of("学时", "学分")))) {
                putIfUnparsed(values, "hours", trimNumber(numbers.get(0)));
                putIfUnparsed(values, "credits", trimNumber(numbers.get(1)));
            }
        }
    }

    private void putIfUnparsed(Map<String, String> values, String key, String value) {
        boolean parsed = "hours".equals(key)
            ? parseHours(values.get(key)) != null
            : parseCredits(values.get(key)) != null;
        if (!parsed) {
            values.put(key, value);
        }
    }

    private String nearbyText(List<SourceSegment> segments, int index, int radius) {
        StringBuilder builder = new StringBuilder();
        int start = Math.max(0, index - radius);
        int end = Math.min(segments.size() - 1, index + radius);
        for (int i = start; i <= end; i++) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(segments.get(i).text());
        }
        return builder.toString();
    }

    private String matchLabeledValue(String text, String alias) {
        List<String> separators = List.of("：", ":", " ", "|");
        String normalizedText = sanitizeText(text);
        for (String separator : separators) {
            String pattern = "^.*?" + Pattern.quote(alias) + "\\s*" + Pattern.quote(separator) + "\\s*(.+)$";
            Matcher matcher = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(normalizedText);
            if (matcher.matches()) {
                return matcher.group(1);
            }
        }
        return "";
    }

    private List<ObjectiveCandidate> extractObjectiveCandidates(List<SourceSegment> segments) {
        List<LineEntry> lines = flattenLines(segments);
        List<ObjectiveCandidate> candidates = new ArrayList<>();
        Set<String> uniqueContents = new LinkedHashSet<>();

        boolean inObjectiveSection = false;
        ObjectiveAccumulator current = null;

        for (LineEntry entry : lines) {
            String line = entry.text();

            ObjectiveLineMatch tableObjective = matchObjectiveFromCells(entry.cells());
            if (tableObjective != null) {
                current = flushObjective(current, candidates, uniqueContents);
                current = startAccumulator(tableObjective, line, entry.sortOrder());
                current = flushObjective(current, candidates, uniqueContents);
                inObjectiveSection = true;
                continue;
            }

            // For PDF-style text (cells not split by pipes), try grade composition table line
            if (entry.cells().size() <= 1) {
                ObjectiveLineMatch gradeMatch = matchGradeTableText(line);
                if (gradeMatch != null) {
                    current = flushObjective(current, candidates, uniqueContents);
                    current = startAccumulator(gradeMatch, line, entry.sortOrder());
                    current = flushObjective(current, candidates, uniqueContents);
                    inObjectiveSection = true;
                    continue;
                }
            }

            if (isObjectiveSectionStart(line)) {
                current = flushObjective(current, candidates, uniqueContents);
                inObjectiveSection = true;

                String remainder = stripObjectiveHeading(line);
                if (StringUtils.hasText(remainder)) {
                    current = processObjectiveLine(remainder, entry.sortOrder(), current, candidates, uniqueContents);
                }
                continue;
            }

            ObjectiveLineMatch explicitMatch = matchObjectiveLine(line, inObjectiveSection);
            if (explicitMatch != null) {
                current = flushObjective(current, candidates, uniqueContents);
                current = startAccumulator(explicitMatch, line, entry.sortOrder());
                if (StringUtils.hasText(explicitMatch.content())) {
                    current = flushObjective(current, candidates, uniqueContents);
                }
                inObjectiveSection = true;
                continue;
            }

            if (!inObjectiveSection) {
                continue;
            }

            if (isObjectiveSectionStop(line)) {
                current = flushObjective(current, candidates, uniqueContents);
                inObjectiveSection = false;
                continue;
            }

            if (shouldSkipObjectiveLine(line)) {
                continue;
            }

            if (current != null && shouldAppendToObjective(line)) {
                current.append(line);
                continue;
            }

            if (looksLikeStandaloneObjective(line)) {
                current = flushObjective(current, candidates, uniqueContents);
                current = new ObjectiveAccumulator(null, sanitizeObjectiveContent(line), line, entry.sortOrder(), "", "", "");
                current = flushObjective(current, candidates, uniqueContents);
            }
        }

        flushObjective(current, candidates, uniqueContents);
        candidates.sort(Comparator.comparingInt(ObjectiveCandidate::sortOrder));
        return candidates;
    }

    private List<ObjectiveCandidate> selectPrimaryObjectiveCandidates(List<ObjectiveCandidate> candidates) {
        List<ObjectiveCandidate> relationTableCandidates = candidates.stream()
            .filter(candidate -> StringUtils.hasText(candidate.rawNumber))
            .filter(candidate -> looksLikeObjectiveRelationRow(splitCells(candidate.originalText)))
            .toList();
        return relationTableCandidates.size() >= 2 ? relationTableCandidates : candidates;
    }

    private ObjectiveAccumulator processObjectiveLine(
        String line,
        int sortOrder,
        ObjectiveAccumulator current,
        List<ObjectiveCandidate> candidates,
        Set<String> uniqueContents
    ) {
        String normalized = sanitizeText(line);
        ObjectiveLineMatch match = matchObjectiveLine(normalized, true);
        if (match != null) {
            current = flushObjective(current, candidates, uniqueContents);
            return startAccumulator(match, normalized, sortOrder);
        }

        if (looksLikeStandaloneObjective(normalized)) {
            current = flushObjective(current, candidates, uniqueContents);
            return new ObjectiveAccumulator(null, sanitizeObjectiveContent(normalized), normalized, sortOrder, "", "", "");
        }

        if (current != null && shouldAppendToObjective(normalized)) {
            current.append(normalized);
            return current;
        }

        return current;
    }

    private ObjectiveAccumulator startAccumulator(ObjectiveLineMatch match, String originalText, int sortOrder) {
        ObjectiveAccumulator acc = new ObjectiveAccumulator(
            match.rawNumber(),
            sanitizeObjectiveContent(match.content()),
            sanitizeText(originalText),
            sortOrder,
            match.relationLevel(),
            match.gradReqId(),
            match.gradReqDesc()
        );
        if (match.explicitWeight() != null) {
            acc.setExplicitWeight(match.explicitWeight());
        }
        return acc;
    }

    private ObjectiveAccumulator flushObjective(
        ObjectiveAccumulator current,
        List<ObjectiveCandidate> candidates,
        Set<String> uniqueContents
    ) {
        if (current == null) {
            return null;
        }

        String content = sanitizeObjectiveContent(current.content());
        if (!shouldKeepObjective(content)) {
            return null;
        }

        String uniqueKey = normalizeForMatch(content);
        if (uniqueContents.contains(uniqueKey)) {
            return null;
        }

        // Prefer weight from the grade table (already resolved), fall back to inline text weight
        Double explicitWeight = current.explicitWeight() != null ? current.explicitWeight() : extractWeight(content);
        candidates.add(new ObjectiveCandidate(
            current.rawNumber(),
            content,
            current.originalText(),
            true,
            current.sortOrder(),
            explicitWeight,
            current.gradReqId(),
            current.gradReqDesc(),
            current.relationLevel(),
            explicitWeight == null ? 0D : explicitWeight
        ));
        uniqueContents.add(uniqueKey);
        return null;
    }

    private List<AssessCandidate> extractAssessCandidates(List<SourceSegment> segments) {
        Map<String, AssessCandidate> items = new LinkedHashMap<>();
        boolean inAssessSection = false;
        List<String> lastAssessMethodHeaders = new ArrayList<>();

        for (LineEntry entry : flattenLines(segments)) {
            String line = entry.text();
            List<String> cells = entry.cells();

            // Track assess method column headers (e.g. "课堂讨论+作业 | 期末考试" row without weights)
            List<String> detectedHeaders = detectAssessMethodHeaders(cells, line);
            if (!detectedHeaders.isEmpty()) {
                lastAssessMethodHeaders = detectedHeaders;
            }

            // "考核方式占总成绩比例 | 30% | 70% | 100%" — map weights to remembered headers
            if (isAssessWeightFooter(line, cells)) {
                if (!lastAssessMethodHeaders.isEmpty()) {
                    extractAssessWeightsFromFooter(cells, line, lastAssessMethodHeaders, items);
                }
                continue;
            }

            if (isAssessSectionStart(line)) {
                inAssessSection = true;
            }

            if (!inAssessSection && !looksLikeIndependentAssessLine(line)) {
                continue;
            }

            if (isAssessSectionStop(line)) {
                inAssessSection = false;
                continue;
            }

            if (shouldSkipAssessLine(line)) {
                continue;
            }

            collectAssessItemsFromCells(cells, items);

            boolean anyRuleMatched = false;
            for (AssessKeywordRule rule : ASSESS_KEYWORD_RULES) {
                if (shouldSkipAssessRuleForLine(rule, line)) {
                    continue;
                }
                Double weight = extractAssessWeight(line, rule.keywords());
                if (weight == null) {
                    continue;
                }

                anyRuleMatched = true;
                AssessCandidate existing = findAssessCandidateByType(items, rule.type());
                if (existing == null) {
                    putAssessCandidate(items, new AssessCandidate(rule.name(), rule.type(), weight, 0.9D, line));
                } else {
                    existing.weight = weight;
                    existing.confidenceScore = Math.max(existing.confidenceScore, 0.92D);
                    existing.originalText = existing.originalText + "\n" + line;
                }
            }

            if (!anyRuleMatched) {
                // Only fall back to generic heuristic when no rule-matched item has a weight yet.
                // Once we have explicit weights, the heuristic only creates spurious items.
                boolean hasAnyPositiveWeight = items.values().stream().anyMatch(c -> c.weight > 0);
                if (!hasAnyPositiveWeight) {
                    collectGenericAssessItems(line, items);
                }
            }
        }

        return new ArrayList<>(items.values());
    }

    private void collectAssessItemsFromCells(List<String> cells, Map<String, AssessCandidate> items) {
        if (cells.size() < 2) {
            return;
        }

        // Find the last pure-percentage cell (contains only number+%, no other text).
        // This handles multi-column rows like ["平时成绩", "课堂讨论", "OBJ1", "30%"].
        Double rowLevelPct = null;
        for (int i = cells.size() - 1; i >= 1; i--) {
            String cell = cells.get(i).trim();
            String stripped = cell.replaceAll("[\\d.%％]+", "").replaceAll("[\\s,]+", "");
            if (!cell.isEmpty() && stripped.isEmpty()) {
                Double w = extractPercentOrPlainWeight(cell);
                if (w != null && Math.abs(w - 100) > 0.01) {
                    rowLevelPct = w;
                    break;
                }
            }
        }

        if (rowLevelPct != null) {
            String merged = String.join(" | ", cells);
            for (AssessKeywordRule rule : ASSESS_KEYWORD_RULES) {
                if (shouldSkipAssessRuleForLine(rule, merged)) {
                    continue;
                }
                for (String cell : cells) {
                    if (containsAny(cell, rule.keywords())) {
                        putAssessCandidate(items, new AssessCandidate(rule.name(), rule.type(), rowLevelPct, 0.95D, merged));
                        break;
                    }
                }
            }
            return;
        }

        // Fallback: pair-wise matching with PERCENT_WEIGHT_PATTERN to avoid matching "100分" etc.
        for (int index = 0; index + 1 < cells.size(); index += 2) {
            String left = cells.get(index);
            String right = cells.get(index + 1);
            String merged = left + " " + right;

            for (AssessKeywordRule rule : ASSESS_KEYWORD_RULES) {
                if (shouldSkipAssessRuleForLine(rule, merged)) {
                    continue;
                }
                if (!containsAny(left, rule.keywords()) && !containsAny(merged, rule.keywords())) {
                    continue;
                }
                Double weight = extractPercentWeight(right);
                if (weight == null) {
                    weight = extractPercentWeight(merged);
                }
                if (weight == null) {
                    continue;
                }
                putAssessCandidate(items, new AssessCandidate(rule.name(), rule.type(), weight, 0.95D, merged));
            }
        }
    }

    private boolean shouldSkipAssessRuleForLine(AssessKeywordRule rule, String line) {
        if ("report".equals(rule.type()) && containsAny(line, List.of("期末", "结业", "考试", "测试"))) {
            return true;
        }
        if ("practice".equals(rule.type()) && containsAny(line, List.of("综合实验报告", "实验报告"))) {
            return true;
        }
        return false;
    }

    private Double extractPercentWeight(String text) {
        Matcher matcher = PERCENT_WEIGHT_PATTERN.matcher(text);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : null;
    }

    private Double extractPercentOrPlainWeight(String text) {
        Double percent = extractPercentWeight(text);
        if (percent != null) {
            return percent;
        }
        String normalized = sanitizeText(text);
        return normalized.matches("\\d+(?:\\.\\d+)?") ? Double.parseDouble(normalized) : null;
    }

    private Double firstWeightValue(List<String> cells, String text) {
        for (String cell : cells) {
            Double value = extractPercentOrPlainWeight(cell);
            if (value != null && Math.abs(value - 100D) > 0.01D) {
                return value;
            }
        }
        List<Double> weights = extractAllWeights(text);
        return weights.isEmpty() ? null : weights.get(0);
    }

    private void collectGenericAssessItems(String text, Map<String, AssessCandidate> items) {
        if (containsAny(text, ASSESS_NOISE_KEYWORDS)) {
            return;
        }

        // Skip rubric/grading description lines — they describe scoring criteria, not assessment weights
        if (containsAny(text, List.of("满分", "及格", "合格", "优秀", "良好", "参考答案", "评分标准"))) {
            return;
        }

        List<Double> weights = extractAllWeights(text);
        if (weights.isEmpty()) {
            return;
        }

        if (!items.containsKey("平时成绩") && weights.size() >= 1) {
            putAssessCandidate(items, new AssessCandidate("平时成绩", "normal", weights.get(0), 0.55D, text));
        }
        if (!items.containsKey("期中成绩") && weights.size() >= 2) {
            putAssessCandidate(items, new AssessCandidate("期中成绩", "mid", weights.get(1), 0.55D, text));
        }
        if (!items.containsKey("期末成绩") && weights.size() >= 3) {
            putAssessCandidate(items, new AssessCandidate("期末成绩", "final", weights.get(2), 0.55D, text));
        }
    }

    private void putAssessCandidate(Map<String, AssessCandidate> items, AssessCandidate candidate) {
        AssessCandidate sameType = findAssessCandidateByType(items, candidate.type);
        if (sameType != null) {
            if (sameType.confidenceScore >= candidate.confidenceScore) {
                return;
            }
            items.entrySet().removeIf(entry -> entry.getValue() == sameType);
        }
        items.put(candidate.name, candidate);
    }

    private AssessCandidate findAssessCandidateByType(Map<String, AssessCandidate> items, String type) {
        return items.values().stream()
            .filter(candidate -> candidate.type.equals(type))
            .findFirst()
            .orElse(null);
    }

    private Double extractAssessWeight(String text, List<String> keywords) {
        String normalized = normalizeForMatch(text);
        for (String keyword : keywords) {
            int startIndex = normalized.indexOf(normalizeForMatch(keyword));
            if (startIndex < 0) {
                continue;
            }
            // Use PERCENT_WEIGHT_PATTERN (not WEIGHT_PATTERN) to avoid matching "100分", "60分" in rubric text
            Matcher matcher = PERCENT_WEIGHT_PATTERN.matcher(normalized.substring(startIndex));
            if (matcher.find()) {
                return Double.parseDouble(matcher.group(1));
            }
        }
        return null;
    }

    private List<Double> extractAllWeights(String text) {
        List<Double> weights = new ArrayList<>();
        Matcher matcher = WEIGHT_PATTERN.matcher(text);
        while (matcher.find()) {
            weights.add(Double.parseDouble(matcher.group(1)));
        }
        return weights;
    }

    private boolean isObjectiveSectionStart(String text) {
        String normalized = normalizeForMatch(text);
        if (OBJECTIVE_SECTION_EXCLUDE_KEYWORDS.stream().anyMatch(keyword -> normalized.contains(normalizeForMatch(keyword)))) {
            return false;
        }
        return looksLikeHeading(text, OBJECTIVE_SECTION_KEYWORDS) || normalized.contains("课程目标与内容");
    }

    private boolean isObjectiveSectionStop(String text) {
        String normalized = normalizeForMatch(text);
        if (sanitizeText(text).matches("^[一二三四五六七八九十]+[、.．].+")
            && !containsAny(text, OBJECTIVE_SECTION_KEYWORDS)) {
            return true;
        }
        if (containsAny(normalized, List.of("课程教学内容设置", "课程教学内容及基本要求", "课程教学内容", "教学安排及教学方式",
            "考核要求与成绩评定", "考核与评价标准", "学生学习建议", "课外阅读参考资料"))) {
            return true;
        }
        return looksLikeHeading(text, OBJECTIVE_SECTION_STOP_KEYWORDS);
    }

    private boolean isAssessSectionStart(String text) {
        if (looksLikeHeading(text, ASSESS_SECTION_KEYWORDS)) {
            return true;
        }
        if (containsAny(text, ASSESS_NOISE_KEYWORDS)) {
            return false;
        }
        return containsAny(text, ASSESS_SECTION_KEYWORDS) && !extractAllWeights(text).isEmpty();
    }

    private boolean isAssessSectionStop(String text) {
        return looksLikeHeading(text, ASSESS_STOP_KEYWORDS);
    }

    private boolean shouldSkipObjectiveLine(String text) {
        if (!StringUtils.hasText(text)) {
            return true;
        }
        if (looksLikeHeading(text, OBJECTIVE_SECTION_KEYWORDS)) {
            return true;
        }
        if (containsAny(text, OBJECTIVE_NOISE_KEYWORDS)) {
            return true;
        }
        return isLikelyAssessmentText(text);
    }

    private boolean shouldSkipAssessLine(String text) {
        if (!StringUtils.hasText(text)) {
            return true;
        }
        if (Pattern.compile("\\d+\\s*-\\s*\\d+\\s*分").matcher(text).find()) {
            return true;
        }
        List<String> cells = splitCells(text);
        if (cells.size() >= 5 && containsAny(cells.get(0), List.of("课程目标", "目标"))) {
            return true;
        }
        if (containsAny(text, List.of("优秀", "良好", "中等", "合格", "不合格", "评价标准表", "评分标准表"))) {
            return true;
        }
        return containsAny(text, ASSESS_NOISE_KEYWORDS);
    }

    private boolean looksLikeStandaloneObjective(String text) {
        if (!StringUtils.hasText(text) || text.length() < 10) {
            return false;
        }
        if (containsAny(text, OBJECTIVE_NOISE_KEYWORDS) || isLikelyAssessmentText(text)) {
            return false;
        }
        if (isObjectiveIntroOrPrerequisiteText(text)) {
            return false;
        }
        return containsAny(text, OBJECTIVE_ACTION_KEYWORDS);
    }

    private boolean isObjectiveIntroOrPrerequisiteText(String text) {
        String sanitized = sanitizeText(text);
        // A sentence ending with colon is introducing items listed below — not itself an objective
        if (sanitized.endsWith(":")) {
            return true;
        }
        String nm = normalizeForMatch(sanitized);
        // Intro phrases that introduce a list of objectives
        if (nm.contains("以下能力") || nm.contains("以下目标") || nm.contains("以下几点") || nm.contains("以下课程目标")) {
            return true;
        }
        // "本课程在...的基础上" is a prerequisite/context description, not a learning objective
        if (nm.contains("本课程") && nm.contains("的基础上")) {
            return true;
        }
        return false;
    }

    private boolean shouldAppendToObjective(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        if (matchObjectiveLine(text, true) != null) {
            return false;
        }
        if (looksLikeHeading(text, OBJECTIVE_SECTION_STOP_KEYWORDS) || looksLikeHeading(text, OBJECTIVE_SECTION_KEYWORDS)) {
            return false;
        }
        if (containsAny(text, OBJECTIVE_NOISE_KEYWORDS) || isLikelyAssessmentText(text)) {
            return false;
        }
        return true;
    }

    private boolean shouldKeepObjective(String text) {
        if (!StringUtils.hasText(text) || text.length() < 10) {
            return false;
        }
        if (containsAny(text, OBJECTIVE_NOISE_KEYWORDS)) {
            return false;
        }
        if (isLikelyAssessmentText(text)) {
            return false;
        }
        if (isObjectiveIntroOrPrerequisiteText(text)) {
            return false;
        }
        return containsAny(text, OBJECTIVE_ACTION_KEYWORDS);
    }

    private boolean looksLikeIndependentAssessLine(String text) {
        return containsAny(text, STRONG_ASSESS_KEYWORDS) && !extractAllWeights(text).isEmpty();
    }

    private boolean isLikelyAssessmentText(String text) {
        int scoreKeywordCount = countMatchedKeywords(text, SCORE_KEYWORDS) + countMatchedKeywords(text, STRONG_ASSESS_KEYWORDS);
        return scoreKeywordCount >= 2 || (!extractAllWeights(text).isEmpty() && containsAny(text, SCORE_KEYWORDS));
    }

    private ObjectiveLineMatch matchObjectiveFromCells(List<String> cells) {
        if (cells.size() < 2) {
            return null;
        }

        for (int index = 0; index + 1 < cells.size(); index += 2) {
            ObjectiveLineMatch headingMatch = matchObjectiveHeading(cells.get(index));
            if (headingMatch == null) {
                continue;
            }
            if (cells.size() >= 4 && !looksLikeObjectiveRelationRow(cells)) {
                continue;
            }
            String content = sanitizeObjectiveContent(cells.get(index + 1));
            if (!StringUtils.hasText(content)) {
                continue;
            }
            String relationLevel = "";
            String gradReqId = "";
            String gradReqDesc = "";
            if (looksLikeObjectiveRelationRow(cells)) {
                relationLevel = extractRelationLevel(cells);
                gradReqId = extractGradReqId(cells);
                gradReqDesc = extractGradReqDesc(cells);
            }
            return new ObjectiveLineMatch(headingMatch.rawNumber(), content, null, relationLevel, gradReqId, gradReqDesc);
        }

        // Grade composition table row: [number, content_text, pct1?, pct2?, ..., total_pct]
        // e.g. cells = ["1", "要求学生能够...", "50%", "60%", "57%"]
        if (cells.size() >= 3 && cells.get(0).trim().matches("\\d{1,2}")) {
            int objNum = Integer.parseInt(cells.get(0).trim());
            if (objNum >= 1 && objNum <= 20) {
                String content = null;
                List<Double> percentages = new ArrayList<>();
                for (int i = 1; i < cells.size(); i++) {
                    String cell = cells.get(i).trim();
                    String stripped = cell.replaceAll("\\d+(?:\\.\\d+)?[%％]?", "").replaceAll("[\\s,.]+", "");
                    List<Double> cellWeights = extractAllWeights(cell);
                    if (!cellWeights.isEmpty() && stripped.length() <= 3) {
                        percentages.addAll(cellWeights);
                    } else if (cell.length() >= 15 && containsAny(cell, OBJECTIVE_ACTION_KEYWORDS)) {
                        content = sanitizeObjectiveContent(cell);
                    }
                }
                if (StringUtils.hasText(content) && !percentages.isEmpty()) {
                    double weight = percentages.get(percentages.size() - 1);
                    return new ObjectiveLineMatch(cells.get(0).trim(), content, weight);
                }
            }
        }

        return null;
    }

    private boolean looksLikeObjectiveRelationRow(List<String> cells) {
        if (cells.size() < 4) {
            return false;
        }
        String relation = sanitizeText(cells.get(2));
        String gradReq = sanitizeText(cells.get(3));
        return relation.matches("(?i)[HML]")
            || gradReq.matches("\\d+(?:\\.\\d+)+")
            || containsAny(String.join(" ", cells), List.of("毕业要求", "关联程度"));
    }

    private String extractRelationLevel(List<String> cells) {
        for (String cell : cells) {
            String value = sanitizeText(cell).toUpperCase(Locale.ROOT);
            if (value.matches("[HML]")) {
                return value;
            }
        }
        return "";
    }

    private String extractGradReqId(List<String> cells) {
        for (String cell : cells) {
            Matcher matcher = Pattern.compile("\\b\\d+(?:\\.\\d+)+\\b").matcher(sanitizeText(cell));
            if (matcher.find()) {
                return matcher.group();
            }
        }
        return "";
    }

    private String extractGradReqDesc(List<String> cells) {
        String gradReqId = extractGradReqId(cells);
        for (int index = 0; index < cells.size(); index++) {
            String cell = sanitizeText(cells.get(index));
            if (!StringUtils.hasText(cell) || cell.equalsIgnoreCase(extractRelationLevel(cells)) || cell.equals(gradReqId)) {
                continue;
            }
            if (cell.contains("毕业要求") && cell.length() <= 16) {
                continue;
            }
            if (index >= 4 && cell.length() >= 8) {
                return cell;
            }
        }
        return "";
    }

    // Detect linearized grade-table rows in PDF text:
    // "1 要求学生能够... 50% 60% 57%"  →  OBJ-1, content, weight=57
    private ObjectiveLineMatch matchGradeTableText(String text) {
        String sanitized = sanitizeText(text);
        Matcher numMatcher = Pattern.compile("^(\\d{1,2})\\s+(.+)").matcher(sanitized);
        if (!numMatcher.matches()) {
            return null;
        }
        int objNum = Integer.parseInt(numMatcher.group(1));
        if (objNum < 1 || objNum > 20) {
            return null;
        }
        // Iteratively strip the rightmost "XX%" token to collect trailing weights
        String rest = numMatcher.group(2).trim();
        List<Double> trailingWeights = new ArrayList<>();
        Pattern trailPct = Pattern.compile("^(.+)\\s+(\\d+(?:\\.\\d+)?)[%％]$");
        while (true) {
            Matcher m = trailPct.matcher(rest);
            if (!m.matches()) {
                break;
            }
            trailingWeights.add(0, Double.parseDouble(m.group(2)));
            rest = m.group(1).trim();
        }
        if (trailingWeights.isEmpty()) {
            return null;
        }
        String content = sanitizeObjectiveContent(rest);
        if (!StringUtils.hasText(content) || content.length() < 15) {
            return null;
        }
        if (!containsAny(content, OBJECTIVE_ACTION_KEYWORDS) || isObjectiveIntroOrPrerequisiteText(content)) {
            return null;
        }
        // The last element (rightmost in original text) is the "占总成绩比例" column
        double weight = trailingWeights.get(trailingWeights.size() - 1);
        return new ObjectiveLineMatch(numMatcher.group(1), content, weight);
    }

    // Returns matched AssessKeywordRule names for a row that looks like assess-method column headers.
    // A header row has 2+ assess-keyword cells but no percentage values.
    private List<String> detectAssessMethodHeaders(List<String> cells, String text) {
        if (WEIGHT_PATTERN.matcher(text).find() || looksLikeNumericWeightRow(cells, text)) {
            return new ArrayList<>();
        }
        List<String> matchedRuleNames = assessHeaderNamesInOrder(cells, text);
        return matchedRuleNames.size() >= 2 ? matchedRuleNames : new ArrayList<>();
    }

    private List<String> assessHeaderNamesInOrder(List<String> cells, String text) {
        List<String> matchedRuleNames = new ArrayList<>();
        List<String> tokens = cells.size() >= 2 ? cells : List.of(text.split("\\s{2,}"));
        Set<String> usedTypes = new LinkedHashSet<>();
        for (String token : tokens) {
            AssessKeywordRule rule = assessRuleForHeaderToken(token);
            if (rule == null || usedTypes.contains(rule.type())) {
                continue;
            }
            matchedRuleNames.add(normalizeAssessDisplayName(token, rule));
            usedTypes.add(rule.type());
        }
        return matchedRuleNames;
    }

    private String normalizeAssessDisplayName(String token, AssessKeywordRule rule) {
        String value = sanitizeText(token)
            .replaceAll("(?i)\\bassessment\\b", "")
            .replaceAll("成绩比例.*$", "")
            .replaceAll("考核方式及成绩比例.*$", "")
            .replaceAll("占课程最终成绩.*$", "")
            .replaceAll("评定$", "")
            .replaceAll("成绩$", "")
            .replaceAll("[()（）%％]", "")
            .trim();
        value = WHITESPACE_PATTERN.matcher(value).replaceAll("");
        if (!StringUtils.hasText(value) || value.length() > 24 || containsAny(value, List.of("课程目标", "支撑毕业要求", "合计"))) {
            return rule.name();
        }
        if ("normal".equals(rule.type()) && value.equals("平时")) {
            return "平时成绩";
        }
        if ("practice".equals(rule.type()) && value.equals("实验")) {
            return "实验项目";
        }
        if ("final".equals(rule.type()) && value.equals("期末")) {
            return "期末成绩";
        }
        return value;
    }

    private AssessKeywordRule assessRuleForHeaderToken(String token) {
        String normalized = normalizeForMatch(token);
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        if (containsAny(token, List.of("平时", "课堂", "作业", "regular", "assignment", "quiz"))) {
            return assessRuleByType("normal");
        }
        if (containsAny(token, List.of("期中", "midterm"))) {
            return assessRuleByType("mid");
        }
        if (containsAny(token, List.of("期末", "结业", "测试", "考试", "final"))) {
            return assessRuleByType("final");
        }
        if (containsAny(token, List.of("实验项目", "实践项目", "课程设计", "上机", "项目", "practice", "lab", "project"))) {
            return assessRuleByType("practice");
        }
        if (containsAny(token, List.of("报告", "汇报", "presentation", "report"))) {
            return assessRuleByType("report");
        }
        for (AssessKeywordRule rule : ASSESS_KEYWORD_RULES) {
            if (containsAny(token, rule.keywords())) {
                return rule;
            }
        }
        return null;
    }

    private AssessKeywordRule assessRuleByType(String type) {
        return ASSESS_KEYWORD_RULES.stream()
            .filter(rule -> rule.type().equals(type))
            .findFirst()
            .orElse(null);
    }

    private ObjAssessMatrix buildObjAssessMatrix(List<SourceSegment> segments) {
        List<String> methodNames = new ArrayList<>();
        List<String> methodTypes = new ArrayList<>();
        List<ObjAssessMatrixRow> rows = new ArrayList<>();
        boolean inMappingSection = false;

        for (LineEntry entry : flattenLines(segments)) {
            String line = entry.text();
            List<String> cells = entry.cells();

            if (isMappingTableHeader(line)) {
                inMappingSection = true;
                List<String> fromHeader = detectMappingColumnHeaders(cells, line);
                if (!fromHeader.isEmpty()) {
                    methodNames = new ArrayList<>(fromHeader);
                    methodTypes = fromHeader.stream().map(this::assessTypeForHeader).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
                }
                continue;
            }

            if (!inMappingSection) continue;

            if (isAssessWeightFooter(line, cells)) {
                inMappingSection = false;
                continue;
            }

            // Sub-header row: no objective number, may have assess method column names
            Integer objNum = cells.isEmpty() ? null : parseObjectiveNumberCell(cells.get(0));
            if (objNum == null) {
                List<String> detected = detectMappingColumnHeaders(cells, line);
                if (!detected.isEmpty()) {
                    methodNames = new ArrayList<>(detected);
                    methodTypes = detected.stream().map(this::assessTypeForHeader).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
                }
                continue;
            }

            if (objNum < 1 || objNum > 20 || methodNames.isEmpty()) continue;

            // Collect pure-percentage cells only (skip text content cells)
            List<Double> percentages = new ArrayList<>();
            for (int i = 1; i < cells.size(); i++) {
                String cell = cells.get(i).trim();
                String stripped = cell.replaceAll("[\\d.%％]+", "").replaceAll("[\\s,]+", "");
                if (!cell.isEmpty() && stripped.isEmpty()) {
                    Double pct = extractPercentOrPlainWeight(cell);
                    if (pct != null) percentages.add(pct);
                }
            }

            if (percentages.isEmpty()) continue;

            Double totalWeight = null;
            List<Double> proportions;
            if (percentages.size() >= methodNames.size() + 1) {
                totalWeight = percentages.get(percentages.size() - 1);
                proportions = new ArrayList<>(percentages.subList(0, methodNames.size()));
            } else if (percentages.size() >= 2 && percentages.size() < methodNames.size() + 1) {
                totalWeight = percentages.get(percentages.size() - 1);
                proportions = new ArrayList<>(percentages.subList(0, percentages.size() - 1));
                while (proportions.size() < methodNames.size()) {
                    proportions.add(0, 0D);
                }
            } else if (percentages.size() == methodNames.size()) {
                proportions = new ArrayList<>(percentages);
            } else {
                continue;
            }

            rows.add(new ObjAssessMatrixRow("OBJ-" + objNum, objNum, proportions, totalWeight));
        }

        return methodNames.isEmpty() || rows.isEmpty()
            ? null
            : new ObjAssessMatrix(new ArrayList<>(methodNames), new ArrayList<>(methodTypes), rows);
    }

    private boolean isMappingTableHeader(String line) {
        String nm = normalizeForMatch(line);
        if (containsAny(nm, List.of("评价标准", "评分标准", "优秀", "良好", "中等", "合格", "不合格"))) {
            return false;
        }
        return nm.contains("课程目标")
            && nm.contains("考核方式")
            && (nm.contains("成绩比例") || nm.contains("成绩评定") || nm.contains("支撑毕业要求")
                || nm.contains("各考核方式") || nm.contains("考核方式中的比例"));
    }

    private List<String> detectMappingColumnHeaders(List<String> cells, String line) {
        if (PERCENT_WEIGHT_PATTERN.matcher(line).find() || looksLikeNumericWeightRow(cells, line)) {
            return new ArrayList<>();
        }
        return assessHeaderNamesInOrder(cells, line);
    }

    private Integer parseObjectiveNumberCell(String text) {
        Matcher matcher = Pattern.compile("^(?:课程目标|教学目标|目标|OBJ-?)?\\s*([0-9一二三四五六七八九十]+)\\s*$", Pattern.CASE_INSENSITIVE)
            .matcher(sanitizeText(text));
        if (!matcher.find()) {
            return null;
        }
        String raw = matcher.group(1);
        if (raw.matches("\\d+")) {
            return Integer.parseInt(raw);
        }
        return chineseNumberToInt(raw);
    }

    private boolean looksLikeNumericWeightRow(List<String> cells, String text) {
        if (cells.size() < 2) {
            return false;
        }
        int numericCount = 0;
        for (String cell : cells) {
            if (extractPercentOrPlainWeight(cell) != null) {
                numericCount++;
            }
        }
        return numericCount >= 2 && !containsAny(text, STRONG_ASSESS_KEYWORDS);
    }

    private int chineseNumberToInt(String raw) {
        return switch (raw) {
            case "一" -> 1;
            case "二" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "九" -> 9;
            case "十" -> 10;
            default -> 0;
        };
    }

    private List<String> detectLegacyMappingColumnHeaders(List<String> cells, String line) {
        List<String> headers = new ArrayList<>();
        List<String> tokens = cells.size() >= 2 ? cells : List.of(line.split("\\s{2,}"));
        for (String token : tokens) {
            String nm = normalizeForMatch(token);
            if (nm.isEmpty() || nm.contains("课程目标") || nm.contains("总成绩比例") || nm.contains("各考核方式")) {
                continue;
            }
            boolean isAssessRelated = ASSESS_KEYWORD_RULES.stream()
                .anyMatch(rule -> containsAny(token, rule.keywords()));
            if (isAssessRelated) {
                headers.add(token.trim());
            }
        }
        return headers;
    }

    private List<ObjectiveAssessMappingSuggestion> matrixToMappings(
        ObjAssessMatrix matrix,
        List<AssessItemDraftSuggestion> assessItems
    ) {
        List<ObjectiveAssessMappingSuggestion> result = new ArrayList<>();
        if (matrix == null) return result;
        Set<String> uniqueKeys = new LinkedHashSet<>();

        for (ObjAssessMatrixRow row : matrix.rows()) {
            double rowTotal = row.totalWeight() != null && row.totalWeight() > 0D
                ? row.totalWeight()
                : row.proportions().stream().mapToDouble(Double::doubleValue).sum();
            for (int i = 0; i < matrix.methodNames().size() && i < row.proportions().size(); i++) {
                double proportion = row.proportions().get(i);
                if (proportion <= 0 || rowTotal <= 0D) continue;
                String methodName = matrix.methodNames().get(i);
                String methodType = matrix.methodTypes().get(i);
                AssessItemDraftSuggestion assessItem = findAssessItemForHeader(methodName, assessItems);
                String itemName = assessItem != null ? assessItem.itemName() : methodName;
                String itemType = assessItem != null ? assessItem.itemType() : methodType;
                String uniqueKey = row.objectiveCode() + "|" + itemType + "|" + itemName;
                if (!uniqueKeys.add(uniqueKey)) continue;
                result.add(new ObjectiveAssessMappingSuggestion(
                    row.objectiveCode(),
                    row.objectiveNumber(),
                    itemName,
                    itemType,
                    round2(proportion / rowTotal * 100D),
                    round3(0.9D),
                    "HIGH",
                    "目标考核映射表"
                ));
            }
        }
        return result;
    }

    private AssessItemDraftSuggestion findAssessItemForHeader(String header, List<AssessItemDraftSuggestion> assessItems) {
        String type = assessTypeForHeader(header);
        for (AssessItemDraftSuggestion item : assessItems) {
            if (StringUtils.hasText(type) && type.equals(item.itemType())) {
                return item;
            }
        }
        String normalizedHeader = normalizeForMatch(header);
        for (AssessItemDraftSuggestion item : assessItems) {
            if (normalizeForMatch(item.itemName()).contains(normalizedHeader)
                || normalizedHeader.contains(normalizeForMatch(item.itemName()))) {
                return item;
            }
        }
        return null;
    }

    private String assessTypeForHeader(String header) {
        for (AssessKeywordRule rule : ASSESS_KEYWORD_RULES) {
            if (rule.name().equals(header) || containsAny(header, rule.keywords())) {
                return rule.type();
            }
        }
        return "";
    }

    // "考核方式占总成绩比例 | 30% | 70% | 100%"
    private boolean isAssessWeightFooter(String text, List<String> cells) {
        String target = cells.size() >= 2 ? cells.get(0) : text;
        String nm = normalizeForMatch(target);
        if (nm.contains("算法") || nm.contains("计算") || nm.contains("考核方式1") || nm.contains("考核方式2") || nm.contains("=")) {
            return false;
        }
        boolean hasNumericWeights = !extractAllWeights(text).isEmpty()
            || cells.stream().skip(1).anyMatch(cell -> extractPercentOrPlainWeight(cell) != null);
        if (nm.contains("合计") && hasNumericWeights) {
            return true;
        }
        return (nm.contains("考核方式") || nm.contains("考核比例")) && nm.contains("比例")
            && hasNumericWeights;
    }

    private void extractAssessWeightsFromFooter(
        List<String> cells, String text,
        List<String> methodHeaders,
        Map<String, AssessCandidate> items
    ) {
        List<Double> weights = new ArrayList<>();
        if (cells.size() >= 2) {
            for (int i = 1; i < cells.size(); i++) {
                Double value = extractPercentOrPlainWeight(cells.get(i));
                if (value != null && Math.abs(value - 100) > 0.01) {
                    weights.add(value);
                }
            }
        } else {
            extractAllWeights(text).stream()
                .filter(w -> Math.abs(w - 100) > 0.01)
                .forEach(weights::add);
        }
        List<String> resolvedHeaders = methodHeaders;
        double footerWeightTotal = weights.stream().mapToDouble(Double::doubleValue).sum();
        if (weights.size() == 2 && Math.abs(footerWeightTotal - 100D) <= 0.01D) {
            resolvedHeaders = List.of("平时成绩", "期末成绩");
        }
        Set<String> appliedRuleNames = new LinkedHashSet<>();
        for (int i = 0; i < Math.min(weights.size(), resolvedHeaders.size()); i++) {
            String ruleName = resolvedHeaders.get(i);
            double weight = weights.get(i);
            AssessKeywordRule rule = assessRuleForHeaderToken(ruleName);
            if (rule != null) {
                String itemName = normalizeAssessDisplayName(ruleName, rule);
                appliedRuleNames.add(itemName);
                putAssessCandidate(items, new AssessCandidate(
                    itemName, rule.type(), weight, 0.96D,
                    "成绩构成表：" + itemName + " " + weight + "%"
                ));
            }
        }
        double appliedTotal = appliedRuleNames.stream()
            .map(items::get)
            .filter(candidate -> candidate != null)
            .mapToDouble(candidate -> candidate.weight)
            .sum();
        if (Math.abs(appliedTotal - 100D) <= 0.01D) {
            items.keySet().removeIf(ruleName -> !appliedRuleNames.contains(ruleName));
        }
    }

    private ObjectiveLineMatch matchObjectiveLine(String text, boolean inObjectiveSection) {
        String normalized = sanitizeText(text);
        normalized = stripObjectiveArtifacts(normalized);
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        if (!inObjectiveSection && normalized.contains("|")) {
            return null;
        }

        ObjectiveLineMatch explicit = matchObjectiveHeading(normalized);
        if (explicit != null) {
            return explicit;
        }

        if (!inObjectiveSection) {
            return null;
        }

        Matcher numbered = NUMBERED_OBJECTIVE_PATTERN.matcher(normalized);
        if (numbered.matches()) {
            return new ObjectiveLineMatch(numbered.group(1), sanitizeObjectiveContent(numbered.group(2)));
        }

        Matcher english = ENGLISH_NUMBERED_OBJECTIVE_PATTERN.matcher(normalized);
        if (english.matches()) {
            return new ObjectiveLineMatch(english.group(1), sanitizeObjectiveContent(english.group(2)));
        }

        return null;
    }

    private String stripObjectiveArtifacts(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        return text
            .replaceAll("(?i)^\\*?\\(Course Objectives?\\)\\s*", "")
            .replaceAll("(?i)^\\(Teaching Objectives?\\)\\s*", "")
            .replaceAll("(?i)^\\(Learning Objectives?\\)\\s*", "")
            .trim();
    }

    private ObjectiveLineMatch matchObjectiveHeading(String text) {
        Matcher explicit = EXPLICIT_OBJECTIVE_PATTERN.matcher(sanitizeText(text));
        if (!explicit.matches()) {
            return null;
        }

        String rawNumber = defaultIfBlank(explicit.group(1), null);
        String content = sanitizeObjectiveContent(explicit.group(2));
        return new ObjectiveLineMatch(rawNumber, content);
    }

    private void assignObjectiveWeights(List<ObjectiveCandidate> candidates) {
        double explicitTotal = candidates.stream()
            .map(this::effectiveObjectiveWeight)
            .filter(weight -> weight != null)
            .mapToDouble(Double::doubleValue)
            .sum();

        List<ObjectiveCandidate> missingWeights = candidates.stream()
            .filter(candidate -> effectiveObjectiveWeight(candidate) == null)
            .toList();

        if (!missingWeights.isEmpty()) {
            double remaining = explicitTotal > 0D && explicitTotal < 100D ? 100D - explicitTotal : 100D;
            double average = remaining / missingWeights.size();
            missingWeights.forEach(candidate -> candidate.weight = average);
        }

        double total = candidates.stream().mapToDouble(candidate -> candidate.weight).sum();
        if (total <= 0D) {
            double average = 100D / candidates.size();
            candidates.forEach(candidate -> candidate.weight = average);
        } else if (Math.abs(total - 100D) > 0.01D) {
            candidates.forEach(candidate -> candidate.weight = candidate.weight / total * 100D);
        }

        balanceWeights(candidates.stream().map(candidate -> candidate.weight).toList(), updatedWeights -> {
            for (int index = 0; index < candidates.size(); index++) {
                candidates.get(index).weight = updatedWeights.get(index);
            }
        });
    }

    private Double effectiveObjectiveWeight(ObjectiveCandidate candidate) {
        if (candidate.explicitWeight != null) {
            return candidate.explicitWeight;
        }
        return candidate.weight > 0D ? candidate.weight : null;
    }

    private void applyObjectiveWeightsFromMatrix(List<ObjectiveCandidate> candidates, ObjAssessMatrix matrix) {
        if (matrix == null) {
            return;
        }
        Map<Integer, Double> weightsByObjective = new LinkedHashMap<>();
        for (ObjAssessMatrixRow row : matrix.rows()) {
            if (row.totalWeight() != null && row.totalWeight() > 0D) {
                weightsByObjective.put(row.objectiveNumber(), row.totalWeight());
            }
        }
        if (weightsByObjective.isEmpty()) {
            return;
        }
        for (int index = 0; index < candidates.size(); index++) {
            ObjectiveCandidate candidate = candidates.get(index);
            Integer objectiveNumber = parseObjectiveNumber(candidate.rawNumber);
            if (objectiveNumber == null) {
                objectiveNumber = index + 1;
            }
            Double weight = weightsByObjective.get(objectiveNumber);
            if (weight != null && weight > 0D) {
                candidate.weight = weight;
            }
        }
    }

    private Integer parseObjectiveNumber(String rawNumber) {
        if (!StringUtils.hasText(rawNumber)) {
            return null;
        }
        if (rawNumber.matches("\\d+")) {
            return Integer.parseInt(rawNumber);
        }
        int value = chineseNumberToInt(rawNumber);
        return value > 0 ? value : null;
    }

    private void alignAssessCandidatesWithMatrixNames(List<AssessCandidate> candidates, ObjAssessMatrix matrix) {
        if (matrix == null || candidates.isEmpty()) {
            return;
        }
        Set<String> usedTypes = new LinkedHashSet<>();
        for (int index = 0; index < matrix.methodNames().size(); index++) {
            String methodName = matrix.methodNames().get(index);
            String methodType = index < matrix.methodTypes().size() ? matrix.methodTypes().get(index) : assessTypeForHeader(methodName);
            if (!StringUtils.hasText(methodName) || !StringUtils.hasText(methodType) || !usedTypes.add(methodType)) {
                continue;
            }
            for (AssessCandidate candidate : candidates) {
                if (methodType.equals(candidate.type)) {
                    candidate.name = methodName;
                    break;
                }
            }
        }
    }

    private void mergeAssessCandidatesFromMatrix(List<AssessCandidate> candidates, ObjAssessMatrix matrix) {
        if (matrix == null || matrix.methodNames().isEmpty()) {
            return;
        }
        for (int index = 0; index < matrix.methodNames().size(); index++) {
            String methodName = matrix.methodNames().get(index);
            String methodType = index < matrix.methodTypes().size() ? matrix.methodTypes().get(index) : assessTypeForHeader(methodName);
            if (!StringUtils.hasText(methodName) || !StringUtils.hasText(methodType)) {
                continue;
            }
            boolean exists = candidates.stream().anyMatch(candidate -> methodType.equals(candidate.type));
            if (exists) {
                continue;
            }
            double weight = 0D;
            for (ObjAssessMatrixRow row : matrix.rows()) {
                if (index < row.proportions().size()) {
                    weight += row.proportions().get(index);
                }
            }
            if (weight <= 0D) {
                continue;
            }
            candidates.add(new AssessCandidate(
                methodName,
                methodType,
                round2(weight),
                0.93D,
                "目标考核映射表：" + methodName + " " + round2(weight) + "%"
            ));
        }
    }

    private void completeAssessTypes(List<AssessCandidate> candidates) {
        candidates.removeIf(candidate -> candidate.weight <= 0D || !StringUtils.hasText(candidate.originalText));
    }

    private void normalizeAssessWeights(List<AssessCandidate> candidates) {
        if (candidates.isEmpty()) {
            return;
        }

        double total = candidates.stream().mapToDouble(candidate -> candidate.weight).sum();
        if (total <= 0D) {
            double average = 100D / candidates.size();
            candidates.forEach(candidate -> candidate.weight = average);
        } else if (Math.abs(total - 100D) > 0.01D) {
            candidates.forEach(candidate -> candidate.weight = candidate.weight / total * 100D);
        }

        balanceWeights(candidates.stream().map(candidate -> candidate.weight).toList(), updatedWeights -> {
            for (int index = 0; index < candidates.size(); index++) {
                candidates.get(index).weight = updatedWeights.get(index);
            }
        });
    }

    private void balanceWeights(List<Double> rawWeights, java.util.function.Consumer<List<Double>> consumer) {
        List<Double> normalized = new ArrayList<>();
        double sum = 0D;
        for (double weight : rawWeights) {
            double value = round2(weight);
            normalized.add(value);
            sum += value;
        }

        if (!normalized.isEmpty()) {
            double delta = round2(100D - sum);
            int lastIndex = normalized.size() - 1;
            normalized.set(lastIndex, round2(normalized.get(lastIndex) + delta));
        }

        consumer.accept(normalized);
    }

    private List<LineEntry> flattenLines(List<SourceSegment> segments) {
        List<LineEntry> lines = new ArrayList<>();
        for (SourceSegment segment : segments) {
            for (String line : splitLines(segment.text())) {
                lines.add(new LineEntry(line, splitCells(line), segment.sortOrder()));
            }
        }
        return lines;
    }

    private String stripObjectiveHeading(String text) {
        String normalized = sanitizeText(text);
        for (String keyword : OBJECTIVE_SECTION_KEYWORDS) {
            String pattern = "(?i)^.*?" + Pattern.quote(keyword) + "\\s*";
            String plain = normalized.replaceFirst(pattern, "");
            if (!plain.equals(normalized)) {
                return sanitizeObjectiveContent(plain);
            }
        }
        return "";
    }

    private List<String> splitLines(String text) {
        return text.lines()
            .map(this::sanitizeText)
            .filter(StringUtils::hasText)
            .toList();
    }

    private List<String> splitCells(String text) {
        return List.of(sanitizeText(text).split("\\s*\\|\\s*"))
            .stream()
            .map(this::sanitizeText)
            .filter(StringUtils::hasText)
            .toList();
    }

    private boolean looksLikeHeading(String text, List<String> keywords) {
        String normalized = normalizeForMatch(text);
        if (normalized.length() > 40) {
            return false;
        }
        return keywords.stream().anyMatch(keyword -> normalized.contains(normalizeForMatch(keyword)));
    }

    private boolean containsAny(String text, List<String> keywords) {
        String normalized = normalizeForMatch(text);
        return keywords.stream().anyMatch(keyword -> normalized.contains(normalizeForMatch(keyword)));
    }

    private int countMatchedKeywords(String text, List<String> keywords) {
        String normalized = normalizeForMatch(text);
        int count = 0;
        for (String keyword : keywords) {
            if (normalized.contains(normalizeForMatch(keyword))) {
                count++;
            }
        }
        return count;
    }

    private boolean matchesAlias(String label, List<String> aliases) {
        String normalized = normalizeForMatch(label);
        return aliases.stream().anyMatch(alias -> normalized.equals(normalizeForMatch(alias)));
    }

    private String sanitizeMetadataValue(String text) {
        String normalized = sanitizeText(text);
        normalized = normalized.replaceAll("^[：:|\\-]+", "").trim();
        normalized = normalized.replaceAll("(?i)^[(（]\\s*(中文|英文|chinese|english|zh|en|中|英)\\s*[)）]\\s*", "").trim();
        normalized = normalized.replaceAll("[（(]\\s*[^)）]{1,20}[)）]$", "").trim();
        return normalized;
    }

    private String sanitizeObjectiveContent(String text) {
        String normalized = sanitizeText(text);
        normalized = normalized.replace("*课程目标", " ");
        normalized = normalized.replace("课程目标*", " ");
        normalized = normalized.replace("(Course Object)", " ");
        normalized = normalized.replace("(Course Objective)", " ");
        normalized = normalized.replaceAll("^[、.．:：)）\\-]+", "").trim();
        // Strip leading table row number artifact: "1 | ", "2 | " etc.
        normalized = normalized.replaceAll("^\\d+\\s*\\|\\s*", "").trim();
        // Strip trailing pipe-separated numeric/percentage columns from table rows: " | 50% | 60% | 57%"
        normalized = normalized.replaceAll("(\\s*\\|\\s*\\d+(?:\\.\\d+)?\\s*[%％]?)+\\s*$", "").trim();
        normalized = normalized.replaceAll("\\s*\\|\\s*$", "").trim();
        return WHITESPACE_PATTERN.matcher(normalized).replaceAll(" ").trim();
    }

    private Double extractWeight(String text) {
        Matcher matcher = WEIGHT_PATTERN.matcher(text);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : null;
    }

    private ObjectiveTypePrediction classifyObjectiveType(String content) {
        String normalized = content.toLowerCase(Locale.ROOT);
        int bestType = 1;
        int bestScore = Integer.MIN_VALUE;

        for (int type = 1; type <= 3; type++) {
            int score = 0;
            for (String keyword : OBJECTIVE_HIGH_WEIGHT_KEYWORDS.getOrDefault(type, List.of())) {
                if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                    score += type == 2 ? 3 : 2;
                }
            }
            for (String keyword : OBJECTIVE_MID_WEIGHT_KEYWORDS.getOrDefault(type, List.of())) {
                if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                    score += 1;
                }
            }

            if (type == 2 && normalized.contains("能够") && !normalized.contains("不能")) {
                score += 2;
            }
            if (type == 3 && (normalized.contains("培养") || normalized.contains("素养") || normalized.contains("责任"))) {
                score += 2;
            }
            if (isLikelyAssessmentText(content)) {
                score -= 4;
            }

            if (score > bestScore) {
                bestType = type;
                bestScore = score;
            }
        }

        return new ObjectiveTypePrediction(bestType, Math.max(bestScore, 0));
    }

    private double computeObjectiveConfidence(ObjectiveCandidate candidate, ObjectiveTypePrediction prediction) {
        double score = 0D;

        if (candidate.content.length() >= 18) {
            score += 0.28D;
        } else if (candidate.content.length() >= 12) {
            score += 0.18D;
        } else {
            score += 0.08D;
        }

        if (StringUtils.hasText(candidate.rawNumber)) {
            score += 0.24D;
        }

        score += candidate.inObjectiveSection ? 0.22D : 0.08D;

        if (prediction.keywordScore() >= 3) {
            score += 0.18D;
        } else if (prediction.keywordScore() >= 1) {
            score += 0.10D;
        }

        if (candidate.explicitWeight != null) {
            score += 0.10D;
        } else {
            score += 0.05D;
        }

        if (!isLikelyAssessmentText(candidate.content)) {
            score += 0.05D;
        }

        return Math.min(score, 1D);
    }

    private String deriveNameFromFile(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "";
        }
        int dotIndex = fileName.lastIndexOf('.');
        String baseName = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        return sanitizeText(baseName);
    }

    private boolean containsChinese(String text) {
        return text != null && text.chars().anyMatch(ch -> ch >= 0x4E00 && ch <= 0x9FFF);
    }

    private Integer parseInteger(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1).split("\\.")[0]) : null;
    }

    private Integer parseHours(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*学时").matcher(text);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1).split("\\.")[0]);
        }
        return parseInteger(text);
    }

    private Double parseDouble(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : null;
    }

    private Double parseCredits(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*学分").matcher(text);
        if (matcher.find()) {
            return Double.parseDouble(matcher.group(1));
        }
        return parseDouble(text);
    }

    private List<Double> extractPlainNumbers(String text) {
        List<Double> numbers = new ArrayList<>();
        if (!StringUtils.hasText(text)) {
            return numbers;
        }
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        while (matcher.find()) {
            numbers.add(Double.parseDouble(matcher.group(1)));
        }
        return numbers;
    }

    private String trimNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.000001D) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.valueOf(value);
    }

    private String confidenceLevel(double score) {
        if (score >= 0.8D) {
            return "HIGH";
        }
        if (score >= 0.5D) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private String fileExtension(String fileName) {
        if (!StringUtils.hasText(fileName) || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    private String sanitizeText(String text) {
        if (text == null) {
            return "";
        }

        String normalized = text
            .replace('\u0007', ' ')
            .replace('\u000b', ' ')
            .replace('\u00a0', ' ')
            .replace('（', '(')
            .replace('）', ')')
            .replace('：', ':')
            .replace('，', ',')
            .replace('；', ';')
            .replace('｜', '|')
            .replaceAll("[\\t\\x0B\\f\\r]+", " ")
            .replaceAll("\\s*\\|\\s*", " | ")
            .trim();
        return WHITESPACE_PATTERN.matcher(normalized).replaceAll(" ").trim();
    }

    private String normalizeForMatch(String text) {
        return sanitizeText(text).replace(" ", "").toLowerCase(Locale.ROOT);
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    private double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private double round3(double value) {
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).doubleValue();
    }

    public record ParsedOutlineDraft(
        List<ObjectiveDraftSuggestion> objectives,
        List<AssessItemDraftSuggestion> assessItems,
        List<ObjectiveAssessMappingSuggestion> mappings,
        ObjAssessMatrix objAssessMatrix,
        List<SourceSegment> sourceSegments,
        CourseInfo courseInfo
    ) {
    }

    public record ObjAssessMatrix(
        List<String> methodNames,
        List<String> methodTypes,
        List<ObjAssessMatrixRow> rows
    ) {
    }

    public record ObjAssessMatrixRow(
        String objectiveCode,
        int objectiveNumber,
        List<Double> proportions,
        Double totalWeight
    ) {
    }

    public record ObjectiveDraftSuggestion(
        String code,
        String content,
        int objType,
        double weight,
        String gradReqId,
        String gradReqDesc,
        String relationLevel,
        double confidenceScore,
        String confidenceLevel,
        String originalText
    ) {
    }

    public record AssessItemDraftSuggestion(
        String itemName,
        String itemType,
        double weight,
        double confidenceScore,
        String confidenceLevel,
        String originalText
    ) {
    }

    public record ObjectiveAssessMappingSuggestion(
        String objectiveCode,
        int objectiveNumber,
        String assessItemName,
        String assessItemType,
        double contributionWeight,
        double confidenceScore,
        String confidenceLevel,
        String originalText
    ) {
    }

    public record CourseInfo(
        String courseCode,
        String courseNameZh,
        String courseNameEn,
        String courseType,
        String targetStudents,
        String teachingLanguage,
        String collegeName,
        Integer hours,
        Double credits,
        String prerequisiteCourse,
        String courseOwner,
        List<TeachingContentInfo> teachingContents,
        List<AssessmentDetail> assessmentDetails,
        List<AssessmentStandard> assessmentStandards,
        AssessmentPolicy assessmentPolicy
    ) {
        public CourseInfo(
            String courseCode,
            String courseNameZh,
            String courseNameEn,
            String courseType,
            String targetStudents,
            String teachingLanguage,
            String collegeName,
            Integer hours,
            Double credits,
            String prerequisiteCourse,
            String courseOwner
        ) {
            this(
                courseCode,
                courseNameZh,
                courseNameEn,
                courseType,
                targetStudents,
                teachingLanguage,
                collegeName,
                hours,
                credits,
                prerequisiteCourse,
                courseOwner,
                List.of(),
                List.of(),
                List.of(),
                new AssessmentPolicy("", "", "", "", "")
            );
        }
    }

    public record TeachingContentInfo(
        String title,
        Integer lectureHours,
        Integer practiceHours,
        String teachingMethod,
        String relatedObjectives,
        String requirements,
        String originalText
    ) {
    }

    public record AssessmentDetail(
        String name,
        Double weight,
        String content,
        String evaluationMethod,
        String supports,
        String originalText
    ) {
    }

    public record AssessmentStandard(
        String assessmentMethod,
        String objective,
        String excellent,
        String good,
        String medium,
        String pass,
        String fail,
        String scorePercent,
        String originalText
    ) {
    }

    public record AssessmentPolicy(
        String scoreRecordMode,
        String finalGradeComposition,
        String assessmentMode,
        String makeupExam,
        String originalText
    ) {
    }

    public record SourceSegment(String label, String text, int sortOrder) {
    }

    private record TeachingHeading(String title, Integer hours) {
    }

    private record CourseNameParts(String zh, String en) {
    }

    private record AssessmentDetailBlock(
        String name,
        String type,
        Double weight,
        String content,
        String evaluationMethod,
        String supports,
        String originalText
    ) {
    }

    private record ObjectiveTypePrediction(int type, int keywordScore) {
    }

    private record AssessKeywordRule(String name, String type, List<String> keywords) {
    }

    private record ObjectiveLineMatch(
        String rawNumber,
        String content,
        Double explicitWeight,
        String relationLevel,
        String gradReqId,
        String gradReqDesc
    ) {
        ObjectiveLineMatch(String rawNumber, String content) {
            this(rawNumber, content, null, "", "", "");
        }

        ObjectiveLineMatch(String rawNumber, String content, Double explicitWeight) {
            this(rawNumber, content, explicitWeight, "", "", "");
        }
    }

    private record LineEntry(String text, List<String> cells, int sortOrder) {
    }

    private static final class MutableAssessmentDetailBlock {
        private final String type;
        private final String name;
        private Double weight;
        private String content = "";
        private String evaluationMethod = "";
        private String supports = "";
        private final StringBuilder originalText = new StringBuilder();

        private MutableAssessmentDetailBlock(String type, String name) {
            this.type = type;
            this.name = name;
        }

        private void appendOriginal(String text) {
            if (!StringUtils.hasText(text)) {
                return;
            }
            if (originalText.length() > 0) {
                originalText.append('\n');
            }
            originalText.append(text);
        }
    }

    private static final class ObjectiveAccumulator {
        private final String rawNumber;
        private final int sortOrder;
        private final String relationLevel;
        private final String gradReqId;
        private final String gradReqDesc;
        private final StringBuilder content;
        private final StringBuilder originalText;
        private Double explicitWeight;

        private ObjectiveAccumulator(
            String rawNumber,
            String content,
            String originalText,
            int sortOrder,
            String relationLevel,
            String gradReqId,
            String gradReqDesc
        ) {
            this.rawNumber = rawNumber;
            this.sortOrder = sortOrder;
            this.relationLevel = defaultIfBlankStatic(relationLevel, "");
            this.gradReqId = defaultIfBlankStatic(gradReqId, "");
            this.gradReqDesc = defaultIfBlankStatic(gradReqDesc, "");
            this.content = new StringBuilder(defaultIfBlankStatic(content, ""));
            this.originalText = new StringBuilder(defaultIfBlankStatic(originalText, ""));
        }

        private void setExplicitWeight(Double weight) { this.explicitWeight = weight; }
        private Double explicitWeight() { return explicitWeight; }

        private void append(String text) {
            if (!StringUtils.hasText(text)) {
                return;
            }
            if (this.content.length() > 0) {
                this.content.append(' ');
            }
            if (this.originalText.length() > 0) {
                this.originalText.append(' ');
            }
            this.content.append(text);
            this.originalText.append(text);
        }

        private String content() {
            return this.content.toString();
        }

        private String originalText() {
            return this.originalText.toString();
        }

        private String rawNumber() {
            return this.rawNumber;
        }

        private int sortOrder() {
            return this.sortOrder;
        }

        private String relationLevel() {
            return this.relationLevel;
        }

        private String gradReqId() {
            return this.gradReqId;
        }

        private String gradReqDesc() {
            return this.gradReqDesc;
        }

        private static String defaultIfBlankStatic(String value, String defaultValue) {
            return StringUtils.hasText(value) ? value : defaultValue;
        }
    }

    private static final class ObjectiveCandidate {
        private final String rawNumber;
        private final String content;
        private final String originalText;
        private final boolean inObjectiveSection;
        private final int sortOrder;
        private final Double explicitWeight;
        private final String gradReqId;
        private final String gradReqDesc;
        private final String relationLevel;
        private double weight;

        private ObjectiveCandidate(
            String rawNumber,
            String content,
            String originalText,
            boolean inObjectiveSection,
            int sortOrder,
            Double explicitWeight,
            String gradReqId,
            String gradReqDesc,
            String relationLevel,
            double weight
        ) {
            this.rawNumber = rawNumber;
            this.content = content;
            this.originalText = originalText;
            this.inObjectiveSection = inObjectiveSection;
            this.sortOrder = sortOrder;
            this.explicitWeight = explicitWeight;
            this.gradReqId = gradReqId;
            this.gradReqDesc = gradReqDesc;
            this.relationLevel = relationLevel;
            this.weight = weight;
        }

        public int sortOrder() {
            return sortOrder;
        }
    }

    private static final class AssessCandidate {
        private String name;
        private final String type;
        private double weight;
        private double confidenceScore;
        private String originalText;

        private AssessCandidate(String name, String type, double weight, double confidenceScore, String originalText) {
            this.name = name;
            this.type = type;
            this.weight = weight;
            this.confidenceScore = confidenceScore;
            this.originalText = originalText;
        }
    }
}
