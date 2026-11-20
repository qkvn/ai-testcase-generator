package com.example.demo;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "AI rà soát & sửa": tìm test case trùng / sai rồi ĐỀ XUẤT sửa (người dùng duyệt mới áp dụng).
 *  1. Trùng lặp: so độ giống nội dung bằng thuật toán (không tốn lượt AI) trên toàn bộ phiên.
 *  2. Sai / mơ hồ: AI đối chiếu từng test case với đoạn tài liệu gốc (SRS / User Story).
 *  3. Áp dụng: lưu bản sao trước khi sửa để có thể hoàn tác.
 */
@Service
public class FixService {

    private static final Logger log = LoggerFactory.getLogger(FixService.class);

    /** Độ giống nội dung (Jaccard theo từ) từ ngưỡng này trở lên thì coi là trùng. */
    static final double SIMILAR_THRESHOLD = 0.85;
    /** Gần như giống hệt -> độ tin cậy cao. */
    static final double NEAR_IDENTICAL = 0.97;
    /** Tóm tắt cũng phải gần giống (tránh gộp nhầm "FR-01 hợp lệ" với "FR-02 hợp lệ" có các bước giống nhau). */
    static final double SUMMARY_THRESHOLD = 0.8;
    static final int MAX_EXCERPT = 4000; // giữ mỗi lượt gọi AI nhỏ (gói Groq miễn phí: 8.000 token/phút)
    private static final int MAX_SNAPSHOTS = 10;
    private static final Set<String> PROBLEMS = Set.of("wrong", "vague", "steps", "link", "other");
    private static final Pattern TC_NUMBER = Pattern.compile("(\\d+)\\s*$");

