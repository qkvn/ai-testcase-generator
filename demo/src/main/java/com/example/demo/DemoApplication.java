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
        // Tài liệu SRS có thể dài hơn giới hạn của kiểu TEXT (~64 KB)
        widenColumn("chat_session", "user_story", "mediumtext", "MEDIUMTEXT");

        // ===== Đăng nhập tài khoản / trang quản trị =====
        addColumnIfMissing("users", "username", "VARCHAR(100)");
        addColumnIfMissing("users", "password_hash", "VARCHAR(100)");
        addColumnIfMissing("users", "role", "VARCHAR(20) DEFAULT 'USER'");
        addColumnIfMissing("users", "locked", "BOOLEAN DEFAULT FALSE");
        addColumnIfMissing("users", "created_at", "DATETIME(6)");
        addColumnIfMissing("users", "last_login_at", "DATETIME(6)");
        createTable("activity_logs", "CREATE TABLE IF NOT EXISTS activity_logs (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "user_id BIGINT, " +
                "username VARCHAR(150), " +
                "action VARCHAR(50), " +
                "detail TEXT, " +
                "created_at DATETIME(6), " +
                "INDEX idx_activity_logs_action (action)" +
                ")");
        createTable("review_snapshots", "CREATE TABLE IF NOT EXISTS review_snapshots (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "session_id BIGINT, " +
                "created_at DATETIME(6), " +
                "summary VARCHAR(255), " +
                "data MEDIUMTEXT, " +
                "INDEX idx_review_snapshots_session (session_id)" +
                ")");
        createTable("password_reset_requests","CREATE TABLE IF NOT EXISTS password_reset_requests (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "identifier VARCHAR(150), " +
                "note TEXT, " +
                "user_id BIGINT, " +
                "status VARCHAR(20), " +
                "created_at DATETIME(6), " +
                "handled_at DATETIME(6)" +
                ")");
    }

    /** Đổi kiểu cột nếu đang khác kiểu mong muốn (VD: TEXT -> MEDIUMTEXT). */
    private void widenColumn(String table, String column, String expectedType, String definition) {
        try {
            String type = jdbcTemplate.query(
                    "SELECT DATA_TYPE FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                    rs -> rs.next() ? rs.getString(1) : null, table, column);
            if (type != null && !type.equalsIgnoreCase(expectedType)) {
                jdbcTemplate.execute("ALTER TABLE " + table + " MODIFY COLUMN " + column + " " + definition);
                System.out.println("====== ĐÃ ĐỔI KIỂU CỘT " + table + "." + column + " THÀNH " + definition + " ======");
            }
        } catch (Exception e) {
            System.out.println("====== LỖI KHI ĐỔI KIỂU CỘT " + table + "." + column + ": " + e.getMessage() + " ======");
        }
    }

    private void createTable(String name, String sql) {
        try {
            jdbcTemplate.execute(sql);
            System.out.println("====== ĐÃ ÉP TẠO BẢNG " + name.toUpperCase() + " THÀNH CÔNG ======");
        } catch (Exception e) {
            System.out.println("====== LỖI KHI TẠO BẢNG " + name.toUpperCase() + ": " + e.getMessage() + " ======");
        }
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