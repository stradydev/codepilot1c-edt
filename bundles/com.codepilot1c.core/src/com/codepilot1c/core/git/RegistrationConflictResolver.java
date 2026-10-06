/*******************************************************************************
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Copyright (C) 2026 codepilot1c-edt contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License v3.0 as published by the
 * Free Software Foundation.
 ******************************************************************************/
package com.codepilot1c.core.git;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves git merge-conflict hunks in a {@code Configuration.mdo} whose both sides consist ONLY of
 * top-level object registration lines ({@code <commonModules>CommonModule.X</commonModules>}) — the
 * "two branches each registered new objects at the same anchor" conflict (feedback 2026-07-21,
 * cases 4 and 7). While the file carries conflict markers EDT parses it only partially, so no BM-API
 * call can repair it; this is a text-level resolver, deliberately narrow:
 *
 * <ul>
 * <li>Every non-blank line of every hunk side (ours, base when diff3-style, theirs) must be a
 * registration line of a MULTI-valued list. Anything else — properties, a single-valued
 * {@code defaultLanguage}, other XML — refuses the whole file and nothing is written.</li>
 * <li>The resolution is the union of both sides, grouped per list element in order of first
 * appearance, ours first. A line present in a diff3 base but missing from a side was deleted by
 * that side and is dropped.</li>
 * <li>Every kept registration must name an object that exists on disk (its own {@code .mdo} with the
 * matching {@code mdclass} kind). Without a base a side-only line may be "the other side deleted it";
 * the disk check drops such a line instead of resurrecting a dangling registration.</li>
 * </ul>
 *
 * Pure (no I/O besides the injected existence predicate) so the matrix is unit-tested.
 */
public final class RegistrationConflictResolver {

    private static final Pattern REGISTRATION =
            Pattern.compile("^\\s*<(\\w+)>([A-Za-z]+)\\.([^<\\s]+)</\\1>\\s*$"); //$NON-NLS-1$

    /** {@code Kind.Name} plus the list element it sits in and the raw line. */
    public record Registration(String element, String kind, String name, String line) {
        public String key() {
            return element + '|' + kind.toLowerCase(Locale.ROOT) + '.' + name.toLowerCase(Locale.ROOT);
        }

        public String fqn() {
            return kind + '.' + name;
        }
    }

    /** Outcome for one file. {@code refusal} non-null ⇒ nothing may be written. */
    public record Result(String resolvedText, int hunks, List<String> keptFromOurs, List<String> keptFromTheirs,
            List<String> droppedMissing, List<String> droppedDeleted, String refusal) {

        public boolean refused() {
            return refusal != null;
        }
    }

    private RegistrationConflictResolver() {
    }

