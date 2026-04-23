package com.example.coa.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.coa.service.InMemoryCoaService;

@RestController
@RequestMapping("/api/v1/parse")
public class ParseController {

    private final InMemoryCoaService coaService;

    public ParseController(InMemoryCoaService coaService) {
        this.coaService = coaService;
    }

    @PostMapping("/upload")
    public Map<String, Object> uploadParseFile(
        @RequestParam(required = false) MultipartFile file,
        @RequestParam Long courseId,
        @RequestParam String semester,
        @RequestParam(required = false) Long outlineId
    ) {
        String fileName = file == null ? "course-outline.pdf" : file.getOriginalFilename();
        return coaService.uploadParseFile(courseId, semester, fileName);
    }

    @GetMapping("/tasks/{taskId}")
    public Map<String, Object> getParseTaskDetail(@PathVariable String taskId) {
        return coaService.getParseTaskDetail(taskId);
    }

    @PutMapping("/drafts/objectives/{id}")
    public Map<String, Object> updateDraftObjective(@PathVariable Long id, @RequestBody Map<String, Object> payload) {
        return coaService.updateParseDraft(id, payload);
    }

    @PostMapping("/tasks/{taskId}/confirm")
    public Map<String, Object> confirmParseTask(@PathVariable String taskId, @RequestBody Map<String, Object> payload) {
        return coaService.confirmParseTask(taskId, payload);
    }
}
