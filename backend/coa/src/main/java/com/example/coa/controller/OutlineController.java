package com.example.coa.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.coa.service.OutlineService;

@RestController
@RequestMapping("/api/v1/outlines")
public class OutlineController {

    private final OutlineService outlineService;

    public OutlineController(OutlineService outlineService) {
        this.outlineService = outlineService;
    }

    @GetMapping
    public Map<String, Object> getOutlines(
        @RequestParam(required = false) Long courseId,
        @RequestParam(required = false) String semester
    ) {
        return outlineService.getOutlines(courseId, semester);
    }

    @PostMapping
    public Map<String, Object> createOutline(@RequestBody Map<String, Object> payload) {
        return outlineService.saveOutline(payload);
    }

    @PutMapping("/{id}")
    public Map<String, Object> updateOutline(@PathVariable Long id, @RequestBody Map<String, Object> payload) {
        payload.put("id", id);
        return outlineService.saveOutline(payload);
    }

    @PatchMapping("/{id}/publish")
    public Map<String, Object> publishOutline(@PathVariable Long id) {
        return outlineService.publishOutline(id);
    }

    @PostMapping("/{id}/publish")
    public Map<String, Object> publishOutlinePost(@PathVariable Long id) {
        return outlineService.publishOutline(id);
    }
}
