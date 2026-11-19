package com.example.demo;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private final CurrentUserService currentUserService;

    public UserController(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(@AuthenticationPrincipal OAuth2User principal) {
        User user = currentUserService.resolve(principal);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Chưa đăng nhập");
        }
        Map<String, Object> info = toUserInfo(user);
        // Ảnh đại diện Google lấy từ phiên đăng nhập nếu DB chưa có
        if (info.get("picture") == null && principal.getAttribute("picture") != null
                && !"".equals(principal.getAttribute("picture"))) {
            info.put("picture", principal.getAttribute("picture"));
        }
        return ResponseEntity.ok(info);
    }

    static Map<String, Object> toUserInfo(User u) {
        Map<String, Object> info = new HashMap<>();
        info.put("id", u.getId());
        info.put("name", u.getName() != null ? u.getName() : u.getUsername());
        info.put("email", u.getEmail());
        info.put("picture", u.getPicture() == null || u.getPicture().isBlank() ? null : u.getPicture());
        info.put("username", u.getUsername());
        info.put("role", u.getRole() == null ? "USER" : u.getRole());
        info.put("provider", u.getProvider());
        return info;
    }
}
