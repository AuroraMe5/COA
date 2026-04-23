package com.example.coa.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.coa.service.InMemoryCoaService;

@RestController
@RequestMapping("/api/v1/intelligent-suggestions")
public class SuggestionController {

    private final InMemoryCoaService coaService;

    public SuggestionController(InMemoryCoaService coaService) {
        this.coaService = coaService;
    }

    @GetMapping
    public Map<String, Object> getSuggestions(
        @RequestParam(required = false) String semester,
        @RequestParam(required = false) String courseId,
        @RequestParam(required = false) String priority,
        @RequestParam(required = false) String isRead,
        @RequestParam(required = false) String isDismissed
    ) {
        return coaService.getSuggestions(Map.of(
            "semester", semester == null ? "" : semester,
            "courseId", courseId == null ? "" : courseId,
            "priority", priority == null ? "" : priority,
            "isRead", isRead == null ? "" : isRead,
            "isDismissed", isDismissed == null ? "" : isDismissed
        ));
    }

    @GetMapping("/{id}")
    public Map<String, Object> getSuggestionDetail(@PathVariable Long id) {
        return coaService.getSuggestionDetail(id);
    }

    @PatchMapping("/{id}/read")
    public Map<String, Object> markSuggestionRead(@PathVariable Long id) {
        return coaService.markSuggestionRead(id);
    }

    @PatchMapping("/{id}/dismiss")
    public Map<String, Object> dismissSuggestion(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> payload) {
        return coaService.dismissSuggestion(id, payload == null ? Map.of() : payload);
    }

    @PostMapping("/{id}/create-measure")
    public Map<String, Object> createMeasureFromSuggestion(@PathVariable Long id) {
        return coaService.createMeasureFromSuggestion(id);
    }
}
