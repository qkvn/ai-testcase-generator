package com.example.demo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/** Nghiệp vụ trang quản trị: thống kê, người dùng online, quản lý tài khoản. */
@Service
public class AdminService {

    // Được coi là "đang online" nếu có hoạt động trong 5 phút gần nhất
    static final long ONLINE_WINDOW_MILLIS = 5 * 60 * 1000;
    private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private final SecureRandom random = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final UserRepository userRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final PasswordResetRequestRepository resetRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminService(JdbcTemplate jdbc, UserRepository userRepository, ChatSessionRepository chatSessionRepository,
                        PasswordResetRequestRepository resetRepository, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.userRepository = userRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.resetRepository = resetRepository;
        this.passwordEncoder = passwordEncoder;
    }

    // ===================== NGƯỜI DÙNG ONLINE =====================

    /** principal name trong bảng SPRING_SESSION -> (thời điểm hoạt động cuối, số phiên). */
    private Map<String, long[]> onlinePrincipals() {
        Map<String, long[]> result = new HashMap<>();
        long since = System.currentTimeMillis() - ONLINE_WINDOW_MILLIS;
        try {
            jdbc.query("SELECT PRINCIPAL_NAME, MAX(LAST_ACCESS_TIME) AS LAST_ACCESS, COUNT(*) AS CNT FROM SPRING_SESSION "
                            + "WHERE PRINCIPAL_NAME IS NOT NULL AND LAST_ACCESS_TIME > ? GROUP BY PRINCIPAL_NAME",
                    (RowCallbackHandler) rs -> { result.put(rs.getString("PRINCIPAL_NAME"), new long[]{rs.getLong("LAST_ACCESS"), rs.getLong("CNT")}); },
                    since);
        } catch (Exception ignored) {
            // Bảng session chưa tồn tại (VD: chạy test) -> coi như không có ai online
        }
        return result;
    }

    /**
     * Tên định danh của người dùng trong bảng session:
     * đăng nhập tài khoản -> username, đăng nhập Google -> providerId (Google "sub").
     */
    private static List<String> principalNames(User u) {
        List<String> names = new ArrayList<>();
        if (u.getUsername() != null) names.add(u.getUsername());
        if (u.getProviderId() != null) names.add(u.getProviderId());
        return names;
    }

    /** Thời điểm hoạt động cuối của tài khoản trong 5 phút gần nhất (0 = không hoạt động). */
    private static long lastActive(User u, Map<String, long[]> online) {
        long last = 0;
        for (String name : principalNames(u)) {
            long[] v = online.get(name);
            if (v != null) last = Math.max(last, v[0]);
        }
        return last;
    }

    /**
     * Danh sách cho mục "Đang online": mọi người đang hoạt động + TẤT CẢ quản trị viên
     * (kể cả người vừa được cấp quyền mà chưa online) kèm trạng thái hoạt động / không hoạt động.
     */
    public List<Map<String, Object>> onlineUsers() {
        Map<String, long[]> online = onlinePrincipals();
        List<Map<String, Object>> list = new ArrayList<>();
        for (User u : userRepository.findAll()) {
            long last = lastActive(u, online);
            if (last == 0 && !u.isAdmin()) continue;
            Map<String, Object> row = userSummary(u);
            row.put("online", last > 0);
            row.put("lastAccess", last > 0 ? last : null);
            list.add(row);
        }
        // Đang hoạt động lên trước, rồi tới quản trị viên, mới hoạt động gần nhất lên đầu
        list.sort((a, b) -> {
            int c = Boolean.compare((boolean) b.get("online"), (boolean) a.get("online"));
            if (c != 0) return c;
            Long la = (Long) a.get("lastAccess"), lb = (Long) b.get("lastAccess");
            if (la == null) la = (Long) a.get("lastLoginAt");
            if (lb == null) lb = (Long) b.get("lastLoginAt");
            return Long.compare(lb == null ? 0 : lb, la == null ? 0 : la);
        });
        return list;
    }

    /** Buộc đăng xuất: xóa mọi phiên đăng nhập của tài khoản. */
    public int forceLogout(User u) {
        int removed = 0;
        for (String name : principalNames(u)) {
            try {
                removed += jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?", name);
            } catch (Exception ignored) { }
        }
        return removed;
    }

    // ===================== THỐNG KÊ =====================

