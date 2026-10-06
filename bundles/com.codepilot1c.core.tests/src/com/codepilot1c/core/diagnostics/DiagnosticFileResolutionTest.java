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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;

import org.junit.Test;

import com.codepilot1c.core.diagnostics.DiagnosticFileResolution.Result;
import com.codepilot1c.core.diagnostics.DiagnosticFileResolution.Status;

/**
 * Behaviour of {@code get_diagnostics scope=file} path resolution in a multi-project workspace.
 * Reproduces 2026-09-18-get-diagnostics-project-name-ignored-cross-project-path-match.md: a base
 * configuration ("Accounting management", a name with a space) and an extension ("yaxunit") that
 * adopts its common modules, so the same project-relative path exists in both.
 */
public class DiagnosticFileResolutionTest {

    private static final String BASE = "Accounting management"; //$NON-NLS-1$
    private static final String EXT = "yaxunit"; //$NON-NLS-1$
    private static final List<String> PROJECTS = List.of(BASE, EXT);

    private static final String ADOPTED = "src/CommonModules/Integration_Vault/Module.bsl"; //$NON-NLS-1$
    private static final String BASE_ONLY = "src/CommonModules/BaseOnly/Module.bsl"; //$NON-NLS-1$
    private static final String EXT_ONLY = "src/CommonModules/ExtOnly/Module.bsl"; //$NON-NLS-1$

    /** Workspace files as "project/relative". */
    private static final Set<String> FILES = Set.of(
            BASE + "/" + ADOPTED, //$NON-NLS-1$
            EXT + "/" + ADOPTED, //$NON-NLS-1$
            BASE + "/" + BASE_ONLY, //$NON-NLS-1$
            EXT + "/" + EXT_ONLY); //$NON-NLS-1$

    private static final BiPredicate<String, String> EXISTS = (project, rel) -> FILES.contains(project + "/" + rel); //$NON-NLS-1$

    private static Result resolve(String path, String project) {
        return DiagnosticFileResolution.resolve(path, project, PROJECTS, EXISTS);
    }

    // --- project_name is the resolution root --------------------------------------------------

    @Test
    public void projectNameWithBarePathResolvesInsideThatProjectNotTheFirstSortedOne() {
        Result r = resolve(ADOPTED, EXT);
        assertTrue(r.message(), r.resolved());
        assertEquals(EXT, r.projectName());
        assertEquals("/yaxunit/" + ADOPTED, r.workspacePath()); //$NON-NLS-1$
    }

    @Test
    public void projectNameWithSpaceResolves() {
        Result r = resolve(ADOPTED, BASE);
        assertTrue(r.message(), r.resolved());
        assertEquals("/Accounting management/" + ADOPTED, r.workspacePath()); //$NON-NLS-1$
    }

    @Test
    public void projectNameWithPathAlreadyPrefixedByThatProjectResolves() {
        Result r = resolve("yaxunit/" + ADOPTED, EXT); //$NON-NLS-1$
        assertTrue(r.message(), r.resolved());
        assertEquals("/yaxunit/" + ADOPTED, r.workspacePath()); //$NON-NLS-1$

        Result spaced = resolve("/Accounting management/" + ADOPTED, BASE); //$NON-NLS-1$
        assertTrue(spaced.message(), spaced.resolved());
        assertEquals(BASE, spaced.projectName());
    }

    @Test
    public void projectNameIsNeverRedirectedToAnotherProjectHoldingThePath() {
        Result r = resolve(BASE_ONLY, EXT);
        assertEquals(Status.NOT_FOUND, r.status());
        assertNull(r.workspacePath());
        assertEquals(List.of("/Accounting management/" + BASE_ONLY), r.candidates()); //$NON-NLS-1$
        assertTrue(r.message(), r.message().contains("yaxunit")); //$NON-NLS-1$
        assertTrue(r.message(), r.message().contains("/Accounting management/" + BASE_ONLY)); //$NON-NLS-1$
    }

    @Test
    public void pathNamingADifferentProjectThanProjectNameIsRejected() {
        Result r = resolve("Accounting management/" + ADOPTED, EXT); //$NON-NLS-1$
        assertEquals(Status.PROJECT_MISMATCH, r.status());
        assertFalse(r.resolved());
    }

    @Test
    public void unknownProjectNameIsRejectedListingOpenProjects() {
        Result r = resolve(ADOPTED, "nope"); //$NON-NLS-1$
        assertEquals(Status.UNKNOWN_PROJECT, r.status());
        assertTrue(r.message(), r.message().contains(BASE) && r.message().contains(EXT));
    }

    @Test
    public void projectNameMatchesCaseInsensitivelyWhenUnique() {
        Result r = resolve(ADOPTED, "YAXUNIT"); //$NON-NLS-1$
        assertTrue(r.message(), r.resolved());
        assertEquals(EXT, r.projectName());
    }

    // --- no project_name -----------------------------------------------------------------------

    @Test
    public void barePathPresentInSeveralProjectsIsAmbiguousWithCandidates() {
        Result r = resolve(ADOPTED, null);
        assertEquals(Status.AMBIGUOUS, r.status());
        assertEquals(List.of("/Accounting management/" + ADOPTED, "/yaxunit/" + ADOPTED), r.candidates()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(r.message(), r.message().contains("project_name")); //$NON-NLS-1$
    }

    @Test
    public void barePathUniqueToOneProjectResolvesThere() {
        Result r = resolve(EXT_ONLY, ""); //$NON-NLS-1$
        assertTrue(r.message(), r.resolved());
        assertEquals("/yaxunit/" + EXT_ONLY, r.workspacePath()); //$NON-NLS-1$
    }

    @Test
    public void workspaceRootedPathResolvesInTheNamedProjectEvenWhenAmbiguousBare() {
        Result r = resolve("yaxunit/" + ADOPTED, null); //$NON-NLS-1$
        assertTrue(r.message(), r.resolved());
        assertEquals(EXT, r.projectName());

        Result spaced = resolve("\\Accounting management\\" + ADOPTED.replace('/', '\\'), null); //$NON-NLS-1$
        assertTrue(spaced.message(), spaced.resolved());
        assertEquals("/Accounting management/" + ADOPTED, spaced.workspacePath()); //$NON-NLS-1$
    }

    @Test
    public void workspaceRootedPathMissingInItsProjectIsNotFoundNotRedirected() {
        Result r = resolve("yaxunit/" + BASE_ONLY, null); //$NON-NLS-1$
        assertEquals(Status.NOT_FOUND, r.status());
        assertEquals(EXT, r.projectName());
    }

    @Test
    public void legacyConfigurationPrefixIsStripped() {
        Result r = resolve("Configuration/" + EXT_ONLY, EXT); //$NON-NLS-1$
        assertTrue(r.message(), r.resolved());
        assertEquals(EXT_ONLY, r.relativePath());
    }

    @Test
    public void missingFileIsNotFound() {
        Result r = resolve("src/CommonModules/Missing/Module.bsl", null); //$NON-NLS-1$
        assertEquals(Status.NOT_FOUND, r.status());
        assertTrue(r.candidates().isEmpty());
    }

    @Test
    public void blankPathIsNotFound() {
        assertEquals(Status.NOT_FOUND, resolve("  ", EXT).status()); //$NON-NLS-1$
        assertEquals(Status.NOT_FOUND, resolve(null, null).status());
    }
}
