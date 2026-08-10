package com.codepilot1c.core.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Tests for {@link DiagnosticGroupSamples}.
 *
 * <p>The flat three-message cap hid the very text the caller needed: a
 * {@code form-event-regions} group elided the exact required region name behind
 * {@code (+1 more variants)}, forcing a second scan of a single line to read a
 * name the first answer already had. Reported in BF-11156. The replacement caps
 * characters instead of variants, and these tests pin both halves — short
 * variants all survive, and the block can never grow past what the old cap
 * already permitted.</p>
 */
public class DiagnosticGroupSamplesTest {

    /** The live case: same rule, one short message per required region. */
    private static final List<String> REGION_VARIANTS = List.of(
            "Module must contain region FormTableItemsEventHandlersBasisDocuments", //$NON-NLS-1$
            "Module must contain region FormTableItemsEventHandlersProducts", //$NON-NLS-1$
            "Module must contain region FormTableItemsEventHandlersServices", //$NON-NLS-1$
            "Module must contain region FormHeaderItemsEventHandlers", //$NON-NLS-1$
            "Module must contain region FormCommandHandlers"); //$NON-NLS-1$

    @Test
    public void shortVariantsAreNoLongerElided() {
        DiagnosticGroupSamples.Selection selection = DiagnosticGroupSamples.select(REGION_VARIANTS);

        assertEquals(0, selection.hidden());
        assertEquals(REGION_VARIANTS.size(), selection.shown().size());
        assertTrue("the reported region name must be in the aggregated output", //$NON-NLS-1$
                selection.shown().stream()
                        .anyMatch(s -> s.contains("FormTableItemsEventHandlersBasisDocuments"))); //$NON-NLS-1$
    }

    @Test
    public void longVariantsStillStopAtThree() {
        // Three 160-char messages already fill the old cap, so a fourth long one
        // must not be added — that is what keeps the block from growing.
        List<String> longMessages = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            longMessages.add(("variant" + i + " ").repeat(40)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        DiagnosticGroupSamples.Selection selection = DiagnosticGroupSamples.select(longMessages);

        assertEquals(DiagnosticGroupSamples.MIN_SAMPLES, selection.shown().size());
        assertEquals(6 - DiagnosticGroupSamples.MIN_SAMPLES, selection.hidden());
    }

    @Test
    public void theSampleBlockNeverExceedsTheOldFlatCap() {
        // The scan-volume guarantee: whatever the variants look like, the rendered
        // block stays within MIN_SAMPLES x SAMPLE_MAX_CHARS.
        int oldWorstCase = DiagnosticGroupSamples.MIN_SAMPLES * DiagnosticGroupSamples.SAMPLE_MAX_CHARS;
        for (int length : new int[] {1, 5, 20, 60, 130, 160, 400}) {
            List<String> messages = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                messages.add(i + "x".repeat(Math.max(1, length - String.valueOf(i).length()))); //$NON-NLS-1$
            }
            DiagnosticGroupSamples.Selection selection = DiagnosticGroupSamples.select(messages);
            int rendered = selection.shown().stream().mapToInt(String::length).sum();
            assertTrue("length=" + length + " rendered=" + rendered, rendered <= oldWorstCase); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void aFloodOfOneWordVariantsIsBounded() {
        List<String> messages = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            messages.add("v" + i); //$NON-NLS-1$
        }
        DiagnosticGroupSamples.Selection selection = DiagnosticGroupSamples.select(messages);

        assertEquals(DiagnosticGroupSamples.MAX_SAMPLES, selection.shown().size());
        assertEquals(500 - DiagnosticGroupSamples.MAX_SAMPLES, selection.hidden());
    }

    @Test
    public void repeatsCollapseAndTheHiddenCountReflectsDistinctVariants() {
        List<String> messages = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            messages.addAll(REGION_VARIANTS);
        }
        DiagnosticGroupSamples.Selection selection = DiagnosticGroupSamples.select(messages);

        assertEquals(REGION_VARIANTS.size(), selection.shown().size());
        assertEquals(0, selection.hidden());
    }

    @Test
    public void firstSeenOrderIsPreserved() {
        DiagnosticGroupSamples.Selection selection = DiagnosticGroupSamples.select(REGION_VARIANTS);
        assertEquals(REGION_VARIANTS, selection.shown());
    }

    @Test
    public void aMessageLongerThanTheCapIsTruncatedWithAnEllipsis() {
        String long_ = "y".repeat(DiagnosticGroupSamples.SAMPLE_MAX_CHARS + 50); //$NON-NLS-1$
        DiagnosticGroupSamples.Selection selection = DiagnosticGroupSamples.select(List.of(long_));

        String sample = selection.shown().get(0);
        assertEquals(DiagnosticGroupSamples.SAMPLE_MAX_CHARS, sample.length());
        assertTrue(sample, sample.endsWith("…")); //$NON-NLS-1$
    }

    @Test
    public void blanksAndNullsAreIgnoredRatherThanRenderedEmpty() {
        DiagnosticGroupSamples.Selection selection = DiagnosticGroupSamples.select(
                Arrays.asList(null, "", "   ", "real message")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(List.of("real message"), selection.shown()); //$NON-NLS-1$
        assertEquals(0, selection.hidden());
    }

    @Test
    public void nothingToShowYieldsAnEmptySelection() {
        assertTrue(DiagnosticGroupSamples.select(null).shown().isEmpty());
        assertEquals(0, DiagnosticGroupSamples.select(null).hidden());
        assertTrue(DiagnosticGroupSamples.select(List.of()).shown().isEmpty());
    }
}
