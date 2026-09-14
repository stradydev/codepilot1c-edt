package com.codepilot1c.core.edt.metadata;

import java.util.Locale;

/**
 * The address of the configuration root — the one metadata object that has no {@code <Type>.<Name>}
 * FQN because it is a singleton.
 *
 * <p>Every FQN the toolset parses is a {@code <Type>.<Name>} pair (optionally continued by
 * {@code .<Marker>.<Name>} segments), so the root used to be unaddressable in both directions:
 * {@code edt_metadata_details} answered {@code exists:false} ("this value carries no type token")
 * and {@code update_metadata} read the bare token as a PARENT still waiting for its child part and
 * raised {@code METADATA_PARENT_NOT_FOUND}. The configuration's own name is not a way out — it is
 * not an FQN name segment, so {@code Configuration.<ConfigName>} fails as an unsupported kind. Net
 * effect: {@code synonym}, {@code version}, {@code namePrefix}, {@code defaultRoles},
 * {@code compatibilityMode} and {@code configurationExtensionPurpose} — routine, per-release edits —
 * were unreachable through MCP (codepilot1c-feedback
 * {@code 2026-09-11-update-metadata-cannot-address-configuration-root}).</p>
 *
 * <p><strong>{@code Configuration} is not a token this plugin invented.</strong> It is the FQN the
 * BM itself registers the root top object under: {@code buildExportTargets} has always appended the
 * literal {@code "Configuration"} to every force-export batch, and that call is what writes
 * {@code src/Configuration/Configuration.mdo}. Reserving it as an ADDRESS therefore only lets the
 * resolver name what the export pipeline already names.</p>
 *
 * <p>It collides with nothing: {@link MetadataKind} carries no {@code Configuration} constant (the
 * root is not a collection of objects, so it has no {@code TopLevelCollections} entry), and a bare
 * type token is an error everywhere else in the toolset rather than a shorthand for "the collection
 * of that kind". The name-segment form stays REFUSED on purpose — see
 * {@link #nameSegmentRejectionMessage} — because accepting two spellings for a singleton buys
 * nothing and the refusal is the only place a caller learns the right one.</p>
 */
public final class ConfigurationRootFqn {

    /** The FQN the configuration root answers to. */
    public static final String TOKEN = "Configuration"; //$NON-NLS-1$

    private ConfigurationRootFqn() {
    }

    /** {@code true} when {@code fqn} is the bare reserved root token. */
    public static boolean isRootFqn(String fqn) {
        return fqn != null && isRootToken(fqn.trim());
    }

    /**
     * {@code true} when a dotted FQN LEADS with the reserved root token — i.e. the caller meant the
     * root but appended segments to it. Shape-only: it says nothing about whether anything resolves.
     */
    public static boolean hasRootTypeToken(String fqn) {
        if (fqn == null) {
            return false;
        }
        String trimmed = fqn.trim();
        int dot = trimmed.indexOf('.');
        return dot > 0 && isRootToken(trimmed.substring(0, dot));
    }

    /**
     * Explains a miss on {@code Configuration.<Something>} instead of reporting it as an absent
     * object. Both tools used to answer a variant of "not found" here, which reads as "this
     * configuration has no such object" — the opposite of the truth.
     */
    public static String nameSegmentRejectionMessage(String fqn) {
        return "The configuration root is a singleton and is addressed by the bare reserved token '" //$NON-NLS-1$
                + TOKEN + "' — with NO .<Name> segment. The configuration's own name is not an FQN name " //$NON-NLS-1$
                + "part, so '" + fqn + "' names nothing. Pass '" + TOKEN + "' on its own to read or change " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "synonym, version, namePrefix, defaultRoles, compatibilityMode or " //$NON-NLS-1$
                + "configurationExtensionPurpose."; //$NON-NLS-1$
    }

    private static boolean isRootToken(String token) {
        String normalized = token.toLowerCase(Locale.ROOT).replace('ё', 'е');
        return "configuration".equals(normalized) || "конфигурация".equals(normalized); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
