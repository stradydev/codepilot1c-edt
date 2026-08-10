/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.tools;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.diagnostics.DiagnosticBaseline;
import com.codepilot1c.core.diagnostics.DiagnosticOrigin;
import com.codepilot1c.core.diagnostics.DiagnosticOriginSelection;
import com.codepilot1c.core.diagnostics.DiagnosticSeverityFilter;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.ui.diagnostics.DiagnosticBaselineStore;
import com.codepilot1c.ui.diagnostics.EdtDiagnostic;
import com.codepilot1c.ui.diagnostics.EdtDiagnostic.Severity;
import com.codepilot1c.ui.diagnostics.EdtDiagnosticsCollector;
import com.codepilot1c.ui.diagnostics.EdtDiagnosticsCollector.DiagnosticsQuery;
import com.codepilot1c.ui.diagnostics.EdtDiagnosticsCollector.DiagnosticsResult;

/**
 * Tool for getting EDT diagnostics (errors, warnings) for project, file or active editor.
 *
 * <p>This tool allows the LLM to retrieve compiler/checker diagnostics from 1C EDT,
 * enabling intelligent auto-fix workflows.</p>
 *
 * <p>Example usage by LLM:</p>
 * <ul>
 * <li>"get_diagnostics" → get errors from project</li>
 * <li>"get_diagnostics(severity='warning')" → get errors and warnings</li>
 * <li>"get_diagnostics(path='/Project/Module.bsl')" → get diagnostics for specific file</li>
 * </ul>
 */
