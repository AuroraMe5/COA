package com.example.coa.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.coa.service.InMemoryCoaService;

@RestController
@RequestMapping("/api/v1/analysis")
public class DashboardController {

    private final InMemoryCoaService coaService;

    public DashboardController(InMemoryCoaService coaService) {
        this.coaService = coaService;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> getDashboardData() {
        return coaService.getDashboardData();
    }
}
