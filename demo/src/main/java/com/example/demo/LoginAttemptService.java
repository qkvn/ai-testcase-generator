package com.example.demo;

import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Chống dò mật khẩu: sai 5 lần liên tiếp -> tạm khóa đăng nhập tài khoản đó 5 phút. */
@Service
public class LoginAttemptService {
    private static final int MAX_ATTEMPTS = 5;
    private static final long BLOCK_MILLIS = 5 * 60 * 1000;

    private record Attempt(int count, long blockedUntil) {}
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    private static String key(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    /** Số giây còn bị chặn (0 nếu không bị chặn). */
    public long secondsBlocked(String username) {
        Attempt a = attempts.get(key(username));
        if (a == null) return 0;
        long left = a.blockedUntil() - System.currentTimeMillis();
        return left > 0 ? (left + 999) / 1000 : 0;
    }

    public void fail(String username) {
        long now = System.currentTimeMillis();
        attempts.compute(key(username), (k, a) -> {
            // Hết thời gian chặn thì đếm lại từ đầu
            int count = (a == null || (a.blockedUntil() > 0 && a.blockedUntil() < now)) ? 1 : a.count() + 1;
            long until = count >= MAX_ATTEMPTS ? now + BLOCK_MILLIS : 0;
            return new Attempt(count, until);
        });
    }

    public void success(String username) {
        attempts.remove(key(username));
    }
}
