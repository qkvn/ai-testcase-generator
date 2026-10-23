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

        List<TestCase> testCases = testCaseService.generateAndSaveTestCases(userStory, lang, currentUser);
        return ResponseEntity.ok(testCases);
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
    @PutMapping("/testcases/{id}")
    public ResponseEntity<?> updateTestCase(@PathVariable Long id, @RequestBody TestCase updatedTc) {
        return ResponseEntity.ok(testCaseService.updateTestCase(id, updatedTc));
    }
}