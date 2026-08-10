package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

/**
 * Tests for {@link CommonModuleDefaults}.
 *
 * <p>Pins the decision behind the environment default {@code create_metadata}
 * applies to a new CommonModule. The defect it fixes was probed live: a module
 * created with no properties has every environment flag {@code false}, which
 * EDT rejects on the spot with four {@code md-legacy-emf-check} errors plus
 * {@code common-module-type}. See
 * {@code issues/2026-08-10-new-module-staleness-probe-findings.md}.</p>
 */
public class CommonModuleDefaultsTest {

    // --- the default itself -------------------------------------------------

    @Test
    public void noPropertiesYieldsCanonicalServerModule() {
        // com.e1c.v8codestyle.md.CommonModuleTypes.SERVER, verified live: setting
        // exactly these cleared every diagnostic on the probe module.
        Map<String, Boolean> defaults = CommonModuleDefaults.environmentDefaults(Set.of());

        assertEquals(Boolean.FALSE, defaults.get("clientManagedApplication")); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, defaults.get("clientOrdinaryApplication")); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, defaults.get("server")); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, defaults.get("externalConnection")); //$NON-NLS-1$
    }

    @Test
    public void nullPropertiesAreTreatedAsNoProperties() {
        // create_metadata leaves `properties` null when the caller omits it —
        // the very call shape that produced the invalid module.
        assertFalse(CommonModuleDefaults.environmentDefaults(null).isEmpty());
    }

    @Test
    public void theDefaultSetsAtLeastOneEnvironment() {
        // The whole point: md-legacy-emf-check demands a non-empty environment.
        // Guards a future edit of the map from re-creating the invalid state.
        Map<String, Boolean> defaults = CommonModuleDefaults.environmentDefaults(null);
        assertTrue(CommonModuleDefaults.ENVIRONMENT_FLAGS.stream()
                .anyMatch(flag -> Boolean.TRUE.equals(defaults.get(flag))));
    }

    // --- the gate: an explicit caller choice is never widened ---------------

    @Test
    public void anyStatedEnvironmentFlagSuppressesTheDefault() {
        // All-or-nothing: one flag expresses an intent for the whole set, so
        // topping it up would silently widen where the module is compiled.
        for (String flag : CommonModuleDefaults.ENVIRONMENT_FLAGS) {
            assertTrue(flag, CommonModuleDefaults.statesEnvironment(Set.of(flag)));
            assertTrue(flag, CommonModuleDefaults.environmentDefaults(Set.of(flag)).isEmpty());
        }
    }

    @Test
    public void statedEnvironmentIsMatchedTheWayCreateMetadataResolvesProperties() {
        // create_metadata normalizes a property key case-insensitively and drops
        // _/-/space before resolving it to an EMF feature. The gate has to match
        // the same spellings, or a caller who wrote `client_managed_application`
        // would have their explicit choice overwritten.
        assertTrue(CommonModuleDefaults.statesEnvironment(Set.of("Server"))); //$NON-NLS-1$
        assertTrue(CommonModuleDefaults.statesEnvironment(Set.of("SERVER"))); //$NON-NLS-1$
        assertTrue(CommonModuleDefaults.statesEnvironment(Set.of("client_managed_application"))); //$NON-NLS-1$
        assertTrue(CommonModuleDefaults.statesEnvironment(Set.of("external-connection"))); //$NON-NLS-1$
        assertTrue(CommonModuleDefaults.statesEnvironment(Set.of("client ordinary application"))); //$NON-NLS-1$
    }

    @Test
    public void otherPropertiesDoNotCountAsAnEnvironment() {
        // These are real CommonModule properties, but none of them is an
        // environment, so a module carrying only them is still the invalid
        // all-false module the default exists to prevent.
        assertFalse(CommonModuleDefaults.statesEnvironment(Set.of("privileged"))); //$NON-NLS-1$
        assertFalse(CommonModuleDefaults.statesEnvironment(Set.of("global"))); //$NON-NLS-1$
        assertFalse(CommonModuleDefaults.statesEnvironment(Set.of("returnValuesReuse"))); //$NON-NLS-1$
        assertFalse(CommonModuleDefaults.environmentDefaults(Set.of("privileged")).isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void serverCallAloneStillGetsAnEnvironment() {
        // serverCall is a capability, not an environment: md-legacy-emf-check
        // does not accept it in place of one, and a server-call module needs
        // `server` anyway — which the default supplies.
        assertFalse(CommonModuleDefaults.ENVIRONMENT_FLAGS.contains("serverCall")); //$NON-NLS-1$
        assertFalse(CommonModuleDefaults.statesEnvironment(Set.of("serverCall"))); //$NON-NLS-1$

        Map<String, Boolean> defaults = CommonModuleDefaults.environmentDefaults(Set.of("serverCall")); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, defaults.get("server")); //$NON-NLS-1$
    }

    @Test
    public void aStatedEnvironmentAmongOtherPropertiesStillSuppresses() {
        assertTrue(CommonModuleDefaults.statesEnvironment(
                List.of("privileged", "comment", "server"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void everyDefaultedKeyIsAKnownEnvironmentFlag() {
        // A typo in a feature name would silently no-op in EdtMetadataService
        // (getEStructuralFeature returns null), leaving the module invalid with
        // no error. Pin the key vocabulary here instead.
        assertTrue(CommonModuleDefaults.ENVIRONMENT_FLAGS
                .containsAll(CommonModuleDefaults.environmentDefaults(null).keySet()));
    }
}
