/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.diagnostics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The baseline-diff rule of {@code get_diagnostics}: turn a project's known-noisy diagnostics into a
 * saved snapshot, then answer "what did my change ADD" instead of re-dumping the whole baseline.
 *
 * <p>Pure logic, no EDT/UI type and no file I/O — the caller reads and writes the snapshot text and
 * hands the parsed form back here, so the rule is unit-testable outside the OSGi runtime. Same split
 * as {@link DiagnosticSeverityFilter} and {@link DiagnosticPathFilter}.</p>
 *
 * <h2>Why the fingerprint excludes the line number</h2>
 *
 * <p>A baseline is compared against a build where the caller has just edited code, so nearly every
 * line number below the edit has shifted. Including the line would report the entire tail of a module
 * as "new" after inserting one procedure — the failure mode that makes a diff feature worthless. The
 * fingerprint is therefore rule + subject + message, and multiplicity carries the information a line
 * would have: a snapshot stores how many times each fingerprint occurred, and only occurrences
 * BEYOND that count are reported as new.</p>
 *
 * <p>The cost of that choice, stated plainly because it is a real limit: a change that moves an
 * existing violation from one line to another inside the same file is invisible to the diff, and so is
 * replacing one occurrence with a different one of the same rule and message. The diff answers "are
 * there MORE of these than before", not "is this the same one".</p>
 */
public final class DiagnosticBaseline {

    /** Mode values of the {@code baseline} parameter. */
    public static final String MODE_OFF = "off"; //$NON-NLS-1$

    /** Replace the stored snapshot for this project with the current scan. */
    public static final String MODE_SAVE = "save"; //$NON-NLS-1$

    /** Report only what the stored snapshot does not already account for. */
    public static final String MODE_DIFF = "diff"; //$NON-NLS-1$

    /** Field separator inside a fingerprint: a control character no diagnostic text carries. */
    private static final String SEP = "\u0001"; //$NON-NLS-1$

    private static final String HEADER_PREFIX = "# codepilot1c-diagnostics-baseline v1 "; //$NON-NLS-1$

    /** Longest stem a snapshot file name keeps before it is truncated and hash-suffixed. */
    private static final int MAX_FILE_NAME_STEM = 100;

    private DiagnosticBaseline() {
        // utility
    }

    /**
     * Normalizes the {@code baseline} parameter. Unknown and blank values mean {@link #MODE_OFF} —
     * an unrecognized mode must not silently behave like {@code diff}, which would hide diagnostics
     * the caller never asked to hide.
     *
     * @param raw the raw parameter, may be {@code null}
     * @return one of {@link #MODE_OFF}, {@link #MODE_SAVE}, {@link #MODE_DIFF}
     */
    public static String mode(String raw) {
        if (raw == null) {
            return MODE_OFF;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "save", "write", "record" -> MODE_SAVE; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "diff", "delta", "since" -> MODE_DIFF; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            default -> MODE_OFF;
        };
    }

    /**
     * Builds the line-independent identity of one diagnostic.
     *
     * <p>Fields are joined with a separator that cannot occur in them after normalization, so two
     * different diagnostics cannot collide by concatenation. A blank rule id is represented
     * explicitly rather than dropped — otherwise every rule-less item in a project would share one
     * fingerprint and the counts would be meaningless.</p>
     *
     * @param checkId the rule id, may be {@code null}
     * @param filePath the file path, may be {@code null}
     * @param objectPresentation the object presentation, may be {@code null}
     * @param message the diagnostic message, may be {@code null}
     * @return a stable fingerprint string
     */
    public static String fingerprint(
            String checkId, String filePath, String objectPresentation, String message) {
        return field(checkId) + SEP + field(filePath) + SEP + field(objectPresentation)
                + SEP + field(message);
    }

