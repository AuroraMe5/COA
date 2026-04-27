package com.example.coa.service;

import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@Transactional
public class GradeService {

    private final InMemoryCoaService delegate;

    public GradeService(InMemoryCoaService delegate) {
        this.delegate = delegate;
    }

    public Map<String, Object> uploadGradeFile(Long courseId, Long classId, Long assessItemId, String semester, MultipartFile file) {
        return delegate.uploadGradeFile(courseId, classId, assessItemId, semester, file);
    }

    public Map<String, Object> getGradeBatchPreview(String batchId) {
        return delegate.getGradeBatchPreview(batchId);
    }

    public Map<String, Object> updateGradePreviewRow(String batchId, Map<String, Object> payload) {
        return delegate.updateGradePreviewRow(batchId, payload);
    }

    public Map<String, Object> confirmGradeBatch(String batchId) {
        return delegate.confirmGradeBatch(batchId);
    }

    public Map<String, Object> getImportedGrades(Map<String, String> filters) {
        return delegate.getImportedGrades(filters);
    }

    public Map<String, Object> saveImportedGradeRow(Map<String, Object> payload) {
        return delegate.saveImportedGradeRow(payload);
    }

    public Map<String, Object> deleteImportedGradeRow(Long courseId, String semester, String studentNo, Long assessItemId) {
        return delegate.deleteImportedGradeRow(courseId, semester, studentNo, assessItemId);
    }

    public Map<String, Object> getClasses(String keyword) {
        return delegate.getClasses(keyword);
    }

    public Map<String, Object> saveClass(Long id, Map<String, Object> payload) {
        return delegate.saveClass(id, payload);
    }

    public Map<String, Object> getClassStudents(Long classId, String keyword) {
        return delegate.getClassStudents(classId, keyword);
    }

    public Map<String, Object> uploadStudents(Long classId, MultipartFile file) {
        return delegate.uploadStudents(classId, file);
    }

    public Map<String, Object> saveStudent(Long id, Map<String, Object> payload) {
        return delegate.saveStudent(id, payload);
    }

    public Map<String, Object> deleteStudent(Long id) {
        return delegate.deleteStudent(id);
    }

    public Map<String, Object> getClassCourses(Long classId, String semester) {
        return delegate.getClassCourses(classId, semester);
    }

    public Map<String, Object> saveClassCourse(Map<String, Object> payload) {
        return delegate.saveClassCourse(payload);
    }

    public Map<String, Object> deleteClassCourse(Long id) {
        return delegate.deleteClassCourse(id);
    }
}
