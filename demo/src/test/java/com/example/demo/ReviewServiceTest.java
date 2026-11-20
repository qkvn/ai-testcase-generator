package com.example.demo;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReviewServiceTest {

    private final ReviewService service = new ReviewService();

    private static Requirement req(String code) {
        Requirement r = new Requirement();
        r.setCode(code);
        r.setDescription("Yêu cầu " + code);
        r.setPriority("High");
        return r;
    }

    private static TestCase tc(long dbId, String id, String summary, String pre, List<String> steps, String expected, String... reqs) {
        TestCase t = new TestCase();
        t.setDbId(dbId);
        t.setId(id);
        t.setSummary(summary);
        t.setPrecondition(pre);
        t.setSteps(new ArrayList<>(steps));
        t.setExpected_result(expected);
        t.setRequirementIds(List.of(reqs));
        return t;
    }

    private static long count(ReviewService.ReviewReport r, String code) {
        return r.issues().stream().filter(i -> i.code().equals(code)).count();
    }

    @Test
    void detectsAllIssueTypes() {
        List<Requirement> reqs = List.of(req("REQ-01"), req("REQ-02"), req("REQ-03"));
        List<TestCase> tcs = List.of(
                tc(1, "TC-01", "Đăng nhập thành công", "Có tài khoản", List.of("Mở trang", "Nhập đúng"), "Vào trang chủ", "REQ-01"),
                tc(2, "TC-02", "  đăng nhập THÀNH CÔNG!  ", "Có tài khoản", List.of("Mở trang", "Nhập đúng"), "Vào trang chủ", "REQ-01"),
                tc(3, "TC-03", "Sai mật khẩu", "", List.of(), "", "REQ-02"),
                tc(4, "TC-04", "Không gắn yêu cầu", "Có tài khoản", List.of("Chỉ một bước"), "OK"));
        ReviewService.ReviewReport r = service.review(reqs, tcs);

        assertEquals(1, count(r, "DUPLICATE_SUMMARY"));          // TC-01 & TC-02 trùng (bỏ qua hoa/thường, dấu câu)
        assertEquals(1, count(r, "MISSING_STEPS"));              // TC-03
        assertEquals(1, count(r, "MISSING_EXPECTED"));           // TC-03
        assertEquals(1, count(r, "MISSING_PRECONDITION"));       // TC-03
        assertEquals(1, count(r, "ONE_STEP"));                   // TC-04
        assertEquals(1, count(r, "UNLINKED_TC"));                // TC-04
        assertEquals(1, count(r, "SINGLE_TEST"));                // REQ-02 chỉ có TC-03
        assertEquals(1, count(r, "UNCOVERED_REQ"));              // REQ-03
        assertEquals(3, r.errors());
        assertEquals(3, r.warnings());
        assertEquals(2, r.infos());
        assertEquals(ReviewService.Severity.ERROR, r.issues().get(0).severity());

        ReviewService.Issue dup = r.issues().stream().filter(i -> i.code().equals("DUPLICATE_SUMMARY")).findFirst().orElseThrow();
        assertEquals(List.of("TC-01", "TC-02"), dup.targets());
        assertEquals(List.of(1L, 2L), dup.dbIds());

        // Yêu cầu "yếu" (0–1 test case) được đánh dấu để AI ưu tiên
        assertTrue(r.requirements().stream().filter(s -> s.code().equals("REQ-01")).noneMatch(ReviewService.RequirementStat::weak));
        assertTrue(r.requirements().stream().filter(s -> !s.code().equals("REQ-01")).allMatch(ReviewService.RequirementStat::weak));
        assertTrue(r.score() < 100);
    }

    @Test
    void perfectSuiteScores100() {
        List<Requirement> reqs = List.of(req("REQ-01"));
        List<TestCase> tcs = List.of(
                tc(1, "TC-01", "Hợp lệ", "Có tài khoản", List.of("B1", "B2"), "OK", "REQ-01"),
                tc(2, "TC-02", "Không hợp lệ", "Có tài khoản", List.of("B1", "B2"), "Báo lỗi", "REQ-01"));
        ReviewService.ReviewReport r = service.review(reqs, tcs);
        assertTrue(r.issues().isEmpty());
        assertEquals(100, r.score());
    }

    @Test
    void noRequirementsSkipsLinkChecksAndEmptyHasNoScore() {
        List<TestCase> tcs = List.of(tc(1, "TC-01", "A", "P", List.of("B1", "B2"), "OK"));
        assertEquals(0, count(service.review(List.of(), tcs), "UNLINKED_TC"));
        assertNull(service.review(List.of(), List.of()).score());
    }

    @Test
    void normalizeSummaryIgnoresAccentsCaseAndPunctuation() {
        assertEquals(ReviewService.normalizeSummary("Đăng nhập thành công!"), ReviewService.normalizeSummary("dang  NHAP thanh cong"));
        assertNotEquals(ReviewService.normalizeSummary("Đăng nhập"), ReviewService.normalizeSummary("Đăng xuất"));
    }
}
