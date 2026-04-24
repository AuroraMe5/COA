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
import org.springframework.web.bind.annotation.DeleteMapping;
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
        @RequestParam(required = false) Long courseId,
        @RequestParam String semester,
        @RequestParam(required = false) Long outlineId
    ) {
        return coaService.uploadParseFile(courseId, semester, file, outlineId);
    }

    @GetMapping("/tasks/{taskId}")
    public Map<String, Object> getParseTaskDetail(@PathVariable String taskId) {
        return coaService.getParseTaskDetail(taskId);
    }

    @PutMapping("/drafts/objectives/{id}")
    public Map<String, Object> updateDraftObjective(@PathVariable Long id, @RequestBody Map<String, Object> payload) {
        return coaService.updateParseDraft(id, payload);
    }

    @PostMapping("/tasks/{taskId}/drafts/objectives")
    public Map<String, Object> createDraftObjective(@PathVariable String taskId, @RequestBody Map<String, Object> payload) {
        return coaService.createParseDraft(taskId, payload);
    }

    @DeleteMapping("/drafts/objectives/{id}")
    public Map<String, Object> deleteDraftObjective(@PathVariable Long id) {
        return coaService.deleteParseDraft(id);
    }

    @PutMapping("/drafts/assess-items/{id}")
    public Map<String, Object> updateDraftAssessItem(@PathVariable Long id, @RequestBody Map<String, Object> payload) {
        return coaService.updateParseAssessDraft(id, payload);
    }

    @PostMapping("/tasks/{taskId}/drafts/assess-items")
    public Map<String, Object> createDraftAssessItem(@PathVariable String taskId, @RequestBody Map<String, Object> payload) {
        return coaService.createParseAssessDraft(taskId, payload);
    }

    @DeleteMapping("/drafts/assess-items/{id}")
    public Map<String, Object> deleteDraftAssessItem(@PathVariable Long id) {
        return coaService.deleteParseAssessDraft(id);
    }

    @PutMapping("/tasks/{taskId}/mapping")
    public Map<String, Object> updateParseMappingMatrix(@PathVariable String taskId, @RequestBody Map<String, Object> payload) {
        return coaService.updateParseMappingMatrix(taskId, payload);
    }

    @PostMapping("/tasks/{taskId}/confirm")
    public Map<String, Object> confirmParseTask(@PathVariable String taskId, @RequestBody Map<String, Object> payload) {
        return coaService.confirmParseTask(taskId, payload);
    }
}
