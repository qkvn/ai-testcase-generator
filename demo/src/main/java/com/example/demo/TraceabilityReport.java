package com.example.demo;

import java.util.List;

/**
 * Báo cáo truy vết yêu cầu (Requirement Traceability Matrix) và độ bao phủ của một phiên.
 */
public record TraceabilityReport(
        Long sessionId,
        int totalRequirements,
        int coveredRequirements,
        double coveragePercent,
        int totalTestCases,
        int linkedTestCases,
        List<RequirementCoverage> requirements,
        List<String> uncoveredRequirements,
        List<String> orphanTestCases
) {
    public record RequirementCoverage(
            Long dbId,
            String code,
            String description,
            String priority,
            List<String> testCaseIds,
            boolean covered
    ) {}
}
