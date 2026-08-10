/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Behaviour of the {@code baseline} save/diff rule of {@code get_diagnostics}.
 *
 * <p>Asserted by result. The cases that matter are the ones where a plausible-looking implementation
 * silently lies: an unknown mode behaving like {@code diff} (hiding diagnostics nobody asked to hide),
 * a line-sensitive fingerprint reporting a whole module as new after one insertion, and a corrupt
 * snapshot failing the call instead of degrading.</p>
 */
public class DiagnosticBaselineTest {

    @Test
    public void unknownModeIsOffNotDiff() {
        // Defaulting an unrecognized value to diff would suppress real diagnostics on a typo.
        assertEquals(DiagnosticBaseline.MODE_OFF, DiagnosticBaseline.mode(null));
        assertEquals(DiagnosticBaseline.MODE_OFF, DiagnosticBaseline.mode(""));
        assertEquals(DiagnosticBaseline.MODE_OFF, DiagnosticBaseline.mode("diffff"));
        assertEquals(DiagnosticBaseline.MODE_SAVE, DiagnosticBaseline.mode(" SAVE "));
        assertEquals(DiagnosticBaseline.MODE_DIFF, DiagnosticBaseline.mode("Diff"));
    }

    @Test
    public void fingerprintIgnoresTheLineNumberByConstruction() {
        // There is no line parameter at all — the same violation moved down the file keeps identity.
        String a = DiagnosticBaseline.fingerprint("ql-temp-table-index", "m.bsl", "Obj", "no index");
        String b = DiagnosticBaseline.fingerprint("ql-temp-table-index", "m.bsl", "Obj", "no index");
        assertEquals(a, b);
    }

    @Test
    public void fingerprintNormalizesWhitespaceButKeepsFieldsDistinct() {
        assertEquals(
                DiagnosticBaseline.fingerprint("r", "m.bsl", "Obj", "two  words"),
                DiagnosticBaseline.fingerprint("r", "m.bsl", "Obj", " two\n words "));
        // Field boundaries must not be forgeable by moving text between fields.
        assertNotEquals(
                DiagnosticBaseline.fingerprint("r", "m.bsl", "", "Obj"),
                DiagnosticBaseline.fingerprint("r", "m.bsl", "Obj", ""));
    }

    @Test
    public void blankRuleIdsDoNotCollapseIntoOneFingerprint() {
        assertNotEquals(
                DiagnosticBaseline.fingerprint(null, "a.bsl", null, "first"),
                DiagnosticBaseline.fingerprint(null, "a.bsl", null, "second"));
    }

    @Test
    public void diffAgainstAnEmptyBaselineReportsEverything() {
        // A first diff before any save must show the full picture, not an empty "clean" answer.
        List<String> current = List.of("a", "b", "c");
        assertEquals(List.of(0, 1, 2), DiagnosticBaseline.selectNew(null, current));
        assertEquals(List.of(0, 1, 2),
                DiagnosticBaseline.selectNew(DiagnosticBaseline.parse(""), current));
    }

    @Test
    public void diffSuppressesExactlyTheRecordedMultiplicity() {
        Map<String, Integer> counts = DiagnosticBaseline.tally(List.of("a", "a", "b"));
        DiagnosticBaseline.Snapshot snap = DiagnosticBaseline.parse(
                DiagnosticBaseline.serialize("2026-08-10T00:00:00Z", counts));
        // Three "a" now where two were known: one is new. "b" unchanged. "c" wholly new.
        List<String> current = List.of("a", "a", "a", "b", "c");
        assertEquals(List.of(2, 4), DiagnosticBaseline.selectNew(snap, current));
    }

    @Test
    public void diffReportsNothingWhenTheScanMatchesTheBaseline() {
        Map<String, Integer> counts = DiagnosticBaseline.tally(List.of("a", "a", "b"));
        DiagnosticBaseline.Snapshot snap = DiagnosticBaseline.parse(
                DiagnosticBaseline.serialize("stamp", counts));
        assertTrue(DiagnosticBaseline.selectNew(snap, List.of("a", "a", "b")).isEmpty());
    }

    @Test
    public void aFixedDiagnosticSimplyDisappearsWithoutDisturbingTheRest() {
        Map<String, Integer> counts = DiagnosticBaseline.tally(List.of("a", "b"));
        DiagnosticBaseline.Snapshot snap = DiagnosticBaseline.parse(
                DiagnosticBaseline.serialize("stamp", counts));
        // "a" was fixed; "b" remains pre-existing; nothing is reported as new.
        assertTrue(DiagnosticBaseline.selectNew(snap, List.of("b")).isEmpty());
    }

