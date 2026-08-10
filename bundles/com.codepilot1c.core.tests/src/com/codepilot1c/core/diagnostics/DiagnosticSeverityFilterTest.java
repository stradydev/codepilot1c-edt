/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.diagnostics;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests for {@link DiagnosticSeverityFilter}.
 *
 * <p>The regression these pin: {@code severity=error} had no branch of its own in
 * {@code GetDiagnosticsTool.parseSeverity} and fell through to INFO, so asking for errors returned
 * every warning too — indistinguishable from an unfiltered call. The rule lives in core precisely so
 * a test can hold it; the UI tool only maps the level onto its enum.</p>
 */
public class DiagnosticSeverityFilterTest {

    @Test
    public void errorNarrowsToErrorsOnly() {
        assertEquals(DiagnosticSeverityFilter.LEVEL_ERROR, DiagnosticSeverityFilter.minLevel("error"));
        assertEquals(DiagnosticSeverityFilter.LEVEL_ERROR, DiagnosticSeverityFilter.minLevel("errors"));
    }

    @Test
    public void errorIsCaseAndPaddingInsensitive() {
        assertEquals(DiagnosticSeverityFilter.LEVEL_ERROR, DiagnosticSeverityFilter.minLevel("ERROR"));
        assertEquals(DiagnosticSeverityFilter.LEVEL_ERROR, DiagnosticSeverityFilter.minLevel("  Error  "));
    }

    @Test
    public void warningAdmitsErrorsAndWarnings() {
        assertEquals(DiagnosticSeverityFilter.LEVEL_WARNING, DiagnosticSeverityFilter.minLevel("warning"));
        assertEquals(DiagnosticSeverityFilter.LEVEL_WARNING, DiagnosticSeverityFilter.minLevel("warnings"));
        assertEquals(DiagnosticSeverityFilter.LEVEL_WARNING, DiagnosticSeverityFilter.minLevel("warn"));
    }

    @Test
    public void infoAndAllAdmitEverything() {
        assertEquals(DiagnosticSeverityFilter.LEVEL_INFO, DiagnosticSeverityFilter.minLevel("info"));
        assertEquals(DiagnosticSeverityFilter.LEVEL_INFO, DiagnosticSeverityFilter.minLevel("all"));
    }

    @Test
    public void unknownAndMissingFallBackToNoFiltering() {
        assertEquals(DiagnosticSeverityFilter.LEVEL_INFO, DiagnosticSeverityFilter.minLevel(null));
        assertEquals(DiagnosticSeverityFilter.LEVEL_INFO, DiagnosticSeverityFilter.minLevel(""));
        assertEquals(DiagnosticSeverityFilter.LEVEL_INFO, DiagnosticSeverityFilter.minLevel("critical"));
    }

    @Test
    public void levelsAreOrderedInfoWarningError() {
        assertEquals(0, DiagnosticSeverityFilter.LEVEL_INFO);
        assertEquals(1, DiagnosticSeverityFilter.LEVEL_WARNING);
        assertEquals(2, DiagnosticSeverityFilter.LEVEL_ERROR);
    }
}
