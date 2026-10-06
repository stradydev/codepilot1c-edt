/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.diagnostics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Decides which workspace file a {@code get_diagnostics scope=file} call means.
 *
 * <p>Pure Java (no workspace access) so the rule can be unit-tested; the UI collector supplies the
 * accessible project names and a {@code (project, projectRelativePath) -> exists} predicate.</p>
 *
 * <p>Rules:</p>
 * <ol>
 *   <li>{@code project_name} given: the path is resolved inside that project only. Both
 *       {@code src/...} and {@code <project>/src/...} work. A path whose first segment names a
 *       DIFFERENT project is rejected, never redirected.</li>
 *   <li>No {@code project_name}, first segment is a workspace project: resolved inside that project
 *       only (workspace-rooted form).</li>
 *   <li>No {@code project_name}, bare project-relative path: every accessible project is probed; a
 *       single hit resolves, several hits are rejected as ambiguous with the candidate list.</li>
 * </ol>
 *
 * <p>History: the collector used to probe projects in alphabetical order and return the first hit,
 * ignoring {@code project_name}. In a workspace holding a base configuration and an extension that
 * adopts its modules, {@code project_name:"yaxunit", path:"src/CommonModules/M/Module.bsl"} silently
 * answered for {@code Accounting management/src/CommonModules/M/Module.bsl}
 * (2026-09-18-get-diagnostics-project-name-ignored-cross-project-path-match.md).</p>
 */
public final class DiagnosticFileResolution {

    /** Resolution outcome. */
    public enum Status {
        RESOLVED, NOT_FOUND, AMBIGUOUS, UNKNOWN_PROJECT, PROJECT_MISMATCH
    }

    /**
     * @param status       outcome
     * @param projectName  resolved project (RESOLVED), otherwise the project the lookup was confined
     *                     to, or {@code null}
     * @param relativePath project-relative path of the resolved file (RESOLVED only)
     * @param candidates   workspace paths ({@code /Project/rel}) that matched — the ambiguity set, or
     *                     the other projects holding the path when the named project does not
     * @param message      caller-facing explanation for every non-RESOLVED status
     */
    public record Result(
            Status status, String projectName, String relativePath, List<String> candidates, String message) {

        public boolean resolved() {
            return status == Status.RESOLVED;
        }

        /** {@code /Project/rel} of the resolved file, or {@code null} when not resolved. */
        public String workspacePath() {
            return resolved() ? DiagnosticFileResolution.workspacePath(projectName, relativePath) : null;
        }
    }

    private DiagnosticFileResolution() {
        // static utility
    }

    /**
     * @param rawPath           the {@code path} argument
     * @param requestedProject  the {@code project_name} argument, may be {@code null}/blank
     * @param accessibleProjects open workspace project names, in the order candidates are reported
     * @param fileExists        {@code (projectName, projectRelativePath) -> true} when the file exists
     */
    public static Result resolve(String rawPath, String requestedProject, Collection<String> accessibleProjects,
            BiPredicate<String, String> fileExists) {
        List<String> projects = accessibleProjects == null ? List.of() : new ArrayList<>(accessibleProjects);
        String path = normalize(rawPath);
        if (path.isEmpty()) {
            return failure(Status.NOT_FOUND, null, List.of(), "path is empty"); //$NON-NLS-1$
        }
        String firstSegment = firstSegment(path);
        String rest = afterFirstSegment(path);

        if (requestedProject != null && !requestedProject.isBlank()) {
            return resolveInRequestedProject(path, firstSegment, rest, requestedProject.trim(), projects, fileExists);
        }

        String rootProject = rest == null ? null : matchProject(firstSegment, projects);
        if (rootProject != null) {
            Result inRoot = probe(rootProject, relativeForms(rest), fileExists);
            if (inRoot != null) {
                return inRoot;
            }
            return failure(Status.NOT_FOUND, rootProject, List.of(),
                    "File not found in project '" + rootProject + "': " + rest); //$NON-NLS-1$ //$NON-NLS-2$
        }

        List<String> hits = new ArrayList<>();
        Result single = null;
        for (String project : projects) {
            Result hit = probe(project, relativeForms(path), fileExists);
            if (hit != null) {
                hits.add(hit.workspacePath());
                single = hit;
            }
        }
        if (hits.size() == 1) {
            return single;
        }
        if (hits.size() > 1) {
            return failure(Status.AMBIGUOUS, null, hits,
                    "Ambiguous path '" + path + "': it exists in several projects: " //$NON-NLS-1$ //$NON-NLS-2$
                            + String.join(", ", hits) //$NON-NLS-1$
                            + ". Pass project_name or prefix the path with the project name."); //$NON-NLS-1$
        }
        return failure(Status.NOT_FOUND, null, List.of(), "File not found in workspace: " + path); //$NON-NLS-1$
    }

