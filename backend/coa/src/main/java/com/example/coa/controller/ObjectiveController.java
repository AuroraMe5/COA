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

import com.example.coa.service.OutlineService;

@RestController
@RequestMapping("/api/v1/objectives")
public class ObjectiveController {

    private final OutlineService outlineService;

    public ObjectiveController(OutlineService outlineService) {
        this.outlineService = outlineService;
    }

    @GetMapping
    public Map<String, Object> getObjectives(
        @RequestParam(required = false) Long courseId,
        @RequestParam(required = false) String semester
    ) {
        return outlineService.getObjectives(courseId, semester);
    }

    @GetMapping("/{id}")
    public Map<String, Object> getObjectiveDetail(@PathVariable Long id) {
        return outlineService.getObjectiveDetail(id);
    }

    @PostMapping
    public Map<String, Object> createObjective(@RequestBody Map<String, Object> payload) {
        return outlineService.saveObjective(payload);
    }

    @PutMapping("/{id}")
    public Map<String, Object> updateObjective(@PathVariable Long id, @RequestBody Map<String, Object> payload) {
        payload.put("id", id);
        return outlineService.saveObjective(payload);
    }

    @PutMapping("/batch-weights")
    public Map<String, Object> saveObjectiveWeights(@RequestBody Map<String, Object> payload) {
        return outlineService.saveObjectiveWeights(payload);
    }

    @PostMapping("/batch")
    public Map<String, Object> batchCreateObjectives(@RequestBody Map<String, Object> payload) {
        return outlineService.saveObjectiveBatch(payload);
    }
}
