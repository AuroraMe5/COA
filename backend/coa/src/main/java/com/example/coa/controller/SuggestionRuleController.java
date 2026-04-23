package com.example.coa.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.coa.service.InMemoryCoaService;

@RestController
@RequestMapping("/api/v1/suggestion-rules")
public class SuggestionRuleController {

    private final InMemoryCoaService coaService;

    public SuggestionRuleController(InMemoryCoaService coaService) {
        this.coaService = coaService;
    }

    @GetMapping
    public List<Map<String, Object>> getSuggestionRules() {
        return coaService.getSuggestionRules();
    }

    @PutMapping("/{id}")
    public Map<String, Object> updateSuggestionRule(@PathVariable Long id, @RequestBody Map<String, Object> payload) {
        return coaService.updateSuggestionRule(id, payload);
    }

    @PostMapping("/trigger")
    public Map<String, Object> triggerSuggestionRules(@RequestBody Map<String, Object> payload) {
        return coaService.triggerSuggestionRules(payload);
    }
}