    @Test
    public void snapshotRoundTripsThroughTextIncludingTheStamp() {
        Map<String, Integer> counts = DiagnosticBaseline.tally(List.of("x", "x", "y"));
        String text = DiagnosticBaseline.serialize("2026-08-10T07:00:00Z", counts);
        DiagnosticBaseline.Snapshot snap = DiagnosticBaseline.parse(text);
        assertEquals("2026-08-10T07:00:00Z", snap.savedAt());
        assertEquals(Integer.valueOf(2), snap.counts().get("x"));
        assertEquals(Integer.valueOf(1), snap.counts().get("y"));
        assertEquals(3, snap.totalOccurrences());
    }

    @Test
    public void parseSurvivesCrlfAndCorruptRowsInsteadOfFailingTheCall() {
        String text = "# codepilot1c-diagnostics-baseline v1 stamp\r\n"
                + "2\tkeeper\r\n"
                + "notanumber\tignored\r\n"
                + "\r\n"
                + "0\tzero-is-not-an-occurrence\r\n"
                + "5\r\n";
        DiagnosticBaseline.Snapshot snap = DiagnosticBaseline.parse(text);
        assertEquals("stamp", snap.savedAt());
        assertEquals(Map.of("keeper", 2), snap.counts());
        // Degrading means over-reporting new items, never hiding them.
        assertEquals(List.of(1), DiagnosticBaseline.selectNew(snap, List.of("keeper", "ignored")));
    }

    @Test
    public void anEmptySnapshotIsRecognizableAsEmpty() {
        assertTrue(DiagnosticBaseline.parse(null).isEmpty());
        assertTrue(DiagnosticBaseline.parse("# codepilot1c-diagnostics-baseline v1 s\n").isEmpty());
    }

    // --- storage file name --------------------------------------------------

    @Test
    public void aProjectNameWithSpacesBecomesOneUsableFileName() {
        // "Accounting management" is the real sandbox project; a raw name is not a legal file name.
        assertEquals("Accounting_management.txt", DiagnosticBaseline.storageFileName("Accounting management"));
    }

    @Test
    public void noScopeKeyCanEscapeTheBaselineDirectory() {
        // Every separator maps to '_', so the result is always a single path segment.
        for (String hostile : List.of("../../etc/passwd", "a/b\\c", "C:\\Windows\\x", "..\\..\\y")) {
            String name = DiagnosticBaseline.storageFileName(hostile);
            assertFalse(name, name.contains("/"));
            assertFalse(name, name.contains("\\"));
            assertFalse(name, name.contains(":"));
        }
    }

    @Test
    public void aBlankScopeKeyStillYieldsAName() {
        assertEquals("default.txt", DiagnosticBaseline.storageFileName(null));
        assertEquals("default.txt", DiagnosticBaseline.storageFileName("   "));
    }

    @Test
    public void twoDifferentLongKeysDoNotCollapseOntoOneSnapshot() {
        String a = "x".repeat(160) + "-alpha";
        String b = "x".repeat(160) + "-beta";
        assertNotEquals(DiagnosticBaseline.storageFileName(a), DiagnosticBaseline.storageFileName(b));
    }

    // --- notes: a filtered answer must never look like a clean one ----------

    @Test
    public void theDiffNoteStatesHowManyWereHiddenAndHowToSeeThem() {
        String note = DiagnosticBaseline.diffNote(6402, "2026-08-10T09:00:00Z");
        assertTrue(note, note.contains("6402"));
        assertTrue(note, note.contains("2026-08-10T09:00:00Z"));
        assertTrue(note, note.contains("baseline=off"));
    }

    @Test
    public void theDiffNoteSurvivesAnUnstampedSnapshot() {
        String note = DiagnosticBaseline.diffNote(1, "");
        assertTrue(note, note.contains("unstamped"));
        assertTrue(note, note.contains("1 pre-existing diagnostic "));
    }

    @Test
    public void aFirstDiffSaysNothingIsRecordedRatherThanLookingClean() {
        String note = DiagnosticBaseline.noBaselineNote();
        assertTrue(note, note.contains("no baseline"));
        assertTrue(note, note.contains("baseline=save"));
    }

    @Test
    public void theSaveNoteAdmitsOverridingTheLimitOnlyWhenOneWasAsked() {
        assertTrue(DiagnosticBaseline.saveNote(12, "stamp", true).contains("max_items was ignored"));
        assertFalse(DiagnosticBaseline.saveNote(12, "stamp", false).contains("max_items"));
        assertTrue(DiagnosticBaseline.saveNote(12, "stamp", false).contains("12 diagnostics recorded"));
    }

    @Test
    public void anUnusableStorageSaysNothingWasFiltered() {
        // The dangerous outcome would be a full report that READS as a diff.
        String note = DiagnosticBaseline.unavailableNote(DiagnosticBaseline.MODE_DIFF, "access denied");
        assertTrue(note, note.contains("access denied"));
        assertTrue(note, note.contains("nothing was filtered"));
    }
}
