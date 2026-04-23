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

import com.example.coa.service.InMemoryCoaService;

@RestController
@RequestMapping("/api/v1/outlines")
public class OutlineController {

    private final InMemoryCoaService coaService;

    public OutlineController(InMemoryCoaService coaService) {
        this.coaService = coaService;
    }

    @GetMapping
    public Map<String, Object> getOutlines(
        @RequestParam(required = false) Long courseId,
        @RequestParam(required = false) String semester
    ) {
        return coaService.getOutlines(courseId, semester);
    }

    @PostMapping
    public Map<String, Object> createOutline(@RequestBody Map<String, Object> payload) {
        return coaService.saveOutline(payload);
    }

    @PutMapping("/{id}")
    public Map<String, Object> updateOutline(@PathVariable Long id, @RequestBody Map<String, Object> payload) {
        payload.put("id", id);
        return coaService.saveOutline(payload);
    }

    @PatchMapping("/{id}/publish")
    public Map<String, Object> publishOutline(@PathVariable Long id) {
        return coaService.publishOutline(id);
    }

    @PostMapping("/{id}/publish")
    public Map<String, Object> publishOutlinePost(@PathVariable Long id) {
        return coaService.publishOutline(id);
    }
}
