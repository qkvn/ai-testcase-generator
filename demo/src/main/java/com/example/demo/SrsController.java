package com.example.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/** Tải tài liệu SRS (.txt, .md, .docx, .pdf) lên, đọc nội dung và sinh test case theo từng phần. */
@RestController
@RequestMapping("/api/srs")
public class SrsController {
    private static final Logger log = LoggerFactory.getLogger(SrsController.class);
    /** Giới hạn nội dung một phần gửi cho AI (ký tự). */
    private static final int MAX_SECTION_CHARS = 12_000;

    private final SrsService srsService;
    private final TestCaseService testCaseService;
    private final CurrentUserService currentUserService;
    private final ChatSessionRepository chatSessionRepository;
    private final ActivityLogService activityLog;

    public SrsController(SrsService srsService, TestCaseService testCaseService, CurrentUserService currentUserService,
                         ChatSessionRepository chatSessionRepository, ActivityLogService activityLog) {
        this.srsService = srsService;
        this.testCaseService = testCaseService;
        this.currentUserService = currentUserService;
        this.chatSessionRepository = chatSessionRepository;
        this.activityLog = activityLog;
    }

    public record SectionGenerateRequest(Long sessionId, String fileName, String sectionTitle, String content,
                                         String sourceText, String lang) {}

    /** Đọc file và trả về nội dung đã chia phần (chưa gọi AI). */
    @PostMapping("/parse")
    public ResponseEntity<?> parse(@RequestParam("file") MultipartFile file, @AuthenticationPrincipal OAuth2User principal) throws Exception {
        User user = currentUserService.resolve(principal);
        if (user == null) return error(HttpStatus.UNAUTHORIZED, "unauthorized", "Vui lòng đăng nhập");
        SrsService.SrsDocument doc = srsService.parse(file.getOriginalFilename(), file.getBytes());
        activityLog.log(user, "SRS_UPLOAD", "Tải lên " + doc.fileName() + " (" + doc.chars() + " ký tự, "
                + doc.sections().size() + " phần)");
        return ResponseEntity.ok(doc);
    }

    /** Sinh test case cho một phần của tài liệu; phần đầu tiên tạo phiên mới, các phần sau thêm vào phiên đó. */
    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestBody SectionGenerateRequest body, @AuthenticationPrincipal OAuth2User principal) {
        User user = currentUserService.resolve(principal);
        if (user == null) return error(HttpStatus.UNAUTHORIZED, "unauthorized", "Vui lòng đăng nhập");

        String content = body.content() == null ? "" : body.content().trim();
        if (content.isEmpty()) return error(HttpStatus.BAD_REQUEST, "empty_section", "Phần này không có nội dung");
        if (content.length() > MAX_SECTION_CHARS) content = content.substring(0, MAX_SECTION_CHARS);
        String title = body.sectionTitle() == null || body.sectionTitle().isBlank() ? "SRS" : body.sectionTitle().trim();

        if (body.sessionId() != null) {
            ChatSession session = chatSessionRepository.findById(body.sessionId()).orElse(null);
            if (session == null) return error(HttpStatus.NOT_FOUND, "not_found", "Không tìm thấy lịch sử");
            if (session.getUser() == null || !session.getUser().getId().equals(user.getId())) {
                return error(HttpStatus.FORBIDDEN, "forbidden", "Bạn không có quyền sửa phiên này");
            }
        }
        String source = body.sourceText();
        if (source != null && source.length() > SrsService.MAX_CHARS) source = source.substring(0, SrsService.MAX_CHARS);

        GenerateResponse result = testCaseService.generateFromSrsSection(user, body.sessionId(), body.fileName(), title,
                content, source, body.lang() == null ? "vi" : body.lang());
        activityLog.log(user, "GENERATE", "SRS " + (body.fileName() == null ? "" : body.fileName()) + " — " + title
                + ": " + result.testCases().size() + " test case");
        return ResponseEntity.ok(result);
    }

    // ---------- Xử lý lỗi: trả {"code","message"} để giao diện hiển thị ----------

    @ExceptionHandler(SrsService.SrsException.class)
    public ResponseEntity<?> handleSrs(SrsService.SrsException e) {
        return error(HttpStatus.BAD_REQUEST, e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> handleTooLarge(MaxUploadSizeExceededException e) {
        return error(HttpStatus.valueOf(413), "too_large", "File vượt quá 5 MB");
    }

    @ExceptionHandler(TestCaseService.AiFormatException.class)
    public ResponseEntity<?> handleAi(TestCaseService.AiFormatException e) {
        return error(HttpStatus.BAD_GATEWAY, "ai_format", e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> handleRuntime(RuntimeException e) {
        log.error("Lỗi SRS", e);
        String msg = e.getMessage() == null ? "Lỗi hệ thống, vui lòng thử lại." : e.getMessage();
        if (msg.length() > 300) msg = msg.substring(0, 300) + "...";
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "error", msg);
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }
}
