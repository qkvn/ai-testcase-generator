package com.example.demo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long> {
    List<ActivityLog> findTop300ByOrderByIdDesc();
    List<ActivityLog> findTop300ByActionOrderByIdDesc(String action);
}
