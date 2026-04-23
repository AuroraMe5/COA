package com.example.coa.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.coa.service.InMemoryCoaService;

@RestController
@RequestMapping("/api/v1/collect")
public class CollectController {

    private final InMemoryCoaService coaService;

    public CollectController(InMemoryCoaService coaService) {
        this.coaService = coaService;
    }

    @PostMapping("/grades/upload")
    public Map<String, Object> uploadGradeFile(
        @RequestParam(required = false) MultipartFile file,
        @RequestParam Long courseId,
        @RequestParam Long assessItemId,
        @RequestParam String semester
    ) {
        String fileName = file == null ? "grade-import.xlsx" : file.getOriginalFilename();
        return coaService.uploadGradeFile(courseId, assessItemId, semester, fileName);
    }

    @GetMapping("/grades/batches/{batchId}/preview")
    public Map<String, Object> getGradeBatchPreview(@PathVariable String batchId) {
        return coaService.getGradeBatchPreview(batchId);
    }

    @PostMapping("/grades/batches/{batchId}/confirm")
    public Map<String, Object> confirmGradeBatch(@PathVariable String batchId, @RequestBody(required = false) Map<String, Object> payload) {
        return coaService.confirmGradeBatch(batchId);
    }

    @GetMapping("/grades")
    public Map<String, Object> getImportedGrades(
        @RequestParam(required = false) String courseId,
        @RequestParam(required = false) String assessItemId,
        @RequestParam(required = false) String semester,
        @RequestParam(required = false) String pageNo,
        @RequestParam(required = false) String pageSize
    ) {
        return coaService.getImportedGrades(Map.of(
            "courseId", courseId == null ? "" : courseId,
            "assessItemId", assessItemId == null ? "" : assessItemId,
            "semester", semester == null ? "" : semester,
            "pageNo", pageNo == null ? "" : pageNo,
            "pageSize", pageSize == null ? "" : pageSize
        ));
    }

    @GetMapping("/student-evals")
    public Map<String, Object> getStudentEvaluations(
        @RequestParam(required = false) Long courseId,
        @RequestParam(required = false) String semester
    ) {
        return coaService.getStudentEvaluations(courseId, semester);
    }

    @PostMapping("/student-evals/batch")
    public Map<String, Object> saveStudentEvaluations(@RequestBody Map<String, Object> payload) {
        return coaService.saveStudentEvaluations(payload);
    }

    @GetMapping("/supervisor-evals")
    public Map<String, Object> getSupervisorEvaluations(
        @RequestParam(required = false) Long courseId,
        @RequestParam(required = false) String semester
    ) {
        return coaService.getSupervisorEvaluations(courseId, semester);
    }

    @GetMapping("/teacher-reflections")
    public Map<String, Object> getTeachingReflection(
        @RequestParam(required = false) Long courseId,
        @RequestParam(required = false) String semester
    ) {
        return coaService.getTeachingReflection(courseId, semester);
    }

    @PostMapping("/teacher-reflections")
    public Map<String, Object> saveTeachingReflection(@RequestBody Map<String, Object> payload) {
        return coaService.saveTeachingReflection(payload);
    }
}
