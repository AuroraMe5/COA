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
        @RequestParam(required = false) Long classId,
        @RequestParam(required = false) Long assessItemId,
        @RequestParam String semester
    ) {
        return gradeService.uploadGradeFile(courseId, classId, assessItemId, semester, file);
    }

    @GetMapping("/assessment-contents")
    public Map<String, Object> getAssessmentContents(
        @RequestParam Long courseId,
        @RequestParam String semester
    ) {
        return gradeService.getAssessmentContents(courseId, semester);
    }

    @PutMapping("/assessment-contents")
    public Map<String, Object> saveAssessmentContents(@RequestBody Map<String, Object> payload) {
        return gradeService.saveAssessmentContents(payload);
    }

    @GetMapping("/classes")
    public Map<String, Object> getClasses(@RequestParam(required = false) String keyword) {
        return gradeService.getClasses(keyword);
    }

    @PostMapping("/classes")
    public Map<String, Object> createClass(@RequestBody Map<String, Object> payload) {
        return gradeService.saveClass(null, payload);
    }

    @PutMapping("/classes/{id}")
    public Map<String, Object> updateClass(@PathVariable Long id, @RequestBody Map<String, Object> payload) {
        return gradeService.saveClass(id, payload);
    }

    @GetMapping("/classes/{id}/students")
    public Map<String, Object> getClassStudents(@PathVariable Long id, @RequestParam(required = false) String keyword) {
        return gradeService.getClassStudents(id, keyword);
    }

    @PostMapping("/classes/{id}/students/upload")
    public Map<String, Object> uploadStudents(@PathVariable Long id, @RequestParam(required = false) MultipartFile file) {
        return gradeService.uploadStudents(id, file);
    }

    @PostMapping("/students")
    public Map<String, Object> createStudent(@RequestBody Map<String, Object> payload) {
        return gradeService.saveStudent(null, payload);
    }

    @PutMapping("/students/{id}")
    public Map<String, Object> updateStudent(@PathVariable Long id, @RequestBody Map<String, Object> payload) {
        return gradeService.saveStudent(id, payload);
    }

    @DeleteMapping("/students/{id}")
    public Map<String, Object> deleteStudent(@PathVariable Long id) {
        return gradeService.deleteStudent(id);
    }

    @GetMapping("/class-courses")
    public Map<String, Object> getClassCourses(
        @RequestParam(required = false) Long classId,
        @RequestParam(required = false) String semester
    ) {
        return gradeService.getClassCourses(classId, semester);
    }

    @PostMapping("/class-courses")
    public Map<String, Object> saveClassCourse(@RequestBody Map<String, Object> payload) {
        return gradeService.saveClassCourse(payload);
    }

    @DeleteMapping("/class-courses/{id}")
    public Map<String, Object> deleteClassCourse(@PathVariable Long id) {
        return gradeService.deleteClassCourse(id);
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
        return gradeService.confirmGradeBatch(batchId, payload == null ? Map.of() : payload);
    }

    @GetMapping("/grades")
    public Map<String, Object> getImportedGrades(
        @RequestParam(required = false) String courseId,
        @RequestParam(required = false) String classId,
        @RequestParam(required = false) String assessItemId,
        @RequestParam(required = false) String semester,
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String pageNo,
        @RequestParam(required = false) String pageSize
    ) {
        return gradeService.getImportedGrades(Map.of(
            "courseId", courseId == null ? "" : courseId,
            "classId", classId == null ? "" : classId,
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
