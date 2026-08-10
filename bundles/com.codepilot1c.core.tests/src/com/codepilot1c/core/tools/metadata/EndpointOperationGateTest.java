package com.codepilot1c.core.tools.metadata;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import java.util.function.Predicate;

import org.junit.Test;

import com.codepilot1c.core.edt.validation.ValidationOperation;

/**
 * Tests for {@link EndpointOperationGate}.
 *
 * <p>{@code edt_validate_request} used to answer {@code valid:true} and issue a
 * token for an operation the calling port's profile gates off — a green light
 * for something {@code tools/call} would then reject. Reported 2026-08-08 in
 * {@code 2026-08-08-ensure-module-artifact-validates-but-gated-unreachable.md},
 * with {@code ensure_module_artifact} as the live case.</p>
 */
public class EndpointOperationGateTest {

    private static final Predicate<String> EXPOSES_NOTHING = name -> false;
    private static final Predicate<String> EXPOSES_EVERYTHING = name -> true;

    @Test
    public void gatedOperationIsRefused() {
        String message = EndpointOperationGate.refusalMessageOrNull(
                ValidationOperation.ENSURE_MODULE_ARTIFACT,
                "ensure_module_artifact", //$NON-NLS-1$
                EXPOSES_NOTHING);

        assertNotNull(message);
        assertTrue(message, message.contains("ensure_module_artifact")); //$NON-NLS-1$
        assertTrue("must say no token was issued:\n" + message, //$NON-NLS-1$
                message.contains("No validation_token was issued")); //$NON-NLS-1$
        assertTrue("must point at discover_tools:\n" + message, //$NON-NLS-1$
                message.contains("discover_tools")); //$NON-NLS-1$
    }

    @Test
    public void exposedOperationPassesThrough() {
        assertNull(EndpointOperationGate.refusalMessageOrNull(
                ValidationOperation.ENSURE_MODULE_ARTIFACT,
                "ensure_module_artifact", //$NON-NLS-1$
                EXPOSES_EVERYTHING));
    }

    @Test
    public void unknownProfileFailsOpen() {
        // No predicate means the router supplied no per-endpoint view (in-process
        // agent call, a test). Guessing from the global env could refuse a call
        // that would have succeeded, so nothing is refused.
        assertNull(EndpointOperationGate.refusalMessageOrNull(
                ValidationOperation.ENSURE_MODULE_ARTIFACT,
                "ensure_module_artifact", //$NON-NLS-1$
                null));
    }

    @Test
    public void unresolvedOperationFailsOpen() {
        assertNull(EndpointOperationGate.refusalMessageOrNull(
                null, "whatever", EXPOSES_NOTHING)); //$NON-NLS-1$
    }

    @Test
    public void theGateIsCheckedAgainstTheDispatchingTool() {
        // dcs_upsert_parameter is executed by dcs_manage, so the visibility of
        // the composite router — not of the operation name — is what decides.
        // Checking the operation name would fail open on every composite one.
        Predicate<String> exposesDcsManage = name -> "dcs_manage".equals(name); //$NON-NLS-1$
        assertNull(EndpointOperationGate.refusalMessageOrNull(
                ValidationOperation.DCS_UPSERT_PARAMETER,
                "dcs_upsert_parameter", //$NON-NLS-1$
                exposesDcsManage));

        String message = EndpointOperationGate.refusalMessageOrNull(
                ValidationOperation.DCS_UPSERT_PARAMETER,
                "dcs_upsert_parameter", //$NON-NLS-1$
                EXPOSES_NOTHING);
        assertNotNull(message);
        assertTrue("must name the dispatching tool:\n" + message, //$NON-NLS-1$
                message.contains("dcs_manage")); //$NON-NLS-1$
    }

    @Test
    public void everyOperationResolvesToAToolTheGateCanTest() {
        // A null target tool name would silently fail the gate open for that
        // operation. Every enum value must map to a tool name the profile
        // predicate can be asked about.
        for (ValidationOperation operation : ValidationOperation.values()) {
            String toolName = ValidationPayloadKeyContract.targetToolName(operation);
            assertNotNull(operation.name(), toolName);
            assertTrue(operation.name() + " -> '" + toolName + "'", !toolName.isBlank()); //$NON-NLS-1$ //$NON-NLS-2$
            assertNotNull(operation.name(), EndpointOperationGate.refusalMessageOrNull(
                    operation, operation.getToolName(), EXPOSES_NOTHING));
        }
    }

    @Test
    public void onlyTheDispatchingToolDecides() {
        // A profile that exposes every OTHER tool must still refuse: the gate has
        // to test the executing tool, not merely "some tool is exposed".
        Set<String> everythingElse = Set.of("get_diagnostics", "read_file", "grep"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull(EndpointOperationGate.refusalMessageOrNull(
                ValidationOperation.CREATE_METADATA,
                "create_metadata", //$NON-NLS-1$
                everythingElse::contains));
    }
}
