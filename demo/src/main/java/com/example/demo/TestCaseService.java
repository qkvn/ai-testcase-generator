package com.example.demo;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TestCaseService {

    private static final Logger log = LoggerFactory.getLogger(TestCaseService.class);
    private static final Pattern REQ_NUMBER = Pattern.compile("(\\d+)\\s*$");

    private final WebClient webClient;
    private final TestCaseRepository testCaseRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final RequirementRepository requirementRepository;

    // Bỏ qua trường lạ & chấp nhận chuỗi đơn ở chỗ cần mảng -> chịu được output "lệch chuẩn" của AI
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);

    @Value("${GROQ_API_KEY}")
    private String API_KEY;

    // Tên model đọc từ biến môi trường GROQ_MODEL (đặt trên Render).
    // Nếu không đặt thì dùng giá trị mặc định phía sau dấu ":".
    @Value("${GROQ_MODEL:openai/gpt-oss-120b}")
    private String model;

    public TestCaseService(TestCaseRepository testCaseRepository,
                           ChatSessionRepository chatSessionRepository,
                           RequirementRepository requirementRepository) {
        this.testCaseRepository = testCaseRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.requirementRepository = requirementRepository;
        this.webClient = WebClient.builder().build();
    }

    private static String languageInstruction(String lang) {
        return "en".equalsIgnoreCase(lang)
                ? "BẮT BUỘC TRẢ VỀ KẾT QUẢ BẰNG TIẾNG ANH (ENGLISH)."
                : "BẮT BUỘC TRẢ VỀ KẾT QUẢ BẰNG TIẾNG VIỆT (VIETNAMESE).";
    }

    // =====================================================================
    // SINH TEST CASE + TÁCH YÊU CẦU
    // =====================================================================

    public GenerateResponse generateAndSaveTestCases(String userStory, String lang, User currentUser) {
        String systemPrompt = "Ban la mot Senior QA Engineer. Nhiem vu cua ban: "
                + "(1) Phan tich User Story thanh danh sach yeu cau nguyen tu (chuc nang, rang buoc du lieu, phi chuc nang neu co), danh ma REQ-01, REQ-02, ... "
                + "(2) Sinh cac Test Case bao phu TOAN BO cac yeu cau: moi yeu cau co it nhat 1 test case, gom ca truong hop hop le, khong hop le va gia tri bien. "
                + "Moi test case phai ghi ro cac ma yeu cau ma no kiem tra trong truong requirementIds. "
                + "Bat buoc chi tra ve MOT object JSON thuan tuy co dang: "
                + "{\"requirements\":[{\"id\":\"REQ-01\",\"description\":\"...\",\"priority\":\"High|Medium|Low\"}],"
                + "\"testCases\":[{\"id\":\"TC-01\",\"summary\":\"...\",\"precondition\":\"...\",\"steps\":[\"...\"],\"expected_result\":\"...\",\"requirementIds\":[\"REQ-01\"]}]}. "
                + "Khong tra ve markdown hoac loi giai thich. "
                + languageInstruction(lang);

        try {
            // 1. GỌI AI (gọi trước, thành công mới lưu lịch sử để khỏi tạo phiên rỗng khi lỗi)
            JsonNode root = callGroqForJson(systemPrompt, userStory);

            JsonNode reqNode;
            JsonNode tcNode;
            if (root.isArray()) {
                // Model trả về kiểu cũ (chỉ có mảng test case)
                reqNode = mapper.createArrayNode();
                tcNode = root;
            } else {
                reqNode = root.path("requirements");
                tcNode = root.has("testCases") ? root.path("testCases") : root.path("test_cases");
            }

            List<Requirement> requirements = parseRequirements(reqNode);
            List<TestCase> testCases = new ArrayList<>();
            if (tcNode.isArray()) {
                for (JsonNode n : tcNode) testCases.add(mapper.treeToValue(n, TestCase.class));
            }
            if (testCases.isEmpty()) {
                throw new RuntimeException("AI không trả về test case nào");
            }
            keepOnlyKnownRequirementCodes(testCases, requirements);

            // 2. TẠO VÀ LƯU LỊCH SỬ (SESSION)
            ChatSession session = new ChatSession();
            String title = userStory.length() > 35 ? userStory.substring(0, 35) + "..." : userStory;
            session.setTitle(title);
            session.setUserStory(userStory);
            session.setUser(currentUser);
            ChatSession savedSession = chatSessionRepository.save(session);

            // 3. GẮN YÊU CẦU + TEST CASE VÀO LỊCH SỬ VỪA TẠO
            requirements.forEach(r -> r.setChatSession(savedSession));
            testCases.forEach(tc -> tc.setChatSession(savedSession));

            List<Requirement> savedReqs = requirementRepository.saveAll(requirements);
            List<TestCase> savedTcs = testCaseRepository.saveAll(testCases);
            return new GenerateResponse(savedSession.getId(), savedReqs, savedTcs);

        } catch (WebClientResponseException e) {
            log.error("Groq từ chối (status {}): {}", e.getStatusCode().value(), e.getResponseBodyAsString(), e);
            throw new RuntimeException("Groq từ chối (" + e.getStatusCode().value() + "): "
                    + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            log.error("Lỗi khi sinh test case", e);
            throw new RuntimeException("Lỗi hệ thống: " + e.getMessage(), e);
        }
    }

    /** Gọi Groq, lấy nội dung trả lời và bóc tách phần JSON (object hoặc mảng). */
    private JsonNode callGroqForJson(String systemPrompt, String userContent) throws Exception {
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userContent)
                ),
                "temperature", 0.2,
                "max_tokens", 8000 // Đủ chỗ cho cả yêu cầu + test case (kể cả model có bước suy luận)
        );

        String rawResponse = webClient.post()
                .uri("https://api.groq.com/openai/v1/chat/completions")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                .bodyValue(mapper.writeValueAsString(requestBody))
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
        String content = rootNode.path("choices").get(0).path("message").path("content").asText();

        // THUẬT TOÁN DỌN DẸP DỮ LIỆU JSON
        content = content.replace("```json", "").replace("```", "").trim();
        int firstObj = content.indexOf('{');
        int firstArr = content.indexOf('[');
        boolean objectFirst = firstObj != -1 && (firstArr == -1 || firstObj < firstArr);
        int start = objectFirst ? firstObj : firstArr;
        int end = objectFirst ? content.lastIndexOf('}') : content.lastIndexOf(']');
        if (start != -1 && end > start) {
            content = content.substring(start, end + 1);
        }
        return mapper.readTree(content);
    }

    private List<Requirement> parseRequirements(JsonNode reqNode) {
        List<Requirement> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (reqNode == null || !reqNode.isArray()) return result;
        int counter = 1;
        for (JsonNode n : reqNode) {
            String code = firstText(n, "id", "code");
            code = (code == null || code.isBlank()) ? String.format("REQ-%02d", counter) : code.trim().toUpperCase(Locale.ROOT);
            counter++;
            if (!seen.add(code)) continue;
            Requirement r = new Requirement();
            r.setCode(code);
            r.setDescription(n.isTextual() ? n.asText() : firstText(n, "description", "summary", "name"));
            r.setPriority(normalizePriority(firstText(n, "priority")));
            result.add(r);
        }
        return result;
    }

    private static String firstText(JsonNode n, String... fields) {
        for (String f : fields) {
            JsonNode v = n.get(f);
            if (v != null && !v.isNull()) return v.asText();
        }
        return null;
    }

    private static String normalizePriority(String p) {
        if (p == null) return "Medium";
        String s = p.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("h") || s.startsWith("cao")) return "High";
        if (s.startsWith("l") || s.startsWith("th")) return "Low";
        return "Medium";
    }

    /** Bỏ các mã yêu cầu mà AI "bịa" ra (không có trong danh sách yêu cầu). */
    private static void keepOnlyKnownRequirementCodes(List<TestCase> testCases, List<Requirement> requirements) {
        if (requirements.isEmpty()) return;
        Set<String> known = new HashSet<>();
        requirements.forEach(r -> known.add(r.getCode()));
        for (TestCase tc : testCases) {
            List<String> ids = tc.getRequirementIds();
            ids.removeIf(id -> !known.contains(id));
            tc.setRequirementIds(ids);
        }
    }

    // =====================================================================
    // TRUY VẾT YÊU CẦU & ĐỘ BAO PHỦ
    // =====================================================================

    public List<Requirement> getRequirementsBySession(Long sessionId) {
        return requirementRepository.findByChatSessionIdOrderByDbIdAsc(sessionId);
    }

    /** Tính ma trận truy vết và độ bao phủ yêu cầu của một phiên. */
    public TraceabilityReport getTraceability(Long sessionId) {
        List<Requirement> requirements = getRequirementsBySession(sessionId);
        List<TestCase> testCases = testCaseRepository.findByChatSessionId(sessionId);

        Map<String, List<String>> tcByReq = new LinkedHashMap<>();
        requirements.forEach(r -> tcByReq.put(r.getCode(), new ArrayList<>()));

        List<String> orphans = new ArrayList<>();
        int linked = 0;
        for (TestCase tc : testCases) {
            boolean hasValidLink = false;
            for (String code : tc.getRequirementIds()) {
                List<String> list = tcByReq.get(code);
                if (list != null) {
                    list.add(tc.getId());
                    hasValidLink = true;
                }
            }
            if (hasValidLink) linked++;
            else orphans.add(tc.getId());
        }

        List<TraceabilityReport.RequirementCoverage> rows = new ArrayList<>();
        List<String> uncovered = new ArrayList<>();
        int covered = 0;
        for (Requirement r : requirements) {
            List<String> tcs = tcByReq.get(r.getCode());
            boolean isCovered = !tcs.isEmpty();
            if (isCovered) covered++;
            else uncovered.add(r.getCode());
            rows.add(new TraceabilityReport.RequirementCoverage(
                    r.getDbId(), r.getCode(), r.getDescription(), r.getPriority(), tcs, isCovered));
        }

        double percent = requirements.isEmpty() ? 0 : Math.round(covered * 1000.0 / requirements.size()) / 10.0;
        return new TraceabilityReport(sessionId, requirements.size(), covered, percent,
                testCases.size(), linked, rows, uncovered, orphans);
    }

    /**
     * Dùng AI phân tích lại: tách yêu cầu (từ User Story gốc hoặc suy ra từ test case)
     * và ánh xạ test case hiện có vào yêu cầu. Thay thế toàn bộ yêu cầu cũ của phiên.
     */
    public TraceabilityReport analyzeTraceability(Long sessionId, String lang) {
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy lịch sử"));
        List<TestCase> testCases = testCaseRepository.findByChatSessionId(sessionId);
        if (testCases.isEmpty()) {
            throw new RuntimeException("Phiên này chưa có test case để phân tích");
        }

        String systemPrompt = "Ban la mot Senior QA Engineer chuyen ve truy vet yeu cau (Requirement Traceability). "
                + "Dau vao gom User Story (co the trong) va danh sach Test Case da co. "
                + "Hay: (1) xac dinh danh sach yeu cau nguyen tu cua chuc nang, danh ma REQ-01, REQ-02, ... "
                + "Neu User Story trong thi suy ra yeu cau tu cac test case. Duoc phep liet ke ca yeu cau chua co test case nao kiem tra. "
                + "(2) Anh xa moi test case toi cac yeu cau ma no kiem tra. "
                + "Bat buoc chi tra ve MOT object JSON thuan tuy co dang: "
                + "{\"requirements\":[{\"id\":\"REQ-01\",\"description\":\"...\",\"priority\":\"High|Medium|Low\"}],"
                + "\"mapping\":[{\"testCaseId\":\"TC-01\",\"requirementIds\":[\"REQ-01\"]}]}. "
                + "Khong tra ve markdown hoac loi giai thich. "
                + languageInstruction(lang);

        try {
            ObjectNode input = mapper.createObjectNode();
            input.put("userStory", session.getUserStory() == null ? "" : session.getUserStory());
            ArrayNode tcArr = input.putArray("testCases");
            for (TestCase tc : testCases) {
                ObjectNode o = tcArr.addObject();
                o.put("id", tc.getId());
                o.put("summary", tc.getSummary());
                o.put("expected_result", tc.getExpected_result());
            }

            JsonNode root = callGroqForJson(systemPrompt, mapper.writeValueAsString(input));
            List<Requirement> requirements = parseRequirements(root.path("requirements"));
            if (requirements.isEmpty()) {
                throw new RuntimeException("AI không xác định được yêu cầu nào");
            }

            Map<String, List<String>> mapping = new HashMap<>();
            for (JsonNode m : root.path("mapping")) {
                String tcId = firstText(m, "testCaseId", "id", "test_case_id");
                if (tcId == null) continue;
                List<String> ids = new ArrayList<>();
                JsonNode reqIds = m.has("requirementIds") ? m.get("requirementIds") : m.path("requirement_ids");
                if (reqIds.isArray()) reqIds.forEach(x -> ids.add(x.asText()));
                else if (reqIds.isTextual()) ids.add(reqIds.asText());
                mapping.put(tcId.trim(), ids);
            }

            // Thay thế yêu cầu cũ bằng yêu cầu mới
            requirementRepository.deleteAll(getRequirementsBySession(sessionId));
            requirements.forEach(r -> r.setChatSession(session));
            requirementRepository.saveAll(requirements);

            for (TestCase tc : testCases) {
                tc.setRequirementIds(mapping.getOrDefault(tc.getId() == null ? "" : tc.getId().trim(), List.of()));
            }
            keepOnlyKnownRequirementCodes(testCases, requirements);
            testCaseRepository.saveAll(testCases);

            return getTraceability(sessionId);

        } catch (WebClientResponseException e) {
            log.error("Groq từ chối (status {}): {}", e.getStatusCode().value(), e.getResponseBodyAsString(), e);
            throw new RuntimeException("Groq từ chối (" + e.getStatusCode().value() + "): "
                    + e.getResponseBodyAsString(), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Lỗi khi phân tích truy vết", e);
            throw new RuntimeException("Lỗi hệ thống: " + e.getMessage(), e);
        }
    }

    public Requirement addRequirement(Long sessionId, Requirement data) {
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy lịch sử"));
        // Sinh mã kế tiếp: REQ-(số lớn nhất + 1)
        int max = 0;
        for (Requirement r : getRequirementsBySession(sessionId)) {
            Matcher m = REQ_NUMBER.matcher(r.getCode() == null ? "" : r.getCode());
            if (m.find()) max = Math.max(max, Integer.parseInt(m.group(1)));
        }
        Requirement r = new Requirement();
        r.setCode(String.format("REQ-%02d", max + 1));
        r.setDescription(data.getDescription());
        r.setPriority(normalizePriority(data.getPriority()));
        r.setChatSession(session);
        return requirementRepository.save(r);
    }

    public Requirement updateRequirement(Long id, Requirement data) {
        Requirement existing = requirementRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy yêu cầu"));
        if (data.getDescription() != null) existing.setDescription(data.getDescription());
        if (data.getPriority() != null) existing.setPriority(normalizePriority(data.getPriority()));
        return requirementRepository.save(existing);
    }

    /** Xóa yêu cầu và gỡ liên kết của nó khỏi các test case cùng phiên. */
    public void deleteRequirement(Long id) {
        Requirement existing = requirementRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy yêu cầu"));
        Long sessionId = existing.getChatSession().getId();
        String code = existing.getCode();
        List<TestCase> affected = new ArrayList<>();
        for (TestCase tc : testCaseRepository.findByChatSessionId(sessionId)) {
            List<String> ids = tc.getRequirementIds();
            if (ids.remove(code)) {
                tc.setRequirementIds(ids);
                affected.add(tc);
            }
        }
        testCaseRepository.saveAll(affected);
        requirementRepository.delete(existing);
    }

    public Long getSessionIdOfRequirement(Long requirementId) {
        return requirementRepository.findById(requirementId)
                .map(r -> r.getChatSession().getId())
                .orElse(null);
    }

    // =====================================================================
    // QUẢN LÝ PHIÊN & TEST CASE
    // =====================================================================

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
        existing.setRequirementIds(updatedData.getRequirementIds());
        return testCaseRepository.save(existing);
    }
}
