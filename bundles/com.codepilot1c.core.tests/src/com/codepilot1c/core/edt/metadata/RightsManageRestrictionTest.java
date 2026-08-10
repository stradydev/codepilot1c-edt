package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.edt.metadata.RightsManageRequest.RightGrant;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;

/**
 * Pins the RLS/restriction half of the {@code rights_manage} contract.
 *
 * <p>Background: codepilot1c-feedback
 * {@code 2026-08-08-rights-manage-cannot-set-rls-restriction-condition} — a condition-based
 * restriction had no MCP path at all and forced a hand edit of the role's {@code .rights} file,
 * which is exactly the manual path the tool exists to replace.</p>
 *
 * <p>The round-trip test here is the one that matters most: {@code rights_manage} applies the
 * payload carried by the <em>validation token</em>, not the one the caller sent, so a canonical
 * form that drops {@code restriction} would leave the whole feature mute — validating fine and
 * writing nothing. That failure mode has already shipped once on a different tool.</p>
 */
public class RightsManageRestrictionTest {

    private static final String FIELD = "grants"; //$NON-NLS-1$
    private static final String CONDITION =
            "WHERE Company IN (SELECT Company FROM Catalog.UserCompanies)"; //$NON-NLS-1$

    private final MetadataRequestValidationService service = new MetadataRequestValidationService();

