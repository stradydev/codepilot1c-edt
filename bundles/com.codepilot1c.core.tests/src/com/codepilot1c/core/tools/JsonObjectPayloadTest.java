package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Tests for {@link JsonObjectPayload}.
 *
 * <p>{@code edt_validate_request} answered a bare {@code payload must be an
 * object} when the payload arrived serialized — ordinary for MCP, where most
 * payloads are a JSON string somewhere along the way. Reported 2026-08-08 in
 * {@code 2026-08-08-edt-validate-request-no-container-semantic-check.md}.</p>
 */
public class JsonObjectPayloadTest {

    @Test
    public void aSerializedObjectBecomesAMap() {
        Map<String, Object> parsed = JsonObjectPayload.parse(
                "{\"project\":\"AM\",\"name\":\"Probe\"}"); //$NON-NLS-1$
        assertEquals("AM", parsed.get("project")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Probe", parsed.get("name")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anIntegralNumberStaysIntegral() {
        // The reason this class exists rather than Gson's Map default: some
        // normalizers read values through String.valueOf, where a Double would
        // turn length:150 into "150.0" and change the metadata written.
        Map<String, Object> parsed = JsonObjectPayload.parse(
                "{\"length\":150,\"precision\":0}"); //$NON-NLS-1$
        assertEquals(Integer.valueOf(150), parsed.get("length")); //$NON-NLS-1$
        assertEquals("150", String.valueOf(parsed.get("length"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Integer.valueOf(0), parsed.get("precision")); //$NON-NLS-1$
    }

    @Test
    public void aNumberTooBigForIntBecomesLong() {
        Map<String, Object> parsed = JsonObjectPayload.parse("{\"big\":4294967296}"); //$NON-NLS-1$
        assertEquals(Long.valueOf(4294967296L), parsed.get("big")); //$NON-NLS-1$
    }

    @Test
    public void aFractionalNumberStaysDouble() {
        Map<String, Object> parsed = JsonObjectPayload.parse("{\"ratio\":1.5}"); //$NON-NLS-1$
        assertEquals(Double.valueOf(1.5), parsed.get("ratio")); //$NON-NLS-1$
    }

    @Test
    public void nestedObjectsAndArraysAreConvertedToo() {
        // The payload's nested 'properties' object is where per-object values
        // belong, so it has to survive the string form intact.
        Map<String, Object> parsed = JsonObjectPayload.parse(
                "{\"properties\":{\"type\":\"String\",\"length\":10},\"tags\":[\"a\",1,true]}"); //$NON-NLS-1$

        assertTrue(parsed.get("properties") instanceof Map); //$NON-NLS-1$
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) parsed.get("properties"); //$NON-NLS-1$
        assertEquals("String", properties.get("type")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Integer.valueOf(10), properties.get("length")); //$NON-NLS-1$
        assertEquals(List.of("a", 1, Boolean.TRUE), parsed.get("tags")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void booleansAndNullsSurvive() {
        Map<String, Object> parsed = JsonObjectPayload.parse(
                "{\"force\":true,\"comment\":null}"); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, parsed.get("force")); //$NON-NLS-1$
        assertTrue(parsed.containsKey("comment")); //$NON-NLS-1$
        assertNull(parsed.get("comment")); //$NON-NLS-1$
    }

    @Test
    public void keyOrderIsPreserved() {
        // Refusal messages list the payload's keys; a shuffled order makes two
        // runs of the same call read differently.
        Map<String, Object> parsed = JsonObjectPayload.parse(
                "{\"z\":1,\"a\":2,\"m\":3}"); //$NON-NLS-1$
        assertEquals(List.of("z", "a", "m"), List.copyOf(parsed.keySet())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void anythingThatIsNotAJsonObjectYieldsNull() {
        // The caller must still get the type complaint, not a silently empty payload.
        assertNull(JsonObjectPayload.parse("[1,2,3]")); //$NON-NLS-1$
        assertNull(JsonObjectPayload.parse("\"just a string\"")); //$NON-NLS-1$
        assertNull(JsonObjectPayload.parse("42")); //$NON-NLS-1$
        assertNull(JsonObjectPayload.parse("not json at all")); //$NON-NLS-1$
        assertNull(JsonObjectPayload.parse("{unclosed")); //$NON-NLS-1$
        assertNull(JsonObjectPayload.parse("")); //$NON-NLS-1$
        assertNull(JsonObjectPayload.parse("   ")); //$NON-NLS-1$
        assertNull(JsonObjectPayload.parse(null));
    }

    @Test
    public void anEmptyJsonObjectIsAValidPayload() {
        // Distinct from "not an object": an empty payload is a payload, and the
        // per-operation required-field checks are what should judge it.
        Map<String, Object> parsed = JsonObjectPayload.parse("{}"); //$NON-NLS-1$
        assertTrue(parsed != null && parsed.isEmpty());
    }
}
