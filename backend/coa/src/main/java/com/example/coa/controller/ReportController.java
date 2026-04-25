package com.example.coa.controller;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
import com.example.coa.service.report.ReportWordBuilder;

@RestController
@RequestMapping("/api/v1/report")
public class ReportController {

    private final ReportDataAssembler assembler;
    private final ReportWordBuilder builder;

    public ReportController(ReportDataAssembler assembler, ReportWordBuilder builder) {
        this.assembler = assembler;
        this.builder = builder;
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
            .map(item -> Map.<String, Object>of(
                "id", item.objectiveId,
                "name", item.objectiveCode,
                "description", item.objectiveDesc,
                "achievement", item.achievement,
                "judgement", item.judgement
            ))
            .toList();
        return ResponseEntity.ok(Map.of(
            "courseInfo", Map.of(
                "id", ctx.courseInfo.id,
                "name", ctx.courseInfo.name,
                "code", ctx.courseInfo.code,
                "semester", ctx.outlineInfo.semester
            ),
            "objectives", objectives,
            "studentCount", ctx.studentCount,
            "weakStudentCount", ctx.weakStudents.size(),
            "overallAchievement", ctx.overallAchievement,
            "generatedAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        ));
    }

    private String encodeFilename(String filename) {
        return URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
