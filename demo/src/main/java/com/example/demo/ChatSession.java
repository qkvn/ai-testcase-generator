package com.example.demo;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@Table(name = "chat_session")
public class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Tiêu đề của phiên chat (Ví dụ: "Tính năng đăng xuất")
    @Column(nullable = false, length = 255)
    private String title;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // Một ChatSession chứa nhiều TestCase
    @OneToMany(mappedBy = "chatSession", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JsonManagedReference
    private List<TestCase> testCases;

    // Liên kết với bảng User (Nhiều ChatSession thuộc về 1 User)
    @ManyToOne
    @JoinColumn(name = "user_id")
    @JsonIgnore // Cực kỳ quan trọng để tránh lỗi vòng lặp khi trả về JSON
    private User user;

    // Getters và Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public List<TestCase> getTestCases() { return testCases; }
    public void setTestCases(List<TestCase> testCases) { this.testCases = testCases; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    @Column(name = "is_pinned", nullable = false)
    private boolean isPinned = false;

    // Getter và Setter cho isPinned (Thêm vào cuối file)
    public boolean isPinned() { return isPinned; }
    public void setPinned(boolean isPinned) { this.isPinned = isPinned; }
}