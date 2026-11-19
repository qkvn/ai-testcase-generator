package com.example.demo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String email;
    private String name;
    private String picture;
    // "google" hoặc "local" (đăng ký bằng tên tài khoản / mật khẩu)
    private String provider;
    private String providerId;

    // ===== Đăng nhập bằng tài khoản / mật khẩu =====
    @Column(length = 100)
    private String username;

    // Mật khẩu đã mã hóa một chiều bằng BCrypt (không thể giải ngược)
    @JsonIgnore
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    // USER | ADMIN
    @Column(length = 20)
    private String role;

    // Tài khoản bị admin khóa
    private Boolean locked;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    public boolean isAdmin() { return "ADMIN".equals(role); }
    public boolean isLockedSafe() { return Boolean.TRUE.equals(locked); }
}
