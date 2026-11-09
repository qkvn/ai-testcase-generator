package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.annotation.PostConstruct;

@SpringBootApplication
public class DemoApplication {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @PostConstruct
    public void forceCreateDatabaseTables() {
        try {
            // Đã thêm 'id BIGINT AUTO_INCREMENT PRIMARY KEY' để vượt qua lớp bảo vệ của Aiven
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS test_case_steps (" +
                                 "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                                 "test_case_db_id BIGINT NOT NULL, " +
                                 "step_description TEXT" +
                                 ")");
            System.out.println("====== ĐÃ ÉP TẠO BẢNG TEST_CASE_STEPS THÀNH CÔNG ======");
        } catch (Exception e) {
            System.out.println("====== LỖI KHI TẠO BẢNG: " + e.getMessage() + " ======");
        }

        // ===== Truy vết yêu cầu: bảng requirements + 2 cột mới =====
        // Trên Render/Aiven, Hibernate không tự thêm bảng/cột nên phải tạo thủ công.
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS requirements (" +
                                 "db_id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                                 "code VARCHAR(50), " +
                                 "description TEXT, " +
                                 "priority VARCHAR(20), " +
                                 "session_id BIGINT, " +
                                 "INDEX idx_requirements_session (session_id)" +
                                 ")");
            System.out.println("====== ĐÃ ÉP TẠO BẢNG REQUIREMENTS THÀNH CÔNG ======");
        } catch (Exception e) {
            System.out.println("====== LỖI KHI TẠO BẢNG REQUIREMENTS: " + e.getMessage() + " ======");
        }
        addColumnIfMissing("chat_session", "user_story", "TEXT");
        addColumnIfMissing("test_cases", "requirement_ids", "TEXT");
    }

    // MySQL 8 không hỗ trợ "ADD COLUMN IF NOT EXISTS" nên kiểm tra information_schema trước
    private void addColumnIfMissing(String table, String column, String definition) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns " +
                    "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                    Integer.class, table, column);
            if (count != null && count == 0) {
                jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
                System.out.println("====== ĐÃ THÊM CỘT " + table + "." + column + " ======");
            }
        } catch (Exception e) {
            System.out.println("====== LỖI KHI THÊM CỘT " + table + "." + column + ": " + e.getMessage() + " ======");
        }
    }
}