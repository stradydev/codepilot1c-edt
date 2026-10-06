package com.codepilot1c.core.edt.metadata;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Request for metadata update operation.
 */
public record UpdateMetadataRequest(
        String projectName,
        String targetFqn,
        Map<String, Object> changes
) {
    public void validate() {
        if (projectName == null || projectName.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_FOUND,
                    "projectName is required", false); //$NON-NLS-1$
        }
        if (targetFqn == null || targetFqn.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_NOT_FOUND,
                    "targetFqn is required", false); //$NON-NLS-1$
        }
        if (changes == null || changes.isEmpty()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_CHANGE,
                    "changes are required", false); //$NON-NLS-1$
        }
        // Refuse here what the mutator would refuse later: a token used to be issued for a changes shape
        // the mutator then rejected or ignored (feedback 2026-10-06, case 2).
        for (String key : changes.keySet()) {
            if (!CHANGE_KEYS.contains(key)) {
                throw new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_CHANGE,
                        "Unknown changes key '" + key + "': expected " + CHANGE_KEYS, false); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        Object predefinedOps = changes.get("predefined_ops"); //$NON-NLS-1$
        if (predefinedOps != null) {
            if (!(predefinedOps instanceof List<?> ops) || ops.isEmpty()) {
                throw new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_CHANGE,
                        "predefined_ops must be a non-empty list of {op, name, ...}", false); //$NON-NLS-1$
            }
            for (Object raw : ops) {
                Object op = raw instanceof Map<?, ?> m ? m.get("op") : null; //$NON-NLS-1$
                Object name = raw instanceof Map<?, ?> m ? m.get("name") : null; //$NON-NLS-1$
                if (op == null || !CatalogPredefinedOps.OPS.contains(String.valueOf(op).trim().toLowerCase(Locale.ROOT))
                        || name == null || String.valueOf(name).isBlank()) {
                    throw new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_CHANGE,
                            "predefined_ops item must be {op: add|set|delete, name, ...}, got: " + raw, false); //$NON-NLS-1$
                }
            }
        }
    }

    /** Top-level keys of {@code changes} the mutator understands. */
    public static final Set<String> CHANGE_KEYS =
            Set.of("set", "unset", "children_ops", "predefined_ops"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
}
