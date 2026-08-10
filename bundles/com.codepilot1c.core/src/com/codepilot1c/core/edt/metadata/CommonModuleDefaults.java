package com.codepilot1c.core.edt.metadata;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure-Java decision for the environment flags a freshly created
 * {@code CommonModule} must carry.
 *
 * <p>Background: a CommonModule whose {@code clientManagedApplication},
 * {@code clientOrdinaryApplication}, {@code server} and
 * {@code externalConnection} are all {@code false} is invalid by the platform
 * model itself — EDT answers with four {@code md-legacy-emf-check} errors
 * («No one environment is not set», one per property) plus a
 * {@code common-module-type} code-style error. Those are the ecore defaults,
 * so every module created through {@code create_metadata} without explicit
 * properties was born broken and the caller had to know to repair it. Probed
 * live on {@code workspace-sandbox}; see
 * {@code issues/2026-08-10-new-module-staleness-probe-findings.md}.</p>
 *
 * <p>The canonical «Server module» flag set is read off
 * {@code com.e1c.v8codestyle.md.CommonModuleTypes.SERVER}: exactly
 * {@code clientOrdinaryApplication}, {@code server} and
 * {@code externalConnection}. Setting those three cleared every diagnostic on
 * the probe module. The EMF mutation itself lives in {@code EdtMetadataService}
 * where the object and its {@code EClass} are available.</p>
 */
public final class CommonModuleDefaults {

    private CommonModuleDefaults() { }

    /**
     * The four flags the platform counts as a module's <em>environment</em> —
     * the ones {@code md-legacy-emf-check} demands at least one of.
     *
     * <p>{@code serverCall} is deliberately absent: it is a separate
     * capability («Вызов сервера»), not an environment, and the check does not
     * accept it in place of one. See {@link #environmentDefaults} for why that
     * distinction decides the gate.</p>
     */
    public static final Set<String> ENVIRONMENT_FLAGS = Collections.unmodifiableSet(
            new LinkedHashSet<>(List.of(
                    "clientManagedApplication", //$NON-NLS-1$
                    "clientOrdinaryApplication", //$NON-NLS-1$
                    "server", //$NON-NLS-1$
                    "externalConnection"))); //$NON-NLS-1$

    /**
     * The flag map to apply to a brand-new CommonModule, or an empty map when
     * the caller already stated an environment and must not be overridden.
     *
     * <p>The gate is all-or-nothing on purpose: a caller who passes a single
     * environment flag has expressed an intent for the whole set, so topping it
     * up with the server defaults would silently widen the module. A caller who
     * passes none — including one who passes only {@code serverCall} or only
     * {@code privileged} — has expressed no environment at all, and the invalid
     * all-false module is exactly what we are fixing.</p>
     *
     * @param callerPropertyKeys the property keys of the create request as the
     *        caller wrote them (may be {@code null}); matched
     *        case-insensitively and ignoring {@code _}/{@code -}/space, the
     *        same normalization {@code create_metadata} applies when resolving
     *        a property to an EMF feature
     * @return canonical flag values keyed by EMF feature name; never
     *         {@code null}
     */
    public static Map<String, Boolean> environmentDefaults(Collection<String> callerPropertyKeys) {
        if (statesEnvironment(callerPropertyKeys)) {
            return Map.of();
        }
        Map<String, Boolean> defaults = new LinkedHashMap<>();
        // com.e1c.v8codestyle.md.CommonModuleTypes.SERVER — every flag it does
        // not list stays false, which is also the ecore default.
        defaults.put("clientManagedApplication", Boolean.FALSE); //$NON-NLS-1$
        defaults.put("clientOrdinaryApplication", Boolean.TRUE); //$NON-NLS-1$
        defaults.put("server", Boolean.TRUE); //$NON-NLS-1$
        defaults.put("externalConnection", Boolean.TRUE); //$NON-NLS-1$
        return Collections.unmodifiableMap(defaults);
    }

    /**
     * Whether the caller named at least one {@link #ENVIRONMENT_FLAGS
     * environment flag} among the create properties.
     *
     * @param callerPropertyKeys property keys as written by the caller; may be
     *        {@code null}
     * @return {@code true} when the environment is the caller's to decide
     */
    public static boolean statesEnvironment(Collection<String> callerPropertyKeys) {
        if (callerPropertyKeys == null || callerPropertyKeys.isEmpty()) {
            return false;
        }
        Set<String> normalizedFlags = new LinkedHashSet<>();
        for (String flag : ENVIRONMENT_FLAGS) {
            normalizedFlags.add(normalize(flag));
        }
        for (String key : callerPropertyKeys) {
            if (normalizedFlags.contains(normalize(key))) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        return value
                .replace("_", "") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("-", "") //$NON-NLS-1$ //$NON-NLS-2$
                .replace(" ", "") //$NON-NLS-1$ //$NON-NLS-2$
                .toLowerCase(Locale.ROOT);
    }
}