    private final TestCaseService testCaseService;
    private final TestCaseRepository testCaseRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ReviewSnapshotRepository snapshotRepository;

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);

    public FixService(TestCaseService testCaseService, TestCaseRepository testCaseRepository,
                      ChatSessionRepository chatSessionRepository, ReviewSnapshotRepository snapshotRepository) {
        this.testCaseService = testCaseService;
        this.testCaseRepository = testCaseRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.snapshotRepository = snapshotRepository;
    }

    // =====================================================================
    // KIỂU DỮ LIỆU
    // =====================================================================

    /** Nội dung có thể sửa của một test case. */
    public record Fields(String summary, String precondition, List<String> steps, String expected_result,
                         List<String> requirementIds) {
        static Fields of(TestCase tc) {
            return new Fields(tc.getSummary(), tc.getPrecondition(), cleanSteps(tc.getSteps()),
                    tc.getExpected_result(), tc.getRequirementIds());
        }

        /** So sánh bỏ qua khoảng trắng thừa / null (để phát hiện test case đã bị sửa tay). */
        boolean sameAs(Fields o) {
            return sameContent(o) && sameLinks(o);
        }

        /** Chỉ so nội dung (tóm tắt, điều kiện, các bước, kết quả), không so liên kết yêu cầu. */
        boolean sameContent(Fields o) {
            return o != null && norm(summary).equals(norm(o.summary)) && norm(precondition).equals(norm(o.precondition))
                    && cleanSteps(steps).stream().map(Fields::norm).toList()
                       .equals(cleanSteps(o.steps).stream().map(Fields::norm).toList())
                    && norm(expected_result).equals(norm(o.expected_result));
        }

        boolean sameLinks(Fields o) {
            return o != null && new ArrayList<>(requirementIds == null ? List.of() : requirementIds)
                    .equals(new ArrayList<>(o.requirementIds == null ? List.of() : o.requirementIds));
        }

        private static String norm(String s) {
            return s == null ? "" : s.trim().replaceAll("\\s+", " ");
        }
    }

    /**
     * Một đề xuất sửa.
     * kind: "edit" (sửa nội dung) | "merge" (gộp test case trùng: giữ dbId, xóa removeDbIds, gom liên kết yêu cầu).
     * source: "rule" (thuật toán) | "ai". confidence: high | medium | low.
     * problem: wrong | vague | steps | link | other | duplicate.
     * evidenceVerified: câu trích dẫn của AI có thật sự nằm trong tài liệu không (null nếu không có trích dẫn).
     */
    public record Fix(String key, String kind, String source, String confidence, String problem, String reason,
                      String evidence, Boolean evidenceVerified, Long dbId, String tcId, Fields before, Fields after,
                      List<Long> removeDbIds, List<String> removeTcIds, Integer similarity) {}

    /** Một đề xuất người dùng đã chấp nhận (gửi từ giao diện). */
    public record ApplyItem(String kind, Long dbId, Fields before, Fields after, List<Long> removeDbIds) {}

    public record ApplyResult(int applied, int skipped, List<Long> skippedDbIds, int undoCount, List<TestCase> testCases) {}

    public record UndoResult(boolean undone, String summary, int undoCount, List<TestCase> testCases) {}

    /** Dữ liệu bản sao để hoàn tác. */
    record SnapTc(Long dbId, String id, Fields fields) {}

    record Snapshot(List<SnapTc> edited, List<SnapTc> removed) {}

    // =====================================================================
    // 1. TRÙNG LẶP (THUẬT TOÁN)
    // =====================================================================

    /** Đề xuất gộp các test case trùng trong phiên. scope: chỉ lấy nhóm có ít nhất 1 test case trong scope (null = tất cả). */
    public List<Fix> duplicateFixes(Long sessionId, Collection<Long> scope) {
        return duplicateFixes(testCaseRepository.findByChatSessionId(sessionId), scope);
    }

    static List<Fix> duplicateFixes(List<TestCase> all, Collection<Long> scope) {
        int n = all.size();
        List<Set<String>> tokens = all.stream().map(FixService::tokens).toList();
        List<Set<String>> summaryTokens = all.stream().map(tc -> words(tc.getSummary())).toList();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        double[] minSim = new double[n];
        Arrays.fill(minSim, 1.0);
        List<double[]> edges = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (tokens.get(i).isEmpty()) continue;
            for (int j = i + 1; j < n; j++) {
                double s = jaccard(tokens.get(i), tokens.get(j));
                if (s >= SIMILAR_THRESHOLD && jaccard(summaryTokens.get(i), summaryTokens.get(j)) >= SUMMARY_THRESHOLD) {
                    union(parent, i, j);
                    edges.add(new double[]{i, j, s});
                }
            }
        }
        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        Map<Integer, Double> groupMin = new HashMap<>();
        for (double[] e : edges) groupMin.merge(find(parent, (int) e[0]), e[2], Math::min);

        Set<Long> scopeSet = scope == null ? null : new HashSet<>(scope);
        List<Fix> result = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> g : groups.entrySet()) {
            List<TestCase> members = g.getValue().stream().map(all::get).toList();
            if (members.size() < 2) continue;
            if (scopeSet != null && members.stream().noneMatch(tc -> scopeSet.contains(tc.getDbId()))) continue;

            // Giữ test case đầy đủ nhất; bằng nhau thì giữ cái có mã nhỏ hơn (tạo trước)
            TestCase keep = members.stream().max(Comparator.comparingInt(FixService::completeness)
                    .thenComparing(tc -> -tcNumber(tc.getId()))).orElseThrow();
            List<TestCase> removes = members.stream().filter(tc -> tc != keep).toList();
            double sim = groupMin.getOrDefault(g.getKey(), 1.0);
            result.add(mergeFix("rule", sim >= NEAR_IDENTICAL ? "high" : "medium", null, keep, removes,
                    (int) Math.round(sim * 100)));
        }
        return result;
    }

    private static Fix mergeFix(String source, String confidence, String reason, TestCase keep, List<TestCase> removes,
                                Integer similarity) {
        LinkedHashSet<String> reqs = new LinkedHashSet<>(keep.getRequirementIds());
        removes.forEach(r -> reqs.addAll(r.getRequirementIds()));
        Fields before = Fields.of(keep);
        Fields after = new Fields(before.summary(), before.precondition(), before.steps(), before.expected_result(),
                new ArrayList<>(reqs));
        return new Fix("merge-" + keep.getDbId(), "merge", source, confidence, "duplicate", reason, null, null,
                keep.getDbId(), keep.getId(), before, after,
                removes.stream().map(TestCase::getDbId).toList(), removes.stream().map(TestCase::getId).toList(),
                similarity);
    }

    /** Bộ từ (đã bỏ dấu, chữ thường) của toàn bộ nội dung test case. */
    static Set<String> tokens(TestCase tc) {
        StringBuilder sb = new StringBuilder();
        for (String part : new String[]{tc.getSummary(), tc.getPrecondition(), tc.getExpected_result()}) {
            if (part != null) sb.append(part).append(' ');
        }
        cleanSteps(tc.getSteps()).forEach(s -> sb.append(s).append(' '));
        return words(sb.toString());
    }

    static Set<String> words(String s) {
        Set<String> set = new HashSet<>();
        for (String t : ReviewService.normalizeSummary(s).split(" ")) if (!t.isBlank()) set.add(t);
        return set;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1;
        int inter = 0;
        for (String x : a) if (b.contains(x)) inter++;
        return (double) inter / (a.size() + b.size() - inter);
    }

    private static int completeness(TestCase tc) {
        int score = cleanSteps(tc.getSteps()).size() * 2;
        if (tc.getExpected_result() != null && !tc.getExpected_result().isBlank()) score += 3;
        if (tc.getPrecondition() != null && !tc.getPrecondition().isBlank()) score += 1;
        return score;
    }

    private static int find(int[] p, int i) {
        while (p[i] != i) { p[i] = p[p[i]]; i = p[i]; }
        return i;
    }

    private static void union(int[] p, int a, int b) {
        int ra = find(p, a), rb = find(p, b);
        if (ra != rb) p[Math.max(ra, rb)] = Math.min(ra, rb);
    }

    // =====================================================================
    // 2. AI ĐỐI CHIẾU VỚI TÀI LIỆU GỐC
    // =====================================================================

    /**
     * AI kiểm tra các test case (dbIds) so với tài liệu gốc của phiên.
     * excludeDbIds: test case sẽ bị xóa do gộp trùng -> bỏ qua.
     */
    public List<Fix> aiFixes(Long sessionId, List<Long> dbIds, Collection<Long> excludeDbIds, String lang) {
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy lịch sử"));
        Set<Long> wanted = new HashSet<>(dbIds == null ? List.of() : dbIds);
        Set<Long> excluded = new HashSet<>(excludeDbIds == null ? List.of() : excludeDbIds);
        List<TestCase> chunk = testCaseRepository.findByChatSessionId(sessionId).stream()
                .filter(tc -> wanted.contains(tc.getDbId()) && !excluded.contains(tc.getDbId())).toList();
        if (chunk.isEmpty()) return List.of();

        List<Requirement> requirements = testCaseService.getRequirementsBySession(sessionId);
        Set<String> knownCodes = new HashSet<>();
        requirements.forEach(r -> knownCodes.add(r.getCode()));
        String source = session.getUserStory() == null ? "" : session.getUserStory();

        // Yêu cầu liên quan tới các test case cần kiểm tra (để AI phát hiện gắn sai mã thì gửi tối đa 40 yêu cầu)
        Set<String> linked = new HashSet<>();
        chunk.forEach(tc -> linked.addAll(tc.getRequirementIds()));
        List<Requirement> reqsToSend = requirements.size() <= 40 ? requirements
                : requirements.stream().filter(r -> linked.contains(r.getCode())).toList();

        StringBuilder query = new StringBuilder();
        reqsToSend.stream().filter(r -> linked.contains(r.getCode())).forEach(r -> query.append(r.getDescription()).append('\n'));
        chunk.forEach(tc -> query.append(tc.getSummary()).append(' ').append(tc.getExpected_result()).append('\n'));
        String excerpt = relevantExcerpt(source, query.toString(), MAX_EXCERPT);

        String systemPrompt = "Ban la mot Senior QA Engineer dang KIEM TRA LAI cac test case. Doi chieu TUNG test case voi "
                + "trich doan tai lieu goc (sourceExcerpt) va mo ta yeu cau (requirements). "
                + "CHI bao cao khi test case THUC SU co van de thuoc mot trong cac loai: "
                + "wrong = sai so voi tai lieu (gia tri, gioi han, quy tac nghiep vu, thong bao, luong xu ly khac tai lieu); "
                + "vague = ket qua mong doi mo ho, khong kiem chung duoc (VD 'he thong hoat dong binh thuong'); "
                + "steps = cac buoc thieu hoac sai thu tu nen khong thuc hien duoc; "
                + "link = gan sai ma yeu cau (requirementIds); "
                + "duplicate = hai hay nhieu test case trung y nghia (cung dieu kien, cung du lieu, cung ket qua). "
                + "KHONG sua van phong, KHONG doi y nghia test case, KHONG them tinh nang khong co trong tai lieu. "
                + "Neu tat ca test case deu dung thi tra ve {\"fixes\":[]}. "
                + "Voi loai edit: chi dua vao 'changes' nhung truong CAN SUA, gia tri moi la noi dung DAY DU cua truong do. "
                + "'evidence' la cau trich NGUYEN VAN tu sourceExcerpt lam can cu (de chuoi rong neu khong co). "
                + "'confidence': high chi khi tai lieu noi ro rang; medium khi hop ly nhung tai lieu khong noi ro; low khi chi la phong doan. "
                + "'reason' giai thich ngan gon van de. "
                + "Bat buoc chi tra ve MOT object JSON thuan tuy co dang: "
                + "{\"fixes\":[{\"type\":\"edit\",\"id\":\"TC-03\",\"problem\":\"wrong|vague|steps|link\",\"reason\":\"...\","
                + "\"evidence\":\"...\",\"confidence\":\"high|medium|low\",\"changes\":{\"summary\":\"...\",\"precondition\":\"...\","
                + "\"steps\":[\"...\"],\"expected_result\":\"...\",\"requirementIds\":[\"REQ-01\"]}},"
                + "{\"type\":\"duplicate\",\"ids\":[\"TC-03\",\"TC-07\"],\"keep\":\"TC-03\",\"reason\":\"...\",\"confidence\":\"high|medium|low\"}]}. "
                + "Khong tra ve markdown hoac loi giai thich. "
                + TestCaseService.languageInstruction(lang);

        try {
            ObjectNode input = mapper.createObjectNode();
            input.put("sourceExcerpt", excerpt);
            ArrayNode reqArr = input.putArray("requirements");
            for (Requirement r : reqsToSend) {
                ObjectNode o = reqArr.addObject();
                o.put("id", r.getCode());
                o.put("description", r.getDescription());
            }
            ArrayNode tcArr = input.putArray("testCases");
            for (TestCase tc : chunk) {
                ObjectNode o = tcArr.addObject();
                o.put("id", tc.getId());
                o.put("summary", tc.getSummary());
                o.put("precondition", tc.getPrecondition());
                ArrayNode steps = o.putArray("steps");
                cleanSteps(tc.getSteps()).forEach(steps::add);
                o.put("expected_result", tc.getExpected_result());
                ArrayNode ids = o.putArray("requirementIds");
                tc.getRequirementIds().forEach(ids::add);
            }
            JsonNode root = testCaseService.callGroqForJson(systemPrompt, mapper.writeValueAsString(input));
            return parseAiFixes(root, chunk, knownCodes, source);
        } catch (WebClientResponseException e) {
            log.error("Groq từ chối (status {}): {}", e.getStatusCode().value(), e.getResponseBodyAsString(), e);
            throw new RuntimeException("Groq từ chối (" + e.getStatusCode().value() + "): " + e.getResponseBodyAsString(), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Lỗi khi AI rà soát test case", e);
            throw new RuntimeException("Lỗi hệ thống: " + e.getMessage(), e);
        }
    }

    /** Kiểm tra & làm sạch kết quả AI: chỉ giữ đề xuất hợp lệ, thực sự thay đổi nội dung. */
    static List<Fix> parseAiFixes(JsonNode root, List<TestCase> chunk, Set<String> knownCodes, String source) {
        JsonNode arr = root.isArray() ? root : root.path("fixes");
        List<Fix> result = new ArrayList<>();
        if (!arr.isArray()) return result;
        Map<String, TestCase> byCode = new HashMap<>();
        chunk.forEach(tc -> { if (tc.getId() != null) byCode.put(tc.getId().trim().toUpperCase(Locale.ROOT), tc); });

        // Lượt 1: gộp trùng
        Set<Long> inMerge = new HashSet<>();
        Set<Long> removed = new HashSet<>();
        for (JsonNode n : arr) {
            String type = lower(TestCaseService.firstText(n, "type", "kind"));
            if (!type.equals("duplicate") && !type.equals("merge")) continue;
            LinkedHashMap<Long, TestCase> members = new LinkedHashMap<>();
            for (JsonNode idNode : n.path("ids")) {
                TestCase tc = byCode.get(idNode.asText("").trim().toUpperCase(Locale.ROOT));
                if (tc != null && !inMerge.contains(tc.getDbId())) members.put(tc.getDbId(), tc);
            }
            if (members.size() < 2) continue;
            String keepCode = TestCaseService.firstText(n, "keep");
            TestCase keep = keepCode == null ? null : byCode.get(keepCode.trim().toUpperCase(Locale.ROOT));
            if (keep == null || !members.containsKey(keep.getDbId())) {
                keep = members.values().stream().max(Comparator.comparingInt(FixService::completeness)
                        .thenComparing(tc -> -tcNumber(tc.getId()))).orElseThrow();
            }
            final TestCase kept = keep;
            List<TestCase> removes = members.values().stream().filter(tc -> tc != kept).toList();
            inMerge.addAll(members.keySet());
            removes.forEach(tc -> removed.add(tc.getDbId()));
            result.add(mergeFix("ai", confidence(n), text(n, "reason"), kept, removes, null));
        }

        // Lượt 2: sửa nội dung
        Set<Long> edited = new HashSet<>();
        for (JsonNode n : arr) {
            String type = lower(TestCaseService.firstText(n, "type", "kind"));
            if (type.equals("duplicate") || type.equals("merge")) continue;
            String code = TestCaseService.firstText(n, "id", "tcId", "testCaseId");
            TestCase tc = code == null ? null : byCode.get(code.trim().toUpperCase(Locale.ROOT));
            if (tc == null || removed.contains(tc.getDbId()) || edited.contains(tc.getDbId())) continue;

            Fields before = Fields.of(tc);
            JsonNode ch = n.path("changes");
            if (!ch.isObject()) continue;
            String summary = nonBlank(TestCaseService.firstText(ch, "summary"), before.summary());
            String precondition = ch.has("precondition") && !ch.get("precondition").isNull()
                    ? ch.get("precondition").asText() : before.precondition();
            List<String> steps = before.steps();
            JsonNode st = ch.path("steps");
            if (st.isArray() && !st.isEmpty()) {
                List<String> s = new ArrayList<>();
                st.forEach(x -> { if (!x.asText("").isBlank()) s.add(x.asText().trim()); });
                if (!s.isEmpty()) steps = s;
            } else if (st.isTextual() && !st.asText().isBlank()) {
                steps = List.of(st.asText().trim());
            }
            String expected = nonBlank(TestCaseService.firstText(ch, "expected_result", "expectedResult"), before.expected_result());
            List<String> reqIds = before.requirementIds();
            JsonNode rq = ch.path("requirementIds");
            if (rq.isArray() && !rq.isEmpty()) {
                List<String> r = new ArrayList<>();
                rq.forEach(x -> {
                    String c = x.asText("").trim().toUpperCase(Locale.ROOT);
                    if (knownCodes.contains(c) && !r.contains(c)) r.add(c);
                });
                if (!r.isEmpty()) reqIds = r;
            }
            Fields after = new Fields(summary, precondition, steps, expected, reqIds);
            if (after.sameAs(before)) continue; // AI không thực sự đổi gì
            edited.add(tc.getDbId());

            String problem = lower(TestCaseService.firstText(n, "problem"));
            if (!PROBLEMS.contains(problem)) problem = "other";
            String conf = confidence(n);
            String evidence = text(n, "evidence");
            Boolean verified = null;
            if (evidence == null) {
                if (conf.equals("high")) conf = "medium"; // không có căn cứ thì không được coi là chắc chắn
            } else {
                verified = appearsIn(evidence, source);
                if (!verified) conf = conf.equals("high") ? "medium" : "low"; // trích dẫn không có trong tài liệu
            }
            result.add(new Fix("edit-" + tc.getDbId(), "edit", "ai", conf, problem, text(n, "reason"), evidence, verified,
                    tc.getDbId(), tc.getId(), before, after, List.of(), List.of(), null));
        }
        return result;
    }

    /** Câu trích dẫn có nằm trong tài liệu không (bỏ dấu, dấu câu; chấp nhận khác biệt nhỏ). */
    static boolean appearsIn(String quote, String source) {
        String q = ReviewService.normalizeSummary(quote);
        String s = ReviewService.normalizeSummary(source);
        if (q.isEmpty() || s.isEmpty()) return false;
        if (s.contains(q)) return true;
        Set<String> srcTokens = new HashSet<>(Arrays.asList(s.split(" ")));
        String[] qt = q.split(" ");
        int hit = 0;
        for (String t : qt) if (srcTokens.contains(t)) hit++;
        return qt.length >= 4 && hit >= qt.length * 0.9;
    }

    /**
     * Lấy các đoạn tài liệu liên quan nhất tới nội dung cần kiểm tra (TF-IDF đơn giản),
     * giữ nguyên thứ tự trong tài liệu, tổng không quá maxChars.
     */
    static String relevantExcerpt(String source, String query, int maxChars) {
        if (source == null) return "";
        if (source.length() <= maxChars) return source;
        List<String> paras = new ArrayList<>();
        for (String p : source.split("\\n\\s*\\n")) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            if (t.length() <= 1500) { paras.add(t); continue; }
            StringBuilder buf = new StringBuilder(); // đoạn quá dài -> cắt theo dòng
            for (String line : t.split("\\n")) {
                if (buf.length() + line.length() > 1500 && buf.length() > 0) { paras.add(buf.toString()); buf.setLength(0); }
                buf.append(line).append('\n');
            }
            if (buf.length() > 0) paras.add(buf.toString().trim());
        }
        List<Set<String>> ptoks = new ArrayList<>();
        Map<String, Integer> df = new HashMap<>();
        for (String p : paras) {
            Set<String> set = new HashSet<>(Arrays.asList(ReviewService.normalizeSummary(p).split(" ")));
            set.removeIf(String::isBlank);
            ptoks.add(set);
            set.forEach(t -> df.merge(t, 1, Integer::sum));
        }
        Set<String> q = new HashSet<>(Arrays.asList(ReviewService.normalizeSummary(query).split(" ")));
        q.removeIf(String::isBlank);
        int total = paras.size();
        Integer[] order = new Integer[total];
        double[] score = new double[total];
        for (int i = 0; i < total; i++) {
            order[i] = i;
            for (String t : q) if (ptoks.get(i).contains(t)) score[i] += Math.log(1.0 + (double) total / df.get(t));
            score[i] /= Math.sqrt(Math.max(1, ptoks.get(i).size()) / 20.0 + 1); // không ưu tiên đoạn dài
        }
        Arrays.sort(order, (a, b) -> Double.compare(score[b], score[a]));
        TreeSet<Integer> chosen = new TreeSet<>();
        int used = 0;
        for (int i : order) {
            if (score[i] <= 0) break;
            int len = paras.get(i).length() + 6;
            if (used + len > maxChars) continue;
            chosen.add(i);
            used += len;
        }
        if (chosen.isEmpty()) return source.substring(0, maxChars);
        StringBuilder sb = new StringBuilder();
        int prev = -2;
        for (int i : chosen) {
            if (sb.length() > 0) sb.append(i == prev + 1 ? "\n\n" : "\n\n...\n\n");
            sb.append(paras.get(i));
            prev = i;
        }
        return sb.toString();
    }

    // =====================================================================
    // 3. ÁP DỤNG & HOÀN TÁC
    // =====================================================================

    @Transactional
    public ApplyResult apply(Long sessionId, List<ApplyItem> items) throws Exception {
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy lịch sử"));
        Map<Long, TestCase> byId = new LinkedHashMap<>();
        testCaseRepository.findByChatSessionId(sessionId).forEach(tc -> byId.put(tc.getDbId(), tc));
        Set<String> knownCodes = new HashSet<>();
        testCaseService.getRequirementsBySession(sessionId).forEach(r -> knownCodes.add(r.getCode()));

        Map<Long, SnapTc> editedSnap = new LinkedHashMap<>();
        List<SnapTc> removedSnap = new ArrayList<>();
        Set<Long> removed = new HashSet<>();
        List<TestCase> toDelete = new ArrayList<>();
        int applied = 0, edits = 0, merges = 0;
        List<Long> skippedIds = new ArrayList<>();

        for (ApplyItem it : items == null ? List.<ApplyItem>of() : items) {
            TestCase tc = it.dbId() == null ? null : byId.get(it.dbId());
            if (tc == null || removed.contains(tc.getDbId())) { skippedIds.add(it.dbId()); continue; }

            if ("merge".equals(it.kind())) {
                List<TestCase> removes = new ArrayList<>();
                for (Long rid : it.removeDbIds() == null ? List.<Long>of() : it.removeDbIds()) {
                    TestCase r = byId.get(rid);
                    if (r != null && r != tc && !removed.contains(rid) && !removes.contains(r)) removes.add(r);
                }
                if (removes.isEmpty()) { skippedIds.add(it.dbId()); continue; }
                editedSnap.putIfAbsent(tc.getDbId(), new SnapTc(tc.getDbId(), tc.getId(), Fields.of(tc)));
                LinkedHashSet<String> reqs = new LinkedHashSet<>(tc.getRequirementIds());
                for (TestCase r : removes) {
                    reqs.addAll(r.getRequirementIds());
                    removedSnap.add(new SnapTc(r.getDbId(), r.getId(), Fields.of(r)));
                    removed.add(r.getDbId());
                    toDelete.add(r);
                }
                tc.setRequirementIds(new ArrayList<>(reqs));
                merges++;
            } else {
                // Nội dung test case đã bị sửa (tay hoặc bởi đề xuất khác) kể từ lúc rà soát -> bỏ qua để không ghi đè
                if (it.after() == null || !Fields.of(tc).sameContent(it.before())) { skippedIds.add(it.dbId()); continue; }
                editedSnap.putIfAbsent(tc.getDbId(), new SnapTc(tc.getDbId(), tc.getId(), Fields.of(tc)));
                Fields a = it.after();
                if (a.summary() != null && !a.summary().isBlank()) tc.setSummary(a.summary().trim());
                tc.setPrecondition(a.precondition());
                if (a.steps() != null && !cleanSteps(a.steps()).isEmpty()) tc.setSteps(new ArrayList<>(cleanSteps(a.steps())));
                if (a.expected_result() != null && !a.expected_result().isBlank()) tc.setExpected_result(a.expected_result().trim());
                // Chỉ đổi liên kết khi AI đề xuất đổi (giữ liên kết mới có được sau khi gộp trùng)
                if (a.requirementIds() != null && !a.sameLinks(it.before())) {
                    List<String> r = a.requirementIds().stream().filter(knownCodes::contains).toList();
                    if (!r.isEmpty() || knownCodes.isEmpty()) tc.setRequirementIds(r);
                }
                edits++;
            }
            applied++;
        }

        if (applied > 0) {
            // Test case sắp bị xóa thì không cần lưu bản "sửa"
            removed.forEach(editedSnap::remove);
            testCaseRepository.saveAll(byId.values().stream().filter(tc -> editedSnap.containsKey(tc.getDbId())).toList());
            testCaseRepository.deleteAll(toDelete);

            ReviewSnapshot snap = new ReviewSnapshot();
            snap.setChatSession(session);
            snap.setCreatedAt(LocalDateTime.now());
            snap.setSummary(edits + " edit, " + merges + " merge");
            snap.setData(mapper.writeValueAsString(new Snapshot(new ArrayList<>(editedSnap.values()), removedSnap)));
            snapshotRepository.save(snap);
            List<ReviewSnapshot> all = snapshotRepository.findByChatSessionIdOrderByIdDesc(sessionId);
            if (all.size() > MAX_SNAPSHOTS) snapshotRepository.deleteAll(all.subList(MAX_SNAPSHOTS, all.size()));
        }
        return new ApplyResult(applied, skippedIds.size(), skippedIds, (int) snapshotRepository.countByChatSessionId(sessionId),
                TestCaseService.byTcNumber(testCaseRepository.findByChatSessionId(sessionId)));
    }

    /** Hoàn tác lần áp dụng gần nhất: khôi phục nội dung đã sửa và tạo lại test case đã xóa (giữ mã TC cũ). */
    @Transactional
    public UndoResult undo(Long sessionId) throws Exception {
        List<ReviewSnapshot> snaps = snapshotRepository.findByChatSessionIdOrderByIdDesc(sessionId);
        if (snaps.isEmpty()) {
            return new UndoResult(false, null, 0, TestCaseService.byTcNumber(testCaseRepository.findByChatSessionId(sessionId)));
        }
        ReviewSnapshot last = snaps.get(0);
        Snapshot data = mapper.readValue(last.getData(), Snapshot.class);
        Map<Long, TestCase> byId = new HashMap<>();
        Set<String> usedCodes = new HashSet<>();
        int maxTc = 0;
        for (TestCase tc : testCaseRepository.findByChatSessionId(sessionId)) {
            byId.put(tc.getDbId(), tc);
            usedCodes.add(tc.getId());
            maxTc = Math.max(maxTc, tcNumber(tc.getId()));
        }
        List<TestCase> toSave = new ArrayList<>();
        for (SnapTc s : data.edited() == null ? List.<SnapTc>of() : data.edited()) {
            TestCase tc = byId.get(s.dbId());
            if (tc == null) continue; // đã bị người dùng xóa sau đó
            setFields(tc, s.fields());
            toSave.add(tc);
        }
        for (SnapTc s : data.removed() == null ? List.<SnapTc>of() : data.removed()) {
            TestCase tc = new TestCase();
            String code = s.id();
            if (code == null || usedCodes.contains(code)) code = String.format("TC-%02d", ++maxTc);
            usedCodes.add(code);
            tc.setId(code);
            setFields(tc, s.fields());
            tc.setChatSession(last.getChatSession());
            toSave.add(tc);
        }
        testCaseRepository.saveAll(toSave);
        snapshotRepository.delete(last);
        return new UndoResult(true, last.getSummary(), snaps.size() - 1, TestCaseService.byTcNumber(testCaseRepository.findByChatSessionId(sessionId)));
    }

    public int undoCount(Long sessionId) {
        return (int) snapshotRepository.countByChatSessionId(sessionId);
    }

    private static void setFields(TestCase tc, Fields f) {
        tc.setSummary(f.summary());
        tc.setPrecondition(f.precondition());
        tc.setSteps(new ArrayList<>(f.steps() == null ? List.of() : f.steps()));
        tc.setExpected_result(f.expected_result());
        tc.setRequirementIds(f.requirementIds());
    }

    // =====================================================================
    // TIỆN ÍCH
    // =====================================================================

    static List<String> cleanSteps(List<String> steps) {
        if (steps == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : steps) if (s != null && !s.isBlank()) out.add(s);
        return out;
    }

    static int tcNumber(String code) {
        if (code == null) return 0;
        Matcher m = TC_NUMBER.matcher(code);
        try { return m.find() ? Integer.parseInt(m.group(1)) : 0; } catch (NumberFormatException e) { return 0; }
    }

    private static String confidence(JsonNode n) {
        String c = lower(TestCaseService.firstText(n, "confidence"));
        return c.equals("high") || c.equals("low") ? c : "medium";
    }

    private static String text(JsonNode n, String field) {
        String v = TestCaseService.firstText(n, field);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String nonBlank(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v.trim();
    }
}
