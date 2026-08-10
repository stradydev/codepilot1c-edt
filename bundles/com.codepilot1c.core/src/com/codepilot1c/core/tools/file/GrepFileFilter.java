/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.file;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides which files {@code grep} opens — the search corpus rule, extracted from {@code GrepTool}
 * so it can be unit-tested without a workspace.
 *
 * <p>Two silent-zero traps live here, both reported from real sessions:</p>
 * <ul>
 * <li>The default corpus used to be {@code .bsl/.os/.java/.xml} only, so an item name inside a
 * {@code .form} or {@code .mdo} was never searched and the caller got a clean "0 matches" that read
 * as "not present". 1C metadata sources are now part of the default corpus.</li>
 * <li>A {@code file_pattern} was matched against the file NAME with a full match, so the habitual
 * {@code **}{@code /*.bsl} matched nothing at all. Path-shaped globs now match on their last
 * segment, and a comma-separated list is accepted.</li>
 * </ul>
 */
public final class GrepFileFilter {

    /**
     * Extensions searched when the caller passes no {@code file_pattern}. BSL and OScript are the
     * code; {@code .mdo}/{@code .form}/{@code .dcs}/{@code .rights} are the 1C metadata sources a
     * caller means when they search for an object, item or role name; {@code .xml}/{@code .java}
     * cover the remaining plugin-side content.
     */
    private static final List<String> DEFAULT_EXTENSIONS = List.of(
            ".bsl", ".os", ".mdo", ".form", ".dcs", ".rights", ".xml", ".java"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$

    private GrepFileFilter() {
        // utility
    }

    /**
     * @return the default corpus rendered for a caller-facing message, e.g. {@code "*.bsl, *.os, ..."}
     */
    public static String describeDefaultCorpus() {
        StringBuilder sb = new StringBuilder();
        for (String ext : DEFAULT_EXTENSIONS) {
            if (sb.length() > 0) {
                sb.append(", "); //$NON-NLS-1$
            }
            sb.append('*').append(ext);
        }
        return sb.toString();
    }

    /**
     * @param name the file name (no path)
     * @param filePattern the caller's {@code file_pattern}; {@code null}/blank selects the default corpus
     * @return whether this file should be opened and searched
     */
    public static boolean matches(String name, String filePattern) {
        if (name == null) {
            return false;
        }
        if (filePattern == null || filePattern.isBlank()) {
            String lower = name.toLowerCase(Locale.ROOT);
            for (String ext : DEFAULT_EXTENSIONS) {
                if (lower.endsWith(ext)) {
                    return true;
                }
            }
            return false;
        }
        for (String single : filePattern.split(",")) { //$NON-NLS-1$
            String glob = single.trim();
            if (glob.isEmpty()) {
                continue;
            }
            if (matchesSingleGlob(name, glob)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesSingleGlob(String name, String glob) {
        // A path-shaped glob ("**/*.bsl", "src/**/*.form") is matched on its last segment: this
        // filter only ever sees a bare file name, and a full match against the whole glob would
        // silently reject every file.
        String effective = glob;
        int lastSep = Math.max(effective.lastIndexOf('/'), effective.lastIndexOf('\\'));
        if (lastSep >= 0) {
            effective = effective.substring(lastSep + 1);
        }
        if (effective.isEmpty() || "**".equals(effective)) { //$NON-NLS-1$
            return true;
        }
        String regex = effective
                .replace(".", "\\.") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("*", ".*") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("?", "."); //$NON-NLS-1$ //$NON-NLS-2$
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(name).matches();
    }
}