public class GetDiagnosticsTool implements ITool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(GetDiagnosticsTool.class);

    private static final String SCHEMA = """
            {
                "type": "object",
                "properties": {
                    "scope": {
                        "type": "string",
                        "enum": ["project", "file", "active_editor"],
                        "description": "Scope of live UI diagnostics: project, file, or active_editor. Use edt_diagnostics:metadata_smoke when UI is unavailable."
                    },
                    "path": {
                        "type": "string",
                        "description": "Workspace-relative file path for scope=file."
                    },
                    "project_name": {
                        "type": "string",
                        "description": "EDT project name for scope=project (if omitted, default project or workspace diagnostics are used). OPTIONAL for scope=file — the project is auto-resolved from the file path, so you can call get_diagnostics(scope=file, path=...) without project_name."
                    },
                    "severity": {
                        "type": "string",
                        "enum": ["error", "warning", "info"],
                        "description": "Minimum severity level: error (errors only), warning (errors and warnings), info (all). Default: info"
                    },
                    "origin": {
                        "type": "string",
                        "enum": ["diagnostics", "all", "compiler", "analyzer", "custom-check", "review-annotation", "unknown"],
                        "description": "Which marker provenances to include. Default 'diagnostics' = real EDT diagnostics only; review/comment annotations contributed by other EDT plugins are left out and never counted. Pass 'all' when you also want those annotations, or one origin name (comma-separated list accepted) to narrow to a single provenance."
                    },
                    "max_items": {
                        "type": "integer",
                        "description": "Maximum number of diagnostics. 0 means unlimited. Default: 0"
                    },
                    "wait_ms": {
                        "type": "integer",
                        "description": "Time to wait for diagnostics to be recalculated before reading, in ms (0-5000, values above are clamped to 5000). Default: 0. Use ~3000 right after a metadata/code mutation so EDT/BSL-LS markers catch up, or when a previous call returned a suspiciously empty/partial snapshot."
                    },
                    "include_runtime_markers": {
                        "type": "boolean",
                        "description": "Include diagnostics from the EDT runtime marker manager (project-wide validation results: metadata/object checks). Default true — keep it on for scope=project to get the full picture. Set false to restrict to workspace-attached markers only (rarely needed). Note: for scope=file on an OPEN module the live diagnostics come from the editor's annotations, so toggling this has little visible effect there; it mainly matters for scope=project."
                    },
                    "path_contains": {
                        "type": "string",
                        "description": "Narrow a scan to named areas: keep only diagnostics whose file path OR object name contains one of these substrings (case-insensitive, comma-separated, either slash accepted). USE THIS on scope=project instead of paging a whole configuration — 'CommonModules/MyModule' or 'MyModule,MySecondModule' answers 'does anything of mine show an error' without the thousands of pre-existing ones. Applied before max_items, so the limit cuts your narrowed set, not the project. Items that carry neither a path nor an object name are dropped when you narrow (they cannot be shown to belong to the area you named)."
                    },
                    "baseline": {
                        "type": "string",
                        "enum": ["off", "save", "diff"],
                        "description": "Answer 'what did MY change add' instead of re-reading a configuration's known noise. Call once with 'save' to record the current diagnostics of this scope, then 'diff' reports only what is not already in that snapshot. Default 'off'. Identity ignores the line number (an inserted procedure shifts every line below it and would otherwise make a module's whole tail look new) and counts multiplicity instead, so the diff answers 'are there MORE of these than before', not 'is this the same one'. A 'save' ignores max_items on purpose — a snapshot of a truncated scan would report everything past the cut as new next time."
                    },
                    "line_from": {
                        "type": "integer",
                        "description": "Lower bound of the line range (1-based, inclusive). 0 = no limit. USE this together with line_to when editing/inspecting a specific method or fragment: only diagnostics within the range are returned, which sharply shrinks the response on large modules (with hundreds of warnings). Example: editing a function on lines 40-75 -> line_from=40, line_to=75."
                    },
                    "line_to": {
                        "type": "integer",
                        "description": "Upper bound of the line range (1-based, inclusive). 0 = no limit. Set together with line_from to focus on a method/selection. Diagnostics with no precise line (lineNumber<=0) are filtered out when a range is set."
                    },
                    "include_check_help": {
                        "type": "boolean",
                        "description": "RECOMMENDED whenever you intend to FIX or explain diagnostics: appends a 'Check details' section with the FULL official rule explanation + fix guidance (Markdown, the same content as EDT's Check Info view) for each unique rule in the result. This is the authoritative source on how to resolve a diagnostic — prefer it over guessing from the message. The section is DEDUPLICATED: each rule is explained exactly once even if dozens of diagnostics share it, so it stays compact on repeated warnings (cost scales with the number of distinct rules, not diagnostics). Works on its own — it resolves rule ids internally, you do NOT need include_check_id. Default false. Use help_locale to choose language. Rules whose bundle ships no description are named in one summary entry rather than dropped, so an absent explanation is never mistaken for a missed rule. Leaner alternative for one-off lookups: leave this off and call get_diagnostics_details for just the rules you care about."
                    },
                    "help_locale": {
                        "type": "string",
                        "enum": ["en", "ru"],
                        "description": "Language for the rule explanations when include_check_help=true: 'en' (default) or 'ru'. Falls back to English if the requested locale has no localized description."
                    }
                },
                "required": []
            }
            """; //$NON-NLS-1$

    @Override
    public String getName() {
        return "get_diagnostics"; //$NON-NLS-1$
    }

    @Override
    public String getCategory() {
        // Place this UI-bundle tool in the operator-facing "diagnostics" group
        // (otherwise the ITool default "general" lands it in "dynamic").
        return "diagnostics"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Live EDT diagnostics (errors/warnings) for a project, file, or active editor; grouped by rule, each tagged " //$NON-NLS-1$
                + "with its v8-code-style rule code. Options: path_contains to narrow a project scan to named modules/areas " //$NON-NLS-1$
                + "(the cheap way to ask 'did anything of MINE break' on a large config); baseline=save once then " //$NON-NLS-1$
                + "baseline=diff to report only what your change ADDED on top of a configuration's known noise; " //$NON-NLS-1$
                + "line_from/line_to to focus on a " //$NON-NLS-1$
                + "method and cut tokens on big " //$NON-NLS-1$
                + "modules; severity/max_items to filter; include_check_help=true to append the official rule explanation+fix " //$NON-NLS-1$
                + "(use when you intend to FIX); include_runtime_markers (project-wide checks). " //$NON-NLS-1$
                + "Only real EDT diagnostics are returned by default: review/comment annotations contributed by other " //$NON-NLS-1$
                + "EDT plugins are excluded and never counted — pass origin='all' if you want to see them too. " //$NON-NLS-1$
                + "CAUTION: a sudden drop to 0 on a file you expected to be dirty usually means EDT is still recalculating " //$NON-NLS-1$
                + "markers (cold start / right after an edit) — not a clean file; re-run or pass wait_ms (≤5000). " //$NON-NLS-1$
                + "For .dcs (scope=file) it also flags curated elements invalid in the DCS schema (e.g. <editFormat>) that EDT's " //$NON-NLS-1$
                + "lenient importer drops — a targeted check, not full schema validation."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        // Parse parameters
        String scope = (String) parameters.getOrDefault("scope", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String path = (String) parameters.get("path"); //$NON-NLS-1$
        String projectName = (String) parameters.get("project_name"); //$NON-NLS-1$
        String severityStr = (String) parameters.getOrDefault("severity", "info"); //$NON-NLS-1$ //$NON-NLS-2$
        int maxItems = getIntParam(parameters, "max_items", 0); //$NON-NLS-1$
        long waitMs = getIntParam(parameters, "wait_ms", 0); //$NON-NLS-1$
        // Runtime markers default to true for ALL scopes — EDT places the
        // bulk of BSL diagnostics (syntax, type checks, BSL-checks) in the
        // runtime marker manager rather than as workspace-attached markers
        // on the .bsl IFile. With scope=file/active_editor the prior
        // default (false) made the call silently return 0/0/0 — see
        // 2026-05-19-diagnostics-space-in-project-name.md.
        //
        // The "cross-module noise" concern from issue #24 is mitigated by
        // the strict ALL-tokens filter in markerMatchesContext (fixed by
        // RelativePathCandidates project-prefix stripping so the surviving
        // tokens are precise module discriminators). Callers can still
        // pass include_runtime_markers:false explicitly when they want
        // workspace markers only.
        boolean includeRuntimeMarkers = getBooleanParam(parameters, "include_runtime_markers", true); //$NON-NLS-1$
        int lineFrom = getIntParam(parameters, "line_from", 0); //$NON-NLS-1$
        int lineTo = getIntParam(parameters, "line_to", 0); //$NON-NLS-1$
        boolean includeCheckHelp = getBooleanParam(parameters, "include_check_help", false); //$NON-NLS-1$
        String helpLocale = (String) parameters.getOrDefault("help_locale", "en"); //$NON-NLS-1$ //$NON-NLS-2$
        // Marker provenance filter. Default drops review/comment overlays
        // contributed by other plugins (they are not EDT diagnostics) — see
        // DiagnosticOrigin.
        String originFilter = (String) parameters.getOrDefault(
                "origin", DiagnosticOrigin.defaultFilter()); //$NON-NLS-1$
        // Narrowing by file path / object name. Honored on every scope and applied inside the
        // collector BEFORE the max_items cut — see EdtDiagnosticsCollector.applyPathFilter.
        String pathContains = (String) parameters.get("path_contains"); //$NON-NLS-1$
        String baselineMode = DiagnosticBaseline.mode((String) parameters.get("baseline")); //$NON-NLS-1$

        // Validate parameters
        if (maxItems < 0) maxItems = 0;
        if (waitMs < 0) waitMs = 0;
        if (waitMs > 5000) waitMs = 5000;
        int[] range = com.codepilot1c.core.diagnostics.DiagnosticsLineFilter.normalize(lineFrom, lineTo);
        lineFrom = range[0];
        lineTo = range[1];

        // Parse severity
        Severity minSeverity = parseSeverity(severityStr);

        // A baseline works on the WHOLE scope: the query runs unlimited and the caller's max_items is
        // applied afterwards, to what survived the diff. Limiting first would compute the diff against
        // a truncated scan — the snapshot would miss everything past the cut and report it as new on
        // the next call, which is the same mistake the path filter had to be moved before the limit to
        // avoid (Round-16).
        int reportedMaxItems = maxItems;
        int queryMaxItems = DiagnosticBaseline.MODE_OFF.equals(baselineMode) ? maxItems : 0;

        DiagnosticsQuery query = new DiagnosticsQuery(
                minSeverity, queryMaxItems, true, waitMs, includeRuntimeMarkers, lineFrom, lineTo,
                includeCheckHelp, helpLocale, originFilter, pathContains);
        EdtDiagnosticsCollector collector = EdtDiagnosticsCollector.getInstance();

        String normalizedScope = normalizeScope(scope, path, projectName);
        boolean collectWorkspaceDiagnostics = false;
        if ("project".equals(normalizedScope) && (projectName == null || projectName.isBlank())) { //$NON-NLS-1$
            projectName = collector.resolveDefaultProjectName();
            if (projectName == null || projectName.isBlank()) {
                collectWorkspaceDiagnostics = true;
            }
        }
        CompletableFuture<DiagnosticsResult> resultFuture;

        resultFuture = switch (normalizedScope) {
            case "project" -> collectWorkspaceDiagnostics
                    ? collector.collectFromWorkspace(query)
                    : collector.collectFromProject(projectName, query); //$NON-NLS-1$
            case "file" -> collector.collectFromFile(path, query); //$NON-NLS-1$
            default -> collector.collectFromActiveEditor(query);
        };

        // The scan's identity for the baseline snapshot. Captured before the lambda because
        // projectName is reassigned above when the default project has to be resolved.
        final String scopeKey = "project".equals(normalizedScope) //$NON-NLS-1$
                ? (projectName == null || projectName.isBlank() ? "workspace" : projectName) //$NON-NLS-1$
                : path;

        return resultFuture.thenApply(result -> {
            if (DiagnosticBaseline.MODE_OFF.equals(baselineMode)) {
                return ToolResult.success(result.formatForLlm());
            }
            String key = scopeKey == null || scopeKey.isBlank() ? result.filePath() : scopeKey;
            return applyBaseline(result, baselineMode, key, reportedMaxItems);
        }).exceptionally(e -> {
            LOG.error("get_diagnostics failed: %s", e.getMessage()); //$NON-NLS-1$
            return ToolResult.failure("Failed to get diagnostics: " + e.getMessage()); //$NON-NLS-1$
        });
    }

    /**
     * Applies the {@code baseline} mode to a finished scan: records the snapshot, or reports only what
     * the snapshot does not already account for.
     *
     * <p>Only the mechanical part is here — read the file, map each diagnostic to its fingerprint, keep
     * the indices core selected, recount, trim. Every decision ({@code fingerprint}, {@code selectNew},
     * the serialized format, the note wording) is {@link DiagnosticBaseline} in core, because this
     * bundle has no test runtime and a rule that cannot be tested does not belong in it.</p>
     *
     * <p>The counts are recomputed from the reported subset with the same origin-aware helper the
     * collector uses, so a diff cannot claim the project's total error count while showing three items.
     * Any storage failure reports the scan in full with a note saying so — never a filtered-looking
     * answer that was not filtered.</p>
     */
    private ToolResult applyBaseline(
            DiagnosticsResult result, String mode, String scopeKey, int maxItems) {
        List<EdtDiagnostic> all = result.diagnostics();
        List<String> fingerprints = new ArrayList<>(all.size());
        for (EdtDiagnostic diagnostic : all) {
            fingerprints.add(DiagnosticBaseline.fingerprint(
                    diagnostic.checkId(), diagnostic.filePath(),
                    diagnostic.objectPresentation(), diagnostic.message()));
        }

        if (DiagnosticBaseline.MODE_SAVE.equals(mode)) {
            String savedAt = Instant.now().toString();
            DiagnosticBaselineStore.Access written = DiagnosticBaselineStore.write(
                    scopeKey, DiagnosticBaseline.serialize(savedAt, DiagnosticBaseline.tally(fingerprints)));
            if (!written.ok()) {
                LOG.warn("baseline save failed for %s: %s", scopeKey, written.reason()); //$NON-NLS-1$
                return ToolResult.success(result.formatForLlm()
                        + DiagnosticBaseline.unavailableNote(mode, written.reason()));
            }
            return ToolResult.success(result.formatForLlm()
                    + DiagnosticBaseline.saveNote(fingerprints.size(), savedAt, maxItems > 0));
        }

        DiagnosticBaselineStore.Access stored = DiagnosticBaselineStore.read(scopeKey);
        if (!stored.ok()) {
            LOG.warn("baseline read failed for %s: %s", scopeKey, stored.reason()); //$NON-NLS-1$
            return ToolResult.success(result.formatForLlm()
                    + DiagnosticBaseline.unavailableNote(mode, stored.reason()));
        }
        if (stored.text() == null || stored.text().isBlank()) {
            return ToolResult.success(result.formatForLlm() + DiagnosticBaseline.noBaselineNote());
        }

        DiagnosticBaseline.Snapshot snapshot = DiagnosticBaseline.parse(stored.text());
        List<Integer> keptIndices = DiagnosticBaseline.selectNew(snapshot, fingerprints);
        int suppressed = all.size() - keptIndices.size();
        List<EdtDiagnostic> reported = new ArrayList<>(keptIndices.size());
        for (int index : keptIndices) {
            reported.add(all.get(index));
        }
        // The caller's limit applies to the narrowed set, last, exactly as it does after the path
        // filter in the collector.
        if (maxItems > 0 && reported.size() > maxItems) {
            reported = new ArrayList<>(reported.subList(0, maxItems));
        }
        int[] counts = DiagnosticOriginSelection.countBySeverity(
                reported,
                EdtDiagnostic::origin,
                d -> d.severity() == null ? 0 : d.severity().getLevel());
        DiagnosticsResult filtered = new DiagnosticsResult(
                result.filePath(), result.editorDirty(), reported,
                counts[DiagnosticOriginSelection.ERRORS],
                counts[DiagnosticOriginSelection.WARNINGS],
                counts[DiagnosticOriginSelection.INFOS],
                result.checkDetails());
        return ToolResult.success(filtered.formatForLlm()
                + DiagnosticBaseline.diffNote(suppressed, snapshot.savedAt()));
    }

    /**
     * Maps the {@code severity} parameter onto the collector's gate. The rule itself lives in
     * {@link DiagnosticSeverityFilter} (core, unit-tested) because this bundle has no test runtime.
     *
     * <p>History: {@code "error"} used to have no branch of its own and fell through to
     * {@code default -> Severity.INFO} (regression in 6fa4cf7), so {@code severity=error} returned
     * byte-for-byte the same answer as {@code severity=info} — warnings and all. Callers read that
     * as "scope=file leaked project-wide results".</p>
     */
    private Severity parseSeverity(String str) {
        int level = DiagnosticSeverityFilter.minLevel(str);
        if (level == DiagnosticSeverityFilter.LEVEL_ERROR) {
            return Severity.ERROR;
        }
        if (level == DiagnosticSeverityFilter.LEVEL_WARNING) {
            return Severity.WARNING;
        }
        return Severity.INFO;
    }

    private int getIntParam(Map<String, Object> params, String key, int defaultValue) {
        Object value = params.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number num) {
            return num.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean getBooleanParam(Map<String, Object> params, String key, boolean defaultValue) {
        Object value = params.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String str = String.valueOf(value).trim();
        if (str.isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(str);
    }

    private String normalizeScope(String scope, String path, String projectName) {
        if (scope != null && !scope.isBlank()) {
            String normalized = scope.trim().toLowerCase();
            if ("project".equals(normalized) || "file".equals(normalized) || "active_editor".equals(normalized)) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                if ("file".equals(normalized) && (path == null || path.isBlank())) { //$NON-NLS-1$
                    return "project"; //$NON-NLS-1$
                }
                return normalized;
            }
        }
        if (path != null && !path.isBlank()) {
            return "file"; //$NON-NLS-1$
        }
        return "project"; //$NON-NLS-1$
    }
}
