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
    // Địa chỉ API Groq (đổi được để chạy thử với AI giả lập khi kiểm thử)
    @Value("${GROQ_BASE_URL:https://api.groq.com/openai/v1}")
    private String groqBaseUrl;

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

    static String languageInstruction(String lang) {
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
        } catch (AiFormatException e) {
            log.error("AI trả sai định dạng sau nhiều lần thử", e);
            throw e;
        } catch (Exception e) {
            log.error("Lỗi khi sinh test case", e);
            throw new RuntimeException("Lỗi hệ thống: " + e.getMessage(), e);
        }
    }

    // Luôn nhắc AI: không hỏi lại, tự suy luận khi User Story quá ngắn, chỉ trả JSON
    private static final String NO_QUESTION_RULE =
            " QUY TAC BAT BUOC: Neu User Story qua ngan hoac mo ho (VD chi vai tu nhu 'dang xuat'), "
            + "hay tu suy luan cac yeu cau pho bien, hop ly cho chuc nang do. "
            + "TUYET DOI khong hoi lai nguoi dung, khong giai thich, khong viet van ban ngoai JSON.";

    /**
     * Gọi Groq và trả về JSON đã parse.
     * - Bật JSON mode để AI bắt buộc trả JSON hợp lệ.
     * - Nếu AI vẫn trả văn bản thường / JSON hỏng -> tự thử lại 1 lần với lời nhắc mạnh hơn.
     * - Nếu model không hỗ trợ JSON mode -> thử lại không dùng JSON mode.
     */
    JsonNode callGroqForJson(String systemPrompt, String userContent) throws Exception {
        boolean useJsonMode = true;
        Exception lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            String prompt = systemPrompt + NO_QUESTION_RULE
                    + (attempt > 1 ? " Lan truoc ban da tra ve sai dinh dang. CHI TRA VE DUY NHAT MOT OBJECT JSON HOP LE." : "");
            try {
                return parseAiJson(requestGroq(prompt, userContent, useJsonMode));
            } catch (WebClientResponseException e) {
                String body = e.getResponseBodyAsString();
                if (e.getStatusCode().value() == 400 && body.contains("json_validate_failed")) {
                    // JSON mode bật nhưng AI sinh JSON hỏng -> thử lại
                    log.warn("Groq json_validate_failed (lần {}), thử lại", attempt);
                    lastError = e;
                } else if (e.getStatusCode().value() == 400 && useJsonMode && body.contains("response_format")) {
                    // Model không hỗ trợ JSON mode -> tắt đi và thử lại
                    log.warn("Model không hỗ trợ JSON mode, chuyển sang chế độ thường");
                    useJsonMode = false;
                    lastError = e;
                } else {
                    throw e;
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                log.warn("AI trả về không phải JSON (lần {}): {}", attempt, e.getOriginalMessage());
                lastError = e;
            }
        }
        throw new AiFormatException("AI trả về dữ liệu không đúng định dạng. Vui lòng bấm sinh lại "
                + "hoặc mô tả User Story chi tiết hơn.", lastError);
    }

    /** Lỗi do AI trả sai định dạng (để hiện thông báo dễ hiểu cho người dùng). */
    public static class AiFormatException extends RuntimeException {
        public AiFormatException(String message, Throwable cause) { super(message, cause); }
    }

    private String requestGroq(String systemPrompt, String userContent, boolean jsonMode) throws Exception {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userContent)
        ));
        requestBody.put("temperature", 0.2);
        requestBody.put("max_tokens", 8000); // Đủ chỗ cho cả yêu cầu + test case (kể cả model có bước suy luận)
        if (jsonMode) requestBody.put("response_format", Map.of("type", "json_object"));

        String body = mapper.writeValueAsString(requestBody);
        for (int attempt = 1; ; attempt++) {
            try {
                return webClient.post()
                        .uri(groqBaseUrl + "/chat/completions")
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(String.class)
                        // Lỗi máy chủ Groq (5xx) -> thử lại sau 2 giây. Lỗi 400/401/404 thử lại cũng vô ích.
                        .retryWhen(Retry.fixedDelay(3, Duration.ofSeconds(2))
                                .filter(ex -> ex instanceof WebClientResponseException w && w.getStatusCode().is5xxServerError())
                                // Hết lượt thử thì ném lại lỗi gốc, không ném "Retries exhausted" nữa
                                .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                        .block();
            } catch (WebClientResponseException e) {
                if (e.getStatusCode().value() != 429) throw e;
                // Vượt giới hạn token/phút của Groq -> chờ đúng thời gian Groq yêu cầu rồi thử lại
                long wait = retryDelayMs(e) + 500;
                if (attempt >= MAX_RATE_LIMIT_RETRIES || wait > MAX_RATE_LIMIT_WAIT_MS) {
                    log.warn("Groq giới hạn tốc độ, bỏ cuộc sau {} lần (cần chờ {} ms)", attempt, wait);
                    throw new RateLimitException(wait);
                }
                log.warn("Groq giới hạn tốc độ (lần {}), chờ {} ms rồi thử lại", attempt, wait);
                Thread.sleep(wait);
            }
        }
    }

    private static final int MAX_RATE_LIMIT_RETRIES = 4;
    private static final long MAX_RATE_LIMIT_WAIT_MS = 30_000;
    // VD: "Please try again in 832.5ms" / "in 7.66s" / "in 1m30.5s" / "in 2h3m"
    private static final Pattern TRY_AGAIN = Pattern.compile(
            "try again in\\s*(?:(\\d+)h)?(?:(\\d+)m(?!s))?(?:(\\d+(?:\\.\\d+)?)(ms|s))?", Pattern.CASE_INSENSITIVE);

    /** Thời gian Groq yêu cầu chờ (ms): đọc từ thông báo lỗi, nếu không có thì từ header Retry-After. */
    static long retryDelayMs(WebClientResponseException e) {
        long ms = parseTryAgainMs(e.getResponseBodyAsString());
        if (ms > 0) return ms;
        String header = e.getHeaders().getFirst("retry-after");
        try {
            if (header != null) return (long) (Double.parseDouble(header.trim()) * 1000);
        } catch (NumberFormatException ignored) { /* dùng mặc định */ }
        return 5_000;
    }

    static long parseTryAgainMs(String text) {
        if (text == null) return 0;
        Matcher m = TRY_AGAIN.matcher(text);
        if (!m.find()) return 0;
        double ms = 0;
        if (m.group(1) != null) ms += Long.parseLong(m.group(1)) * 3_600_000d;
        if (m.group(2) != null) ms += Long.parseLong(m.group(2)) * 60_000d;
        if (m.group(3) != null) ms += Double.parseDouble(m.group(3)) * ("ms".equalsIgnoreCase(m.group(4)) ? 1 : 1000);
        return (long) Math.ceil(ms);
    }

    /** Groq từ chối vì vượt giới hạn tốc độ (gói miễn phí) dù đã chờ và thử lại. */
    public static class RateLimitException extends RuntimeException {
        private final long retryAfterMs;

        public RateLimitException(long retryAfterMs) {
            super("Groq đang giới hạn tốc độ (gói miễn phí giới hạn số token mỗi phút). Vui lòng thử lại sau khoảng "
                    + Math.max(1, Math.round(retryAfterMs / 1000.0)) + " giây.");
            this.retryAfterMs = retryAfterMs;
        }

        public long getRetryAfterMs() { return retryAfterMs; }
    }

    /** Lấy nội dung trả lời của AI và bóc tách phần JSON (object hoặc mảng). */
    private JsonNode parseAiJson(String rawResponse) throws Exception {
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

    static String firstText(JsonNode n, String... fields) {
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
    // SINH TEST CASE TỪ TÀI LIỆU SRS (từng phần)
    // =====================================================================

    /**
     * Sinh test case cho MỘT phần của tài liệu SRS.
     * sessionId = null -> tạo phiên mới (tên = tên file); ngược lại thêm vào phiên đã có.
     * Mã REQ / TC được đánh số tiếp nối các phần trước để không bị trùng.
     */
    public GenerateResponse generateFromSrsSection(User user, Long sessionId, String fileName, String sectionTitle,
                                                   String content, String sourceText, String lang) {
        String systemPrompt = "Ban la mot Senior QA Engineer. Noi dung nguoi dung gui la MOT PHAN cua tai lieu dac ta yeu cau phan mem (SRS) "
                + "ten \"" + safe(fileName) + "\", muc \"" + safe(sectionTitle) + "\". Nhiem vu: "
                + "(1) Trich xuat cac yeu cau CO THE KIEM THU trong phan nay (chuc nang, quy tac nghiep vu, rang buoc du lieu, phi chuc nang neu co), "
                + "danh ma REQ-01, REQ-02, ...; neu tai lieu co ma yeu cau goc (VD: FR-01, UC-02) thi ghi ma goc o dau mo ta trong ngoac vuong. "
                + "(2) Sinh cac Test Case bao phu TOAN BO cac yeu cau vua trich xuat: moi yeu cau it nhat 1 test case, gom truong hop hop le, "
                + "khong hop le va gia tri bien. Moi test case ghi ro ma yeu cau no kiem tra trong truong requirementIds. "
                + "Neu phan nay khong chua yeu cau nao co the kiem thu (VD: muc luc, gioi thieu chung, lich su phien ban) thi tra ve "
                + "{\"requirements\":[],\"testCases\":[]}. "
                + "Bat buoc chi tra ve MOT object JSON thuan tuy co dang: "
                + "{\"requirements\":[{\"id\":\"REQ-01\",\"description\":\"...\",\"priority\":\"High|Medium|Low\"}],"
                + "\"testCases\":[{\"id\":\"TC-01\",\"summary\":\"...\",\"precondition\":\"...\",\"steps\":[\"...\"],\"expected_result\":\"...\",\"requirementIds\":[\"REQ-01\"]}]}. "
                + "Khong tra ve markdown hoac loi giai thich. "
                + languageInstruction(lang);

        try {
            JsonNode root = callGroqForJson(systemPrompt, "# " + sectionTitle + "\n\n" + content);
            JsonNode reqNode = root.isArray() ? mapper.createArrayNode() : root.path("requirements");
            JsonNode tcNode = root.isArray() ? root : (root.has("testCases") ? root.path("testCases") : root.path("test_cases"));

            List<Requirement> requirements = parseRequirements(reqNode);
            List<TestCase> testCases = new ArrayList<>();
            if (tcNode.isArray()) {
                for (JsonNode n : tcNode) testCases.add(mapper.treeToValue(n, TestCase.class));
            }
            keepOnlyKnownRequirementCodes(testCases, requirements);

            // 1. Lấy / tạo phiên
            ChatSession session;
            if (sessionId == null) {
                session = new ChatSession();
                String title = fileName == null || fileName.isBlank() ? "SRS" : fileName.trim();
                session.setTitle(title.length() > 120 ? title.substring(0, 117) + "..." : title);
                session.setUserStory(sourceText != null ? sourceText : content);
                session.setUser(user);
                session = chatSessionRepository.save(session);
            } else {
                session = chatSessionRepository.findById(sessionId)
                        .orElseThrow(() -> new RuntimeException("Không tìm thấy lịch sử"));
            }

            // 2. Đánh số tiếp nối các phần trước
            int maxReq = 0;
            for (Requirement r : getRequirementsBySession(session.getId())) maxReq = Math.max(maxReq, trailingNumber(r.getCode()));
            int maxTc = 0;
            for (TestCase tc : testCaseRepository.findByChatSessionId(session.getId())) maxTc = Math.max(maxTc, trailingNumber(tc.getId()));

            Map<String, String> codeMap = new HashMap<>();
            for (Requirement r : requirements) {
                String newCode = String.format("REQ-%02d", ++maxReq);
                codeMap.put(r.getCode(), newCode);
                r.setCode(newCode);
                r.setChatSession(session);
            }
            for (TestCase tc : testCases) {
                tc.setId(String.format("TC-%02d", ++maxTc));
                List<String> mapped = new ArrayList<>();
                for (String id : tc.getRequirementIds()) {
                    String m = codeMap.get(id);
                    if (m != null) mapped.add(m);
                }
                tc.setRequirementIds(mapped);
                tc.setChatSession(session);
            }

            List<Requirement> savedReqs = requirementRepository.saveAll(requirements);
            List<TestCase> savedTcs = testCaseRepository.saveAll(testCases);
            return new GenerateResponse(session.getId(), savedReqs, savedTcs);

        } catch (WebClientResponseException e) {
            log.error("Groq từ chối (status {}): {}", e.getStatusCode().value(), e.getResponseBodyAsString(), e);
            throw new RuntimeException("Groq từ chối (" + e.getStatusCode().value() + "): "
                    + e.getResponseBodyAsString(), e);
        } catch (AiFormatException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Lỗi khi sinh test case từ SRS", e);
            throw new RuntimeException("Lỗi hệ thống: " + e.getMessage(), e);
        }
    }

    private static int trailingNumber(String code) {
        if (code == null) return 0;
        Matcher m = REQ_NUMBER.matcher(code);
        try { return m.find() ? Integer.parseInt(m.group(1)) : 0; } catch (NumberFormatException e) { return 0; }
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("\"", "'").replace("\n", " ");
    }

    // =====================================================================
    // RÀ SOÁT: AI ĐỀ XUẤT TÌNH HUỐNG CÒN THIẾU & THÊM TEST CASE VÀO PHIÊN
    // =====================================================================

    /** Một test case AI đề xuất bổ sung (chưa lưu). type: error | boundary | invalid | security | other */
    public record Suggestion(String requirementId, String type, String reason, String summary, String precondition,
                             List<String> steps, String expected_result) {}

    private static final Set<String> SUGGESTION_TYPES = Set.of("error", "boundary", "invalid", "security", "other");

    /**
     * AI đọc từng yêu cầu cùng các test case đã có và đề xuất tình huống còn thiếu
     * (trường hợp lỗi, giá trị biên, dữ liệu không hợp lệ, phân quyền...). Không lưu vào DB.
     */
    public List<Suggestion> suggestMissingTests(Long sessionId, List<String> requirementCodes, String lang) {
        List<Requirement> all = getRequirementsBySession(sessionId);
        Set<String> wanted = new HashSet<>(requirementCodes == null ? List.of() : requirementCodes);
        List<Requirement> targets = all.stream().filter(r -> wanted.contains(r.getCode())).toList();
        if (targets.isEmpty()) return List.of();
        List<TestCase> testCases = testCaseRepository.findByChatSessionId(sessionId);

        String systemPrompt = "Ban la mot Senior QA Engineer dang ra soat bo test case. Voi MOI yeu cau duoc gui, hay doc cac test case DA CO "
                + "va de xuat toi da 3 test case CON THIEU, uu tien: truong hop loi / ngoai le (error), gia tri bien (boundary), "
                + "du lieu khong hop le (invalid), phan quyen / bao mat (security). "
                + "TUYET DOI khong de xuat tinh huong trung hoac gan giong test case da co. Neu yeu cau da duoc kiem thu day du thi khong de xuat. "
                + "Truong reason giai thich ngan gon vi sao can them test case nay. "
                + "Bat buoc chi tra ve MOT object JSON thuan tuy co dang: "
                + "{\"suggestions\":[{\"requirementId\":\"REQ-01\",\"type\":\"error|boundary|invalid|security|other\",\"reason\":\"...\","
                + "\"summary\":\"...\",\"precondition\":\"...\",\"steps\":[\"...\"],\"expected_result\":\"...\"}]}. "
                + "Khong tra ve markdown hoac loi giai thich. "
                + languageInstruction(lang);

        try {
            ObjectNode input = mapper.createObjectNode();
            ArrayNode reqArr = input.putArray("requirements");
            for (Requirement r : targets) {
                ObjectNode o = reqArr.addObject();
                o.put("id", r.getCode());
                o.put("description", r.getDescription());
                o.put("priority", r.getPriority());
                ArrayNode existing = o.putArray("existingTestCases");
                for (TestCase tc : testCases) {
                    if (!tc.getRequirementIds().contains(r.getCode())) continue;
                    ObjectNode t = existing.addObject();
                    t.put("id", tc.getId());
                    t.put("summary", tc.getSummary());
                    t.put("expected_result", tc.getExpected_result());
                }
            }

            JsonNode root = callGroqForJson(systemPrompt, mapper.writeValueAsString(input));
            JsonNode arr = root.isArray() ? root : root.path("suggestions");
            List<Suggestion> result = new ArrayList<>();
            Map<String, Integer> perReq = new HashMap<>();
            for (JsonNode n : arr) {
                String reqId = firstText(n, "requirementId", "requirement_id", "requirement");
                reqId = reqId == null ? "" : reqId.trim().toUpperCase(Locale.ROOT);
                String summary = firstText(n, "summary", "title");
                if (!wanted.contains(reqId) || summary == null || summary.isBlank()) continue;
                if (perReq.merge(reqId, 1, Integer::sum) > 3) continue;
                String type = firstText(n, "type");
                type = type == null ? "other" : type.trim().toLowerCase(Locale.ROOT);
                if (!SUGGESTION_TYPES.contains(type)) type = "other";
                List<String> steps = new ArrayList<>();
                JsonNode st = n.path("steps");
                if (st.isArray()) st.forEach(x -> { if (!x.asText().isBlank()) steps.add(x.asText()); });
                else if (st.isTextual() && !st.asText().isBlank()) steps.add(st.asText());
                result.add(new Suggestion(reqId, type, firstText(n, "reason"), summary.trim(),
                        firstText(n, "precondition"), steps, firstText(n, "expected_result", "expectedResult")));
            }
            return result;
        } catch (WebClientResponseException e) {
            log.error("Groq từ chối (status {}): {}", e.getStatusCode().value(), e.getResponseBodyAsString(), e);
            throw new RuntimeException("Groq từ chối (" + e.getStatusCode().value() + "): " + e.getResponseBodyAsString(), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Lỗi khi AI đề xuất test case", e);
            throw new RuntimeException("Lỗi hệ thống: " + e.getMessage(), e);
        }
    }

    /** Thêm test case vào phiên: đánh số TC tiếp nối, chỉ giữ liên kết tới yêu cầu có trong phiên. */
    public List<TestCase> addTestCases(Long sessionId, List<TestCase> items) {
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy lịch sử"));
        Set<String> known = new HashSet<>();
        getRequirementsBySession(sessionId).forEach(r -> known.add(r.getCode()));
        int maxTc = 0;
        for (TestCase tc : testCaseRepository.findByChatSessionId(sessionId)) maxTc = Math.max(maxTc, trailingNumber(tc.getId()));

        List<TestCase> toSave = new ArrayList<>();
        for (TestCase in : items) {
            if (in.getSummary() == null || in.getSummary().isBlank()) continue;
            TestCase tc = new TestCase();
            tc.setId(String.format("TC-%02d", ++maxTc));
            tc.setSummary(in.getSummary().trim());
            tc.setPrecondition(in.getPrecondition());
            tc.setSteps(in.getSteps() == null ? new ArrayList<>() : new ArrayList<>(in.getSteps()));
            tc.setExpected_result(in.getExpected_result());
            tc.setRequirementIds(in.getRequirementIds().stream().filter(known::contains).toList());
            tc.setChatSession(session);
            toSave.add(tc);
        }
        return testCaseRepository.saveAll(toSave);
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
            String story = session.getUserStory() == null ? "" : session.getUserStory();
            // Tài liệu SRS có thể rất dài -> chỉ gửi phần đầu để không vượt giới hạn của AI
            if (story.length() > 30000) story = story.substring(0, 30000);
            input.put("userStory", story);
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
        return byTcNumber(testCaseRepository.findByChatSessionId(sessionId));
    }

    /** Sắp test case theo số TC (TC-01, TC-02, ...) để test case được khôi phục khi hoàn tác nằm đúng vị trí. */
    static List<TestCase> byTcNumber(List<TestCase> list) {
        List<TestCase> out = new ArrayList<>(list);
        out.sort(Comparator.comparingInt((TestCase tc) -> trailingNumber(tc.getId()))
                .thenComparing(TestCase::getDbId, Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
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

    /** Lưu nhiều test case trong 1 request (thay vì gọi PUT từng cái). */
    public List<TestCase> updateTestCases(List<TestCase> updates) {
        Map<Long, TestCase> byId = new HashMap<>();
        for (TestCase u : updates) if (u.getDbId() != null) byId.put(u.getDbId(), u);
        List<TestCase> existing = testCaseRepository.findAllById(byId.keySet());
        for (TestCase tc : existing) {
            TestCase u = byId.get(tc.getDbId());
            tc.setSummary(u.getSummary());
            tc.setPrecondition(u.getPrecondition());
            tc.setSteps(u.getSteps());
            tc.setExpected_result(u.getExpected_result());
            tc.setRequirementIds(u.getRequirementIds());
        }
        return testCaseRepository.saveAll(existing);
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
