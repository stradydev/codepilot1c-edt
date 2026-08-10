/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.metadata;

/**
 * Which incoming references genuinely block a {@code delete_metadata} — the rule, as pure logic.
 *
 * <h2>The bug this exists to fix</h2>
 *
 * <p>Live on the sandbox, 2026-08-10: a brand-new {@code SessionParameter} created through the normal
 * {@code create_metadata} path, with no module, no children and no user of any kind, was refused with
 * {@code METADATA_DELETE_CONFLICT} — and the ONE reference the message cited was
 * {@code Configuration#sessionParameters}, i.e. its own entry in the configuration composition. The
 * same held for a {@code CommonModule}, which additionally cited
 * {@code CommonModule.<Name>#source}: its own module. Since <em>every</em> top-level object is listed
 * in {@code Configuration#<collection>} by construction, the non-{@code force} top-level delete path
 * was unreachable for every kind, while the message advertised a cure ("clean the references, then
 * repeat") that no amount of cleaning could reach. {@code force=true} was the only way through, which
 * turned a safety check into pure friction and taught callers to pass the override reflexively — the
 * opposite of what a guard is for.</p>
 *
 * <h2>The rule</h2>
 *
 * <p>A reference blocks a delete only if it would still point somewhere after the delete. Two kinds
 * of reference would not, and are therefore not counted:</p>
 *
 * <ul>
 * <li><b>The object's own subtree.</b> The referrer's top object IS the object being deleted (the
 * {@code #source} back-reference from its own module is the proven case). It goes away with it.</li>
 * <li><b>Its own composition membership.</b> The referrer is the {@code Configuration} and the
 * feature is the very collection {@code removeTopLevelObjectLinks} unregisters the object from —
 * {@link TopLevelCollections#configurationTag} or the generic {@code content} list.</li>
 * </ul>
 *
 * <p>Everything else still blocks, {@code Configuration}'s <em>other</em> references included:
 * {@code Configuration#defaultRoles} pointing at a Role, {@code #defaultLanguage} at a Language and
 * the like are real users that the delete does not clean up, so they must keep refusing. That is
 * exactly why this filter names the composition feature instead of exempting the whole
 * {@code Configuration} — the coarse version would trade this bug for dangling {@code .mdo} lines,
 * the failure mode {@code removeSubsystemLinks} already had to be written to prevent.</p>
 *
 * <p><b>Fail-safe direction.</b> Every uncertainty resolves to "this reference blocks": an unknown
 * kind, a blank feature name, or a feature name that does not match the mapping keeps the old refusal.
 * A wrong mapping entry can therefore only restore the previous over-strict behaviour — it can never
 * let a genuinely referenced object be deleted silently.</p>
 *
 * <p>Pure string/enum logic, no EDT runtime, so the rule is unit-testable outside the OSGi
 * runtime — same split as {@link com.codepilot1c.core.diagnostics.DiagnosticPathFilter}. The caller
 * ({@code EdtMetadataService.collectIncomingReferences}) supplies the referrer's top-object FQN and
 * the feature name it already resolves for the message samples.</p>
 */
public final class DeleteReferenceScope {

    /** The top-object FQN {@code BmObjectHelper.safeTopFqn} yields for the configuration root. */
    public static final String CONFIGURATION_TOP_FQN = "Configuration"; //$NON-NLS-1$

    /** The generic composition list every top object is also registered in. */
    static final String CONTENT_FEATURE = "content"; //$NON-NLS-1$

    private DeleteReferenceScope() {
        // utility
    }

    /**
     * Whether the referrer lives inside the object being deleted, so the reference dies with it.
     *
     * <p>Compares the FULL target FQN against the referrer's TOP-object FQN, which makes the test
     * fire only for top-level deletes — deliberately. For a child delete
     * ({@code Catalog.X.Attribute.Y}) the referrer's top FQN is the owning {@code Catalog.X}, and a
     * form of that same catalog referencing the attribute is a genuine user that must keep blocking;
     * a "same top object" test would have waved exactly those through.</p>
     *
     * @param targetFqn      FQN of the object being deleted
     * @param referrerTopFqn top-object FQN of the referring object
     * @return whether the reference originates inside the deleted object itself
     */
    public static boolean isOwnSubtreeReference(String targetFqn, String referrerTopFqn) {
        String target = trim(targetFqn);
        return !target.isEmpty() && target.equalsIgnoreCase(trim(referrerTopFqn));
    }

    /**
     * Whether the reference is the object's own registration in the configuration composition, which
     * the delete removes itself.
     *
     * <p>The feature name is matched case-insensitively on purpose: the mapping spells the
     * {@code Configuration.mdo} element name, and for one kind that spelling and the EMF feature name
     * differ in case only ({@code xdtoPackages} vs the {@code getXDTOPackages()} accessor). No two
     * configuration features differ only by case, so the looser test cannot widen the filter onto a
     * different feature.</p>
     *
     * @param targetFqn      FQN of the object being deleted
     * @param kind           kind of the object being deleted, may be {@code null}
     * @param referrerTopFqn top-object FQN of the referring object
     * @param featureName    name of the referring feature
     * @return whether the reference is this object's own composition membership
     */
    public static boolean isOwnMembershipReference(
            String targetFqn, MetadataKind kind, String referrerTopFqn, String featureName) {
        if (kind == null || !isTopLevelFqn(targetFqn)) {
            // Composition membership is a property of top-level objects only; a child is held by its
            // own owner, and that containment reference is filtered out before this rule is reached.
            return false;
        }
        if (!CONFIGURATION_TOP_FQN.equalsIgnoreCase(trim(referrerTopFqn))) {
            return false;
        }
        String feature = trim(featureName);
        if (feature.isEmpty()) {
            return false;
        }
        return feature.equalsIgnoreCase(CONTENT_FEATURE)
                || feature.equalsIgnoreCase(TopLevelCollections.configurationTag(kind));
    }

    /**
     * The rule itself: whether this incoming reference should count against the delete.
     *
     * @param targetFqn      FQN of the object being deleted
     * @param kind           kind of the object being deleted, may be {@code null} when unresolvable
     * @param referrerTopFqn top-object FQN of the referring object
     * @param featureName    name of the referring feature
     * @return whether the reference blocks a non-{@code force} delete
     */
    public static boolean blocksDelete(
            String targetFqn, MetadataKind kind, String referrerTopFqn, String featureName) {
        return !isOwnSubtreeReference(targetFqn, referrerTopFqn)
                && !isOwnMembershipReference(targetFqn, kind, referrerTopFqn, featureName);
    }

    /** Whether {@code fqn} names a top-level object, i.e. carries exactly two segments. */
    public static boolean isTopLevelFqn(String fqn) {
        String value = trim(fqn);
        if (value.isEmpty()) {
            return false;
        }
        return value.split("\\.").length == 2; //$NON-NLS-1$
    }

    /**
     * Resolves the kind of a top-level FQN's first segment, or {@code null} when it names no known
     * kind. Never throws: an unresolvable kind must degrade into "filter nothing", not into a failed
     * delete.
     *
     * @param fqn the FQN whose leading kind segment to resolve
     * @return the kind, or {@code null}
     */
    public static MetadataKind kindOfFqn(String fqn) {
        String value = trim(fqn);
        int dot = value.indexOf('.');
        if (dot <= 0) {
            return null;
        }
        try {
            return MetadataKind.fromString(value.substring(0, dot));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.strip(); //$NON-NLS-1$
    }
}