    /**
     * @param text         the conflicted file content
     * @param objectExists answers whether {@code Kind.Name} exists on disk
     */
    public static Result resolve(String text, Predicate<Registration> objectExists) {
        String eol = text.contains("\r\n") ? "\r\n" : "\n"; //$NON-NLS-1$ //$NON-NLS-2$
        String[] lines = text.split("\r?\n", -1); //$NON-NLS-1$
        List<String> out = new ArrayList<>(lines.length);
        List<String> fromOurs = new ArrayList<>();
        List<String> fromTheirs = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        int hunks = 0;
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            if (!line.startsWith("<<<<<<<")) { //$NON-NLS-1$
                if (line.startsWith("=======") || line.startsWith(">>>>>>>") || line.startsWith("|||||||")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    return refusal(hunks, "stray conflict marker outside a hunk at line " + (i + 1)); //$NON-NLS-1$
                }
                out.add(line);
                i++;
                continue;
            }
            hunks++;
            int start = i + 1;
            List<String> ours = new ArrayList<>();
            List<String> base = null;
            List<String> theirs = new ArrayList<>();
            List<String> current = ours;
            int j = start;
            boolean closed = false;
            for (; j < lines.length; j++) {
                String l = lines[j];
                if (l.startsWith("|||||||") && current == ours) { //$NON-NLS-1$
                    base = new ArrayList<>();
                    current = base;
                } else if (l.startsWith("=======") && current != theirs) { //$NON-NLS-1$
                    current = theirs;
                } else if (l.startsWith(">>>>>>>") && current == theirs) { //$NON-NLS-1$
                    closed = true;
                    break;
                } else if (l.startsWith("<<<<<<<")) { //$NON-NLS-1$
                    return refusal(hunks, "nested conflict marker at line " + (j + 1)); //$NON-NLS-1$
                } else {
                    current.add(l);
                }
            }
            if (!closed) {
                return refusal(hunks, "unterminated conflict hunk starting at line " + (i + 1)); //$NON-NLS-1$
            }
            List<Registration> o = parseSide(ours);
            List<Registration> t = parseSide(theirs);
            List<Registration> b = base == null ? List.of() : parseSide(base);
            if (o == null || t == null || b == null) {
                return refusal(hunks, firstNonRegistration(ours, theirs, base, i + 1));
            }
            Set<String> baseKeys = new LinkedHashSet<>();
            b.forEach(r -> baseKeys.add(r.key()));
            Set<String> oursKeys = keys(o);
            Set<String> theirsKeys = keys(t);
            // element -> ordered kept registrations
            Map<String, List<Registration>> grouped = new LinkedHashMap<>();
            Set<String> seen = new LinkedHashSet<>();
            for (List<Registration> side : List.of(o, t)) {
                boolean isOurs = side == o;
                for (Registration r : side) {
                    if (!seen.add(r.key())) {
                        continue;
                    }
                    if (baseKeys.contains(r.key()) && (!oursKeys.contains(r.key()) || !theirsKeys.contains(r.key()))) {
                        deleted.add(r.fqn());
                        continue;
                    }
                    boolean inOther = isOurs ? theirsKeys.contains(r.key()) : oursKeys.contains(r.key());
                    // Only a one-sided line is the merge's doing; a line both sides agree on is kept as is.
                    if (!inOther && !objectExists.test(r)) {
                        missing.add(r.fqn());
                        continue;
                    }
                    grouped.computeIfAbsent(r.element(), k -> new ArrayList<>()).add(r);
                    if (!inOther) {
                        (isOurs ? fromOurs : fromTheirs).add(r.fqn());
                    }
                }
            }
            grouped.values().forEach(list -> list.forEach(r -> out.add(r.line())));
            i = j + 1;
        }
        if (hunks == 0) {
            return refusal(0, "no conflict markers found"); //$NON-NLS-1$
        }
        return new Result(String.join(eol, out), hunks, fromOurs, fromTheirs, missing, deleted, null);
    }

    /** Registrations of one hunk side, or {@code null} when a non-blank line is not one. */
    private static List<Registration> parseSide(List<String> side) {
        List<Registration> result = new ArrayList<>();
        for (String l : side) {
            if (l.isBlank()) {
                continue;
            }
            Registration r = parse(l);
            if (r == null) {
                return null;
            }
            result.add(r);
        }
        return result;
    }

    static Registration parse(String line) {
        Matcher m = REGISTRATION.matcher(line);
        if (!m.matches() || !isMultiValuedList(m.group(1))) {
            return null;
        }
        return new Registration(m.group(1), m.group(2), m.group(3), line);
    }

    /**
     * Multi-valued registration lists of {@code Configuration.mdo} are plural ({@code commonModules},
     * {@code roles}, {@code defaultRoles}, {@code filterCriteria}); single-valued references are the
     * {@code default*} properties ({@code defaultLanguage}, {@code defaultStyle}, …) — a union there would
     * write two values into one slot, so they never qualify.
     */
    static boolean isMultiValuedList(String element) {
        if ("defaultRoles".equals(element) || "filterCriteria".equals(element)) { //$NON-NLS-1$ //$NON-NLS-2$
            return true;
        }
        return !element.startsWith("default") && element.endsWith("s"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Set<String> keys(List<Registration> side) {
        Set<String> keys = new LinkedHashSet<>();
        side.forEach(r -> keys.add(r.key()));
        return keys;
    }

    private static String firstNonRegistration(List<String> ours, List<String> theirs, List<String> base, int hunkLine) {
        List<List<String>> sides = new ArrayList<>(List.of(ours, theirs));
        if (base != null) {
            sides.add(base);
        }
        for (List<String> side : sides) {
            for (String l : side) {
                if (!l.isBlank() && parse(l) == null) {
                    return "conflict hunk at line " + hunkLine + " is not registration-only (offending line: '" //$NON-NLS-1$ //$NON-NLS-2$
                            + l.strip() + "') — resolve it by hand or via the EDT merge editor"; //$NON-NLS-1$
                }
            }
        }
        return "conflict hunk at line " + hunkLine + " is not registration-only"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Result refusal(int hunks, String reason) {
        return new Result(null, hunks, List.of(), List.of(), List.of(), List.of(), reason);
    }
}
