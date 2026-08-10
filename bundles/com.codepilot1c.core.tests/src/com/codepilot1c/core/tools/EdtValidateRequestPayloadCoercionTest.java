package com.codepilot1c.core.tools;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.codepilot1c.core.tools.metadata.EdtValidateRequestTool;

/**
 * Behavioural cover for the {@code payload}-as-string acceptance in
 * {@code edt_validate_request} — the wiring step that turns
 * {@link JsonObjectPayload} into live behaviour.
 *
 * <p>Asserted through {@code execute}, on the tool's own answer. Whatever the
 * validator does next needs a live EDT and is not this test's business; what is
 * pinned is that a serialized payload is no longer rejected for its type.</p>
 */
public class EdtValidateRequestPayloadCoercionTest {

    private static final String TYPE_COMPLAINT = "payload must be an object"; //$NON-NLS-1$

    @Test
    public void aSerializedPayloadIsNoLongerRejectedForItsType() throws Exception {
        String error = errorOf("{\"name\":\"Probe\",\"kind\":\"CommonModule\"}"); //$NON-NLS-1$
        if (error != null) {
            assertFalse("a parseable JSON string must not be refused as a non-object:\n" + error, //$NON-NLS-1$
                    error.contains(TYPE_COMPLAINT));
        }
    }

    @Test
    public void aStringThatIsNotAJsonObjectStillGetsTheTypeComplaint() throws Exception {
        // Fail-open in the honest direction: the caller is told what is accepted
        // instead of receiving a silently empty payload.
        String error = errorOf("definitely not json"); //$NON-NLS-1$
        assertNotNull(error);
        assertTrue(error, error.contains(TYPE_COMPLAINT));
        assertTrue("must state that a JSON string is accepted too:\n" + error, //$NON-NLS-1$
                error.contains("JSON string that parses to one")); //$NON-NLS-1$
    }

    @Test
    public void aMissingPayloadStillGetsTheTypeComplaint() throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("project", "AM"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("operation", "create_metadata"); //$NON-NLS-1$ //$NON-NLS-2$
        ToolResult result = new EdtValidateRequestTool().execute(params).get(60, TimeUnit.SECONDS);

        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage(), result.getErrorMessage().contains(TYPE_COMPLAINT));
    }

    @Test
    public void theSchemaAdvertisesBothPayloadForms() {
        // MCP clients drop or reject an argument whose declared type it does not
        // match, so accepting a string server-side is inert unless the schema says so.
        String schema = new EdtValidateRequestTool().getParameterSchema();
        assertTrue(schema, schema.contains("[\"object\", \"string\"]")); //$NON-NLS-1$
    }

    /** @return the tool's error message, or {@code null} when the call succeeded */
    private String errorOf(String payload) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("project", "AM"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("operation", "create_metadata"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("payload", payload); //$NON-NLS-1$
        ToolResult result = new EdtValidateRequestTool().execute(params).get(60, TimeUnit.SECONDS);
        return result.isSuccess() ? null : result.getErrorMessage();
    }
}
