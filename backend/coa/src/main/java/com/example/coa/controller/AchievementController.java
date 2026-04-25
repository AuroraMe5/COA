package com.example.coa.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.coa.service.AchievementService;

@RestController
@RequestMapping("/api/v1/achieve")
public class AchievementController {

    private final AchievementService achievementService;

    public AchievementController(AchievementService achievementService) {
        this.achievementService = achievementService;
    }

    @GetMapping("/results")
    public Map<String, Object> getAchievementResults(
        @RequestParam(required = false) Long courseId,
        @RequestParam(required = false) String semester
    ) {
        return achievementService.getAchievementResults(courseId, semester);
    }

    @PostMapping("/tasks")
    public Map<String, Object> runAchievementCalculation(@RequestBody Map<String, Object> payload) {
        return achievementService.runAchievementCalculation(payload);
    }
}
