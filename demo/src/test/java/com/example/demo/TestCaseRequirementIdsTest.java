package com.example.demo;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestCaseRequirementIdsTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);

    @Test
    void parsesArrayOfRequirementIds() throws Exception {
        TestCase tc = mapper.readValue("{\"id\":\"TC-01\",\"requirementIds\":[\"req-01\",\"REQ-02\",\"REQ-01\"]}", TestCase.class);
        assertEquals(List.of("REQ-01", "REQ-02"), tc.getRequirementIds());
        assertEquals("REQ-01,REQ-02", tc.getRequirementIdsRaw());
    }

    @Test
    void parsesCommaSeparatedStringAndSnakeCase() throws Exception {
        TestCase tc = mapper.readValue("{\"id\":\"TC-02\",\"requirement_ids\":\"REQ-03, REQ-04\",\"unknown\":1}", TestCase.class);
        assertEquals(List.of("REQ-03", "REQ-04"), tc.getRequirementIds());
    }

    @Test
    void serializesAsArrayAndHidesRawColumn() throws Exception {
        TestCase tc = new TestCase();
        tc.setRequirementIds(List.of("REQ-01"));
        String json = mapper.writeValueAsString(tc);
        assertTrue(json.contains("\"requirementIds\":[\"REQ-01\"]"));
        assertFalse(json.contains("requirementIdsRaw"));
    }

    @Test
    void emptyWhenNoLinks() {
        TestCase tc = new TestCase();
        tc.setRequirementIds(null);
        assertTrue(tc.getRequirementIds().isEmpty());
        assertNull(tc.getRequirementIdsRaw());
    }
}
