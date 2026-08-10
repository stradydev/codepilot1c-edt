/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.diagnostics;

import java.util.Locale;

/**
 * Parses the {@code severity} parameter of {@code get_diagnostics} into a minimum severity level.
 *
 * <p>The levels mirror the UI bundle's {@code EdtDiagnostic.Severity} ordinals — INFO(0) &lt;
 * WARNING(1) &lt; ERROR(2) — without depending on any EDT/UI type, so the mapping is unit-testable
 * outside the OSGi runtime. The UI tool holds the enum; this class holds the rule.</p>
 *
 * <p>The rule matters because a missing branch here silently disables the whole severity gate: an
 * unrecognized value falls back to INFO, which admits every diagnostic. That is exactly how
 * {@code severity=error} regressed into a no-op (see the class javadoc of the caller).</p>
 */
public final class DiagnosticSeverityFilter {

    /** Admits everything — errors, warnings and info. */
    public static final int LEVEL_INFO = 0;

    /** Admits errors and warnings. */
    public static final int LEVEL_WARNING = 1;

    /** Admits errors only. */
    public static final int LEVEL_ERROR = 2;

    private DiagnosticSeverityFilter() {
        // utility
    }

    /**
     * Maps a caller-supplied severity string to the minimum level a diagnostic must reach to be
     * reported. Unknown, blank and {@code null} values mean "no filtering" (INFO), which keeps the
     * historical default of returning everything when the caller did not ask for a narrower view.
     *
     * @param raw the raw {@code severity} parameter, may be {@code null}
     * @return one of {@link #LEVEL_INFO}, {@link #LEVEL_WARNING}, {@link #LEVEL_ERROR}
     */
    public static int minLevel(String raw) {
        if (raw == null) {
            return LEVEL_INFO;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "error", "errors" -> LEVEL_ERROR; //$NON-NLS-1$ //$NON-NLS-2$
            case "warning", "warnings", "warn" -> LEVEL_WARNING; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            default -> LEVEL_INFO;
        };
    }
}
