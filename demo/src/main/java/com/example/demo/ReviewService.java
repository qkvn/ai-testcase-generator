package com.example.demo;

import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.*;

/**
 * Rà soát chất lượng bộ test case (không dùng AI):
 *  - Lỗi: thiếu bước, thiếu kết quả mong đợi, trùng tóm tắt
 *  - Cảnh báo: yêu cầu chỉ có 1 test case / chưa có test case, test case không gắn yêu cầu
 *  - Gợi ý: test case chỉ có 1 bước, thiếu điều kiện tiên quyết
 * và tính điểm chất lượng 0–100.
 */
@Service
public class ReviewService {

    public enum Severity { ERROR, WARNING, INFO }

    /**
     * Một vấn đề phát hiện được.
     * targetType: "TC" hoặc "REQ"; targets: mã TC / mã yêu cầu liên quan; dbIds: id test case trong DB (để nhảy tới).
     */
    public record Issue(Severity severity, String code, String targetType, List<String> targets, List<Long> dbIds, String detail) {}

    public record RequirementStat(String code, String description, String priority, int testCaseCount, boolean weak) {}

    public record ReviewReport(Integer score, int errors, int warnings, int infos, int totalTestCases, int totalRequirements,
                               List<Issue> issues, List<RequirementStat> requirements) {}

    public ReviewReport review(List<Requirement> requirements, List<TestCase> testCases) {
        List<Issue> issues = new ArrayList<>();
        Set<String> knownCodes = new HashSet<>();
        requirements.forEach(r -> knownCodes.add(r.getCode()));

        // ---------- Từng test case ----------
        Map<String, List<TestCase>> bySummary = new LinkedHashMap<>();
        for (TestCase tc : testCases) {
            List<String> steps = tc.getSteps() == null ? List.of()
                    : tc.getSteps().stream().filter(s -> s != null && !s.isBlank()).toList();
            if (steps.isEmpty()) {
                issues.add(tcIssue(Severity.ERROR, "MISSING_STEPS", tc, null));
            } else if (steps.size() == 1) {
                issues.add(tcIssue(Severity.INFO, "ONE_STEP", tc, null));
            }
            if (isBlank(tc.getExpected_result())) {
                issues.add(tcIssue(Severity.ERROR, "MISSING_EXPECTED", tc, null));
            }
            if (isBlank(tc.getPrecondition())) {
                issues.add(tcIssue(Severity.INFO, "MISSING_PRECONDITION", tc, null));
            }
            if (!requirements.isEmpty() && tc.getRequirementIds().stream().noneMatch(knownCodes::contains)) {
                issues.add(tcIssue(Severity.WARNING, "UNLINKED_TC", tc, null));
            }
            String key = normalizeSummary(tc.getSummary());
            if (!key.isEmpty()) bySummary.computeIfAbsent(key, k -> new ArrayList<>()).add(tc);
        }

        // ---------- Trùng tóm tắt ----------
        for (List<TestCase> group : bySummary.values()) {
            if (group.size() < 2) continue;
            issues.add(new Issue(Severity.ERROR, "DUPLICATE_SUMMARY", "TC",
                    group.stream().map(TestCase::getId).toList(),
                    group.stream().map(TestCase::getDbId).toList(),
                    group.get(0).getSummary()));
        }

        // ---------- Từng yêu cầu ----------
        Map<String, Integer> count = new HashMap<>();
        for (TestCase tc : testCases) {
            for (String code : new HashSet<>(tc.getRequirementIds())) {
                if (knownCodes.contains(code)) count.merge(code, 1, Integer::sum);
            }
        }
        List<RequirementStat> stats = new ArrayList<>();
        for (Requirement r : requirements) {
            int n = count.getOrDefault(r.getCode(), 0);
            stats.add(new RequirementStat(r.getCode(), r.getDescription(), r.getPriority(), n, n <= 1));
            if (n == 0) {
                issues.add(new Issue(Severity.WARNING, "UNCOVERED_REQ", "REQ", List.of(r.getCode()), List.of(), r.getDescription()));
            } else if (n == 1) {
                issues.add(new Issue(Severity.WARNING, "SINGLE_TEST", "REQ", List.of(r.getCode()), List.of(), r.getDescription()));
            }
        }

        // Sắp xếp: lỗi -> cảnh báo -> gợi ý
        issues.sort(Comparator.comparing(Issue::severity));

        int errors = (int) issues.stream().filter(i -> i.severity() == Severity.ERROR).count();
        int warnings = (int) issues.stream().filter(i -> i.severity() == Severity.WARNING).count();
        int infos = issues.size() - errors - warnings;
        return new ReviewReport(score(errors, warnings, infos, testCases.size() + requirements.size()),
                errors, warnings, infos, testCases.size(), requirements.size(), issues, stats);
    }

    /**
     * Điểm chất lượng: lỗi = 10 điểm phạt, cảnh báo = 5, gợi ý = 1;
     * tổng điểm phạt chia cho số đối tượng (test case + yêu cầu) để bộ lớn không bị phạt quá nặng.
     */
    static Integer score(int errors, int warnings, int infos, int items) {
        if (items == 0) return null;
        double penalty = (errors * 10.0 + warnings * 5.0 + infos) / items;
        return (int) Math.max(0, Math.round(100 - penalty * 10));
    }

    private static Issue tcIssue(Severity s, String code, TestCase tc, String detail) {
        return new Issue(s, code, "TC", List.of(tc.getId() == null ? "?" : tc.getId()),
                tc.getDbId() == null ? List.of() : List.of(tc.getDbId()), detail != null ? detail : tc.getSummary());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Chuẩn hóa tóm tắt để so trùng: bỏ dấu tiếng Việt, chữ thường, bỏ dấu câu và khoảng trắng thừa. */
    static String normalizeSummary(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
        return t;
    }
}
