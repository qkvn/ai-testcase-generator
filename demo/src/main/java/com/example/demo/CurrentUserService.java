package com.example.demo;

import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Xác định người dùng hiện tại cho cả 2 kiểu đăng nhập:
 *  - Đăng nhập tài khoản/mật khẩu: principal có thuộc tính "uid"
 *  - Đăng nhập Google: tìm tài khoản Google theo email
 * Tài khoản bị khóa / đã bị xóa -> trả về null (coi như chưa đăng nhập).
 *
 * Có bộ nhớ đệm ngắn hạn (60 giây) để mỗi request không phải truy vấn lại bảng users:
 * database đặt ở xa nên mỗi câu truy vấn đều tốn đáng kể thời gian.
 * Admin sửa / khóa / xóa tài khoản sẽ xóa bộ nhớ đệm của tài khoản đó ngay (evict).
 */
@Service
public class CurrentUserService {
    private static final long TTL_MILLIS = 60_000;

    private record Cached(User user, long expiresAt) {}

    private final UserRepository userRepository;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public CurrentUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User resolve(OAuth2User principal) {
        if (principal == null) return null;
        Object uid = principal.getAttribute("uid");
        String email = principal.getAttribute("email");
        String key = uid instanceof Number n ? "u:" + n.longValue() : (email == null ? null : "g:" + email);
        if (key == null) return null;

        long now = System.currentTimeMillis();
        Cached hit = cache.get(key);
        User user;
        if (hit != null && hit.expiresAt() > now) {
            user = hit.user();
        } else {
            Optional<User> found = uid instanceof Number n
                    ? userRepository.findById(n.longValue())
                    : userRepository.findFirstByEmailAndProvider(email, "google");
            user = found.orElse(null);
            if (user != null) cache.put(key, new Cached(user, now + TTL_MILLIS));
            else cache.remove(key);
        }
        return user == null || user.isLockedSafe() ? null : user;
    }

    /** Xóa bộ nhớ đệm của một tài khoản (sau khi admin thay đổi tài khoản đó). */
    public void evict(Long userId) {
        if (userId == null) return;
        cache.entrySet().removeIf(e -> userId.equals(e.getValue().user().getId()));
    }
}
