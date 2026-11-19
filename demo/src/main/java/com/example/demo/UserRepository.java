package com.example.demo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    // Tài khoản Google và tài khoản thường tách biệt: luôn tìm email kèm loại tài khoản
    Optional<User> findFirstByEmailAndProvider(String email, String provider);
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
    long countByRole(String role);
    List<User> findAllByOrderByIdDesc();
}
