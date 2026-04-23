package com.example.coa.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.coa.service.InMemoryCoaService;

@RestController
@RequestMapping("/api/v1/analysis")
public class AnalysisController {

    private final InMemoryCoaService coaService;

    public AnalysisController(InMemoryCoaService coaService) {
        this.coaService = coaService;
    }

    @GetMapping("/course-overview")
    public Map<String, Object> getCourseOverview(
        @RequestParam(required = false) Long courseId,
        @RequestParam(required = false) String semester
    ) {
        return coaService.getCourseOverview(courseId, semester);
    }

    @GetMapping("/trend")
    public List<Map<String, Object>> getTrendData(
        @RequestParam(required = false) Long courseId,
        @RequestParam(required = false) Long objectiveId
    ) {
        return coaService.getTrendData(courseId, objectiveId);
    }
}
