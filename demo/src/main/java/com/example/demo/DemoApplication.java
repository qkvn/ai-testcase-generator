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
            // Ép MySQL tạo bảng test_case_steps trực tiếp bằng SQL nếu nó chưa tồn tại
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS test_case_steps (" +
                                 "test_case_db_id BIGINT NOT NULL, " +
                                 "step_description TEXT" +
                                 ")");
            System.out.println("====== ĐÃ ÉP TẠO BẢNG TEST_CASE_STEPS THÀNH CÔNG ======");
        } catch (Exception e) {
            System.out.println("====== LỖI KHI TẠO BẢNG: " + e.getMessage() + " ======");
        }
    }
}