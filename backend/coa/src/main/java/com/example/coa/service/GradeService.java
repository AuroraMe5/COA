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

    public Map<String, Object> uploadGradeFile(Long courseId, Long assessItemId, String semester, MultipartFile file) {
        return delegate.uploadGradeFile(courseId, assessItemId, semester, file);
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
}
