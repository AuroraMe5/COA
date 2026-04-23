package com.example.coa.security;

import java.util.List;

public record AuthenticatedUser(
    Long id,
    String username,
    String realName,
    String email,
    String phone,
    Long collegeId,
    String collegeName,
    List<String> roles,
    Integer status
) {

    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }
}
