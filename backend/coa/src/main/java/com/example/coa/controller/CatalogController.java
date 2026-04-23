package com.example.coa.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
