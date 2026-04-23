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

import com.example.coa.service.InMemoryCoaService;

@RestController
@RequestMapping("/api/v1/improve/measures")
public class ImprovementController {

    private final InMemoryCoaService coaService;

    public ImprovementController(InMemoryCoaService coaService) {
        this.coaService = coaService;
    }

    @GetMapping
    public Map<String, Object> getImprovementMeasures(
        @RequestParam(required = false) String courseId,
        @RequestParam(required = false) String semester,
        @RequestParam(required = false) String status
    ) {
        return coaService.getImprovementMeasures(Map.of(
            "courseId", courseId == null ? "" : courseId,
            "semester", semester == null ? "" : semester,
            "status", status == null ? "" : status
        ));
    }

    @PostMapping
    public Map<String, Object> createImprovementMeasure(@RequestBody Map<String, Object> payload) {
        return coaService.saveImprovementMeasure(payload);
    }

    @PutMapping("/{id}")
    public Map<String, Object> updateImprovementMeasure(@PathVariable Long id, @RequestBody Map<String, Object> payload) {
        payload.put("id", id);
        return coaService.saveImprovementMeasure(payload);
    }

    @PutMapping("/{id}/effect")
    public Map<String, Object> updateImprovementEffect(@PathVariable Long id, @RequestBody Map<String, Object> payload) {
        return coaService.updateImprovementEffect(id, payload);
    }
}
