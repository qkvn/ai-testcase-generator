package com.example.demo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {
    // Tìm toàn bộ lịch sử của 1 user cụ thể và sắp xếp mới nhất lên đầu
    // Xóa dòng findByUserOrderByIdDesc cũ và thay bằng dòng này:
    List<ChatSession> findByUserOrderByIsPinnedDescIdDesc(User user);
}