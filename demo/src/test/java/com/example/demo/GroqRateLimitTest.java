package com.example.demo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GroqRateLimitTest {

    @Test
    void parsesGroqWaitTimes() {
        String real = "Rate limit reached for model `openai/gpt-oss-120b` in organization `org_x` service tier `on_demand` "
                + "on tokens per minute (TPM): Limit 8000, Used 3533, Requested 4578. Please try again in 832.5ms. Need more tokens?";
        assertEquals(833, TestCaseService.parseTryAgainMs(real));
        assertEquals(7660, TestCaseService.parseTryAgainMs("Please try again in 7.66s."));
        assertEquals(90_500, TestCaseService.parseTryAgainMs("Please try again in 1m30.5s."));
        assertEquals(2 * 3_600_000 + 3 * 60_000, TestCaseService.parseTryAgainMs("Please try again in 2h3m."));
        assertEquals(0, TestCaseService.parseTryAgainMs("no hint here"));
    }
}
