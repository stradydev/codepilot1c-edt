package com.codepilot1c.core.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Assembles the {@code include_check_help=true} section of a
 * {@code get_diagnostics} answer.
 *
 * <p>Rules whose contributing bundle ships no HTML description used to be
 * dropped without a word, to save the caller tokens on empty entries. For a
 * rule with no obvious explanation that reads as "there is nothing to know
 * here", and the caller cannot tell an absent description from a rule that was
 * never looked up — the two live cases,
 * {@code bsl-legacy-check-type-in-operator-new} and
 * {@code bsl-legacy-check-string-literal}, were both firing on correct modern
 * code, which is exactly when the caller most needs to know that no
 * explanation exists. Reported twice independently (BF-11156, BF-7805); see
 * {@code 2026-08-08-bf11156-diagnostics-and-metadata-discovery-gaps.md}.</p>
 *
 * <p>The unresolved ids are therefore named, but collapsed into a single
 * trailing entry rather than one entry each, so the answer stays honest without
 * paying per-rule tokens for the absence.</p>
 *
 * <p>Lives in core, with the description lookup passed in, so the rule is
 * unit-testable: the UI bundle that owns the diagnostics collector has no test
 * runtime.</p>
 */
public final class CheckHelpDetails {

    /**
     * The {@code checkId} of the aggregate entry naming the rules with no
     * bundled description. Parenthesized so it cannot be mistaken for a real
     * check id.
     */
    public static final String NO_DESCRIPTION_ID = "(no bundled description)"; //$NON-NLS-1$

    private CheckHelpDetails() { }

    /** One rendered help entry: a check id and its markdown body. */
    public record Entry(String checkId, String markdown) { }

    /**
     * Builds the help entries for the checks that produced the diagnostics.
     *
     * @param checkIds check ids in diagnostic order; nulls, blanks and repeats
     *        are ignored
     * @param markdownById the description lookup — typically
     *        {@code id -> CheckInfoResolver.findMarkdown(id, locale)}
     * @return resolved entries in first-seen order, followed by a single
     *         {@link #NO_DESCRIPTION_ID} entry when some ids resolved to
     *         nothing; empty when there was nothing to resolve
     */
    public static List<Entry> build(
            List<String> checkIds,
            Function<String, Optional<String>> markdownById
    ) {
        if (checkIds == null || checkIds.isEmpty() || markdownById == null) {
            return List.of();
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Entry> entries = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String id : checkIds) {
            if (id == null || id.isBlank() || !seen.add(id)) {
                continue;
            }
            Optional<String> markdown = markdownById.apply(id);
            if (markdown != null && markdown.isPresent() && !markdown.get().isBlank()) {
                entries.add(new Entry(id, markdown.get()));
            } else {
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            entries.add(new Entry(NO_DESCRIPTION_ID, missingDescriptionNote(missing)));
        }
        return Collections.unmodifiableList(entries);
    }

    /**
     * The body of the aggregate entry: names every id whose description is
     * absent, and says that the absence is the answer rather than a lookup that
     * did not happen.
     */
    public static String missingDescriptionNote(List<String> missingIds) {
        return "No description is bundled for: " + String.join(", ", missingIds) //$NON-NLS-1$ //$NON-NLS-2$
                + ". The contributing bundle ships none — this is not a failed lookup, and " //$NON-NLS-1$
                + "asking again with another locale will not produce one. Expected for legacy " //$NON-NLS-1$
                + "bsl-legacy-* rules, which carry no HTML help at all."; //$NON-NLS-1$
    }
}
