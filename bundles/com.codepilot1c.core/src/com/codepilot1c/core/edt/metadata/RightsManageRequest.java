package com.codepilot1c.core.edt.metadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Request to mutate a Role's object-level rights grants.
 *
 * <p>A Role's rights live in the rights model reachable through the BM as
 * {@code Role.getRights()} → {@code RoleDescription}; each grant targets an
 * {@code MdObject} (or sub-object) and one named {@code Right} (Read, Update,
 * Delete, View, Edit, DeletionMark, …) and sets it to one of
 * {@code set} / {@code unset} / {@code provided}.</p>
 */
public record RightsManageRequest(
        String projectName,
        String roleFqn,
        List<RightGrant> grants
) {

    /** Canonical right-value tokens accepted in the {@code value} field. */
    public static final String VALUE_SET = "set"; //$NON-NLS-1$
    public static final String VALUE_UNSET = "unset"; //$NON-NLS-1$
    public static final String VALUE_PROVIDED = "provided"; //$NON-NLS-1$
    /**
     * Fully remove the explicit right entry (and prune the now-empty {@code <object>} block), rather
     * than writing {@code <value>false</value>} ({@link #VALUE_UNSET}) or a valueless inherited entry
     * ({@link #VALUE_PROVIDED}), both of which leave the object's rights block on disk. Needed to strip
     * a stray/invalid grant such as a rights-less-type block that stalls DB restructure (BF-12936).
     */
    public static final String VALUE_REMOVE = "remove"; //$NON-NLS-1$

    /**
     * A single object-level right grant. {@code value} is one of set/unset/provided/remove.
     *
     * <p>{@code restrictions} carries the row-level-security conditions to attach to this
     * grant ({@code ObjectRight.restrictionsByCondition}). Its three states are distinct and
     * all reachable from the wire:</p>
     * <ul>
     *   <li>{@code null} — the key was absent: leave whatever RLS the grant already has ALONE.
     *       This is what makes a plain value-only grant non-destructive to an existing
     *       restriction;</li>
     *   <li>empty list — an explicit erase: drop every condition on this grant;</li>
     *   <li>non-empty list — the conditions to hold, in order (declarative replace, not append,
     *       so re-sending the same request is a no-op rather than a duplicate).</li>
     * </ul>
     */
    public record RightGrant(String objectFqn, String right, String value, List<String> restrictions) {
    }

    public void validate() {
        if (projectName == null || projectName.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_FOUND,
                    "projectName is required", false); //$NON-NLS-1$
        }
        if (roleFqn == null || roleFqn.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_NOT_FOUND,
                    "role is required", false); //$NON-NLS-1$
        }
        if (grants == null || grants.isEmpty()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_CHANGE,
                    "grants are required", false); //$NON-NLS-1$
        }
    }

    /**
     * Parse the {@code grants} payload into normalized {@link RightGrant} records.
     * Pure (no EMF/BM access) so the parsing contract is unit-testable. Accepts a
     * list of {@code {object_fqn, right, value}} maps (or a single such map).
     *
     * <ul>
     *   <li>object key: {@code object_fqn}/{@code object}/{@code fqn}/{@code target_fqn};</li>
     *   <li>right key: {@code right}/{@code right_name}/{@code name};</li>
     *   <li>value key: {@code value}/{@code state}/{@code access} — accepts
     *       set/true/grant/allow, unset/false/revoke/deny, provided/inherit/default;
     *       defaults to {@code set} when omitted;</li>
     *   <li>restriction key: {@code restriction}/{@code restrictions}/{@code rls}/
     *       {@code condition} — optional RLS conditions, see {@link #parseRestrictions}.</li>
     * </ul>
     */
    public static List<RightGrant> parseGrants(Object raw, String field) {
        if (raw == null) {
            return List.of();
        }
        List<?> source;
        if (raw instanceof List<?> list) {
            source = list;
        } else if (raw instanceof Map<?, ?>) {
            source = List.of(raw);
        } else {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_CHANGE,
                    "'" + field + "' must be a list of {object_fqn, right, value} objects", false); //$NON-NLS-1$ //$NON-NLS-2$
        }
        List<RightGrant> result = new ArrayList<>(source.size());
        for (Object item : source) {
            if (!(item instanceof Map<?, ?> entry)) {
                throw new MetadataOperationException(
                        MetadataOperationCode.INVALID_METADATA_CHANGE,
                        "Each '" + field + "' entry must be a {object_fqn, right, value} object", false); //$NON-NLS-1$ //$NON-NLS-2$
            }
            String objectFqn = trimmed(firstValue(entry, "object_fqn", "object", "fqn", "target_fqn")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            if (objectFqn == null || objectFqn.isBlank()) {
                throw new MetadataOperationException(
                        MetadataOperationCode.INVALID_METADATA_CHANGE,
                        "Each '" + field + "' entry requires a non-empty 'object_fqn'", false); //$NON-NLS-1$ //$NON-NLS-2$
            }
            String right = trimmed(firstValue(entry, "right", "right_name", "name")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (right == null || right.isBlank()) {
                throw new MetadataOperationException(
                        MetadataOperationCode.INVALID_METADATA_CHANGE,
                        "Each '" + field + "' entry requires a non-empty 'right' for " + objectFqn, false); //$NON-NLS-1$ //$NON-NLS-2$
            }
            String value = normalizeValue(firstValue(entry, "value", "state", "access"), objectFqn, right); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            List<String> restrictions = parseRestrictions(entry, objectFqn, right);
            if (restrictions != null && VALUE_REMOVE.equals(value)) {
                // A 'remove' drops the whole ObjectRight entry, restrictions and all — so a request
                // that asks for both is self-contradictory whichever way it is honored. Refuse it
                // rather than silently pick one: dropping the restriction quietly is exactly the
                // failure mode where a caller believes an RLS was written and it never was.
                throw new MetadataOperationException(
                        MetadataOperationCode.INVALID_METADATA_CHANGE,
                        "Cannot combine value 'remove' with a restriction for " + right + " on " //$NON-NLS-1$ //$NON-NLS-2$
                                + objectFqn + ": removing the grant deletes its conditions too." //$NON-NLS-1$
                                + " Drop the restriction key, or use set/unset/provided instead.", //$NON-NLS-1$
                        false);
            }
            result.add(new RightGrant(objectFqn, right, value, restrictions));
        }
        return result;
    }

    /**
     * Reads the optional per-grant RLS conditions.
     *
     * <p>Accepts a bare string (one condition), a list of strings, or a list of
     * {@code {condition: "..."}} maps; a blank string or an empty list is the explicit erase.
     * Returns {@code null} when the caller said nothing about restrictions at all — see
     * {@link RightGrant} for why absent and empty must not collapse into one state.</p>
     *
     * <p>Field-level RLS ({@code Rls.fields}) is NOT supported here and a {@code fields} key is
     * REFUSED rather than dropped: a silently ignored key reads to the caller as an applied
     * per-field restriction that was in fact written as a whole-object one.</p>
     */
    private static List<String> parseRestrictions(Map<?, ?> entry, String objectFqn, String right) {
        Object raw = firstValue(entry, "restriction", "restrictions", "rls", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "condition", "conditions", "restriction_by_condition", "restrictionsByCondition"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        if (raw == null) {
            return null;
        }
        if (raw instanceof String text) {
            return text.isBlank() ? List.of() : List.of(text.trim());
        }
        if (raw instanceof Map<?, ?> map) {
            return List.of(restrictionFromMap(map, objectFqn, right));
        }
        if (!(raw instanceof List<?> list)) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_CHANGE,
                    "'restriction' for " + right + " on " + objectFqn //$NON-NLS-1$ //$NON-NLS-2$
                            + " must be a condition string, a list of them, or a list of" //$NON-NLS-1$
                            + " {condition} objects", false); //$NON-NLS-1$
        }
        List<String> conditions = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                conditions.add(restrictionFromMap(map, objectFqn, right));
                continue;
            }
            String text = item == null ? null : String.valueOf(item).trim();
            if (text == null || text.isBlank()) {
                // Inside a list a blank is not the erase gesture (the empty list already is), it is
                // an entry that would serialize as an empty <condition/> — a broken RLS.
                throw new MetadataOperationException(
                        MetadataOperationCode.INVALID_METADATA_CHANGE,
                        "Blank restriction condition for " + right + " on " + objectFqn //$NON-NLS-1$ //$NON-NLS-2$
                                + ". Pass an empty list to clear all conditions instead.", false); //$NON-NLS-1$
            }
            conditions.add(text);
        }
        return List.copyOf(conditions);
    }

    private static String restrictionFromMap(Map<?, ?> map, String objectFqn, String right) {
        if (firstValue(map, "fields", "field") != null) { //$NON-NLS-1$ //$NON-NLS-2$
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_CHANGE,
                    "Field-level RLS is not supported for " + right + " on " + objectFqn //$NON-NLS-1$ //$NON-NLS-2$
                            + ": a restriction here always applies to the whole object." //$NON-NLS-1$
                            + " Drop the 'fields' key, or edit the .rights file directly for a" //$NON-NLS-1$
                            + " per-field condition.", false); //$NON-NLS-1$
        }
        String condition = trimmed(firstValue(map, "condition", "text", "value", "restriction")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        if (condition == null || condition.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_CHANGE,
                    "Each restriction entry for " + right + " on " + objectFqn //$NON-NLS-1$ //$NON-NLS-2$
                            + " requires a non-empty 'condition'", false); //$NON-NLS-1$
        }
        return condition;
    }

    private static String normalizeValue(Object raw, String objectFqn, String right) {
        if (raw == null) {
            return VALUE_SET;
        }
        String text = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        return switch (text) {
            case "", "set", "true", "grant", "allow", "1" -> VALUE_SET; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            case "unset", "false", "revoke", "deny", "0" -> VALUE_UNSET; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            case "provided", "inherit", "inherited", "default" -> VALUE_PROVIDED; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "remove", "clear", "delete", "drop" -> VALUE_REMOVE; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            default -> throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_PROPERTY_VALUE,
                    "Invalid right value '" + raw + "' for " + right + " on " + objectFqn //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            + " (expected set/unset/provided/remove)", false); //$NON-NLS-1$
        };
    }

    private static Object firstValue(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String str && str.equalsIgnoreCase(key)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private static String trimmed(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }
}
