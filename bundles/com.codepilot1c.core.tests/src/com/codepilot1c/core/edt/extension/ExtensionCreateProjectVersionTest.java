package com.codepilot1c.core.edt.extension;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import com._1c.g5.v8.dt.platform.version.Version;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Feedback 2026-09-11 (extension_manage create INTERNAL_ERROR): every failing attempt passed
 * {@code version=1.0.0} — the extension's own product version — where the parameter is the
 * 1C:Enterprise <em>platform runtime</em> version. {@code Version.create("1.0.0")} accepts it, so it
 * used to reach {@code IExtensionProjectManager.create}, land in the new project's
 * {@code DT-INF/PROJECT.PMF} as {@code Runtime-Version} and blow the project context up during the
 * platform's INITIALIZATION phase, where the rollback masked the cause.
 */
public class ExtensionCreateProjectVersionTest {

    @Test
    public void validateRejectsANonPlatformVersion() {
        try {
            request("1.0.0").validate(); //$NON-NLS-1$
            fail("version=1.0.0 is not a supported platform runtime version"); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.INVALID_PROPERTY_VALUE, e.getCode());
            assertTrue(e.getMessage(), e.getMessage().contains("1.0.0")); //$NON-NLS-1$
            assertTrue(e.getMessage(), e.getMessage().contains("8.3.27")); //$NON-NLS-1$
        }
    }

    @Test
    public void validateRejectsGarbage() {
        try {
            request("not-a-version").validate(); //$NON-NLS-1$
            fail("a malformed version must not reach the platform"); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.INVALID_PROPERTY_VALUE, e.getCode());
        }
    }

    @Test
    public void validateAcceptsASupportedPlatformVersion() {
        ExtensionCreateProjectRequest request = request("8.3.27"); //$NON-NLS-1$
        request.validate();
        assertEquals(Version.V8_3_27, request.effectiveVersion(Version.V8_3_25));
    }

    @Test
    public void blankVersionInheritsTheBaseProjectRuntimeVersion() {
        ExtensionCreateProjectRequest request = request(null);
        request.validate();
        assertSame(Version.V8_3_26, request.effectiveVersion(Version.V8_3_26));
    }

    @Test
    public void everySupportedVersionRoundTrips() {
        for (Version supported : Version.getPlatformSupportVersions()) {
            assertEquals(supported,
                    ExtensionCreateProjectRequest.parseSupportedPlatformVersion(supported.toString()));
        }
    }

    private static ExtensionCreateProjectRequest request(String version) {
        return new ExtensionCreateProjectRequest(
                "Accounting management", //$NON-NLS-1$
                "MCPApi", //$NON-NLS-1$
                null,
                version,
                "MCPApi", //$NON-NLS-1$
                "ADD_ON", //$NON-NLS-1$
                "VERSION8_327"); //$NON-NLS-1$
    }
}
