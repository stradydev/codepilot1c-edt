package com.codepilot1c.core.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Chooses which sample messages a collapsed diagnostic group shows.
 *
 * <p>A group renders one line per check id plus a few sample messages, because
 * the variety of a parametrized rule (different names, identifiers, required
 * region names) is what the caller actually needs. The cap used to be a flat
 * three messages, so a rule with more variants elided the rest behind
 * {@code (+N more variants)} — and for {@code form-event-regions} the elided
 * text was the one thing the caller was after: the exact required region name.
 * Re-querying a single line just to read a name that was already in hand is the
 * round-trip reported in BF-11156; see
 * {@code 2026-08-08-bf11156-diagnostics-and-metadata-discovery-gaps.md}.</p>
 *
 * <p>The cap is therefore on <em>characters</em>, not on variants: short
 * messages all fit, long ones still stop at three. By construction the sample
 * block can never be larger than what the flat three-message cap already
 * allowed ({@value #MIN_SAMPLES} × {@value #SAMPLE_MAX_CHARS}), so this cannot
 * regress the scan-volume work that Round-16/18 was about.</p>
 *
 * <p>Lives in core because the collector that renders groups is in the UI
 * bundle, which has no test runtime.</p>
 */
public final class DiagnosticGroupSamples {

    /** Per-message length cap; longer messages are truncated with an ellipsis. */
    public static final int SAMPLE_MAX_CHARS = 160;

    /** Samples always shown, regardless of length — the previous flat cap. */
    public static final int MIN_SAMPLES = 3;

    /** Character budget for the whole sample block beyond {@link #MIN_SAMPLES}. */
    public static final int BLOCK_BUDGET_CHARS = 400;

    /** Hard ceiling, so a rule with hundreds of one-word variants stays bounded. */
    public static final int MAX_SAMPLES = 10;

    private DiagnosticGroupSamples() { }

    /**
     * The samples to render and how many variants were left out.
     *
     * @param shown truncated sample messages, in first-seen order
     * @param hidden how many distinct messages are not in {@code shown}
     */
    public record Selection(List<String> shown, int hidden) { }

    /**
     * Selects the samples for one group.
     *
     * @param messages the group's messages in diagnostic order; nulls, blanks
     *        and repeats are ignored
     * @return the selection; {@code shown} is empty only when there was nothing
     *         to show
     */
    public static Selection select(List<String> messages) {
        if (messages == null || messages.isEmpty()) {
            return new Selection(List.of(), 0);
        }
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String message : messages) {
            if (message != null && !message.isBlank()) {
                distinct.add(message);
            }
        }
        List<String> shown = new ArrayList<>();
        int usedChars = 0;
        for (String message : distinct) {
            String sample = truncate(message);
            boolean mandatory = shown.size() < MIN_SAMPLES;
            if (!mandatory
                    && (shown.size() >= MAX_SAMPLES || usedChars + sample.length() > BLOCK_BUDGET_CHARS)) {
                break;
            }
            shown.add(sample);
            usedChars += sample.length();
        }
        return new Selection(Collections.unmodifiableList(shown), distinct.size() - shown.size());
    }

    /** Truncates one message to {@link #SAMPLE_MAX_CHARS}, ellipsis included. */
    public static String truncate(String message) {
        if (message == null) {
            return ""; //$NON-NLS-1$
        }
        return message.length() > SAMPLE_MAX_CHARS
                ? message.substring(0, SAMPLE_MAX_CHARS - 1) + "…" //$NON-NLS-1$
                : message;
    }
}