    private static Result resolveInRequestedProject(String path, String firstSegment, String rest,
            String requestedProject, List<String> projects, BiPredicate<String, String> fileExists) {
        String project = matchProject(requestedProject, projects);
        if (project == null) {
            return failure(Status.UNKNOWN_PROJECT, null, List.of(),
                    "Project '" + requestedProject + "' is not an open workspace project. Open projects: " //$NON-NLS-1$ //$NON-NLS-2$
                            + (projects.isEmpty() ? "(none)" : String.join(", ", projects))); //$NON-NLS-1$ //$NON-NLS-2$
        }

        LinkedHashSet<String> forms = new LinkedHashSet<>();
        boolean prefixed = rest != null && firstSegment.equalsIgnoreCase(project);
        if (prefixed) {
            forms.addAll(relativeForms(rest));
        }
        forms.addAll(relativeForms(path));
        Result hit = probe(project, forms, fileExists);
        if (hit != null) {
            return hit;
        }

        String otherProject = rest == null || prefixed ? null : matchProject(firstSegment, projects);
        if (otherProject != null) {
            return failure(Status.PROJECT_MISMATCH, project, List.of(),
                    "path starts with project '" + otherProject + "' but project_name is '" + project //$NON-NLS-1$ //$NON-NLS-2$
                            + "'. Drop one of them."); //$NON-NLS-1$
        }

        String bare = prefixed ? rest : path;
        List<String> elsewhere = new ArrayList<>();
        for (String other : projects) {
            if (other.equals(project)) {
                continue;
            }
            Result otherHit = probe(other, relativeForms(bare), fileExists);
            if (otherHit != null) {
                elsewhere.add(otherHit.workspacePath());
            }
        }
        String message = "File not found in project '" + project + "': " + bare; //$NON-NLS-1$ //$NON-NLS-2$
        if (!elsewhere.isEmpty()) {
            message += " (the same path exists in: " + String.join(", ", elsewhere) + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return failure(Status.NOT_FOUND, project, elsewhere, message);
    }

    private static Result probe(String project, Collection<String> forms, BiPredicate<String, String> fileExists) {
        for (String form : forms) {
            if (fileExists.test(project, form)) {
                return new Result(Status.RESOLVED, project, form, List.of(), null);
            }
        }
        return null;
    }

    /** The path itself, then the legacy {@code Configuration/} / {@code Конфигурация/}-stripped form. */
    private static Set<String> relativeForms(String path) {
        LinkedHashSet<String> forms = new LinkedHashSet<>();
        forms.add(path);
        String lower = path.toLowerCase(Locale.ROOT);
        for (String prefix : List.of("configuration/", "конфигурация/")) { //$NON-NLS-1$ //$NON-NLS-2$
            if (lower.startsWith(prefix) && path.length() > prefix.length()) {
                forms.add(path.substring(prefix.length()));
            }
        }
        return forms;
    }

    /** Exact name first, then a unique case-insensitive match. */
    private static String matchProject(String name, List<String> projects) {
        if (name == null || name.isBlank()) {
            return null;
        }
        if (projects.contains(name)) {
            return name;
        }
        String found = null;
        for (String project : projects) {
            if (project.equalsIgnoreCase(name)) {
                if (found != null) {
                    return null;
                }
                found = project;
            }
        }
        return found;
    }

    private static Result failure(Status status, String project, List<String> candidates, String message) {
        return new Result(status, project, null, List.copyOf(candidates), message);
    }

    private static String workspacePath(String project, String relative) {
        return "/" + project + "/" + relative; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return ""; //$NON-NLS-1$
        }
        String s = raw.trim().replace('\\', '/');
        while (s.startsWith("/")) { //$NON-NLS-1$
            s = s.substring(1);
        }
        return s;
    }

    private static String firstSegment(String path) {
        int slash = path.indexOf('/');
        return slash > 0 ? path.substring(0, slash) : path;
    }

    /** Remainder after the first segment, or {@code null} when there is no non-blank remainder. */
    private static String afterFirstSegment(String path) {
        int slash = path.indexOf('/');
        if (slash <= 0 || slash == path.length() - 1) {
            return null;
        }
        String rest = path.substring(slash + 1);
        return rest.isBlank() ? null : rest;
    }
}
