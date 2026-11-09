package com.example.demo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api")
public class TestCaseController {

    @Autowired
    private TestCaseService testCaseService;

    @Autowired
    private TestCaseRepository testCaseRepository;

    @Autowired
    private ChatSessionRepository chatSessionRepository;

    @Autowired
    private UserRepository userRepository;

    @PostMapping("/generate")
    public ResponseEntity<?> generateTestCase(
            @RequestBody String userStory,
            @RequestParam(defaultValue = "vi") String lang,
            @AuthenticationPrincipal OAuth2User principal) {

        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Vui lòng đăng nhập");
        }

        String email = principal.getAttribute("email");
        User currentUser = userRepository.findByEmail(email).orElse(null);

        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Không tìm thấy người dùng");
        }

        GenerateResponse result = testCaseService.generateAndSaveTestCases(userStory, lang, currentUser);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/testcases")
    public List<TestCase> getAllTestCases() {
        return testCaseRepository.findAll();
    }

    @GetMapping("/sessions")
    public ResponseEntity<?> getAllSessions(@AuthenticationPrincipal OAuth2User principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Vui lòng đăng nhập");
        }

        String email = principal.getAttribute("email");
        User currentUser = userRepository.findByEmail(email).orElse(null);

        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Không tìm thấy người dùng");
        }

        List<ChatSession> mySessions = chatSessionRepository.findByUserOrderByIsPinnedDescIdDesc(currentUser);
        return ResponseEntity.ok(mySessions);
    }

    @GetMapping("/sessions/{id}/testcases")
    public List<TestCase> getTestCasesBySession(@PathVariable Long id) {
        return testCaseService.getTestCasesBySession(id);
    }

    @DeleteMapping("/sessions/{id}")
    public void deleteSession(@PathVariable Long id) {
        testCaseService.deleteSession(id);
    }

    @PutMapping("/sessions/{id}/rename")
    public void renameSession(@PathVariable Long id, @RequestBody String newTitle) {
        testCaseService.renameSession(id, newTitle);
    }

    @PutMapping("/sessions/{id}/pin")
    public void togglePinSession(@PathVariable Long id) {
        testCaseService.togglePinSession(id);
    }
    @PutMapping("/testcases")
    public ResponseEntity<?> updateTestCases(@RequestBody List<TestCase> updates) {
        return ResponseEntity.ok(testCaseService.updateTestCases(updates));
    }

    @PutMapping("/testcases/{id}")
    public ResponseEntity<?> updateTestCase(@PathVariable Long id, @RequestBody TestCase updatedTc) {
        return ResponseEntity.ok(testCaseService.updateTestCase(id, updatedTc));
    }

    // =====================================================================
    // TRUY VẾT YÊU CẦU & ĐỘ BAO PHỦ
    // =====================================================================

    /** Ma trận truy vết + độ bao phủ (xem được cả ở chế độ chia sẻ). */
    @GetMapping("/sessions/{id}/traceability")
    public TraceabilityReport getTraceability(@PathVariable Long id) {
        return testCaseService.getTraceability(id);
    }

    @GetMapping("/sessions/{id}/requirements")
    public List<Requirement> getRequirements(@PathVariable Long id) {
        return testCaseService.getRequirementsBySession(id);
    }

    /** AI phân tích lại yêu cầu và ánh xạ test case (dùng cho phiên cũ chưa có yêu cầu). */
    @PostMapping("/sessions/{id}/traceability/analyze")
    public ResponseEntity<?> analyzeTraceability(@PathVariable Long id,
                                                 @RequestParam(defaultValue = "vi") String lang,
                                                 @AuthenticationPrincipal OAuth2User principal) {
        ResponseEntity<?> denied = checkOwner(id, principal);
        if (denied != null) return denied;
        return ResponseEntity.ok(testCaseService.analyzeTraceability(id, lang));
    }

    @PostMapping("/sessions/{id}/requirements")
    public ResponseEntity<?> addRequirement(@PathVariable Long id, @RequestBody Requirement data,
                                            @AuthenticationPrincipal OAuth2User principal) {
        ResponseEntity<?> denied = checkOwner(id, principal);
        if (denied != null) return denied;
        return ResponseEntity.ok(testCaseService.addRequirement(id, data));
    }

    @PutMapping("/requirements/{id}")
    public ResponseEntity<?> updateRequirement(@PathVariable Long id, @RequestBody Requirement data,
                                               @AuthenticationPrincipal OAuth2User principal) {
        ResponseEntity<?> denied = checkOwner(testCaseService.getSessionIdOfRequirement(id), principal);
        if (denied != null) return denied;
        return ResponseEntity.ok(testCaseService.updateRequirement(id, data));
    }

    @DeleteMapping("/requirements/{id}")
    public ResponseEntity<?> deleteRequirement(@PathVariable Long id,
                                               @AuthenticationPrincipal OAuth2User principal) {
        ResponseEntity<?> denied = checkOwner(testCaseService.getSessionIdOfRequirement(id), principal);
        if (denied != null) return denied;
        testCaseService.deleteRequirement(id);
        return ResponseEntity.ok().build();
    }

    /** Trả lỗi dạng {"message": "..."} để giao diện hiển thị thông báo dễ hiểu thay vì "status code 500". */
    @ExceptionHandler(TestCaseService.AiFormatException.class)
    public ResponseEntity<?> handleAiFormat(TestCaseService.AiFormatException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(java.util.Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> handleRuntime(RuntimeException e) {
        String msg = e.getMessage() == null ? "Lỗi hệ thống, vui lòng thử lại." : e.getMessage();
        if (msg.length() > 300) msg = msg.substring(0, 300) + "...";
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(java.util.Map.of("message", msg));
    }

    /** Trả về lỗi nếu người dùng hiện tại không phải chủ phiên; null nếu hợp lệ. */
    private ResponseEntity<?> checkOwner(Long sessionId, OAuth2User principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Vui lòng đăng nhập");
        }
        if (sessionId == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Không tìm thấy dữ liệu");
        }
        ChatSession session = chatSessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Không tìm thấy lịch sử");
        }
        String email = principal.getAttribute("email");
        if (session.getUser() == null || email == null || !email.equals(session.getUser().getEmail())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Bạn không có quyền sửa phiên này");
        }
        return null;
    }
}
