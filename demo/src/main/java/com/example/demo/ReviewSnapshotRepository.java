package com.example.demo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReviewSnapshotRepository extends JpaRepository<ReviewSnapshot, Long> {
    List<ReviewSnapshot> findByChatSessionIdOrderByIdDesc(Long sessionId);
    long countByChatSessionId(Long sessionId);
}
