package com.example.coa.controller;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.coa.service.ReportDataAssembler;
import com.example.coa.service.ReportDataAssembler.ReportContext;
import com.example.coa.service.report.IntelligentAnalyzer;
import com.example.coa.service.report.ReportWordBuilder;

@RestController
@RequestMapping("/api/v1/report")
public class ReportController {

    private final ReportDataAssembler assembler;
    private final ReportWordBuilder builder;
    private final IntelligentAnalyzer analyzer;

    public ReportController(ReportDataAssembler assembler, ReportWordBuilder builder, IntelligentAnalyzer analyzer) {
        this.assembler = assembler;
        this.builder = builder;
        this.analyzer = analyzer;
    }

    @GetMapping("/download")
    public ResponseEntity<byte[]> download(
        @RequestParam Long outlineId,
        @RequestParam(required = false) Long calcRuleId
    ) throws Exception {
        ReportContext ctx = assembler.assemble(outlineId, calcRuleId);
        byte[] docBytes = builder.build(ctx);
        String filename = ctx.courseInfo.getName() + "_达成度报告_" + ctx.outlineInfo.getSemester() + ".docx";
        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename*=UTF-8''" + encodeFilename(filename))
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
            .body(docBytes);
    }

    @GetMapping("/preview-meta")
    public ResponseEntity<Map<String, Object>> previewMeta(
        @RequestParam Long outlineId,
        @RequestParam(required = false) Long calcRuleId
    ) {
        ReportContext ctx = assembler.assemble(outlineId, calcRuleId);
        List<Map<String, Object>> objectives = ctx.objectiveAchievements.stream()
            .map(item -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("id", item.objectiveId);
                result.put("name", item.objectiveCode);
                result.put("description", item.objectiveDesc);
                result.put("achievement", item.achievement);
                result.put("judgement", item.judgement);
                return result;
            })
            .toList();
        List<Map<String, Object>> gradeDistribution = ctx.gradeDistribution.buckets.entrySet().stream()
            .map(entry -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("label", entry.getKey());
                result.put("count", entry.getValue().count);
                result.put("pct", entry.getValue().pct);
                return result;
            })
            .toList();
        List<Map<String, Object>> componentStats = ctx.componentStats.stream()
            .map(item -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("type", item.type);
                result.put("typeName", item.typeName);
                result.put("avgScore", item.avgScore);
                result.put("maxScore", item.maxScore);
                result.put("minScore", item.minScore);
                result.put("passRate", item.passRate);
                result.put("weight", item.weight);
                return result;
            })
            .toList();

        Map<String, Object> smartAnalysis = new LinkedHashMap<>();
        smartAnalysis.put("summary", analyzer.generateConclusion(ctx));
        smartAnalysis.put("achievementSummary", analyzer.generateAchievementSummary(ctx));
        smartAnalysis.put("scoreAnalysis", analyzer.generateScoreAnalysis(ctx));
        smartAnalysis.put("examAnalysis", analyzer.generateExamAnalysis(ctx));
        smartAnalysis.put("improvements", analyzer.generateImprovements(ctx, ctx.existingSuggestions));

        Map<String, Object> courseInfo = new LinkedHashMap<>();
        courseInfo.put("id", ctx.courseInfo.id);
        courseInfo.put("name", ctx.courseInfo.name);
        courseInfo.put("code", ctx.courseInfo.code);
        courseInfo.put("semester", ctx.outlineInfo.semester);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("courseInfo", courseInfo);
        result.put("objectives", objectives);
        result.put("studentCount", ctx.studentCount);
        result.put("weakStudentCount", ctx.weakStudents.size());
        result.put("overallAchievement", ctx.overallAchievement);
        result.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        result.put("smartAnalysis", smartAnalysis);
        result.put("gradeDistribution", gradeDistribution);
        result.put("componentStats", componentStats);
        return ResponseEntity.ok(result);
    }

    private String encodeFilename(String filename) {
        return URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
