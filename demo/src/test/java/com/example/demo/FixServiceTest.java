package com.example.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FixServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static TestCase tc(long dbId, String id, String summary, List<String> steps, String expected, String... reqs) {
        TestCase t = new TestCase();
        t.setDbId(dbId);
        t.setId(id);
        t.setSummary(summary);
        t.setPrecondition("Người dùng ở trang đăng nhập");
        t.setSteps(new ArrayList<>(steps));
        t.setExpected_result(expected);
        t.setRequirementIds(List.of(reqs));
        return t;
    }

    private static List<TestCase> sample() {
        return List.of(
                tc(1, "TC-01", "Đăng nhập thành công", List.of("Nhập tên đăng nhập hợp lệ", "Nhập mật khẩu đúng", "Bấm Đăng nhập"),
                        "Chuyển tới trang chủ", "REQ-01"),
                tc(2, "TC-02", "Đăng nhập với mật khẩu sai", List.of("Nhập tên đăng nhập hợp lệ", "Nhập mật khẩu sai", "Bấm Đăng nhập"),
                        "Báo lỗi sai mật khẩu", "REQ-02"),
                // Gần như giống hệt TC-01 (chỉ khác dấu câu / hoa thường) nhưng gắn yêu cầu khác
                tc(3, "TC-03", "ĐĂNG NHẬP THÀNH CÔNG!", List.of("Nhập tên đăng nhập hợp lệ", "Nhập mật khẩu đúng", "Bấm Đăng nhập."),
                        "Chuyển tới trang chủ", "REQ-03"),
                tc(4, "TC-04", "Đăng nhập khi bỏ trống mật khẩu", List.of("Nhập tên đăng nhập hợp lệ", "Để trống mật khẩu", "Bấm Đăng nhập"),
                        "Báo lỗi bắt buộc nhập mật khẩu", "REQ-02"));
    }

    @Test
    void mergesNearIdenticalAndKeepsAllRequirementLinks() {
        List<FixService.Fix> fixes = FixService.duplicateFixes(sample(), null);
        assertEquals(1, fixes.size(), "Chỉ TC-01 và TC-03 trùng; TC-02 / TC-04 khác ý nên không được gộp");
        FixService.Fix f = fixes.get(0);
        assertEquals("merge", f.kind());
        assertEquals("high", f.confidence());
        assertEquals("TC-01", f.tcId(), "Bằng nhau thì giữ test case tạo trước");
        assertEquals(List.of("TC-03"), f.removeTcIds());
        assertEquals(List.of("REQ-01", "REQ-03"), f.after().requirementIds(), "Gộp xong không được mất liên kết REQ-03");
    }

    @Test
    void doesNotMergeWhenOnlyRequirementCodeDiffers() {
        List<TestCase> list = List.of(
                tc(1, "TC-01", "FR-01 hợp lệ", List.of("Mở chức năng", "Nhập dữ liệu hợp lệ", "Bấm Lưu"), "Lưu thành công", "REQ-01"),
                tc(2, "TC-02", "FR-02 hợp lệ", List.of("Mở chức năng", "Nhập dữ liệu hợp lệ", "Bấm Lưu"), "Lưu thành công", "REQ-02"));
        assertTrue(FixService.duplicateFixes(list, null).isEmpty());
    }

    @Test
    void duplicateScopeOnlyReturnsGroupsTouchingScope() {
        assertTrue(FixService.duplicateFixes(sample(), List.of(2L, 4L)).isEmpty());
        assertEquals(1, FixService.duplicateFixes(sample(), List.of(3L)).size());
    }

    @Test
    void parsesAiEditsAndRejectsInvalidOnes() throws Exception {
        List<TestCase> chunk = sample();
        String source = "FR-02: Khi nhập sai mật khẩu, hệ thống hiển thị thông báo \"Tên đăng nhập hoặc mật khẩu không đúng\".";
        JsonNode root = MAPPER.readTree("""
                {"fixes":[
                  {"type":"edit","id":"TC-02","problem":"wrong","confidence":"high",
                   "evidence":"hệ thống hiển thị thông báo \\"Tên đăng nhập hoặc mật khẩu không đúng\\"",
                   "changes":{"expected_result":"Hiển thị thông báo \\"Tên đăng nhập hoặc mật khẩu không đúng\\""}},
                  {"type":"edit","id":"TC-04","problem":"vague","confidence":"high","evidence":"Mật khẩu phải có ít nhất 12 ký tự",
                   "changes":{"expected_result":"Báo lỗi mật khẩu phải có ít nhất 12 ký tự"}},
                  {"type":"edit","id":"TC-01","confidence":"high","evidence":"","changes":{"summary":"Đăng nhập thành công"}},
                  {"type":"edit","id":"TC-99","changes":{"summary":"không tồn tại"}},
                  {"type":"edit","id":"TC-01","changes":{"requirementIds":["REQ-77"]}},
                  {"type":"duplicate","ids":["TC-01","TC-03"],"keep":"TC-03","reason":"Cùng kịch bản"},
                  {"type":"edit","id":"TC-03","changes":{"summary":"Sẽ bị bỏ vì TC-03 bị gộp?"}}
                ]}""");
        List<FixService.Fix> fixes = FixService.parseAiFixes(root, chunk, Set.of("REQ-01", "REQ-02", "REQ-03"), source);

        FixService.Fix merge = fixes.stream().filter(f -> f.kind().equals("merge")).findFirst().orElseThrow();
        assertEquals("TC-03", merge.tcId(), "Tôn trọng test case AI chọn giữ lại");
        assertEquals(List.of("TC-01"), merge.removeTcIds());

        FixService.Fix e2 = fixes.stream().filter(f -> "TC-02".equals(f.tcId())).findFirst().orElseThrow();
        assertEquals("high", e2.confidence());
        assertEquals(Boolean.TRUE, e2.evidenceVerified());
        assertEquals("Nhập mật khẩu sai", e2.after().steps().get(1), "Trường không sửa phải giữ nguyên");

        FixService.Fix e4 = fixes.stream().filter(f -> "TC-04".equals(f.tcId())).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, e4.evidenceVerified(), "Trích dẫn bịa không có trong tài liệu");
        assertEquals("medium", e4.confidence(), "Trích dẫn bịa -> hạ độ tin cậy");

        // TC-01: không đổi gì / mã yêu cầu lạ / bị xóa do gộp -> bỏ; TC-99 không tồn tại -> bỏ
        assertTrue(fixes.stream().noneMatch(f -> f.kind().equals("edit") && "TC-01".equals(f.tcId())));
        assertTrue(fixes.stream().noneMatch(f -> "TC-99".equals(f.tcId())));
        assertEquals(1, fixes.stream().filter(f -> f.kind().equals("edit") && "TC-03".equals(f.tcId())).count(),
                "TC-03 là test case được giữ nên vẫn sửa được");
        assertEquals(4, fixes.size());
    }

    @Test
    void fieldsComparisonIgnoresWhitespace() {
        FixService.Fields a = new FixService.Fields("Đăng  nhập ", null, List.of("A", " "), "OK", List.of("REQ-01"));
        FixService.Fields b = new FixService.Fields("Đăng nhập", "", List.of("A"), "OK ", List.of("REQ-01"));
        assertTrue(a.sameAs(b));
        assertFalse(a.sameAs(new FixService.Fields("Đăng nhập", "", List.of("A"), "Lỗi", List.of("REQ-01"))));
    }

    @Test
    void excerptPicksRelevantParagraphs() {
        StringBuilder src = new StringBuilder();
        for (int i = 0; i < 40; i++) src.append("## Mục ").append(i).append("\n\nNội dung chung chung về hệ thống số ").append(i)
                .append(" với rất nhiều chữ để làm dài tài liệu.\n\n");
        src.append("## Đăng nhập\n\nMật khẩu sai quá 5 lần thì khóa tài khoản 15 phút.\n\n");
        for (int i = 40; i < 80; i++) src.append("## Mục ").append(i).append("\n\nNội dung chung chung về hệ thống số ").append(i)
                .append(" với rất nhiều chữ để làm dài tài liệu.\n\n");
        String ex = FixService.relevantExcerpt(src.toString(), "khóa tài khoản khi nhập sai mật khẩu", 600);
        assertTrue(ex.length() <= 600 + 20);
        assertTrue(ex.contains("khóa tài khoản 15 phút"));
        assertEquals("ngắn", FixService.relevantExcerpt("ngắn", "x", 600));
    }
}
