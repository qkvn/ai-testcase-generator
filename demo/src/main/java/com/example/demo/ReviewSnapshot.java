package com.example.demo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Bản sao các test case TRƯỚC khi áp dụng đề xuất sửa của AI (để "Hoàn tác").
 * data = JSON gồm các test case bị sửa (edited) và bị xóa khi gộp trùng (removed).
 * Xóa phiên thì bản sao cũng bị xóa theo (cascade từ ChatSession).
 */
@Getter
@Setter
@Entity
@Table(name = "review_snapshots")
public class ReviewSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    @JsonIgnore
    private ChatSession chatSession;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // Mô tả ngắn, VD: "2 sửa, 1 gộp"
    @Column(length = 255)
    private String summary;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String data;
}
