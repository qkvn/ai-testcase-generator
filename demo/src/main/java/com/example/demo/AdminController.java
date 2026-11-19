package com.example.demo;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

import static com.example.demo.AuthController.error;

/** API trang quản trị — chỉ tài khoản có quyền ADMIN gọi được (cấu hình trong SecurityConfig). */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;
    private final ActivityLogService activityLog;
    private final ActivityLogRepository logRepository;
    private final PasswordResetRequestRepository resetRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthController authController;

    public AdminController(AdminService adminService, UserRepository userRepository, CurrentUserService currentUserService,
                           ActivityLogService activityLog, ActivityLogRepository logRepository,
                           PasswordResetRequestRepository resetRepository, PasswordEncoder passwordEncoder,
                           AuthController authController) {
        this.adminService = adminService;
        this.userRepository = userRepository;
        this.currentUserService = currentUserService;
        this.activityLog = activityLog;
        this.logRepository = logRepository;
        this.resetRepository = resetRepository;
        this.passwordEncoder = passwordEncoder;
        this.authController = authController;
    }

    public record UserForm(String username, String name, String email, String role, Boolean locked, String password) {}
    public record PasswordForm(String newPassword) {}
    public record ChangeOwnPasswordForm(String currentPassword, String newPassword) {}

    private User admin(OAuth2User principal) {
        return currentUserService.resolve(principal);
    }

    // ---------- Thống kê & online ----------

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return adminService.stats();
    }

    @GetMapping("/online")
    public List<Map<String, Object>> online() {
        return adminService.onlineUsers();
    }

    @PostMapping("/users/{id}/force-logout")
    public ResponseEntity<?> forceLogout(@PathVariable Long id, @AuthenticationPrincipal OAuth2User principal) {
        User me = admin(principal);
        User u = userRepository.findById(id).orElse(null);
        if (u == null) return error(HttpStatus.NOT_FOUND, "not_found", "Không tìm thấy tài khoản");
        if (me != null && me.getId().equals(u.getId())) {
            return error(HttpStatus.BAD_REQUEST, "self", "Không thể tự buộc đăng xuất chính mình");
        }
        int removed = adminService.forceLogout(u);
        activityLog.log(me, "ADMIN_FORCE_LOGOUT", "Buộc đăng xuất: " + ActivityLogService.displayName(u));
        return ResponseEntity.ok(Map.of("removed", removed));
    }

    // ---------- Quản lý tài khoản ----------

    @GetMapping("/users")
    public List<Map<String, Object>> users() {
        return adminService.listUsers();
    }

    @PostMapping("/users")
    public ResponseEntity<?> createUser(@RequestBody UserForm form, @AuthenticationPrincipal OAuth2User principal) {
        String username = trim(form.username());
        String name = trim(form.name());
        String email = trim(form.email()).toLowerCase(Locale.ROOT);
        String password = form.password() == null ? "" : form.password();
        ResponseEntity<Map<String, Object>> invalid = authController.validateAccount(username, email, password, null);
        if (invalid != null) return invalid;

        User u = new User();
        u.setUsername(username);
        u.setName(name.isEmpty() ? null : name);
        u.setEmail(email.isEmpty() ? null : email);
        u.setPasswordHash(passwordEncoder.encode(password));
        u.setProvider("local");
        u.setRole("ADMIN".equals(form.role()) ? "ADMIN" : "USER");
        u.setLocked(Boolean.TRUE.equals(form.locked()));
        u.setCreatedAt(LocalDateTime.now());
        userRepository.save(u);
        activityLog.log(admin(principal), "ADMIN_CREATE_USER", "Tạo tài khoản: " + username);
        return ResponseEntity.ok(AdminService.userSummary(u));
    }

    @PutMapping("/users/{id}")
    public ResponseEntity<?> updateUser(@PathVariable Long id, @RequestBody UserForm form,
                                        @AuthenticationPrincipal OAuth2User principal) {
        User me = admin(principal);
        User u = userRepository.findById(id).orElse(null);
        if (u == null) return error(HttpStatus.NOT_FOUND, "not_found", "Không tìm thấy tài khoản");

        String username = trim(form.username());
        String name = trim(form.name());
        String email = trim(form.email()).toLowerCase(Locale.ROOT);
        ResponseEntity<Map<String, Object>> invalid = "google".equals(u.getProvider())
                ? authController.validateUsername(username, u.getId())   // email tài khoản Google lấy từ Google, không sửa
                : authController.validateAccount(username, email, null, u.getId());
        if (invalid != null) return invalid;

        String newRole = "ADMIN".equals(form.role()) ? "ADMIN" : "USER";
        boolean newLocked = Boolean.TRUE.equals(form.locked());
        boolean isSelf = me != null && me.getId().equals(u.getId());
        if (isSelf && (!"ADMIN".equals(newRole) || newLocked)) {
            return error(HttpStatus.BAD_REQUEST, "self", "Không thể tự khóa hoặc tự bỏ quyền quản trị của chính mình");
        }
        if (u.isAdmin() && !"ADMIN".equals(newRole) && userRepository.countByRole("ADMIN") <= 1) {
            return error(HttpStatus.BAD_REQUEST, "last_admin", "Phải còn ít nhất một tài khoản quản trị");
        }

        boolean lockedNow = newLocked && !u.isLockedSafe();
        boolean roleChanged = !newRole.equals(u.getRole() == null ? "USER" : u.getRole());
        u.setUsername(username);
        u.setName(name.isEmpty() ? null : name);
        if (!"google".equals(u.getProvider())) u.setEmail(email.isEmpty() ? null : email);
        u.setRole(newRole);
        u.setLocked(newLocked);
        userRepository.save(u);
        // Khóa tài khoản hoặc đổi quyền -> đăng xuất người đó ngay để thay đổi có hiệu lực
        // (được cấp quyền quản trị: đăng nhập lại là vào được trang admin; bị gỡ quyền: mất quyền ngay)
        if (lockedNow || roleChanged) adminService.forceLogout(u);
        currentUserService.evict(u.getId());
        activityLog.log(me, "ADMIN_UPDATE_USER", "Cập nhật tài khoản: " + username
                + (lockedNow ? " (khóa)" : "") + " | quyền: " + newRole);
        return ResponseEntity.ok(AdminService.userSummary(u));
    }

    /** Khóa / mở khóa nhanh. */
    @PostMapping("/users/{id}/toggle-lock")
    public ResponseEntity<?> toggleLock(@PathVariable Long id, @AuthenticationPrincipal OAuth2User principal) {
        User me = admin(principal);
        User u = userRepository.findById(id).orElse(null);
        if (u == null) return error(HttpStatus.NOT_FOUND, "not_found", "Không tìm thấy tài khoản");
        if (me != null && me.getId().equals(u.getId())) {
            return error(HttpStatus.BAD_REQUEST, "self", "Không thể tự khóa chính mình");
        }
        boolean lock = !u.isLockedSafe();
        u.setLocked(lock);
        userRepository.save(u);
        if (lock) adminService.forceLogout(u);
        currentUserService.evict(u.getId());
        activityLog.log(me, lock ? "ADMIN_LOCK_USER" : "ADMIN_UNLOCK_USER",
                (lock ? "Khóa tài khoản: " : "Mở khóa tài khoản: ") + ActivityLogService.displayName(u));
        return ResponseEntity.ok(AdminService.userSummary(u));
    }

    /** Đặt lại mật khẩu: để trống thì hệ thống tự tạo mật khẩu ngẫu nhiên và trả về cho admin. */
    @PostMapping("/users/{id}/reset-password")
    public ResponseEntity<?> resetPassword(@PathVariable Long id, @RequestBody(required = false) PasswordForm form,
                                           @AuthenticationPrincipal OAuth2User principal) {
        User u = userRepository.findById(id).orElse(null);
        if (u == null) return error(HttpStatus.NOT_FOUND, "not_found", "Không tìm thấy tài khoản");
        if (u.getUsername() == null || u.getUsername().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "no_username",
                    "Tài khoản Google này chưa có tên đăng nhập. Hãy đặt tên tài khoản (nút Sửa) trước khi đặt mật khẩu");
        }
        String pw = form == null || form.newPassword() == null || form.newPassword().isBlank()
                ? adminService.randomPassword() : form.newPassword();
        if (pw.length() < 6) return error(HttpStatus.BAD_REQUEST, "bad_password", "Mật khẩu phải có ít nhất 6 ký tự");
        adminService.setPassword(u, pw);
        currentUserService.evict(u.getId());
        activityLog.log(admin(principal), "ADMIN_RESET_PASSWORD", "Đặt lại mật khẩu: " + u.getUsername());
        return ResponseEntity.ok(Map.of("username", u.getUsername(), "password", pw));
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable Long id, @AuthenticationPrincipal OAuth2User principal) {
        User me = admin(principal);
        User u = userRepository.findById(id).orElse(null);
        if (u == null) return error(HttpStatus.NOT_FOUND, "not_found", "Không tìm thấy tài khoản");
        if (me != null && me.getId().equals(u.getId())) {
            return error(HttpStatus.BAD_REQUEST, "self", "Không thể tự xóa tài khoản của chính mình");
        }
        if (u.isAdmin() && userRepository.countByRole("ADMIN") <= 1) {
            return error(HttpStatus.BAD_REQUEST, "last_admin", "Phải còn ít nhất một tài khoản quản trị");
        }
        String label = ActivityLogService.displayName(u);
        adminService.deleteUser(u);
        currentUserService.evict(id);
        activityLog.log(me, "ADMIN_DELETE_USER", "Xóa tài khoản: " + label);
        return ResponseEntity.ok(Map.of("deleted", id));
    }

    // ---------- Nhật ký hoạt động ----------

    @GetMapping("/logs")
    public List<Map<String, Object>> logs(@RequestParam(required = false) String action) {
        List<ActivityLog> logs = action == null || action.isBlank()
                ? logRepository.findTop300ByOrderByIdDesc()
                : logRepository.findTop300ByActionOrderByIdDesc(action);
        List<Map<String, Object>> list = new ArrayList<>();
        for (ActivityLog l : logs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("userId", l.getUserId());
            m.put("username", l.getUsername());
            m.put("action", l.getAction());
            m.put("detail", l.getDetail());
            m.put("createdAt", AdminService.toMillis(l.getCreatedAt()));
            list.add(m);
        }
        return list;
    }

    // ---------- Yêu cầu quên mật khẩu ----------

    @GetMapping("/reset-requests")
    public List<Map<String, Object>> resetRequests() {
        Map<Long, User> users = new HashMap<>();
        userRepository.findAll().forEach(u -> users.put(u.getId(), u));
        List<Map<String, Object>> list = new ArrayList<>();
        for (PasswordResetRequest r : resetRepository.findTop200ByOrderByIdDesc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("identifier", r.getIdentifier());
            m.put("note", r.getNote());
            m.put("status", r.getStatus());
            m.put("createdAt", AdminService.toMillis(r.getCreatedAt()));
            m.put("handledAt", AdminService.toMillis(r.getHandledAt()));
            User u = r.getUserId() == null ? null : users.get(r.getUserId());
            if (u == null && "PENDING".equals(r.getStatus())) u = matchUser(r); // người dùng có thể đăng ký sau khi gửi yêu cầu
            m.put("user", u == null ? null : AdminService.userSummary(u));
            list.add(m);
        }
        return list;
    }

    @PostMapping("/reset-requests/{id}/resolve")
    public ResponseEntity<?> resolveRequest(@PathVariable Long id, @AuthenticationPrincipal OAuth2User principal) {
        PasswordResetRequest r = resetRepository.findById(id).orElse(null);
        if (r == null) return error(HttpStatus.NOT_FOUND, "not_found", "Không tìm thấy yêu cầu");
        User u = r.getUserId() == null ? null : userRepository.findById(r.getUserId()).orElse(null);
        if (u == null) u = matchUser(r);
        if (u == null) return error(HttpStatus.BAD_REQUEST, "no_user", "Yêu cầu này không khớp với tài khoản nào");
        if (r.getUserId() == null) { r.setUserId(u.getId()); resetRepository.save(r); }
        return resetPassword(u.getId(), null, principal);
    }

    @PostMapping("/reset-requests/{id}/dismiss")
    public ResponseEntity<?> dismissRequest(@PathVariable Long id, @AuthenticationPrincipal OAuth2User principal) {
        PasswordResetRequest r = resetRepository.findById(id).orElse(null);
        if (r == null) return error(HttpStatus.NOT_FOUND, "not_found", "Không tìm thấy yêu cầu");
        r.setStatus("DISMISSED");
        r.setHandledAt(LocalDateTime.now());
        resetRepository.save(r);
        activityLog.log(admin(principal), "ADMIN_DISMISS_RESET", "Bỏ qua yêu cầu quên mật khẩu: " + r.getIdentifier());
        return ResponseEntity.ok(Map.of("status", "DISMISSED"));
    }

    // ---------- Đổi mật khẩu admin ----------

    @PostMapping("/me/password")
    public ResponseEntity<?> changeOwnPassword(@RequestBody ChangeOwnPasswordForm form, @AuthenticationPrincipal OAuth2User principal) {
        User me = admin(principal);
        if (me == null) return error(HttpStatus.UNAUTHORIZED, "unauthorized", "Vui lòng đăng nhập lại");
        if (me.getPasswordHash() == null || form.currentPassword() == null
                || !passwordEncoder.matches(form.currentPassword(), me.getPasswordHash())) {
            return error(HttpStatus.BAD_REQUEST, "bad_current", "Mật khẩu hiện tại không đúng");
        }
        if (form.newPassword() == null || form.newPassword().length() < 8) {
            return error(HttpStatus.BAD_REQUEST, "bad_password", "Mật khẩu mới của admin phải có ít nhất 8 ký tự");
        }
        adminService.setPassword(me, form.newPassword());
        currentUserService.evict(me.getId());
        activityLog.log(me, "ADMIN_CHANGE_PASSWORD", "Admin đổi mật khẩu");
        return ResponseEntity.ok(Map.of("message", "ok"));
    }

    /** Tìm tài khoản theo tên tài khoản hoặc email người dùng đã nhập. */
    private User matchUser(PasswordResetRequest r) {
        String id = r.getIdentifier() == null ? "" : r.getIdentifier().trim();
        if (id.isEmpty()) return null;
        Optional<User> u = userRepository.findByUsername(id);
        if (u.isEmpty() && id.contains("@")) u = userRepository.findFirstByEmailAndProvider(id.toLowerCase(Locale.ROOT), "local");
        return u.orElse(null);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
