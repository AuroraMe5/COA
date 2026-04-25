package com.example.coa.service.report;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.example.coa.service.ReportDataAssembler.AssessItemInfo;
import com.example.coa.service.ReportDataAssembler.ComponentStat;
import com.example.coa.service.ReportDataAssembler.DistributionBucket;
import com.example.coa.service.ReportDataAssembler.GradReqAchievement;
import com.example.coa.service.ReportDataAssembler.GradeDistribution;
import com.example.coa.service.ReportDataAssembler.ObjAssessMapInfo;
import com.example.coa.service.ReportDataAssembler.ObjectiveAchievement;
import com.example.coa.service.ReportDataAssembler.ObjectiveInfo;
import com.example.coa.service.ReportDataAssembler.ReportContext;
import com.example.coa.service.ReportDataAssembler.StudentAchievementDetail;
import com.example.coa.service.ReportDataAssembler.StudentScoreSummary;
import com.example.coa.service.ReportDataAssembler.WeakStudent;

@Service
public class ReportWordBuilder {

    private static final String FONT_HEI = "黑体";
    private static final String FONT_SONG = "宋体";

    private final IntelligentAnalyzer analyzer;

    public ReportWordBuilder(IntelligentAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    public byte[] build(ReportContext ctx) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            setupPageLayout(doc);
            addCover(doc, ctx);
            addSection1(doc, ctx);
            addSection2(doc, ctx);
            addSection3(doc, ctx);
            addSection4(doc, ctx);
            addSection5(doc, ctx);
            addSection6(doc, ctx);
            addSection7(doc, ctx);
            addSection9(doc, ctx);
            addAppendixA(doc, ctx);
            addAppendixB(doc, ctx);
            doc.write(out);
            return out.toByteArray();
        }
    }

    private void setupPageLayout(XWPFDocument doc) {
        CTSectPr sectPr = doc.getDocument().getBody().isSetSectPr()
            ? doc.getDocument().getBody().getSectPr()
            : doc.getDocument().getBody().addNewSectPr();
        CTPageSz pageSize = sectPr.isSetPgSz() ? sectPr.getPgSz() : sectPr.addNewPgSz();
        pageSize.setW(BigInteger.valueOf(12240));
        pageSize.setH(BigInteger.valueOf(15840));
        CTPageMar margin = sectPr.isSetPgMar() ? sectPr.getPgMar() : sectPr.addNewPgMar();
        margin.setTop(BigInteger.valueOf(1440));
        margin.setBottom(BigInteger.valueOf(1440));
        margin.setLeft(BigInteger.valueOf(1800));
        margin.setRight(BigInteger.valueOf(1800));
    }

    private void addCover(XWPFDocument doc, ReportContext ctx) {
        addCentered(doc, "重庆理工大学", FONT_HEI, 22, true, 12);
        addCentered(doc, "本科生课程目标达成情况评价及总结报告", FONT_HEI, 16, true, 12);
        addCentered(doc, safe(ctx.outlineInfo.semesterName, ctx.outlineInfo.semester), FONT_SONG, 14, false, 18);
        for (int i = 0; i < 8; i++) {
            doc.createParagraph();
        }
        addCentered(doc, "课程：" + safe(ctx.courseInfo.name, "未命名课程"), FONT_SONG, 14, false, 6);
        addPageBreak(doc);
    }

    private void addSection1(XWPFDocument doc, ReportContext ctx) {
        addHeading1(doc, "一 课程概况");
        XWPFTable table = createTable(doc, 6, 4);
        setRow(table, 0, "课程名称", ctx.courseInfo.name, "学时", value(ctx.courseInfo.hours));
        setRow(table, 1, "课程所属系室", safe(ctx.courseInfo.department, ""), "学分", value(ctx.courseInfo.credits));
        setRow(table, 2, "课程所属学院", safe(ctx.courseInfo.school, ""), "命题教师", safe(ctx.courseInfo.teacher, ctx.outlineInfo.teacher));
        setRow(table, 3, "考试班级", safe(ctx.outlineInfo.className, ""), "", "");
        setRow(table, 4, "总结人", safe(ctx.outlineInfo.teacher, ""), "总结日期", ctx.outlineInfo.reportDate);
        setRow(table, 5, "审核人", "", "学院教学指导委员会", "");
        addBlank(doc);
    }

    private void addSection2(XWPFDocument doc, ReportContext ctx) {
        addHeading1(doc, "二 课程目标对毕业要求的支撑");
        XWPFTable table = createTable(doc, Math.max(ctx.objectives.size() + 1, 2), 5);
        setRow(table, 0, "课程目标", "简述", "关联程度", "毕业要求", "简述");
        boldRow(table.getRow(0));
        int rowIndex = 1;
        for (ObjectiveInfo objective : ctx.objectives) {
            XWPFTableRow row = table.getRow(rowIndex++);
            setCell(row.getCell(0), objective.code, false);
            setCell(row.getCell(1), objective.content, false);
            setCell(row.getCell(2), safe(objective.relationLevel, "H"), "H".equalsIgnoreCase(objective.relationLevel));
            setCell(row.getCell(3), safe(objective.gradReqId, "待补充"), false);
            setCell(row.getCell(4), safe(objective.gradReqDesc, "由" + objective.code + "支撑的毕业要求说明待补充。"), false);
        }
        addBlank(doc);
    }

    private void addSection3(XWPFDocument doc, ReportContext ctx) {
        addHeading1(doc, "三 课程成绩评定及目标达成评价方法");
        addHeading2(doc, "（一）课程目标考核及课程成绩评定方式");

        int cols = ctx.assessItems.size() + 3;
        XWPFTable matrix = createTable(doc, Math.max(ctx.objectives.size() + 2, 3), cols);
        setCell(matrix.getRow(0).getCell(0), "课程目标", true);
        setCell(matrix.getRow(0).getCell(1), "支撑毕业要求", true);
        for (int i = 0; i < ctx.assessItems.size(); i++) {
            setCell(matrix.getRow(0).getCell(i + 2), ctx.assessItems.get(i).name, true);
        }
        setCell(matrix.getRow(0).getCell(cols - 1), "成绩比例", true);

        int rowIndex = 1;
        for (ObjectiveInfo objective : ctx.objectives) {
            XWPFTableRow row = matrix.getRow(rowIndex++);
            setCell(row.getCell(0), objective.code, false);
            setCell(row.getCell(1), safe(objective.gradReqId, "待补充"), false);
            for (int i = 0; i < ctx.assessItems.size(); i++) {
                AssessItemInfo item = ctx.assessItems.get(i);
                double cellWeight = objective.weight * contributionFor(ctx, objective.id, item.id) / 100D;
                setCell(row.getCell(i + 2), percentWeight(cellWeight), false);
            }
            setCell(row.getCell(cols - 1), percentWeight(objective.weight), false);
        }

        XWPFTableRow totalRow = matrix.getRow(rowIndex);
        setCell(totalRow.getCell(0), "合计", true);
        setCell(totalRow.getCell(1), "", false);
        for (int i = 0; i < ctx.assessItems.size(); i++) {
            AssessItemInfo item = ctx.assessItems.get(i);
            double total = ctx.objectives.stream()
                .mapToDouble(objective -> objective.weight * contributionFor(ctx, objective.id, item.id) / 100D)
                .sum();
            setCell(totalRow.getCell(i + 2), percentWeight(total), true);
        }
        setCell(totalRow.getCell(cols - 1), "100%", true);

        addHeading2(doc, "（二）课程目标考核及成绩评定方式描述");
        XWPFTable desc = createTable(doc, Math.max(ctx.assessItems.size() + 1, 2), 5);
        setRow(desc, 0, "考核方式", "权重", "考核内容", "评价办法", "支撑目标");
        boldRow(desc.getRow(0));
        Map<Long, List<ObjAssessMapInfo>> mapsByItem = ctx.objAssessMaps.stream()
            .collect(Collectors.groupingBy(map -> map.assessItemId));
        for (int i = 0; i < ctx.assessItems.size(); i++) {
            AssessItemInfo item = ctx.assessItems.get(i);
            List<String> objectives = mapsByItem.getOrDefault(item.id, List.of()).stream()
                .map(map -> objectiveCode(ctx, map.objectiveId))
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
            setRow(desc, i + 1,
                item.name,
                percentWeight(item.weight),
                item.typeName + "考核围绕课程目标进行过程或结果评价。",
                "按评分标准折算为百分制成绩并参与达成度计算。",
                objectives.isEmpty() ? "全部课程目标" : String.join("、", objectives));
        }
        addBlank(doc);
    }

    private void addSection4(XWPFDocument doc, ReportContext ctx) {
        addHeading1(doc, "四 成绩统计与试题分析");
        XWPFTable distribution = createTable(doc, 3, 6);
        setRow(distribution, 0, "成绩", "优[90,100]", "良[80,90)", "中[70,80)", "及格[60,70)", "不及格[0,60)");
        boldRow(distribution.getRow(0));
        setRow(distribution, 1, "人数",
            count(ctx.gradeDistribution, "优"),
            count(ctx.gradeDistribution, "良"),
            count(ctx.gradeDistribution, "中"),
            count(ctx.gradeDistribution, "及格"),
            count(ctx.gradeDistribution, "不及格"));
        setRow(distribution, 2, "百分比",
            pct(ctx.gradeDistribution, "优"),
            pct(ctx.gradeDistribution, "良"),
            pct(ctx.gradeDistribution, "中"),
            pct(ctx.gradeDistribution, "及格"),
            pct(ctx.gradeDistribution, "不及格"));

        addBlank(doc);
        XWPFTable stats = createTable(doc, Math.max(ctx.componentStats.size() + 1, 2), 5);
        setRow(stats, 0, "评价方式", "平均分", "最高分", "最低分", "及格率");
        boldRow(stats.getRow(0));
        for (int i = 0; i < ctx.componentStats.size(); i++) {
            ComponentStat stat = ctx.componentStats.get(i);
            setRow(stats, i + 1,
                stat.typeName,
                number(stat.avgScore),
                number(stat.maxScore),
                number(stat.minScore),
                percent(stat.passRate));
        }

        addHeading2(doc, "试题分析");
        addBody(doc, analyzer.generateExamAnalysis(ctx));
        addHeading2(doc, "成绩分析");
        addBody(doc, analyzer.generateScoreAnalysis(ctx));
        addBlank(doc);
    }

    private void addSection5(XWPFDocument doc, ReportContext ctx) {
        addHeading1(doc, "五 课程目标达成情况");
        addBody(doc, "本课程考核总分为100分，对全体学生进行统计，课程目标达成情况如下。");
        XWPFTable table = createTable(doc, Math.max(ctx.objectiveAchievements.size() + 1, 2), 4);
        setRow(table, 0, "课程目标", "考核总分", "考核平均分", "课程目标达成情况");
        boldRow(table.getRow(0));
        for (int i = 0; i < ctx.objectiveAchievements.size(); i++) {
            ObjectiveAchievement item = ctx.objectiveAchievements.get(i);
            XWPFTableRow row = table.getRow(i + 1);
            setCell(row.getCell(0), item.objectiveCode, false);
            setCell(row.getCell(1), scoreNumber(item.totalScore), false);
            setCell(row.getCell(2), scoreNumber(item.avgScore), false);
            setCell(row.getCell(3), percent(item.achievement), true);
            setCellBgColor(row.getCell(3), judgementColor(item.achievement));
        }

        addBlank(doc);
        addBody(doc, "各分数段的课程目标达成情况如下。");
        XWPFTable segmented = createTable(doc, Math.max(ctx.objectiveAchievements.size() + 1, 2), 7);
        setRow(segmented, 0, "课程目标", "达成情况值", "[90,100]分数段", "[80,90)分数段", "[70,80)分数段", "[60,70)分数段", "60分以下");
        boldRow(segmented.getRow(0));
        for (int i = 0; i < ctx.objectiveAchievements.size(); i++) {
            ObjectiveAchievement item = ctx.objectiveAchievements.get(i);
            Map<String, Double> bands = ctx.objectiveBandAchievements.getOrDefault(item.objectiveCode, Map.of());
            setRow(segmented, i + 1,
                item.objectiveCode,
                percent(item.achievement),
                percent(bands.getOrDefault("优", 0D)),
                percent(bands.getOrDefault("良", 0D)),
                percent(bands.getOrDefault("中", 0D)),
                percent(bands.getOrDefault("及格", 0D)),
                percent(bands.getOrDefault("不及格", 0D)));
        }
        addBlank(doc);
    }

    private void addSection6(XWPFDocument doc, ReportContext ctx) {
        addHeading1(doc, "六 课程目标达成情况报表");
        XWPFTable table = createTable(doc, Math.max(ctx.objectives.size() + 1, 2), 4);
        setRow(table, 0, "课程目标", "达成途径", "评价依据", "评价方式");
        boldRow(table.getRow(0));
        Map<Long, List<ObjAssessMapInfo>> mapsByObjective = ctx.objAssessMaps.stream()
            .collect(Collectors.groupingBy(map -> map.objectiveId));
        for (int i = 0; i < ctx.objectives.size(); i++) {
            ObjectiveInfo objective = ctx.objectives.get(i);
            List<AssessItemInfo> items = mapsByObjective.getOrDefault(objective.id, List.of()).stream()
                .map(map -> assessItem(ctx, map.assessItemId))
                .filter(item -> item != null)
                .distinct()
                .toList();
            setRow(table, i + 1,
                objective.code,
                achievementPath(objective),
                items.isEmpty() ? "平时作业、实验项目、期末考核" : items.stream().map(item -> item.name).collect(Collectors.joining("、")),
                "根据" + (items.isEmpty() ? "相关考核项" : items.stream().map(item -> item.name).collect(Collectors.joining("、"))) + "分数给出量化评价值。");
        }

        addHeading2(doc, "课程目标达成情况分析");
        for (ObjectiveAchievement item : ctx.objectiveAchievements) {
            addBody(doc, analyzer.analyzeObjective(item, ctx.componentStats, item.objectiveDesc));
        }
        addHeading2(doc, "课程目标达成的具体措施");
        List<String> improvements = analyzer.generateImprovements(ctx, ctx.existingSuggestions);
        for (int i = 0; i < improvements.size(); i++) {
            addNumberedPara(doc, i + 1, improvements.get(i));
        }
        addHeading2(doc, "课程目标达成情况");
        addBody(doc, analyzer.generateThresholdSummary(ctx));
        addBlank(doc);
    }

    private void addSection7(XWPFDocument doc, ReportContext ctx) {
        addHeading1(doc, "七 课程考核方式合理性");
        addBody(doc, analyzer.generateAssessmentRationalityIntro(ctx));
        XWPFTable table = createTable(doc, 7, 3);
        setRow(table, 0, "考核方式合理性", "评价结论", "改进方向");
        boldRow(table.getRow(0));
        setRow(table, 1, "考核内容合理性", "考核内容与教学大纲内容完全符合。", "下一轮考核可继续优化重点难点覆盖。");
        setRow(table, 2, "考核方式合理性", "采用平时作业、实验项目、期末考核等多种方式，可合理评价课程目标达成情况。", "下一轮考核可进一步细化过程性评价记录。");
        setRow(table, 3, "考核数据合理性", "各类原始成绩与课程目标相关性良好。", "下一轮考核可补充分项数据校验。");
        setRow(table, 4, "考核标准合理性", "评价标准明确，可对课程目标达成形成导向。", "下一轮考核可提升评分细则的可操作性。");
        setRow(table, 5, "成绩判定", "严格", "保持统一评分尺度。");
        setRow(table, 6, "综上", "本课程达成度评价依据自评为完全合理。", "下一轮考核可改进。");
        addBlank(doc);
    }

    private void addSection9(XWPFDocument doc, ReportContext ctx) {
        addHeading1(doc, "九 课程目标达成情况、改进举措及应用方法");
        XWPFTable table = createTable(doc, 2, 3);
        setRow(table, 0, "课程目标达成情况", "改进举措", "应用方法");
        boldRow(table.getRow(0));
        setRow(table, 1,
            analyzer.generateAchievementSummary(ctx),
            String.join("\n", analyzer.generateImprovements(ctx, ctx.existingSuggestions)),
            "改进课堂教学方法；改进课程考核标准；持续优化大纲、考核项与课程目标映射。");
        addBlank(doc);
    }

    private void addAppendixA(XWPFDocument doc, ReportContext ctx) {
        addPageBreak(doc);
        addHeading1(doc, "附录A：全体学生各目标达成度明细");
        int cols = 2 + ctx.objectives.size() * 2;
        XWPFTable table = createTable(doc, Math.max(ctx.studentDetails.size() + 1, 2), cols);
        XWPFTableRow header = table.getRow(0);
        setCell(header.getCell(0), "学号", true);
        setCell(header.getCell(1), "姓名", true);
        int col = 2;
        for (ObjectiveInfo objective : ctx.objectives) {
            setCell(header.getCell(col++), objective.code + "得分", true);
        }
        for (ObjectiveInfo objective : ctx.objectives) {
            setCell(header.getCell(col++), objective.code + "达成度", true);
        }
        header.setRepeatHeader(true);
        for (int i = 0; i < ctx.studentDetails.size(); i++) {
            StudentAchievementDetail detail = ctx.studentDetails.get(i);
            XWPFTableRow row = table.getRow(i + 1);
            setCell(row.getCell(0), detail.studentNo, false);
            setCell(row.getCell(1), detail.studentName, false);
            col = 2;
            for (ObjectiveInfo objective : ctx.objectives) {
                setCell(row.getCell(col++), number(detail.objectiveScores.getOrDefault(objective.code, 0D)), false);
            }
            for (ObjectiveInfo objective : ctx.objectives) {
                setCell(row.getCell(col++), number(detail.objectiveAchievements.getOrDefault(objective.code, 0D)), false);
            }
        }
    }

    private void addAppendixB(XWPFDocument doc, ReportContext ctx) {
        addPageBreak(doc);
        addHeading1(doc, "附录B：全体学生总成绩明细");
        XWPFTable table = createTable(doc, Math.max(ctx.studentScoreSummaries.size() + 1, 2), 7);
        setRow(table, 0, "学号", "姓名", "平时成绩", "实验成绩", "期末成绩", "总成绩", "等级");
        boldRow(table.getRow(0));
        table.getRow(0).setRepeatHeader(true);
        for (int i = 0; i < ctx.studentScoreSummaries.size(); i++) {
            StudentScoreSummary item = ctx.studentScoreSummaries.get(i);
            setRow(table, i + 1,
                item.studentNo,
                item.studentName,
                number(item.normalScore),
                number(item.practiceScore),
                number(item.finalScore),
                number(item.totalScore),
                item.gradeLevel);
        }
    }

    private double contributionFor(ReportContext ctx, Long objectiveId, Long assessItemId) {
        return ctx.objAssessMaps.stream()
            .filter(item -> item.objectiveId.equals(objectiveId) && item.assessItemId.equals(assessItemId))
            .mapToDouble(item -> item.contributionWeight)
            .findFirst()
            .orElse(defaultContribution(ctx, assessItemId));
    }

    private double defaultContribution(ReportContext ctx, Long assessItemId) {
        double total = ctx.assessItems.stream().mapToDouble(item -> item.weight).sum();
        if (total <= 0D || ctx.assessItems.isEmpty()) {
            return ctx.assessItems.isEmpty() ? 0D : 100D / ctx.assessItems.size();
        }
        return ctx.assessItems.stream()
            .filter(item -> item.id.equals(assessItemId))
            .mapToDouble(item -> item.weight * 100D / total)
            .findFirst()
            .orElse(0D);
    }

    private String objectiveCode(ReportContext ctx, Long objectiveId) {
        return ctx.objectives.stream()
            .filter(item -> item.id.equals(objectiveId))
            .map(item -> item.code)
            .findFirst()
            .orElse("");
    }

    private AssessItemInfo assessItem(ReportContext ctx, Long assessItemId) {
        return ctx.assessItems.stream()
            .filter(item -> item.id.equals(assessItemId))
            .findFirst()
            .orElse(null);
    }

    private String achievementPath(ObjectiveInfo objective) {
        if (objective.content.contains("工具") || objective.content.contains("调试")) {
            return "课堂讲授、实验、课堂讨论、课后作业";
        }
        if (objective.content.contains("设计") || objective.content.contains("开发")) {
            return "课堂讲授、实验、课堂讨论、演示、课后作业";
        }
        return "课堂讲授、实验、课堂讨论、课后作业";
    }

    private XWPFTable createTable(XWPFDocument doc, int rows, int cols) {
        XWPFTable table = doc.createTable(Math.max(rows, 1), Math.max(cols, 1));
        table.setWidth("100%");
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                setCell(cell, "", false);
            }
        }
        return table;
    }

    private void setRow(XWPFTable table, int rowIndex, String... values) {
        XWPFTableRow row = table.getRow(rowIndex);
        for (int i = 0; i < values.length && i < row.getTableCells().size(); i++) {
            setCell(row.getCell(i), values[i], false);
        }
    }

    private void boldRow(XWPFTableRow row) {
        row.getTableCells().forEach(cell -> {
            String text = cell.getText();
            setCell(cell, text, true);
        });
    }

    private void setCell(XWPFTableCell cell, String text, boolean bold) {
        XWPFParagraph paragraph = cell.getParagraphs().isEmpty() ? cell.addParagraph() : cell.getParagraphs().get(0);
        for (int i = paragraph.getRuns().size() - 1; i >= 0; i--) {
            paragraph.removeRun(i);
        }
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun run = paragraph.createRun();
        run.setFontFamily(FONT_SONG);
        run.setFontSize(10);
        run.setBold(bold);
        String[] lines = safe(text, "").split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                run.addBreak();
            }
            run.setText(lines[i]);
        }
    }

    private void setCellBgColor(XWPFTableCell cell, String hexColor) {
        cell.setColor(hexColor);
    }

    private void addHeading1(XWPFDocument doc, String text) {
        XWPFParagraph paragraph = doc.createParagraph();
        paragraph.setSpacingBefore(240);
        paragraph.setSpacingAfter(120);
        XWPFRun run = paragraph.createRun();
        run.setFontFamily(FONT_HEI);
        run.setFontSize(16);
        run.setBold(true);
        run.setText(text);
    }

    private void addHeading2(XWPFDocument doc, String text) {
        XWPFParagraph paragraph = doc.createParagraph();
        paragraph.setSpacingBefore(120);
        paragraph.setSpacingAfter(60);
        XWPFRun run = paragraph.createRun();
        run.setFontFamily(FONT_HEI);
        run.setFontSize(14);
        run.setBold(true);
        run.setText(text);
    }

    private void addBody(XWPFDocument doc, String text) {
        XWPFParagraph paragraph = doc.createParagraph();
        paragraph.setSpacingBetween(1.5D);
        XWPFRun run = paragraph.createRun();
        run.setFontFamily(FONT_SONG);
        run.setFontSize(12);
        run.setText(safe(text, ""));
    }

    private void addNumberedPara(XWPFDocument doc, int n, String text) {
        addBody(doc, n + ". " + text);
    }

    private void addCentered(XWPFDocument doc, String text, String font, int size, boolean bold, int afterPt) {
        XWPFParagraph paragraph = doc.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        paragraph.setSpacingAfter(afterPt * 20);
        XWPFRun run = paragraph.createRun();
        run.setFontFamily(font);
        run.setFontSize(size);
        run.setBold(bold);
        run.setText(text);
    }

    private void addPageBreak(XWPFDocument doc) {
        XWPFParagraph paragraph = doc.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.addBreak(BreakType.PAGE);
    }

    private void addBlank(XWPFDocument doc) {
        doc.createParagraph();
    }

    private String judgementColor(double achievement) {
        if (achievement >= 0.9D) {
            return "D9EAD3";
        }
        if (achievement >= 0.7D) {
            return "D9EAF7";
        }
        if (achievement >= 0.6D) {
            return "FFF2CC";
        }
        return "F4CCCC";
    }

    private String count(GradeDistribution distribution, String key) {
        DistributionBucket bucket = distribution.buckets.get(key);
        return bucket == null ? "0" : String.valueOf(bucket.count);
    }

    private String pct(GradeDistribution distribution, String key) {
        DistributionBucket bucket = distribution.buckets.get(key);
        return bucket == null ? "0.0%" : percent(bucket.pct);
    }

    private String percent(double value) {
        return String.format("%.1f%%", value * 100D);
    }

    private String percentWeight(double value) {
        return String.format("%.1f%%", value);
    }

    private String number(Object value) {
        if (value == null) {
            return "0.000";
        }
        double number = value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value));
        return String.format("%.3f", number);
    }

    private String scoreNumber(Object value) {
        if (value == null) {
            return "0";
        }
        double number = value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value));
        return Math.abs(number - Math.rint(number)) < 0.000001D
            ? String.valueOf((long) Math.rint(number))
            : String.format("%.2f", number);
    }

    private String value(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number number) {
            double n = number.doubleValue();
            return Math.abs(n - Math.rint(n)) < 0.000001D ? String.valueOf((long) Math.rint(n)) : String.valueOf(n);
        }
        return String.valueOf(value);
    }

    private String safe(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }
}
