package com.example.coa.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.coa.service.OutlineService;

@RestController
@RequestMapping("/api/v1/obj-assess-maps")
public class MappingController {

    private final OutlineService outlineService;

    public MappingController(OutlineService outlineService) {
        this.outlineService = outlineService;
    }

    @GetMapping
    public Map<String, Object> getObjectiveMapping(
        @RequestParam(required = false) Long courseId,
        @RequestParam(required = false) String semester
    ) {
        return outlineService.getObjectiveMapping(courseId, semester);
    }

    @PutMapping
    public Map<String, Object> saveObjectiveMapping(@RequestBody Map<String, Object> payload) {
        return outlineService.saveObjectiveMapping(payload);
    }
}
