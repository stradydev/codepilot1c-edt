/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;

import com.codepilot1c.core.diagnostics.DiagnosticBaseline;

/**
 * Where {@code get_diagnostics} keeps its baseline snapshots: one text file per scanned scope, under
 * {@code <workspace>/.codepilot/diagnostics-baseline/}.
 *
 * <p>Only the I/O lives here. Every rule — the fingerprint, the diff, the serialized format, the file
 * name, the notes — is {@link DiagnosticBaseline} in core, which has a test runtime; this bundle has
 * none, so anything worth a test must not be written here.</p>
 *
 * <p>The workspace was chosen over the bundle's state location so the snapshots are visible to the
 * person using them: a stale baseline is a file you can look at and delete, not hidden plugin state.
 * Every operation reports failure as a value rather than throwing — a diagnostics call must not fail
 * because a snapshot could not be written.</p>
 */
public final class DiagnosticBaselineStore {

    private static final String DIRECTORY = ".codepilot/diagnostics-baseline"; //$NON-NLS-1$

    private DiagnosticBaselineStore() {
        // utility
    }

    /**
     * The outcome of a read or a write.
     *
     * @param text  the snapshot text on a successful read, {@code null} when absent or on failure
     * @param ok    whether the operation succeeded (a missing file IS a success — an empty baseline)
     * @param reason short cause when {@code ok} is {@code false}
     */
    public record Access(String text, boolean ok, String reason) {

        static Access failed(String reason) {
            return new Access(null, false, reason);
        }
    }

    /**
     * Reads the snapshot for {@code scopeKey}. An absent file is not an error: it means nothing has
     * been recorded yet, which {@link DiagnosticBaseline#parse} turns into an empty baseline.
     *
     * @param scopeKey project name or file path identifying the scan
     * @return the access outcome
     */
    public static Access read(String scopeKey) {
        Path file;
        try {
            file = resolve(scopeKey);
        } catch (RuntimeException e) {
            return Access.failed(describe(e));
        }
        if (file == null) {
            return Access.failed("no workspace location"); //$NON-NLS-1$
        }
        if (!Files.isRegularFile(file)) {
            return new Access(null, true, ""); //$NON-NLS-1$
        }
        try {
            return new Access(Files.readString(file, StandardCharsets.UTF_8), true, ""); //$NON-NLS-1$
        } catch (IOException | RuntimeException e) {
            return Access.failed(describe(e));
        }
    }

    /**
     * Writes the snapshot for {@code scopeKey}, creating the directory when needed.
     *
     * @param scopeKey project name or file path identifying the scan
     * @param text the serialized snapshot
     * @return the access outcome; {@code text} is the written file's path on success
     */
    public static Access write(String scopeKey, String text) {
        Path file;
        try {
            file = resolve(scopeKey);
        } catch (RuntimeException e) {
            return Access.failed(describe(e));
        }
        if (file == null) {
            return Access.failed("no workspace location"); //$NON-NLS-1$
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, text == null ? "" : text, StandardCharsets.UTF_8); //$NON-NLS-1$
            return new Access(file.toString(), true, ""); //$NON-NLS-1$
        } catch (IOException | RuntimeException e) {
            return Access.failed(describe(e));
        }
    }

    private static Path resolve(String scopeKey) {
        IPath location = ResourcesPlugin.getWorkspace().getRoot().getLocation();
        if (location == null) {
            return null;
        }
        return Path.of(location.toOSString())
                .resolve(DIRECTORY)
                .resolve(DiagnosticBaseline.storageFileName(scopeKey));
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
