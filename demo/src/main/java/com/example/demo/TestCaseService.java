package com.example.demo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class TestCaseService {

    private static final Logger log = LoggerFactory.getLogger(TestCaseService.class);

    private final WebClient webClient;
    private final TestCaseRepository testCaseRepository;
    private final ChatSessionRepository chatSessionRepository;

    @Value("${GROQ_API_KEY}")
    private String API_KEY;

    // Tên model đọc từ biến môi trường GROQ_MODEL (đặt trên Render).
    // Nếu không đặt thì dùng giá trị mặc định phía sau dấu ":".
    @Value("${GROQ_MODEL:openai/gpt-oss-120b}")
    private String model;

    public TestCaseService(TestCaseRepository testCaseRepository, ChatSessionRepository chatSessionRepository) {
        this.testCaseRepository = testCaseRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.webClient = WebClient.builder().build();
    }

    public List<TestCase> generateAndSaveTestCases(String userStory, String lang, User currentUser) {
        String languageInstruction = lang.equalsIgnoreCase("en")
                ? "BẮT BUỘC TRẢ VỀ KẾT QUẢ BẰNG TIẾNG ANH (ENGLISH)."
                : "BẮT BUỘC TRẢ VỀ KẾT QUẢ BẰNG TIẾNG VIỆT (VIETNAMESE).";

        String systemPrompt = "Ban la mot Senior QA Engineer. Doc User Story va sinh ra cac Test Case. "
                + "Bat buoc chi tra ve mang JSON thuan tuy gom cac object co 5 truong: id, summary, precondition, steps (mang chuoi), expected_result. "
                + "Khong tra ve markdown hoac loi giai thich. "
                + languageInstruction;

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userStory)
                ),
                "temperature", 0.2,
                "max_tokens", 6000 // Đủ chỗ để không bị đứt giữa chừng (kể cả model có bước suy luận)
        );

        try {
            // 1. GỌI API GROQ (gọi trước, thành công mới lưu lịch sử để khỏi tạo phiên rỗng khi lỗi)
            ObjectMapper mapper = new ObjectMapper();
            String jsonString = mapper.writeValueAsString(requestBody);

            String rawResponse = webClient.post()
                    .uri("https://api.groq.com/openai/v1/chat/completions")
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                    .bodyValue(jsonString)
                    .retrieve()
                    .bodyToMono(String.class)
                    // Chỉ thử lại khi lỗi tạm thời (429 hoặc 5xx). Lỗi 400/401/404 thử lại cũng vô ích.
                    .retryWhen(Retry.fixedDelay(3, Duration.ofSeconds(2))
                            .filter(ex -> ex instanceof WebClientResponseException w
                                    && (w.getStatusCode().value() == 429 || w.getStatusCode().is5xxServerError()))
                            // Hết lượt thử thì ném lại lỗi gốc, không ném "Retries exhausted" nữa
                            .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                    .block();

            JsonNode rootNode = mapper.readTree(rawResponse);
            String cleanJsonContent = rootNode.path("choices").get(0).path("message").path("content").asText();

            // 2. THUẬT TOÁN DỌN DẸP DỮ LIỆU JSON
            // Lọc bỏ ký tự markdown thừa
            cleanJsonContent = cleanJsonContent.replace("```json", "").replace("```", "").trim();
            // Gắp chính xác nội dung mảng JSON từ dấu [ đến dấu ]
            int firstBracket = cleanJsonContent.indexOf("[");
            int lastBracket = cleanJsonContent.lastIndexOf("]");
            if (firstBracket != -1 && lastBracket != -1) {
                cleanJsonContent = cleanJsonContent.substring(firstBracket, lastBracket + 1);
            }

            List<TestCase> testCases = mapper.readValue(cleanJsonContent, new TypeReference<List<TestCase>>() {});

            // 3. TẠO VÀ LƯU LỊCH SỬ (SESSION)
            ChatSession session = new ChatSession();
            String title = userStory.length() > 35 ? userStory.substring(0, 35) + "..." : userStory;
            session.setTitle(title);
            session.setUser(currentUser);
            ChatSession savedSession = chatSessionRepository.save(session);

            // 4. GẮN TEST CASE VÀO LỊCH SỬ VỪA TẠO
            for (TestCase tc : testCases) {
                tc.setChatSession(savedSession);
            }

            return testCaseRepository.saveAll(testCases);

        } catch (WebClientResponseException e) {
            log.error("Groq từ chối (status {}): {}", e.getStatusCode().value(), e.getResponseBodyAsString(), e);
            throw new RuntimeException("Groq từ chối (" + e.getStatusCode().value() + "): "
                    + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            log.error("Lỗi khi sinh test case", e);
            throw new RuntimeException("Lỗi hệ thống: " + e.getMessage(), e);
        }
    }

    public List<ChatSession> getAllChatSessions() {
        return chatSessionRepository.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id"));
    }

    public List<TestCase> getTestCasesBySession(Long sessionId) {
        return testCaseRepository.findByChatSessionId(sessionId);
    }

    public void deleteSession(Long sessionId) {
        chatSessionRepository.deleteById(sessionId);
    }

    public void renameSession(Long sessionId, String newTitle) {
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy lịch sử"));
        session.setTitle(newTitle);
        chatSessionRepository.save(session);
    }

    public void togglePinSession(Long sessionId) {
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy lịch sử"));
        session.setPinned(!session.isPinned());
        chatSessionRepository.save(session);
    }

    public TestCase updateTestCase(Long id, TestCase updatedData) {
        TestCase existing = testCaseRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy Test Case"));
        existing.setSummary(updatedData.getSummary());
        existing.setPrecondition(updatedData.getPrecondition());
        existing.setSteps(updatedData.getSteps());
        existing.setExpected_result(updatedData.getExpected_result());
        return testCaseRepository.save(existing);
    }
}