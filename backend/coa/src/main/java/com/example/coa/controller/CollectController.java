package com.example.coa.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.coa.service.GradeService;

@RestController
@RequestMapping("/api/v1/collect")
public class CollectController {

    private final GradeService gradeService;

    public CollectController(GradeService gradeService) {
        this.gradeService = gradeService;
    }

    @PostMapping("/grades/upload")
    public Map<String, Object> uploadGradeFile(
        @RequestParam(required = false) MultipartFile file,
        @RequestParam Long courseId,
        @RequestParam(required = false) Long assessItemId,
        @RequestParam String semester
    ) {
        return gradeService.uploadGradeFile(courseId, assessItemId, semester, file);
    }

    @GetMapping("/grades/batches/{batchId}/preview")
    public Map<String, Object> getGradeBatchPreview(@PathVariable String batchId) {
        return gradeService.getGradeBatchPreview(batchId);
    }

    @PutMapping("/grades/batches/{batchId}/preview-rows")
    public Map<String, Object> updateGradePreviewRow(@PathVariable String batchId, @RequestBody Map<String, Object> payload) {
        return gradeService.updateGradePreviewRow(batchId, payload);
    }

    @PostMapping("/grades/batches/{batchId}/confirm")
    public Map<String, Object> confirmGradeBatch(@PathVariable String batchId, @RequestBody(required = false) Map<String, Object> payload) {
        return gradeService.confirmGradeBatch(batchId);
    }

    @GetMapping("/grades")
    public Map<String, Object> getImportedGrades(
        @RequestParam(required = false) String courseId,
        @RequestParam(required = false) String assessItemId,
        @RequestParam(required = false) String semester,
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String pageNo,
        @RequestParam(required = false) String pageSize
    ) {
        return gradeService.getImportedGrades(Map.of(
            "courseId", courseId == null ? "" : courseId,
            "assessItemId", assessItemId == null ? "" : assessItemId,
            "semester", semester == null ? "" : semester,
            "keyword", keyword == null ? "" : keyword,
            "pageNo", pageNo == null ? "" : pageNo,
            "pageSize", pageSize == null ? "" : pageSize
        ));
    }

    @PostMapping("/grades/rows")
    public Map<String, Object> saveImportedGradeRow(@RequestBody Map<String, Object> payload) {
        return gradeService.saveImportedGradeRow(payload);
    }

    @DeleteMapping("/grades/rows")
    public Map<String, Object> deleteImportedGradeRow(
        @RequestParam Long courseId,
        @RequestParam String semester,
        @RequestParam String studentNo,
        @RequestParam(required = false) Long assessItemId
    ) {
        return gradeService.deleteImportedGradeRow(courseId, semester, studentNo, assessItemId);
    }

}
