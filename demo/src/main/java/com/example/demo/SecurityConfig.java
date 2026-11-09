package com.example.demo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.http.HttpStatus;
import jakarta.servlet.DispatcherType;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.cors.CorsConfigurationSource;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private CustomOAuth2UserService customOAuth2UserService;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable()) // Tắt CSRF để React gọi API dễ dàng
                .authorizeHttpRequests(auth -> auth
                        // Cho phép các lượt forward nội bộ ("/" -> "/index.html") và trang lỗi
                        .dispatcherTypeMatchers(DispatcherType.FORWARD, DispatcherType.ERROR).permitAll()
                        // BẮT BUỘC: Mở khóa các endpoint hệ thống và OAuth2
                        .requestMatchers("/", "/error", "/login**", "/oauth2/**", "/api/user/me").permitAll()
                        // Mở file tĩnh của React để người chưa đăng nhập vẫn xem được trang chủ (landing page)
                        .requestMatchers("/index.html", "/assets/**", "/favicon.ico", "/*.svg", "/*.png", "/*.ico", "/*.ttf", "/*.json", "/*.txt").permitAll()
                        .anyRequest().authenticated()
                )
                // Gọi API khi chưa đăng nhập -> trả 401 cho frontend xử lý, KHÔNG tự chuyển sang trang Google
                .exceptionHandling(ex -> ex
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                request -> request.getRequestURI().startsWith("/api/"))
                )
                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo
                                .userService(customOAuth2UserService)
                        )
                        // Đăng nhập thành công: Dùng đường dẫn tương đối "/" để tự động nhận diện tên miền (localhost hoặc Render)
                        .defaultSuccessUrl("/", true)
                )
                .logout(logout -> logout
                        .logoutUrl("/api/logout")
                        // Đăng xuất thành công: Trở về trang chủ "/"
                        .logoutSuccessUrl("/")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                );
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        // Hỗ trợ đồng thời cả Localhost (khi code máy cá nhân) và Render (khi chạy online)
        configuration.setAllowedOrigins(List.of(
            "http://localhost:5173", 
            "https://ai-testcase-generator-1j9g.onrender.com"
        ));
        
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowCredentials(true);
        configuration.setAllowedHeaders(List.of("*"));
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}