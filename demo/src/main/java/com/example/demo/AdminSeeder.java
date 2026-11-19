package com.example.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Tạo sẵn tài khoản quản trị khi khởi động nếu chưa có.
 * Mặc định: admin / admin1234 — nên đổi bằng biến môi trường ADMIN_USERNAME / ADMIN_PASSWORD trên Render,
 * hoặc đổi ngay trong trang quản trị (mục Cài đặt).
 * Lưu ý: biến môi trường chỉ dùng khi TẠO MỚI, không ghi đè mật khẩu admin đã đổi.
 */
@Component
public class AdminSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${ADMIN_USERNAME:admin}")
    private String adminUsername;

    @Value("${ADMIN_PASSWORD:admin1234}")
    private String adminPassword;

    public AdminSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            User existing = userRepository.findByUsername(adminUsername).orElse(null);
            if (existing != null) {
                if (!existing.isAdmin()) {
                    log.warn("Tài khoản '{}' đã tồn tại nhưng không phải admin — không tự cấp quyền", adminUsername);
                }
                return;
            }
            User admin = new User();
            admin.setUsername(adminUsername);
            admin.setName("Quản trị viên");
            admin.setPasswordHash(passwordEncoder.encode(adminPassword));
            admin.setProvider("local");
            admin.setRole("ADMIN");
            admin.setLocked(false);
            admin.setCreatedAt(LocalDateTime.now());
            userRepository.save(admin);
            log.info("====== ĐÃ TẠO TÀI KHOẢN QUẢN TRỊ '{}' ======", adminUsername);
        } catch (Exception e) {
            log.error("Không tạo được tài khoản quản trị: {}", e.getMessage());
        }
    }
}
