package com.example.demo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ActivityLogService activityLog;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);

        String email = oAuth2User.getAttribute("email");
        String name = oAuth2User.getAttribute("name");
        String picture = oAuth2User.getAttribute("picture");
        String providerId = oAuth2User.getAttribute("sub");

        User user = userRepository.findByEmail(email).orElse(null);
        boolean isNew = user == null;
        if (isNew) {
            user = new User();
            user.setEmail(email);
            user.setProvider("google");
            user.setRole("USER");
            user.setLocked(false);
            user.setCreatedAt(LocalDateTime.now());
        }
        if (user.isLockedSafe()) {
            // Tài khoản bị admin khóa -> không cho đăng nhập
            throw new OAuth2AuthenticationException(new OAuth2Error("account_locked"), "Tài khoản đã bị khóa");
        }
        if (user.getName() == null || "google".equals(user.getProvider())) user.setName(name);
        user.setPicture(picture);
        if (user.getProviderId() == null) user.setProviderId(providerId);
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);
        activityLog.log(user, isNew ? "REGISTER_GOOGLE" : "LOGIN_GOOGLE", isNew ? "Đăng ký bằng Google" : "Đăng nhập bằng Google");

        return oAuth2User;
    }
}
