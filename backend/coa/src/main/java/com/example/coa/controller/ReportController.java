package com.example.coa.controller;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
        return ResponseEntity.ok(builder.buildPreview(ctx));
    }

    private String encodeFilename(String filename) {
        return URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
