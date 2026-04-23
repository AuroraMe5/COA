package com.example.coa.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.coa.service.InMemoryCoaService;

@RestController
@RequestMapping("/api/v1/obj-assess-maps")
public class MappingController {

    private final InMemoryCoaService coaService;

    public MappingController(InMemoryCoaService coaService) {
        this.coaService = coaService;
    }

    @GetMapping
    public Map<String, Object> getObjectiveMapping(
        @RequestParam(required = false) Long courseId,
        @RequestParam(required = false) String semester
    ) {
        return coaService.getObjectiveMapping(courseId, semester);
    }

    @PutMapping
    public Map<String, Object> saveObjectiveMapping(@RequestBody Map<String, Object> payload) {
        return coaService.saveObjectiveMapping(payload);
    }
}
