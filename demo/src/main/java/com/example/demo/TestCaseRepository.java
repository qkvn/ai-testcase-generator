package com.example.demo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface TestCaseRepository extends JpaRepository<TestCase, Long> {

    // Thêm dòng này: Lấy danh sách test case dựa theo ID của lịch sử trò chuyện
    List<TestCase> findByChatSessionId(Long sessionId);
}