    private static Map<String, Object> grant(String value, String restrictionKey, Object restriction) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("object_fqn", "Catalog.Contracts"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("right", "Read"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("value", value); //$NON-NLS-1$
        if (restrictionKey != null) {
            map.put(restrictionKey, restriction);
        }
        return map;
    }

    private static List<String> parseOne(Object restriction) {
        return RightsManageRequest.parseGrants(
                List.of(grant("set", "restriction", restriction)), FIELD).get(0).restrictions(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- three distinct states: absent / erase / set ------------------------------------------

    @Test
    public void absentRestrictionKeyLeavesExistingConditionsAlone() {
        RightGrant parsed = RightsManageRequest.parseGrants(
                List.of(grant("set", null, null)), FIELD).get(0); //$NON-NLS-1$
        assertNull("an absent key must stay null — the service reads null as 'do not touch'," //$NON-NLS-1$
                + " so collapsing it into an empty list would silently wipe an existing RLS", //$NON-NLS-1$
                parsed.restrictions());
    }

    @Test
    public void emptyListIsTheExplicitErase() {
        assertEquals(List.of(), parseOne(List.of()));
    }

    @Test
    public void blankStringIsTheExplicitErase() {
        assertEquals(List.of(), parseOne("   ")); //$NON-NLS-1$
    }

    @Test
    public void bareStringBecomesOneCondition() {
        assertEquals(List.of(CONDITION), parseOne(CONDITION));
    }

    @Test
    public void listOfStringsKeepsOrder() {
        assertEquals(List.of("A", "B"), parseOne(List.of("A", "B"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void acceptsConditionObjects() {
        assertEquals(List.of(CONDITION), parseOne(List.of(Map.of("condition", CONDITION)))); //$NON-NLS-1$
        assertEquals(List.of(CONDITION), parseOne(Map.of("condition", CONDITION))); //$NON-NLS-1$
    }

    @Test
    public void acceptsRestrictionKeyAliases() {
        for (String alias : List.of("restriction", "restrictions", "rls", "condition", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "conditions", "restriction_by_condition", "restrictionsByCondition")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            List<RightGrant> parsed = RightsManageRequest.parseGrants(
                    List.of(grant("set", alias, CONDITION)), FIELD); //$NON-NLS-1$
            assertEquals("alias '" + alias + "' must be accepted", //$NON-NLS-1$ //$NON-NLS-2$
                    List.of(CONDITION), parsed.get(0).restrictions());
        }
    }

    // --- refusals: never swallow what we cannot honor -----------------------------------------

    @Test
    public void fieldLevelRlsIsRefusedNotSilentlyDowngraded() {
        // Dropping 'fields' quietly would write a WHOLE-OBJECT restriction while the caller
        // believes a per-field one was applied — a wrong grant reported as success.
        assertRejected(List.of(grant("set", "restriction", //$NON-NLS-1$ //$NON-NLS-2$
                List.of(Map.of("condition", CONDITION, "fields", List.of("Catalog.Contracts.Sum")))))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void blankConditionInsideAListIsRefused() {
        assertRejected(List.of(grant("set", "restriction", java.util.Arrays.asList("A", "  ")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void conditionObjectWithoutConditionIsRefused() {
        assertRejected(List.of(grant("set", "restriction", List.of(Map.of("text", ""))))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void scalarNonStringRestrictionIsRefused() {
        assertRejected(List.of(grant("set", "restriction", Boolean.TRUE))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void removeCombinedWithRestrictionIsRefused() {
        // 'remove' deletes the whole ObjectRight entry, conditions included: honoring both is
        // impossible, and honoring one silently would mislead either way.
        assertRejected(List.of(grant("remove", "restriction", CONDITION))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void removeWithoutRestrictionStillParses() {
        RightGrant parsed = RightsManageRequest.parseGrants(
                List.of(grant("remove", null, null)), FIELD).get(0); //$NON-NLS-1$
        assertEquals(RightsManageRequest.VALUE_REMOVE, parsed.value());
        assertNull(parsed.restrictions());
    }

    // --- the token round-trip: a canonical form that drops the field kills the feature ---------

    @Test
    public void canonicalPayloadCarriesTheRestrictionThroughTheToken() {
        Map<String, Object> payload = service.normalizeRightsManagePayload(
                "Accounting management", "Role.Reader", //$NON-NLS-1$ //$NON-NLS-2$
                List.of(grant("set", "restriction", CONDITION))); //$NON-NLS-1$ //$NON-NLS-2$

        // Re-parse exactly the way the tool does: from the VALIDATED payload, not the raw input.
        List<RightGrant> reparsed = RightsManageRequest.parseGrants(payload.get("grants"), FIELD); //$NON-NLS-1$
        assertEquals("the restriction must survive normalization, or the mutation never sees it", //$NON-NLS-1$
                List.of(CONDITION), reparsed.get(0).restrictions());
    }

    @Test
    public void canonicalPayloadKeepsAbsentAbsentAndEraseErasing() {
        Map<String, Object> untouched = service.normalizeRightsManagePayload(
                "P", "Role.R", List.of(grant("set", null, null))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse("an absent restriction must NOT materialize as an empty list — that would" //$NON-NLS-1$
                + " turn every plain value grant into a silent RLS wipe", //$NON-NLS-1$
                firstGrant(untouched).containsKey("restriction")); //$NON-NLS-1$
        assertNull(RightsManageRequest.parseGrants(untouched.get("grants"), FIELD) //$NON-NLS-1$
                .get(0).restrictions());

        Map<String, Object> erased = service.normalizeRightsManagePayload(
                "P", "Role.R", List.of(grant("set", "restriction", List.of()))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals("an explicit erase must survive as an empty list", List.of(), //$NON-NLS-1$
                RightsManageRequest.parseGrants(erased.get("grants"), FIELD).get(0).restrictions()); //$NON-NLS-1$
    }

    @Test
    public void canonicalPayloadIsStableAcrossReNormalization() {
        // The tool compares its own normalization of the caller's input against the token payload;
        // an unstable canonical form would log a spurious mismatch on every restricted grant.
        List<Map<String, Object>> input = List.of(grant("set", "rls", CONDITION)); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, Object> once = service.normalizeRightsManagePayload("P", "Role.R", input); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, Object> twice = service.normalizeRightsManagePayload("P", "Role.R", //$NON-NLS-1$ //$NON-NLS-2$
                asListOfMaps(once.get("grants"))); //$NON-NLS-1$
        assertEquals(once, twice);
    }

    // --- reporting ----------------------------------------------------------------------------

    @Test
    public void summaryReportsARestrictionWriteEvenWhenTheValueWasANoOp() {
        String s = RightsManageMessages.formatGrantSummary(1, "Catalog.Contracts", "Read", //$NON-NLS-1$ //$NON-NLS-2$
                "SET", "SET", false, RightsManageMessages.RESTRICTIONS_CHANGED, 1); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a rewritten condition is a real write and must be visible", //$NON-NLS-1$
                s.contains("1 restriction condition(s)")); //$NON-NLS-1$
    }

    @Test
    public void summaryDistinguishesClearedFromUnchangedFromUntouched() {
        String cleared = RightsManageMessages.formatGrantSummary(1, "Catalog.C", "Read", //$NON-NLS-1$ //$NON-NLS-2$
                "SET", "SET", false, RightsManageMessages.RESTRICTIONS_CHANGED, 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(cleared.contains("restriction cleared")); //$NON-NLS-1$

        String unchanged = RightsManageMessages.formatGrantSummary(1, "Catalog.C", "Read", //$NON-NLS-1$ //$NON-NLS-2$
                "SET", "SET", false, RightsManageMessages.RESTRICTIONS_UNCHANGED, 2); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(unchanged.contains("unchanged")); //$NON-NLS-1$

        String untouched = RightsManageMessages.formatGrantSummary(1, "Catalog.C", "Read", //$NON-NLS-1$ //$NON-NLS-2$
                "SET", "UNSET", true, RightsManageMessages.RESTRICTIONS_UNTOUCHED, 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a grant that said nothing about RLS must not mention one", //$NON-NLS-1$
                untouched.contains("restriction")); //$NON-NLS-1$
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstGrant(Map<String, Object> payload) {
        return ((List<Map<String, Object>>) payload.get("grants")).get(0); //$NON-NLS-1$
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asListOfMaps(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : (List<Object>) value) {
            result.add((Map<String, Object>) item);
        }
        return result;
    }

    private static void assertRejected(Object payload) {
        try {
            RightsManageRequest.parseGrants(payload, FIELD);
            fail("Expected MetadataOperationException for payload: " + payload); //$NON-NLS-1$
        } catch (MetadataOperationException expected) {
            // expected
        }
    }
}
