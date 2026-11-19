package com.example.demo;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** Nhật ký hoạt động: đăng nhập, đăng ký, sinh test case, thao tác của admin... */
@Data
@Entity
@Table(name = "activity_logs")
public class ActivityLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(length = 150)
    private String username;

    @Column(length = 50)
    private String action;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
