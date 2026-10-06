package com.codepilot1c.core.edt.publication;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure text repair of the {@code LoadModule _1cws_module} line in an Apache conf that EDT's publish
 * delegate rewrites.
 *
 * <p>Why it exists (decompile of services.core 21.0 {@code ApachePublishDelegate$ConfigUpdate.update}):
 * the delegate drops EVERY {@code LoadModule _1cws_module} line while copying the conf and writes one
 * back only when the result still carries a {@code SetHandler 1c-application} block. So
 * {@code remove} of the LAST publication strips the module line too — on a per-stack Apache with one
 * publication that is every remove, and the next publish then fails with WEB_EXTENSION_NOT_FOUND
 * (feedback 2026-10-05 …web-publication-remove-strips-loadmodule-publish-no-dedup). The delegate is
 * EDT code, so the tool repairs the conf after the operation instead: exactly one module line, the
 * one just published when there is a choice.</p>
 *
 * <p>Line endings, a BOM and every other line are preserved byte-for-byte; a conf that needs no repair
 * comes back as the identical string.</p>
 */
final class ApacheWsModuleLines {

    /** Same shape as EDT's own {@code MODULE_1CWS} (case-insensitive, uncommented lines only). */
    private static final Pattern MODULE_LINE =
            Pattern.compile("^\\s*LoadModule\\s+_1cws_module\\s+(.+?)\\s*$", Pattern.CASE_INSENSITIVE); //$NON-NLS-1$
    private static final Pattern ANY_LOAD_MODULE =
            Pattern.compile("^\\s*LoadModule\\s+\\S+\\s+\\S", Pattern.CASE_INSENSITIVE); //$NON-NLS-1$
    /** The directive the wsap module itself provides — the module must be loaded before its first use. */
    private static final Pattern MODULE_DIRECTIVE_USE =
            Pattern.compile("^\\s*ManagedApplicationDescriptor\\s", Pattern.CASE_INSENSITIVE); //$NON-NLS-1$

    /**
     * Result of {@link #normalize}. {@code text} is the (possibly unchanged) conf; {@code note} is a
     * one-line human summary of what was done or why nothing could be done, {@code null} when the conf
     * was already fine.
     */
    record Outcome(String text, boolean changed, int duplicatesRemoved, boolean reinserted, String note) {
    }

    private ApacheWsModuleLines() {
    }

    /** The path argument of an uncommented {@code LoadModule _1cws_module} line, or {@code null}. */
    static String modulePath(String line) {
        if (line == null) {
            return null;
        }
        Matcher matcher = MODULE_LINE.matcher(line);
        return matcher.matches() ? matcher.group(1) : null;
    }

    /**
     * Comparable identity of a module path: quotes dropped, separators unified to {@code /}, repeated
     * separators collapsed, case folded (Windows paths). {@code "C:\a\wsap24.dll"}, {@code C:/a/wsap24.dll}
     * and {@code "c:/a//wsap24.dll"} are one module.
     */
    static String modulePathKey(String path) {
        if (path == null) {
            return null;
        }
        String key = path.trim();
        if (key.length() >= 2 && (key.startsWith("\"") && key.endsWith("\"") //$NON-NLS-1$ //$NON-NLS-2$
                || key.startsWith("'") && key.endsWith("'"))) { //$NON-NLS-1$ //$NON-NLS-2$
            key = key.substring(1, key.length() - 1).trim();
        }
        key = key.replace('\\', '/').replaceAll("/{2,}", "/"); //$NON-NLS-1$ //$NON-NLS-2$
        return key.toLowerCase(Locale.ROOT);
    }

    /** The first uncommented {@code LoadModule _1cws_module} line of the conf, verbatim, or {@code null}. */
    static String firstModuleLine(String conf) {
        if (conf == null) {
            return null;
        }
        for (String line : splitLines(conf)) {
            if (modulePath(line) != null) {
                return line;
            }
        }
        return null;
    }

