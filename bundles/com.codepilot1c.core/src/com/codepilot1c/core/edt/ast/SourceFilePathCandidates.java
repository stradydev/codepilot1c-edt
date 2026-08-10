/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * The project-relative paths a BSL {@code filePath} argument may mean, in preference order.
 *
 * <h2>Why a list and not one path</h2>
 *
 * <p>Every BSL tool documents {@code filePath} as "path to the module relative to {@code src/}", and
 * {@code EdtServiceGateway.resolveSourceFile} adds the {@code src} segment itself. Two things then go
 * wrong in practice:</p>
 *
 * <ul>
 * <li><b>A caller pastes what another tool printed.</b> {@code glob} answers with
 * {@code src/Documents/X/ObjectModule.bsl} — the natural next step is to hand that to
 * {@code bsl_module_context}, which then looks for {@code src/src/Documents/…} and answers
 * {@code FILE_NOT_FOUND} naming a path that plainly exists. Nothing in the message hints that the
 * prefix was the problem.</li>
 * <li><b>A caller inside the plugin did exactly that.</b> Proven live 2026-08-10:
 * {@code bsl_object_context} on {@code Document.CasinoCashflowTransactions} reported every module as
 * {@code missing / File not found: src/Documents/…/ObjectModule.bsl} while {@code glob} listed the
 * files at that very path — because the aggregator prepended {@code src/} on top of the gateway's own.
 * The message printed the single-prefixed path and the lookup used the doubled one, which is what made
 * the report read as impossible.</li>
 * </ul>
 *
 * <p>So the documented spelling is tried FIRST and the already-prefixed reading only as a fallback:
 * whatever resolves today keeps resolving, and a pasted path stops being a dead end.</p>
 *
 * <p>Pure string logic, no EDT runtime, so the rule is unit-testable outside the OSGi runtime.</p>
 */
public final class SourceFilePathCandidates {

    /** The source folder every EDT project keeps its metadata and modules under. */
    private static final String SOURCE_FOLDER = "src"; //$NON-NLS-1$

    private SourceFilePathCandidates() {
        // utility
    }

    /**
     * Returns the project-relative paths to try for {@code filePath}, most-expected first.
     *
     * <p>Backslashes are accepted as separators and a leading slash is dropped, so a Windows-style or
     * absolute-looking argument is not silently treated as a different file.</p>
     *
     * @param filePath the caller's {@code filePath} argument, may be {@code null}
     * @return the candidates, empty when nothing usable was passed
     */
    public static List<String> forFilePath(String filePath) {
        String normalized = normalize(filePath);
        List<String> candidates = new ArrayList<>(2);
        if (normalized.isEmpty()) {
            return candidates;
        }
        // 1) The documented contract: filePath is relative to src/.
        candidates.add(SOURCE_FOLDER + '/' + normalized);
        // 2) The tolerated reading: the caller already included src/, e.g. pasted from glob.
        if (startsWithSourceFolder(normalized)) {
            candidates.add(normalized);
        }
        return candidates;
    }

    /** Whether {@code normalizedPath} already begins with the {@code src/} segment. */
    static boolean startsWithSourceFolder(String normalizedPath) {
        if (normalizedPath.length() <= SOURCE_FOLDER.length()) {
            // "src" alone names the folder, not a file inside it — nothing to strip.
            return false;
        }
        return normalizedPath.regionMatches(true, 0, SOURCE_FOLDER, 0, SOURCE_FOLDER.length())
                && normalizedPath.charAt(SOURCE_FOLDER.length()) == '/';
    }

    /** Collapses separators to {@code /}, drops a leading one, and trims. */
    static String normalize(String filePath) {
        if (filePath == null) {
            return ""; //$NON-NLS-1$
        }
        String value = filePath.strip().replace('\\', '/');
        while (value.startsWith("/")) { //$NON-NLS-1$
            value = value.substring(1);
        }
        return value;
    }
}
