package com.codepilot1c.core.edt.validation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Behavioural tests for {@link MetadataRequestValidationService#normalizeExtensionAdoptPayload}.
 *
 * <p>Feedback (2026-09-16, BF-13180, {@code extension_manage adopt}): the top-level {@code project},
 * {@code payload.project} and {@code payload.base_project} must all name the same, BASE configuration
 * project (never the extension project being adopted into) — a mismatch used to surface as a bare
 * {@code KNOWLEDGE_REQUIRED / payload.base_project must match project}, correct about the mismatch but
 * silent on which of the two values is the right one, costing a round-trip every time it was hit cold.
 * The method itself needs no live EDT project — it is pure string validation plus a
 * {@link com.codepilot1c.core.edt.extension.ExtensionAdoptObjectRequest#validate()} call — so this runs
 * as a plain unit test, unlike the BM-level authoring it feeds.</p>
 */
public class ExtensionAdoptPayloadValidationTest {

    private final MetadataRequestValidationService service = new MetadataRequestValidationService();

    @Test
    public void matchingProjectAndBaseProjectNormalizesCleanly() {
        Map<String, Object> payload = service.normalizeExtensionAdoptPayload(
                "Accounting management", //$NON-NLS-1$
                "yaxunit", //$NON-NLS-1$
                "Accounting management", //$NON-NLS-1$
                "CommonModule.Integration_Vault", //$NON-NLS-1$
                null);

        assertEquals("Accounting management", payload.get("project")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Accounting management", payload.get("base_project")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("yaxunit", payload.get("extension_project")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void omittedBaseProjectDefaultsToProject() {
        Map<String, Object> payload = service.normalizeExtensionAdoptPayload(
                "Accounting management", //$NON-NLS-1$
                "yaxunit", //$NON-NLS-1$
                null,
                "CommonModule.Integration_Vault", //$NON-NLS-1$
                null);

        assertEquals("Accounting management", payload.get("base_project")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void baseProjectDisagreeingWithProjectNamesTheMismatchAndTheFix() {
        try {
            service.normalizeExtensionAdoptPayload(
                    "Accounting management", //$NON-NLS-1$
                    "yaxunit", //$NON-NLS-1$
                    "yaxunit", // wrong: the extension project instead of the base one //$NON-NLS-1$
                    "CommonModule.Integration_Vault", //$NON-NLS-1$
                    null);
            fail("a base_project/project mismatch must be refused, not silently coerced"); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.KNOWLEDGE_REQUIRED, e.getCode());
            String message = e.getMessage();
            assertTrue("must still name the mismatched fields: " + message, //$NON-NLS-1$
                    message.contains("payload.base_project must match project")); //$NON-NLS-1$
            assertTrue("must now also say which of the two is correct: " + message, //$NON-NLS-1$
                    message.contains("BASE configuration") && message.contains("not the extension project")); //$NON-NLS-1$
        }
    }

    @Test
    public void missingSourceObjectFqnFailsLoudNotSilently() {
        try {
            service.normalizeExtensionAdoptPayload(
                    "Accounting management", //$NON-NLS-1$
                    "yaxunit", //$NON-NLS-1$
                    "Accounting management", //$NON-NLS-1$
                    "", //$NON-NLS-1$
                    null);
            fail("a blank source_object_fqn must be refused"); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            assertFalse("must not be a generic knowledge-required refusal here", //$NON-NLS-1$
                    e.getMessage() == null || e.getMessage().isBlank());
        }
    }
}
