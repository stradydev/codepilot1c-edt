/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses and applies the {@code path_contains} parameter of {@code get_diagnostics} — the narrowing
 * that lets a caller ask "does anything under CommonModules/MyModule show an error" without paying
 * for a whole project scan.
 *
 * <p>Holds no EDT/UI type so the rule is unit-testable outside the OSGi runtime; the UI bundle owns
 * the diagnostic record and calls {@link #matches} per item. Same split as
 * {@link DiagnosticSeverityFilter}.</p>
 *
 * <p><b>Why it tests two fields.</b> A project-wide scan mixes two kinds of item. Workspace-attached
 * markers carry a real file path. Runtime-marker-manager items (metadata/object checks — the bulk of a
 * project scan) frequently carry no usable path at all and identify their subject only through an
 * object presentation such as {@code CommonModule.Error.Module}. Matching the path alone would
 * silently drop exactly the items a project-wide filter exists to find, so a needle is tested against
 * both and matching either is enough.</p>
 */
public final class DiagnosticPathFilter {

    private DiagnosticPathFilter() {
        // utility
    }

    /**
     * Splits a raw {@code path_contains} value into comparable needles: comma-separated, trimmed,
     * lower-cased, back-slashes folded to forward ones so a caller may pass either separator.
     *
     * <p>Blank entries are dropped rather than kept as empty needles — an empty needle matches
     * everything and would turn a typo like {@code "a,,b"} into a silent no-op filter.</p>
     *
     * @param raw the raw parameter, may be {@code null}
     * @return the needles, empty when no usable one was given (meaning "do not filter")
     */
    public static List<String> parse(String raw) {
        List<String> needles = new ArrayList<>();
        if (raw == null) {
            return needles;
        }
        for (String part : raw.split(",")) { //$NON-NLS-1$
            String needle = normalize(part);
            if (!needle.isEmpty()) {
                needles.add(needle);
            }
        }
        return needles;
    }

    /**
     * {@code true} when no needle was supplied, i.e. the caller did not ask for narrowing. Callers
     * use this to skip the filtering pass entirely instead of walking every diagnostic.
     *
     * @param needles the parsed needles
     * @return whether filtering is disabled
     */
    public static boolean isDisabled(List<String> needles) {
        return needles == null || needles.isEmpty();
    }

    /**
     * Tests one diagnostic's coordinates against the needles.
     *
     * <p>A disabled filter admits everything. Otherwise the item is kept when ANY needle occurs in
     * the file path or in the object presentation — OR rather than AND, because several needles
     * express "any of these areas", which is what a caller listing sibling modules means.</p>
     *
     * @param filePath the diagnostic's file path, may be {@code null}
     * @param objectPresentation the diagnostic's object presentation, may be {@code null}
     * @param needles the parsed needles
     * @return whether the diagnostic passes the filter
     */
    public static boolean matches(String filePath, String objectPresentation, List<String> needles) {
        if (isDisabled(needles)) {
            return true;
        }
        String path = normalize(filePath);
        String object = normalize(objectPresentation);
        if (path.isEmpty() && object.isEmpty()) {
            // Nothing to match against. Dropping is the honest answer: the caller asked to narrow to
            // a named area and this item cannot be shown to belong to it.
            return false;
        }
        for (String needle : needles) {
            if (path.contains(needle) || object.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        return value.trim().toLowerCase(Locale.ROOT).replace('\\', '/');
    }
}
