package com.example.demo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface PasswordResetRequestRepository extends JpaRepository<PasswordResetRequest, Long> {
    List<PasswordResetRequest> findTop200ByOrderByIdDesc();
    long countByStatus(String status);
    List<PasswordResetRequest> findByUserIdAndStatus(Long userId, String status);
    void deleteByUserId(Long userId);
}
