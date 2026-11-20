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
    /** Số test case tối đa gửi AI kiểm tra trong một lần (giao diện tự chia nhiều lượt). */
    private static final int MAX_FIX_PER_CALL = 15;

    private final ReviewService reviewService;
    private final TestCaseService testCaseService;
    private final TestCaseRepository testCaseRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final CurrentUserService currentUserService;
    private final ActivityLogService activityLog;
    private final FixService fixService;

    public ReviewController(ReviewService reviewService, TestCaseService testCaseService, TestCaseRepository testCaseRepository,
                            ChatSessionRepository chatSessionRepository, CurrentUserService currentUserService,
                            ActivityLogService activityLog, FixService fixService) {
        this.reviewService = reviewService;
        this.testCaseService = testCaseService;
        this.testCaseRepository = testCaseRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.currentUserService = currentUserService;
        this.activityLog = activityLog;
        this.fixService = fixService;
    }

    public record SuggestRequest(List<String> requirementIds, String lang) {}
    public record FixScanRequest(List<Long> dbIds, List<Long> excludeDbIds, String lang) {}
    public record FixApplyRequest(List<FixService.ApplyItem> items) {}

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

    // ================== AI RÀ SOÁT & SỬA ==================

    /** Bước 1: tìm test case trùng bằng thuật toán (nhanh, không tốn lượt AI). dbIds = phạm vi (rỗng = cả phiên). */
    @PostMapping("/review/fixes/duplicates")
    public ResponseEntity<?> duplicateFixes(@PathVariable Long id, @RequestBody(required = false) FixScanRequest body,
                                            @AuthenticationPrincipal OAuth2User principal) {
        User user = currentUserService.resolve(principal);
        ResponseEntity<?> denied = checkOwner(id, user);
        if (denied != null) return denied;
        List<Long> scope = body == null || body.dbIds() == null || body.dbIds().isEmpty() ? null : body.dbIds();
        return ResponseEntity.ok(Map.of("fixes", fixService.duplicateFixes(id, scope)));
    }

    /** Bước 2: AI đối chiếu một nhóm test case với tài liệu gốc và đề xuất sửa (chưa lưu). */
    @PostMapping("/review/fixes/ai")
    public ResponseEntity<?> aiFixes(@PathVariable Long id, @RequestBody FixScanRequest body,
                                     @AuthenticationPrincipal OAuth2User principal) {
        User user = currentUserService.resolve(principal);
        ResponseEntity<?> denied = checkOwner(id, user);
        if (denied != null) return denied;
        List<Long> ids = body.dbIds() == null ? List.of() : body.dbIds();
        if (ids.isEmpty()) return error(HttpStatus.BAD_REQUEST, "empty", "Không có test case nào để rà soát");
        if (ids.size() > MAX_FIX_PER_CALL) ids = ids.subList(0, MAX_FIX_PER_CALL);
        List<FixService.Fix> fixes = fixService.aiFixes(id, ids, body.excludeDbIds(), body.lang() == null ? "vi" : body.lang());
        activityLog.log(user, "AI_FIX", "AI rà soát " + ids.size() + " test case, đề xuất " + fixes.size() + " chỗ sửa");
        return ResponseEntity.ok(Map.of("fixes", fixes));
    }

    /** Bước 3: áp dụng các đề xuất người dùng đã chấp nhận (lưu bản sao để hoàn tác). */
    @PostMapping("/review/fixes/apply")
    public ResponseEntity<?> applyFixes(@PathVariable Long id, @RequestBody FixApplyRequest body,
                                        @AuthenticationPrincipal OAuth2User principal) throws Exception {
        User user = currentUserService.resolve(principal);
        ResponseEntity<?> denied = checkOwner(id, user);
        if (denied != null) return denied;
        if (body == null || body.items() == null || body.items().isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "empty", "Không có đề xuất nào để áp dụng");
        }
        FixService.ApplyResult result = fixService.apply(id, body.items());
        activityLog.log(user, "FIX_APPLY", "Áp dụng " + result.applied() + " đề xuất sửa (bỏ qua " + result.skipped() + ") ở phiên #" + id);
        return ResponseEntity.ok(result);
    }

    /** Số lần có thể hoàn tác. */
    @GetMapping("/review/fixes/undo")
    public ResponseEntity<?> undoInfo(@PathVariable Long id, @AuthenticationPrincipal OAuth2User principal) {
        User user = currentUserService.resolve(principal);
        ResponseEntity<?> denied = checkOwner(id, user);
        if (denied != null) return denied;
        return ResponseEntity.ok(Map.of("undoCount", fixService.undoCount(id)));
    }

    /** Hoàn tác lần áp dụng gần nhất. */
    @PostMapping("/review/fixes/undo")
    public ResponseEntity<?> undo(@PathVariable Long id, @AuthenticationPrincipal OAuth2User principal) throws Exception {
        User user = currentUserService.resolve(principal);
        ResponseEntity<?> denied = checkOwner(id, user);
        if (denied != null) return denied;
        FixService.UndoResult result = fixService.undo(id);
        if (result.undone()) activityLog.log(user, "FIX_UNDO", "Hoàn tác đề xuất sửa ở phiên #" + id);
        return ResponseEntity.ok(result);
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
