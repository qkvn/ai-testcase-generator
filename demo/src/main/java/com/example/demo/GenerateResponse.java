package com.example.demo;

import java.util.List;

/** Kết quả trả về sau khi AI sinh test case: kèm id phiên và danh sách yêu cầu. */
public record GenerateResponse(Long sessionId, List<Requirement> requirements, List<TestCase> testCases) {}