    /**
     * Selects the indices of the current diagnostics that the baseline does not account for.
     *
     * <p>For a fingerprint the snapshot recorded {@code b} times, the first {@code b} occurrences in
     * the current list are treated as pre-existing and the rest as new. Order therefore matters only
     * in which of several identical items is reported, never in how many.</p>
     *
     * <p>A {@code null} or empty snapshot means "nothing known yet", so everything is new. That is
     * deliberately not an error: a first {@code diff} before any {@code save} should show the caller
     * the full picture rather than an empty answer that looks like success.</p>
     *
     * @param baseline the stored snapshot, may be {@code null}
     * @param currentFingerprints fingerprints of the current diagnostics, in result order
     * @return indices into {@code currentFingerprints} to keep, ascending
     */
    public static List<Integer> selectNew(Snapshot baseline, List<String> currentFingerprints) {
        List<Integer> kept = new ArrayList<>();
        if (currentFingerprints == null) {
            return kept;
        }
        Map<String, Integer> allowance = new LinkedHashMap<>();
        if (baseline != null) {
            allowance.putAll(baseline.counts());
        }
        for (int i = 0; i < currentFingerprints.size(); i++) {
            String fp = currentFingerprints.get(i);
            int remaining = allowance.getOrDefault(fp, 0);
            if (remaining > 0) {
                allowance.put(fp, remaining - 1);
            } else {
                kept.add(i);
            }
        }
        return kept;
    }