    /** The line to write for a module path: forward slashes, quoted — the shape EDT itself writes on Windows. */
    static String moduleLineFor(String modulePath) {
        String path = modulePath.trim();
        if (path.startsWith("\"") && path.endsWith("\"") && path.length() >= 2) { //$NON-NLS-1$ //$NON-NLS-2$
            path = path.substring(1, path.length() - 1);
        }
        return "LoadModule _1cws_module \"" + path.replace('\\', '/') + "\""; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Leaves exactly one {@code LoadModule _1cws_module} line in {@code conf}.
     *
     * <ul>
     *   <li>No module line and a {@code fallbackLine} given: the fallback is inserted after the last
     *       {@code LoadModule} preceding the first {@code ManagedApplicationDescriptor} (or at the top
     *       when there is none), so the directive the module provides is never used before the load.</li>
     *   <li>Several module lines: the first one whose path matches {@code preferredPathKey} is kept (the
     *       module just published); without a match, they are collapsed only when they all name the same
     *       module — two DIFFERENT modules are left untouched and reported, never guessed at.</li>
     * </ul>
     *
     * @param conf the conf text
     * @param preferredPathKey {@link #modulePathKey} of the module that should win, or {@code null}
     * @param fallbackLine the line to restore when none is left, or {@code null} to never insert
     */
    static Outcome normalize(String conf, String preferredPathKey, String fallbackLine) {
        if (conf == null) {
            return new Outcome(null, false, 0, false, null);
        }
        String eol = conf.contains("\r\n") ? "\r\n" : "\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        boolean trailingEol = conf.endsWith("\n"); //$NON-NLS-1$
        List<String> lines = splitLines(conf);

        List<Integer> moduleIndexes = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (modulePath(lines.get(i)) != null) {
                moduleIndexes.add(Integer.valueOf(i));
            }
        }

        if (moduleIndexes.isEmpty()) {
            if (fallbackLine == null || modulePath(fallbackLine) == null) {
                return new Outcome(conf, false, 0, false, null);
            }
            lines.add(insertionIndex(lines), fallbackLine);
            return new Outcome(join(lines, eol, trailingEol), true, 0, true,
                    "restored the LoadModule _1cws_module line EDT's publish delegate stripped: " + fallbackLine); //$NON-NLS-1$
        }
        if (moduleIndexes.size() == 1) {
            return new Outcome(conf, false, 0, false, null);
        }

        int keep = -1;
        if (preferredPathKey != null) {
            for (Integer index : moduleIndexes) {
                if (preferredPathKey.equals(modulePathKey(modulePath(lines.get(index.intValue()))))) {
                    keep = index.intValue();
                    break;
                }
            }
        }
        if (keep < 0) {
            String firstKey = modulePathKey(modulePath(lines.get(moduleIndexes.get(0).intValue())));
            for (Integer index : moduleIndexes) {
                if (!firstKey.equals(modulePathKey(modulePath(lines.get(index.intValue()))))) {
                    return new Outcome(conf, false, 0, false,
                            moduleIndexes.size() + " LoadModule _1cws_module lines name DIFFERENT modules;" //$NON-NLS-1$
                                    + " left untouched - keep exactly one by hand or re-publish with wsap_version"); //$NON-NLS-1$
                }
            }
            keep = moduleIndexes.get(0).intValue();
        }

        List<String> result = new ArrayList<>(lines.size());
        int removed = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (i != keep && moduleIndexes.contains(Integer.valueOf(i))) {
                removed++;
                continue;
            }
            result.add(lines.get(i));
        }
        return new Outcome(join(result, eol, trailingEol), true, removed, false,
                "removed " + removed + " duplicate LoadModule _1cws_module line(s), kept: " + lines.get(keep)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static int insertionIndex(List<String> lines) {
        int firstUse = lines.size();
        for (int i = 0; i < lines.size(); i++) {
            if (MODULE_DIRECTIVE_USE.matcher(lines.get(i)).find()) {
                firstUse = i;
                break;
            }
        }
        int lastLoad = -1;
        for (int i = 0; i < firstUse; i++) {
            if (ANY_LOAD_MODULE.matcher(lines.get(i)).find()) {
                lastLoad = i;
            }
        }
        return lastLoad + 1;
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\r?\n", -1))); //$NON-NLS-1$
        if (text.endsWith("\n")) { //$NON-NLS-1$
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private static String join(List<String> lines, String eol, boolean trailingEol) {
        String joined = String.join(eol, lines);
        return trailingEol ? joined + eol : joined;
    }
}
