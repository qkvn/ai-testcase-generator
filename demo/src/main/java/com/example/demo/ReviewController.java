package com.example.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Tab "Đánh giá": rà soát chất lượng bộ test case, AI đề xuất tình huống còn thiếu, thêm test case vào phiên. */
@RestController
@RequestMapping("/api/sessions/{id}")
public class ReviewController {
    private static final Logger log = LoggerFactory.getLogger(ReviewController.class);
    /** Số yêu cầu tối đa gửi cho AI trong một lần (giao diện tự chia nhiều lượt). */
    private static final int MAX_REQUIREMENTS_PER_CALL = 10;
    private static final int MAX_ADD = 50;

    private final ReviewService reviewService;
    private final TestCaseService testCaseService;
    private final TestCaseRepository testCaseRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final CurrentUserService currentUserService;
    private final ActivityLogService activityLog;

    public ReviewController(ReviewService reviewService, TestCaseService testCaseService, TestCaseRepository testCaseRepository,
                            ChatSessionRepository chatSessionRepository, CurrentUserService currentUserService,
                            ActivityLogService activityLog) {
        this.reviewService = reviewService;
        this.testCaseService = testCaseService;
        this.testCaseRepository = testCaseRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.currentUserService = currentUserService;
        this.activityLog = activityLog;
    }

    public record SuggestRequest(List<String> requirementIds, String lang) {}

    /** Kiểm tra tự động (xem được cả ở chế độ chia sẻ). */
    @GetMapping("/review")
    public ResponseEntity<?> review(@PathVariable Long id, @AuthenticationPrincipal OAuth2User principal) {
        if (currentUserService.resolve(principal) == null) return error(HttpStatus.UNAUTHORIZED, "unauthorized", "Vui lòng đăng nhập");
        if (!chatSessionRepository.existsById(id)) return error(HttpStatus.NOT_FOUND, "not_found", "Không tìm thấy lịch sử");
        return ResponseEntity.ok(reviewService.review(testCaseService.getRequirementsBySession(id),
                testCaseRepository.findByChatSessionId(id)));
    }

    /** AI đề xuất test case còn thiếu cho các yêu cầu được chọn (chưa lưu). */
    @PostMapping("/review/suggest")
    public ResponseEntity<?> suggest(@PathVariable Long id, @RequestBody SuggestRequest body,
                                     @AuthenticationPrincipal OAuth2User principal) {
        User user = currentUserService.resolve(principal);
        ResponseEntity<?> denied = checkOwner(id, user);
        if (denied != null) return denied;
        List<String> ids = body.requirementIds() == null ? List.of() : body.requirementIds();
        if (ids.isEmpty()) return error(HttpStatus.BAD_REQUEST, "no_requirements", "Hãy chọn ít nhất một yêu cầu");
        if (ids.size() > MAX_REQUIREMENTS_PER_CALL) ids = ids.subList(0, MAX_REQUIREMENTS_PER_CALL);

        List<TestCaseService.Suggestion> suggestions = testCaseService.suggestMissingTests(id, ids,
                body.lang() == null ? "vi" : body.lang());
        activityLog.log(user, "AI_REVIEW", "AI đề xuất " + suggestions.size() + " test case cho " + ids.size() + " yêu cầu");
        return ResponseEntity.ok(Map.of("suggestions", suggestions));
    }

    /** Thêm test case (VD: đề xuất của AI) vào phiên, đánh số TC tiếp nối. */
    @PostMapping("/testcases")
    public ResponseEntity<?> addTestCases(@PathVariable Long id, @RequestBody List<TestCase> items,
                                          @AuthenticationPrincipal OAuth2User principal) {
        User user = currentUserService.resolve(principal);
        ResponseEntity<?> denied = checkOwner(id, user);
        if (denied != null) return denied;
        if (items == null || items.isEmpty()) return error(HttpStatus.BAD_REQUEST, "empty", "Không có test case nào để thêm");
        if (items.size() > MAX_ADD) items = items.subList(0, MAX_ADD);
        List<TestCase> saved = testCaseService.addTestCases(id, items);
        activityLog.log(user, "REVIEW_ADD", "Thêm " + saved.size() + " test case đề xuất vào phiên #" + id);
        return ResponseEntity.ok(saved);
    }

    private ResponseEntity<?> checkOwner(Long sessionId, User user) {
        if (user == null) return error(HttpStatus.UNAUTHORIZED, "unauthorized", "Vui lòng đăng nhập");
        ChatSession session = chatSessionRepository.findById(sessionId).orElse(null);
        if (session == null) return error(HttpStatus.NOT_FOUND, "not_found", "Không tìm thấy lịch sử");
        if (session.getUser() == null || !session.getUser().getId().equals(user.getId())) {
            return error(HttpStatus.FORBIDDEN, "forbidden", "Bạn không có quyền sửa phiên này");
        }
        return null;
    }

    @ExceptionHandler(TestCaseService.AiFormatException.class)
    public ResponseEntity<?> handleAi(TestCaseService.AiFormatException e) {
        return error(HttpStatus.BAD_GATEWAY, "ai_format", e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> handleRuntime(RuntimeException e) {
        log.error("Lỗi rà soát", e);
        String msg = e.getMessage() == null ? "Lỗi hệ thống, vui lòng thử lại." : e.getMessage();
        if (msg.length() > 300) msg = msg.substring(0, 300) + "...";
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "error", msg);
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }
}