    /**
     * Counts occurrences per fingerprint, preserving first-seen order so a serialized snapshot is
     * stable across runs and readable in a diff.
     *
     * @param fingerprints the fingerprints to tally
     * @return fingerprint to occurrence count
     */
    public static Map<String, Integer> tally(List<String> fingerprints) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (fingerprints == null) {
            return counts;
        }
        for (String fp : fingerprints) {
            counts.merge(fp, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Renders a snapshot as text: one header line carrying the stamp, then {@code count TAB
     * fingerprint} per entry.
     *
     * @param savedAt an opaque stamp shown back to the caller on a diff (e.g. an ISO timestamp)
     * @param counts fingerprint to occurrence count
     * @return the serialized snapshot
     */
    public static String serialize(String savedAt, Map<String, Integer> counts) {
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER_PREFIX).append(field(savedAt)).append('\n');
        if (counts != null) {
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                sb.append(e.getValue()).append('\t').append(e.getKey()).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Parses a snapshot produced by {@link #serialize}.
     *
     * <p>Tolerant by design: a missing header, an unparsable count or a truncated file yields the
     * entries it could read rather than an exception. A corrupt baseline must degrade into "fewer
     * things are known to be pre-existing" — which over-reports new diagnostics — never into a failed
     * diagnostics call.</p>
     *
     * @param text the serialized snapshot, may be {@code null}
     * @return the parsed snapshot, never {@code null}
     */
    public static Snapshot parse(String text) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        String savedAt = ""; //$NON-NLS-1$
        if (text == null || text.isBlank()) {
            return new Snapshot(savedAt, counts);
        }
        for (String line : text.split("\n")) { //$NON-NLS-1$
            String row = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line; //$NON-NLS-1$
            if (row.isEmpty()) {
                continue;
            }
            if (row.startsWith(HEADER_PREFIX)) {
                savedAt = row.substring(HEADER_PREFIX.length()).trim();
                continue;
            }
            if (row.charAt(0) == '#') {
                continue;
            }
            int tab = row.indexOf('\t');
            if (tab <= 0 || tab == row.length() - 1) {
                continue;
            }
            try {
                int count = Integer.parseInt(row.substring(0, tab).trim());
                if (count > 0) {
                    counts.merge(row.substring(tab + 1), count, Integer::sum);
                }
            } catch (NumberFormatException e) {
                // Unreadable row: skip it, see the tolerance note above.
            }
        }
        return new Snapshot(savedAt, counts);
    }

    /**
     * Turns a scope key — a project name or a workspace-relative file path — into one file name.
     *
     * <p>Every character that is not a letter, a digit, {@code -} or {@code .} becomes {@code _}, which
     * is what makes {@code Accounting management} and {@code src/Module.bsl} usable as file names at
     * all. Because no path separator can survive that mapping, a scope key cannot escape the baseline
     * directory however it is spelled. Long keys are truncated with a hash suffix so two different long
     * keys cannot collapse onto one snapshot.</p>
     *
     * @param scopeKey the scan's identity, may be {@code null}
     * @return a file name, never blank
     */
    public static String storageFileName(String scopeKey) {
        String base = scopeKey == null ? "" : scopeKey.strip(); //$NON-NLS-1$
        if (base.isEmpty()) {
            base = "default"; //$NON-NLS-1$
        }
        StringBuilder sb = new StringBuilder(base.length());
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            sb.append(Character.isLetterOrDigit(c) || c == '-' || c == '.' ? c : '_');
        }
        String name = sb.toString();
        if (name.length() > MAX_FILE_NAME_STEM) {
            name = name.substring(0, MAX_FILE_NAME_STEM) + '-' + Integer.toHexString(base.hashCode());
        }
        return name + ".txt"; //$NON-NLS-1$
    }

    /**
     * The note a {@code diff} appends, stating what was hidden and how to see it again.
     *
     * <p>It always names the count and the baseline's stamp: a diff that silently reports fewer
     * diagnostics than exist is indistinguishable from a clean scan, which is the same failure the
     * {@code severity=error} no-op once produced.</p>
     *
     * @param suppressed how many pre-existing occurrences were not reported
     * @param savedAt the stamp of the baseline used, may be blank
     * @return the note, starting on its own line
     */
    public static String diffNote(int suppressed, String savedAt) {
        String stamp = savedAt == null || savedAt.isBlank() ? "an unstamped snapshot" : savedAt; //$NON-NLS-1$
        return "\n\nNote: baseline diff — " + suppressed //$NON-NLS-1$
                + (suppressed == 1 ? " pre-existing diagnostic" : " pre-existing diagnostics") //$NON-NLS-1$ //$NON-NLS-2$
                + " not shown (baseline of " + stamp + "). Pass baseline=save to re-record it," //$NON-NLS-1$ //$NON-NLS-2$
                + " baseline=off to see everything."; //$NON-NLS-1$
    }

    /** The note for a {@code diff} with nothing recorded yet: everything is reported, and why. */
    public static String noBaselineNote() {
        return "\n\nNote: no baseline is recorded for this scope, so everything below counts as new." //$NON-NLS-1$
                + " Pass baseline=save once to record the current state, then baseline=diff answers" //$NON-NLS-1$
                + " \"what did my change add\"."; //$NON-NLS-1$
    }

    /**
     * The note a {@code save} appends.
     *
     * @param recorded how many occurrences went into the snapshot
     * @param savedAt the stamp written into it
     * @param maxItemsIgnored whether the caller had asked for a limit that was overridden
     * @return the note, starting on its own line
     */
    public static String saveNote(int recorded, String savedAt, boolean maxItemsIgnored) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\nNote: baseline saved at ").append(savedAt) //$NON-NLS-1$
                .append(" — ").append(recorded) //$NON-NLS-1$
                .append(recorded == 1 ? " diagnostic recorded." : " diagnostics recorded."); //$NON-NLS-1$ //$NON-NLS-2$
        if (maxItemsIgnored) {
            // A snapshot taken from a truncated scan would make every item beyond the cut look new on
            // the next diff — the limit has to lose to the snapshot, and the caller has to be told.
            sb.append(" max_items was ignored for this call so the snapshot covers the whole scope."); //$NON-NLS-1$
        }
        sb.append(" Call baseline=diff from now on to see only what is added on top of it."); //$NON-NLS-1$
        return sb.toString();
    }

    /**
     * The note for a baseline that could not be read or written. The diagnostics are still reported in
     * full — a storage problem must degrade into "no filtering", never into a failed call or, worse,
     * into a silently unfiltered answer that looks filtered.
     *
     * @param mode the requested mode
     * @param reason a short human-readable cause
     * @return the note, starting on its own line
     */
    public static String unavailableNote(String mode, String reason) {
        return "\n\nNote: baseline=" + mode + " could not use its storage (" + reason //$NON-NLS-1$ //$NON-NLS-2$
                + "), so nothing was filtered and everything above is reported as-is."; //$NON-NLS-1$
    }

    /**
     * A stored baseline: when it was taken, and how many times each fingerprint occurred.
     *
     * @param savedAt opaque stamp, shown back to the caller so a stale baseline is visible
     * @param counts fingerprint to occurrence count
     */
    public record Snapshot(String savedAt, Map<String, Integer> counts) {

        /** @return total recorded occurrences across all fingerprints */
        public int totalOccurrences() {
            int total = 0;
            for (int c : counts.values()) {
                total += c;
            }
            return total;
        }

        /** @return whether nothing at all is recorded */
        public boolean isEmpty() {
            return counts.isEmpty();
        }
    }

    /**
     * Normalizes one fingerprint field. Whitespace runs collapse so a re-wrapped message keeps its
     * identity, and {@link #SEP} is scrubbed so no field value can forge a field boundary — the
     * property the fingerprint's collision-freedom rests on. Tabs and newlines go with it, which also
     * keeps the serialized {@code count TAB fingerprint} line format parseable.
     */
    private static String field(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        return value.replace(SEP, " ").strip().replaceAll("\\s+", " "); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