    public Map<String, Object> stats() {
        List<User> users = userRepository.findAll();
        LocalDateTime startToday = LocalDate.now().atStartOfDay();
        LocalDateTime start7 = startToday.minusDays(6);

        long google = users.stream().filter(u -> "google".equals(u.getProvider())).count();
        long locked = users.stream().filter(User::isLockedSafe).count();
        long newToday = users.stream().filter(u -> u.getCreatedAt() != null && !u.getCreatedAt().isBefore(startToday)).count();
        long new7 = users.stream().filter(u -> u.getCreatedAt() != null && !u.getCreatedAt().isBefore(start7)).count();

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("totalUsers", users.size());
        s.put("googleUsers", google);
        s.put("localUsers", users.size() - google);
        s.put("lockedUsers", locked);
        s.put("newToday", newToday);
        s.put("new7Days", new7);
        Map<String, long[]> online = onlinePrincipals();
        s.put("onlineNow", users.stream().filter(u -> lastActive(u, online) > 0).count());
        // Gộp các phép đếm vào 1 câu SQL (database ở xa -> mỗi câu truy vấn đều tốn thời gian)
        long[] totals = {0, 0, 0, 0};
        try {
            jdbc.query("SELECT (SELECT COUNT(*) FROM chat_session), (SELECT COUNT(*) FROM test_cases), "
                    + "(SELECT COUNT(*) FROM requirements), (SELECT COUNT(*) FROM password_reset_requests WHERE status = 'PENDING')",
                    (RowCallbackHandler) rs -> { for (int i = 0; i < 4; i++) totals[i] = rs.getLong(i + 1); });
        } catch (Exception ignored) { }
        s.put("totalSessions", totals[0]);
        s.put("totalTestCases", totals[1]);
        s.put("totalRequirements", totals[2]);
        s.put("pendingResetRequests", totals[3]);

        // Lượt sinh test case (số phiên được tạo) theo ngày trong 14 ngày gần nhất
        LocalDate from = LocalDate.now().minusDays(13);
        Map<LocalDate, Long> perDay = new TreeMap<>();
        for (int i = 0; i < 14; i++) perDay.put(from.plusDays(i), 0L);
        try {
            jdbc.query("SELECT DATE(created_at) AS d, COUNT(*) AS c FROM chat_session WHERE created_at >= ? GROUP BY DATE(created_at)",
                    (RowCallbackHandler) rs -> {
                        java.sql.Date d = rs.getDate("d");
                        if (d != null) perDay.put(d.toLocalDate(), rs.getLong("c"));
                    }, Timestamp.valueOf(from.atStartOfDay()));
        } catch (Exception ignored) { }
        List<Map<String, Object>> chart = new ArrayList<>();
        perDay.forEach((d, c) -> chart.add(Map.of("date", d.toString(), "count", c)));
        s.put("generationsPerDay", chart);

        // Top 5 người dùng tạo nhiều phiên nhất
        Map<Long, Long> sessionsByUser = countByUser("SELECT user_id, COUNT(*) FROM chat_session GROUP BY user_id");
        Map<Long, User> byId = new HashMap<>();
        users.forEach(u -> byId.put(u.getId(), u));
        List<Map<String, Object>> top = new ArrayList<>();
        sessionsByUser.entrySet().stream()
                .filter(e -> byId.containsKey(e.getKey()))
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(5)
                .forEach(e -> {
                    Map<String, Object> row = userSummary(byId.get(e.getKey()));
                    row.put("sessions", e.getValue());
                    top.add(row);
                });
        s.put("topUsers", top);
        return s;
    }

    private long count(String sql) {
        try {
            Long v = jdbc.queryForObject(sql, Long.class);
            return v == null ? 0 : v;
        } catch (Exception e) {
            return 0;
        }
    }

    private Map<Long, Long> countByUser(String sql) {
        Map<Long, Long> m = new HashMap<>();
        try {
            jdbc.query(sql, (RowCallbackHandler) rs -> {
                long id = rs.getLong(1);
                if (!rs.wasNull()) m.put(id, rs.getLong(2));
            });
        } catch (Exception ignored) { }
        return m;
    }

    // ===================== QUẢN LÝ TÀI KHOẢN =====================

    public List<Map<String, Object>> listUsers() {
        Map<Long, Long> sessions = countByUser("SELECT user_id, COUNT(*) FROM chat_session GROUP BY user_id");
        Map<Long, Long> testCases = countByUser(
                "SELECT cs.user_id, COUNT(tc.db_id) FROM test_cases tc JOIN chat_session cs ON tc.session_id = cs.id GROUP BY cs.user_id");
        Map<String, long[]> online = onlinePrincipals();

        List<Map<String, Object>> list = new ArrayList<>();
        for (User u : userRepository.findAllByOrderByIdDesc()) {
            Map<String, Object> row = userSummary(u);
            row.put("sessions", sessions.getOrDefault(u.getId(), 0L));
            row.put("testCases", testCases.getOrDefault(u.getId(), 0L));
            row.put("online", principalNames(u).stream().anyMatch(online::containsKey));
            list.add(row);
        }
        return list;
    }

    static Map<String, Object> userSummary(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("name", u.getName());
        m.put("email", u.getEmail());
        m.put("picture", u.getPicture());
        m.put("provider", u.getProvider() == null ? "local" : u.getProvider());
        m.put("role", u.getRole() == null ? "USER" : u.getRole());
        m.put("locked", u.isLockedSafe());
        m.put("hasPassword", u.getPasswordHash() != null);
        m.put("createdAt", toMillis(u.getCreatedAt()));
        m.put("lastLoginAt", toMillis(u.getLastLoginAt()));
        return m;
    }

    static Long toMillis(LocalDateTime t) {
        return t == null ? null : t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    public String randomPassword() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append(PASSWORD_CHARS.charAt(random.nextInt(PASSWORD_CHARS.length())));
        return sb.toString();
    }

    /** Đặt mật khẩu mới (đã mã hóa) và đánh dấu các yêu cầu quên mật khẩu của tài khoản là đã xử lý. */
    @Transactional
    public void setPassword(User u, String rawPassword) {
        u.setPasswordHash(passwordEncoder.encode(rawPassword));
        userRepository.save(u);
        for (PasswordResetRequest r : resetRepository.findByUserIdAndStatus(u.getId(), "PENDING")) {
            r.setStatus("RESOLVED");
            r.setHandledAt(LocalDateTime.now());
            resetRepository.save(r);
        }
    }

    /** Xóa tài khoản kèm toàn bộ lịch sử, test case, yêu cầu của người đó. */
    @Transactional
    public void deleteUser(User u) {
        forceLogout(u);
        chatSessionRepository.deleteAll(chatSessionRepository.findByUserOrderByIsPinnedDescIdDesc(u));
        resetRepository.deleteByUserId(u.getId());
        userRepository.delete(u);
    }
}
