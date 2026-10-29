package com.example.demo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private final WebClient webClient;
    private final TestCaseRepository testCaseRepository;
    private final ChatSessionRepository chatSessionRepository;

    @Value("${GROQ_API_KEY}")
    private String API_KEY;

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
                "model", "openai/gpt-oss-20b",
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userStory)
                ),
                "temperature", 0.2
        );

        try {
            // 1. TẠO VÀ LƯU LỊCH SỬ (SESSION)
            ChatSession session = new ChatSession();
            String title = userStory.length() > 35 ? userStory.substring(0, 35) + "..." : userStory;
            session.setTitle(title);
            session.setUser(currentUser);
            ChatSession savedSession = chatSessionRepository.save(session);

            // 2. GỌI API GROQ
            ObjectMapper mapper = new ObjectMapper();
            String jsonString = mapper.writeValueAsString(requestBody);

            String rawResponse = webClient.post()
                    .uri("https://api.groq.com/openai/v1/chat/completions")
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                    .bodyValue(jsonString)
                    .retrieve()
                    .bodyToMono(String.class)
                    .retryWhen(Retry.fixedDelay(3, Duration.ofSeconds(2)))
                    .block();

            JsonNode rootNode = mapper.readTree(rawResponse);
            String cleanJsonContent = rootNode.path("choices").get(0).path("message").path("content").asText();

            List<TestCase> testCases = mapper.readValue(cleanJsonContent, new TypeReference<List<TestCase>>() {});

            // 3. GẮN TEST CASE VÀO LỊCH SỬ VỪA TẠO
            for (TestCase tc : testCases) {
                tc.setChatSession(savedSession);
            }

            return testCaseRepository.saveAll(testCases);

        } catch (WebClientResponseException e) {
            throw new RuntimeException("Groq từ chối: " + e.getResponseBodyAsString());
        } catch (Exception e) {
            throw new RuntimeException("Lỗi hệ thống: " + e.getMessage());
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