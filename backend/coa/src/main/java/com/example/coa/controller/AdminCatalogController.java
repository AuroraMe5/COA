package com.example.coa.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.coa.common.ApiException;
import com.example.coa.security.AuthInterceptor;
import com.example.coa.security.AuthenticatedUser;
import com.example.coa.service.AdminCatalogService;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminCatalogController {

    private final AdminCatalogService adminCatalogService;

    public AdminCatalogController(AdminCatalogService adminCatalogService) {
        this.adminCatalogService = adminCatalogService;
    }

    @GetMapping("/colleges")
    public Map<String, Object> listColleges(
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String status,
        HttpServletRequest request
    ) {
        requireAdmin(request);
        return adminCatalogService.listColleges(keyword, status);
    }

    @PostMapping("/colleges")
    public Map<String, Object> createCollege(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        requireAdmin(request);
        return adminCatalogService.createCollege(payload);
    }

    @PutMapping("/colleges/{id}")
    public Map<String, Object> updateCollege(
        @PathVariable Long id,
        @RequestBody Map<String, Object> payload,
        HttpServletRequest request
    ) {
        requireAdmin(request);
        return adminCatalogService.updateCollege(id, payload);
    }

    @DeleteMapping("/colleges/{id}")
    public Map<String, Object> deleteCollege(@PathVariable Long id, HttpServletRequest request) {
        requireAdmin(request);
        return adminCatalogService.deleteCollege(id);
    }

    @GetMapping("/majors")
    public Map<String, Object> listMajors(
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) Long collegeId,
        HttpServletRequest request
    ) {
        requireAdmin(request);
        return adminCatalogService.listMajors(keyword, status, collegeId);
    }

    @PostMapping("/majors")
    public Map<String, Object> createMajor(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        requireAdmin(request);
        return adminCatalogService.createMajor(payload);
    }

    @PutMapping("/majors/{id}")
    public Map<String, Object> updateMajor(
        @PathVariable Long id,
        @RequestBody Map<String, Object> payload,
        HttpServletRequest request
    ) {
        requireAdmin(request);
        return adminCatalogService.updateMajor(id, payload);
    }

    @DeleteMapping("/majors/{id}")
    public Map<String, Object> deleteMajor(@PathVariable Long id, HttpServletRequest request) {
        requireAdmin(request);
        return adminCatalogService.deleteMajor(id);
    }

    @GetMapping("/semesters")
    public Map<String, Object> listSemesters(
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String status,
        HttpServletRequest request
    ) {
        requireAdmin(request);
        return adminCatalogService.listSemesters(keyword, status);
    }

    @PostMapping("/semesters")
    public Map<String, Object> createSemester(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        requireAdmin(request);
        return adminCatalogService.createSemester(payload);
    }

    @PutMapping("/semesters/{id}")
    public Map<String, Object> updateSemester(
        @PathVariable Long id,
        @RequestBody Map<String, Object> payload,
        HttpServletRequest request
    ) {
        requireAdmin(request);
        return adminCatalogService.updateSemester(id, payload);
    }

    @DeleteMapping("/semesters/{id}")
    public Map<String, Object> deleteSemester(@PathVariable Long id, HttpServletRequest request) {
        requireAdmin(request);
        return adminCatalogService.deleteSemester(id);
    }

    private void requireAdmin(HttpServletRequest request) {
        AuthenticatedUser user = (AuthenticatedUser) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTR);
        if (user == null || !user.hasRole("ADMIN")) {
            throw new ApiException(HttpStatus.FORBIDDEN, 403, "需要超级管理员权限");
        }
    }
}
