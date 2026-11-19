package com.example.demo;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** Yêu cầu "Quên mật khẩu" người dùng gửi cho admin xử lý. */
@Data
@Entity
@Table(name = "password_reset_requests")
public class PasswordResetRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Tên tài khoản hoặc email người dùng đã nhập
    @Column(length = 150)
    private String identifier;

    @Column(columnDefinition = "TEXT")
    private String note;

    // Tài khoản khớp với identifier (null nếu không tìm thấy)
    @Column(name = "user_id")
    private Long userId;

    // PENDING | RESOLVED | DISMISSED
    @Column(length = 20)
    private String status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "handled_at")
    private LocalDateTime handledAt;
}
