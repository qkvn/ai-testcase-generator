package com.example.demo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

/** Đăng nhập / đăng ký bằng tài khoản & mật khẩu, và gửi yêu cầu quên mật khẩu. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._-]{3,30}$");
    static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService loginAttempts;
    private final ActivityLogService activityLog;
    private final PasswordResetRequestRepository resetRepository;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder,
                          LoginAttemptService loginAttempts, ActivityLogService activityLog,
                          PasswordResetRequestRepository resetRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.loginAttempts = loginAttempts;
        this.activityLog = activityLog;
        this.resetRepository = resetRepository;
    }

    public record LoginRequest(String username, String password, Boolean admin) {}
    public record RegisterRequest(String username, String name, String email, String password) {}
    public record ForgotRequest(String identifier, String note) {}

    static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest body, HttpServletRequest req, HttpServletResponse res) {
        String username = body.username() == null ? "" : body.username().trim();
        String password = body.password() == null ? "" : body.password();
        if (username.isEmpty() || password.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "missing", "Vui lòng nhập tên tài khoản và mật khẩu");
        }
        long blocked = loginAttempts.secondsBlocked(username);
        if (blocked > 0) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("code", "too_many",
                    "seconds", blocked,
                    "message", "Bạn đã nhập sai quá nhiều lần. Vui lòng thử lại sau " + blocked + " giây"));
        }

        // Cho phép đăng nhập bằng tên tài khoản hoặc email
        Optional<User> found = userRepository.findByUsername(username);
        if (found.isEmpty() && username.contains("@")) found = userRepository.findByEmail(username.toLowerCase(Locale.ROOT));
        User user = found.orElse(null);

        if (user == null || user.getPasswordHash() == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            loginAttempts.fail(username);
            return error(HttpStatus.UNAUTHORIZED, "bad_credentials", "Tên tài khoản hoặc mật khẩu không đúng");
        }
        if (user.isLockedSafe()) {
            return error(HttpStatus.FORBIDDEN, "locked", "Tài khoản của bạn đã bị khóa. Vui lòng liên hệ quản trị viên");
        }
        boolean adminLogin = Boolean.TRUE.equals(body.admin());
        if (adminLogin && !user.isAdmin()) {
            return error(HttpStatus.FORBIDDEN, "not_admin", "Tài khoản này không có quyền quản trị");
        }

        loginAttempts.success(username);
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);
        signIn(user, req, res);
        activityLog.log(user, adminLogin ? "ADMIN_LOGIN" : "LOGIN", "Đăng nhập bằng tài khoản");
        return ResponseEntity.ok(UserController.toUserInfo(user));
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest body, HttpServletRequest req, HttpServletResponse res) {
        String username = body.username() == null ? "" : body.username().trim();
        String name = body.name() == null ? "" : body.name().trim();
        String email = body.email() == null ? "" : body.email().trim().toLowerCase(Locale.ROOT);
        String password = body.password() == null ? "" : body.password();

        ResponseEntity<Map<String, Object>> invalid = validateAccount(username, name, email, password, null);
        if (invalid != null) return invalid;

        User user = new User();
        user.setUsername(username);
        user.setName(name);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setProvider("local");
        user.setRole("USER");
        user.setLocked(false);
        user.setCreatedAt(LocalDateTime.now());
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        signIn(user, req, res);
        activityLog.log(user, "REGISTER", "Đăng ký tài khoản mới");
        return ResponseEntity.ok(UserController.toUserInfo(user));
    }

    /**
     * Kiểm tra dữ liệu tài khoản (dùng chung cho đăng ký và admin tạo / sửa tài khoản).
     * password = null nghĩa là không kiểm tra mật khẩu; existingId = id tài khoản đang sửa.
     */
    ResponseEntity<Map<String, Object>> validateAccount(String username, String name, String email, String password, Long existingId) {
        if (!USERNAME.matcher(username).matches()) {
            return error(HttpStatus.BAD_REQUEST, "bad_username", "Tên tài khoản dài 3–30 ký tự, chỉ gồm chữ, số và . _ -");
        }
        if (name.isEmpty()) return error(HttpStatus.BAD_REQUEST, "bad_name", "Vui lòng nhập họ tên");
        if (!EMAIL.matcher(email).matches()) return error(HttpStatus.BAD_REQUEST, "bad_email", "Email không hợp lệ");
        if (password != null && password.length() < 6) {
            return error(HttpStatus.BAD_REQUEST, "bad_password", "Mật khẩu phải có ít nhất 6 ký tự");
        }
        Optional<User> byUsername = userRepository.findByUsername(username);
        if (byUsername.isPresent() && !byUsername.get().getId().equals(existingId)) {
            return error(HttpStatus.CONFLICT, "username_taken", "Tên tài khoản đã tồn tại");
        }
        Optional<User> byEmail = userRepository.findByEmail(email);
        if (byEmail.isPresent() && !byEmail.get().getId().equals(existingId)) {
            return error(HttpStatus.CONFLICT, "email_taken", "Email đã được sử dụng");
        }
        return null;
    }

    @PostMapping("/forgot")
    public ResponseEntity<?> forgot(@RequestBody ForgotRequest body) {
        String identifier = body.identifier() == null ? "" : body.identifier().trim();
        if (identifier.isEmpty()) return error(HttpStatus.BAD_REQUEST, "missing", "Vui lòng nhập tên tài khoản hoặc email");

        Optional<User> user = userRepository.findByUsername(identifier);
        if (user.isEmpty() && identifier.contains("@")) user = userRepository.findByEmail(identifier.toLowerCase(Locale.ROOT));

        PasswordResetRequest r = new PasswordResetRequest();
        r.setIdentifier(identifier.length() > 150 ? identifier.substring(0, 150) : identifier);
        String note = body.note();
        r.setNote(note != null && note.length() > 1000 ? note.substring(0, 1000) : note);
        r.setUserId(user.map(User::getId).orElse(null));
        r.setStatus("PENDING");
        r.setCreatedAt(LocalDateTime.now());
        resetRepository.save(r);
        activityLog.log(r.getUserId(), identifier, "FORGOT_PASSWORD", "Gửi yêu cầu quên mật khẩu");

        // Luôn trả cùng một kết quả để không lộ tài khoản nào tồn tại
        return ResponseEntity.ok(Map.of("message", "ok"));
    }

    /** Lưu thông tin đăng nhập vào session (giống cách đăng nhập Google lưu). */
    private void signIn(User user, HttpServletRequest req, HttpServletResponse res) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        if (user.isAdmin()) authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));

        Map<String, Object> attrs = new HashMap<>();
        attrs.put("uid", user.getId());
        attrs.put("username", user.getUsername());
        attrs.put("name", user.getName() == null ? user.getUsername() : user.getName());
        attrs.put("email", user.getEmail() == null ? "" : user.getEmail());
        attrs.put("picture", "");
        DefaultOAuth2User principal = new DefaultOAuth2User(authorities, attrs, "username");

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        // Đổi session id sau khi đăng nhập (chống tấn công session fixation)
        req.getSession(true);
        req.changeSessionId();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, req, res);
    }
}
