package com.example.coa.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.coa.service.InMemoryCoaService;

@RestController
@RequestMapping("/api/v1")
public class CatalogController {

    private final InMemoryCoaService coaService;

    public CatalogController(InMemoryCoaService coaService) {
        this.coaService = coaService;
    }

    @GetMapping("/courses")
    public Object getCourses() {
        return coaService.getCourses();
    }

    @GetMapping("/courses/{id}")
    public Map<String, Object> getCourseDetail(
        @PathVariable Long id,
        @RequestParam(required = false) String semester
    ) {
        return coaService.getCourseDetail(id, semester);
    }

    @PutMapping("/courses/{id}")
    public Map<String, Object> updateCourse(@PathVariable Long id, @RequestBody Map<String, Object> payload) {
        return coaService.updateCourse(id, payload);
    }

    @PutMapping("/courses/{id}/teaching-contents")
    public Map<String, Object> updateCourseTeachingContents(
        @PathVariable Long id,
        @RequestParam String semester,
        @RequestBody Map<String, Object> payload
    ) {
        return coaService.updateCourseTeachingContents(id, semester, payload);
    }

    @GetMapping("/semesters")
    public Object getSemesters() {
        return coaService.getSemesters();
    }

    @GetMapping("/assess-items")
    public Object getAssessItems() {
        return coaService.getAssessItems();
    }

    @GetMapping("/reference/catalogs")
    public Map<String, Object> getReferenceCatalogs() {
        return coaService.getReferenceCatalogs();
    }
}
