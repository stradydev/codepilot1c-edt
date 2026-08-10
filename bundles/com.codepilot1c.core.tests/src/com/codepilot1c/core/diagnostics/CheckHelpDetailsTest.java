package com.codepilot1c.core.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.Test;

/**
 * Tests for {@link CheckHelpDetails}.
 *
 * <p>Pins the {@code include_check_help=true} assembly rule. Before this,
 * a rule whose bundle ships no HTML description was omitted silently, so
 * {@code bsl-legacy-check-type-in-operator-new} firing on correct modern code
 * produced no help entry and no statement that none exists — reported twice
 * independently (BF-11156, BF-7805).</p>
 */
public class CheckHelpDetailsTest {

    /** A resolver that knows two rules and nothing else. */
    private static final Function<String, Optional<String>> RESOLVER = id -> Optional.ofNullable(
            Map.of(
                    "form-data-path", "Data path help", //$NON-NLS-1$ //$NON-NLS-2$
                    "common-module-type", "Common module type help") //$NON-NLS-1$ //$NON-NLS-2$
                    .get(id));

    @Test
    public void resolvedChecksKeepFirstSeenOrder() {
        List<CheckHelpDetails.Entry> entries = CheckHelpDetails.build(
                List.of("common-module-type", "form-data-path"), RESOLVER); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(2, entries.size());
        assertEquals("common-module-type", entries.get(0).checkId()); //$NON-NLS-1$
        assertEquals("Common module type help", entries.get(0).markdown()); //$NON-NLS-1$
        assertEquals("form-data-path", entries.get(1).checkId()); //$NON-NLS-1$
    }

    @Test
    public void anUndocumentedRuleIsNamedInsteadOfDropped() {
        List<CheckHelpDetails.Entry> entries = CheckHelpDetails.build(
                List.of("form-data-path", "bsl-legacy-check-type-in-operator-new"), RESOLVER); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(2, entries.size());
        CheckHelpDetails.Entry aggregate = entries.get(1);
        assertEquals(CheckHelpDetails.NO_DESCRIPTION_ID, aggregate.checkId());
        assertTrue(aggregate.markdown(),
                aggregate.markdown().contains("bsl-legacy-check-type-in-operator-new")); //$NON-NLS-1$
        assertTrue("must say the absence is the answer:\n" + aggregate.markdown(), //$NON-NLS-1$
                aggregate.markdown().contains("not a failed lookup")); //$NON-NLS-1$
    }

    @Test
    public void allUndocumentedRulesCollapseIntoOneEntry() {
        // One line for the absence, not one entry per rule — the token cost is
        // why they were dropped in the first place.
        List<CheckHelpDetails.Entry> entries = CheckHelpDetails.build(
                List.of("bsl-legacy-check-type-in-operator-new", //$NON-NLS-1$
                        "bsl-legacy-check-string-literal", //$NON-NLS-1$
                        "bsl-legacy-check-something-else"), //$NON-NLS-1$
                RESOLVER);

        assertEquals(1, entries.size());
        assertEquals(CheckHelpDetails.NO_DESCRIPTION_ID, entries.get(0).checkId());
        for (String id : List.of("bsl-legacy-check-type-in-operator-new", //$NON-NLS-1$
                "bsl-legacy-check-string-literal", "bsl-legacy-check-something-else")) { //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(id, entries.get(0).markdown().contains(id));
        }
    }

    @Test
    public void noAggregateEntryWhenEveryRuleIsDocumented() {
        List<CheckHelpDetails.Entry> entries = CheckHelpDetails.build(
                List.of("form-data-path"), RESOLVER); //$NON-NLS-1$
        assertEquals(1, entries.size());
        assertEquals("form-data-path", entries.get(0).checkId()); //$NON-NLS-1$
    }

    @Test
    public void repeatedAndBlankIdsAreIgnored() {
        // Diagnostics arrive per-marker, so the same check id repeats; a marker
        // with no check id at all must not become an empty entry either.
        List<CheckHelpDetails.Entry> entries = CheckHelpDetails.build(
                Arrays.asList("form-data-path", "form-data-path", null, "", "   "), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                RESOLVER);
        assertEquals(1, entries.size());
        assertEquals("form-data-path", entries.get(0).checkId()); //$NON-NLS-1$
    }

    @Test
    public void aBlankDescriptionCountsAsAbsent() {
        // A resolver that answers with whitespace is not an explanation; it must
        // land in the aggregate rather than render an empty help section.
        List<CheckHelpDetails.Entry> entries = CheckHelpDetails.build(
                List.of("whitespace-rule"), id -> Optional.of("   ")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, entries.size());
        assertEquals(CheckHelpDetails.NO_DESCRIPTION_ID, entries.get(0).checkId());
    }

    @Test
    public void nothingToResolveYieldsNoEntries() {
        assertTrue(CheckHelpDetails.build(null, RESOLVER).isEmpty());
        assertTrue(CheckHelpDetails.build(List.of(), RESOLVER).isEmpty());
        assertTrue(CheckHelpDetails.build(List.of("form-data-path"), null).isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void aResolverReturningNullIsTreatedAsAbsent() {
        // Optional-returning APIs get wrapped; a null must not throw here.
        List<CheckHelpDetails.Entry> entries = CheckHelpDetails.build(
                List.of("some-rule"), id -> null); //$NON-NLS-1$
        assertEquals(1, entries.size());
        assertEquals(CheckHelpDetails.NO_DESCRIPTION_ID, entries.get(0).checkId());
    }
}
