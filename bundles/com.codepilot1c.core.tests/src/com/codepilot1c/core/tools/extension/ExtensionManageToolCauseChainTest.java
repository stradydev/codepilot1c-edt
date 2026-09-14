package com.codepilot1c.core.tools.extension;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Feedback 2026-09-11 §2: the platform reports an extension-project creation failure as
 * {@code LifecycleException("Failed to perform phase INITIALIZATION for context ProjectContext: X")}
 * with the participant's real exception attached as the cause and nothing written to the Eclipse log.
 * Reporting only {@code getMessage()} therefore destroyed the single copy of the root cause that ever
 * reached the caller.
 */
public class ExtensionManageToolCauseChainTest {

    @Test
    public void chainedCauseIsReported() {
        Throwable root = new IllegalStateException("Runtime version 1.0.0 is not supported"); //$NON-NLS-1$
        Throwable wrapper = new RuntimeException(
                "Failed to perform phase INITIALIZATION for context ProjectContext: MCPApi", root); //$NON-NLS-1$

        String described = ExtensionManageTool.describeCauseChain(wrapper);

        assertTrue(described, described.contains("ProjectContext: MCPApi")); //$NON-NLS-1$
        assertTrue(described, described.contains("caused by")); //$NON-NLS-1$
        assertTrue(described, described.contains("Runtime version 1.0.0 is not supported")); //$NON-NLS-1$
        assertTrue(described, described.contains(IllegalStateException.class.getName()));
    }

    @Test
    public void messagelessCauseStillNamesItsType() {
        String described = ExtensionManageTool.describeCauseChain(
                new RuntimeException("outer", new NullPointerException())); //$NON-NLS-1$

        assertTrue(described, described.contains(NullPointerException.class.getName()));
    }

    @Test
    public void cyclicChainTerminates() {
        RuntimeException first = new RuntimeException("first"); //$NON-NLS-1$
        RuntimeException second = new RuntimeException("second", first); //$NON-NLS-1$
        first.initCause(second);

        String described = ExtensionManageTool.describeCauseChain(first);

        assertTrue(described, described.contains("first")); //$NON-NLS-1$
        assertTrue(described, described.contains("second")); //$NON-NLS-1$
    }

    @Test
    public void nullErrorIsDescribed() {
        assertEquals("unknown error", ExtensionManageTool.describeCauseChain(null)); //$NON-NLS-1$
    }
}
