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
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Behaviour of the {@code path_contains} narrowing of {@code get_diagnostics}.
 *
 * <p>Asserted by result, not by asserting that the source contains some text — a source-contract test
 * would not notice any of these cases going wrong.</p>
 */
public class DiagnosticPathFilterTest {

    @Test
    public void absentFilterAdmitsEverything() {
        assertTrue(DiagnosticPathFilter.isDisabled(DiagnosticPathFilter.parse(null)));
        assertTrue(DiagnosticPathFilter.isDisabled(DiagnosticPathFilter.parse("   ")));
        assertTrue(DiagnosticPathFilter.matches("any/path.bsl", "Any.Object", List.of()));
    }

    @Test
    public void blankEntriesAreDroppedSoATypoDoesNotDisableTheFilter() {
        // "a,,b" must mean two needles, not three with an empty one that matches everything.
        List<String> needles = DiagnosticPathFilter.parse("Access,,Sales");
        assertEquals(List.of("access", "sales"), needles);
        assertFalse(DiagnosticPathFilter.matches("src/CommonModules/Other/Module.bsl", null, needles));
    }

    @Test
    public void matchesOnFilePathCaseInsensitively() {
        List<String> needles = DiagnosticPathFilter.parse("commonmodules/accessmanagement");
        assertTrue(DiagnosticPathFilter.matches(
                "Accounting management/src/CommonModules/AccessManagement/Module.bsl", null, needles));
    }

    @Test
    public void matchesEitherSeparatorStyle() {
        List<String> needles = DiagnosticPathFilter.parse("CommonModules\\AccessManagement");
        assertTrue(DiagnosticPathFilter.matches(
                "Accounting management/src/CommonModules/AccessManagement/Module.bsl", null, needles));
        assertTrue(DiagnosticPathFilter.matches(
                "Accounting management\\src\\CommonModules\\AccessManagement\\Module.bsl", null, needles));
    }

    @Test
    public void matchesOnObjectPresentationWhenThereIsNoPath() {
        // The regression this guards: runtime-marker items — the bulk of a project scan — often carry
        // no file path at all and name their subject only through the object presentation. Filtering
        // on the path alone would drop exactly what a project-wide filter exists to find.
        List<String> needles = DiagnosticPathFilter.parse("OrphanProbe");
        assertTrue(DiagnosticPathFilter.matches(null, "CommonModule.OrphanProbe.Module", needles));
        assertTrue(DiagnosticPathFilter.matches("", "CommonModule.OrphanProbe.Module", needles));
    }

    @Test
    public void severalNeedlesAreOrNotAnd() {
        List<String> needles = DiagnosticPathFilter.parse("Sales,Access");
        assertTrue(DiagnosticPathFilter.matches("src/CommonModules/AccessManagement/Module.bsl", null, needles));
        assertTrue(DiagnosticPathFilter.matches("src/CommonModules/SalesReport/Module.bsl", null, needles));
        assertFalse(DiagnosticPathFilter.matches("src/CommonModules/Payroll/Module.bsl", null, needles));
    }

    @Test
    public void anItemWithNoCoordinatesIsDroppedWhenNarrowingWasRequested() {
        List<String> needles = DiagnosticPathFilter.parse("Access");
        assertFalse(DiagnosticPathFilter.matches(null, null, needles));
        assertFalse(DiagnosticPathFilter.matches("", "  ", needles));
    }

    @Test
    public void nonMatchingItemIsRejected() {
        List<String> needles = DiagnosticPathFilter.parse("AccessManagement");
        assertFalse(DiagnosticPathFilter.matches(
                "Accounting management/src/CommonModules/Payroll/Module.bsl",
                "CommonModule.Payroll.Module", needles));
    }
}
