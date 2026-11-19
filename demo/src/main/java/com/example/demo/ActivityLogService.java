package com.example.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class ActivityLogService {
    private static final Logger log = LoggerFactory.getLogger(ActivityLogService.class);
    private final ActivityLogRepository repository;

    public ActivityLogService(ActivityLogRepository repository) {
        this.repository = repository;
    }

    public void log(User user, String action, String detail) {
        log(user == null ? null : user.getId(), displayName(user), action, detail);
    }

    public void log(Long userId, String username, String action, String detail) {
        try {
            ActivityLog entry = new ActivityLog();
            entry.setUserId(userId);
            entry.setUsername(username);
            entry.setAction(action);
            entry.setDetail(detail != null && detail.length() > 1000 ? detail.substring(0, 1000) : detail);
            entry.setCreatedAt(LocalDateTime.now());
            repository.save(entry);
        } catch (Exception e) {
            // Ghi nhật ký lỗi không được làm hỏng chức năng chính
            log.warn("Không ghi được nhật ký hoạt động: {}", e.getMessage());
        }
    }

    public static String displayName(User u) {
        if (u == null) return null;
        if (u.getUsername() != null && !u.getUsername().isBlank()) return u.getUsername();
        return u.getEmail();
    }
}
