package com.example.demo;

import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Xác định người dùng hiện tại cho cả 2 kiểu đăng nhập:
 *  - Đăng nhập tài khoản/mật khẩu: principal có thuộc tính "uid"
 *  - Đăng nhập Google: tìm tài khoản Google theo email
 * Tài khoản bị khóa / đã bị xóa -> trả về null (coi như chưa đăng nhập).
 */
@Service
public class CurrentUserService {
    private final UserRepository userRepository;

    public CurrentUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User resolve(OAuth2User principal) {
        if (principal == null) return null;
        Object uid = principal.getAttribute("uid");
        Optional<User> user;
        if (uid instanceof Number n) {
            user = userRepository.findById(n.longValue());
        } else {
            String email = principal.getAttribute("email");
            user = email == null ? Optional.empty() : userRepository.findFirstByEmailAndProvider(email, "google");
        }
        return user.filter(u -> !u.isLockedSafe()).orElse(null);
    }
}
