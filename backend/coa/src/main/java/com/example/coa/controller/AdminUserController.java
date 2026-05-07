package com.example.coa.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.coa.common.ApiException;
import com.example.coa.security.AuthInterceptor;
import com.example.coa.security.AuthenticatedUser;
import com.example.coa.service.AdminUserService;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping("/teachers")
    public Map<String, Object> listTeachers(
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String status,
        HttpServletRequest request
    ) {
        requireAdmin(request);
        return adminUserService.listTeachers(keyword, status);
    }

    @PostMapping("/teachers")
    public Map<String, Object> createTeacher(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        requireAdmin(request);
        return adminUserService.createTeacher(payload);
    }

    @PatchMapping("/teachers/{id}/password")
    public Map<String, Object> resetTeacherPassword(
        @PathVariable Long id,
        @RequestBody Map<String, Object> payload,
        HttpServletRequest request
    ) {
        requireAdmin(request);
        return adminUserService.resetTeacherPassword(id, payload);
    }

    @PatchMapping("/teachers/{id}/status")
    public Map<String, Object> updateTeacherStatus(
        @PathVariable Long id,
        @RequestBody Map<String, Object> payload,
        HttpServletRequest request
    ) {
        requireAdmin(request);
        return adminUserService.updateTeacherStatus(id, payload);
    }

    @DeleteMapping("/teachers/{id}")
    public Map<String, Object> deleteTeacher(@PathVariable Long id, HttpServletRequest request) {
        requireAdmin(request);
        return adminUserService.deleteTeacher(id);
    }

    private void requireAdmin(HttpServletRequest request) {
        AuthenticatedUser user = (AuthenticatedUser) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTR);
        if (user == null || !user.hasRole("ADMIN")) {
            throw new ApiException(HttpStatus.FORBIDDEN, 403, "需要超级管理员权限");
        }
    }
}
