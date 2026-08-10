package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for {@link DeleteReferenceScope} — which incoming references block a {@code delete_metadata}.
 *
 * <p>Written around the live 2026-08-10 sandbox observation: a brand-new {@code SessionParameter} with
 * no module, no children and no user was refused, citing exactly one reference —
 * {@code Configuration#sessionParameters}, its own composition entry. Since every top-level object is
 * listed there by construction, the non-{@code force} top-level delete path was unreachable for every
 * kind. Both live samples ({@code Configuration#sessionParameters} and
 * {@code CommonModule.<Name>#source}) are asserted verbatim below.</p>
 *
 * <p>The other half of the suite is the anti-regression half, and it is the more important one: a
 * coarse "ignore everything coming from the Configuration" fix would also stop
 * {@code Configuration#defaultRoles} from blocking, and the delete does NOT clean that entry up — it
 * would leave a dangling {@code .mdo} line, the failure mode {@code removeSubsystemLinks} exists to
 * prevent. Those cases assert the filter stays narrow.</p>
 */
public class DeleteReferenceScopeTest {

    // --- the proven live cases ----------------------------------------------

    @Test
    public void ownCompositionEntryDoesNotBlockSessionParameter() {
        // The exact reference the live refusal cited, and the only one it cited.
        assertFalse(DeleteReferenceScope.blocksDelete(
                "SessionParameter.Probe", MetadataKind.SESSION_PARAMETER,
                "Configuration", "sessionParameters"));
    }

    @Test
    public void ownCompositionEntryDoesNotBlockCommonModule() {
        assertFalse(DeleteReferenceScope.blocksDelete(
                "CommonModule.Probe", MetadataKind.COMMON_MODULE,
                "Configuration", "commonModules"));
    }

    @Test
    public void ownModuleBackReferenceDoesNotBlock() {
        // The second sample of the live refusal: the object's own module pointing back at it.
        assertFalse(DeleteReferenceScope.blocksDelete(
                "CommonModule.Probe", MetadataKind.COMMON_MODULE,
                "CommonModule.Probe", "source"));
    }

    @Test
    public void genericContentMembershipDoesNotBlock() {
        assertFalse(DeleteReferenceScope.blocksDelete(
                "Catalog.Probe", MetadataKind.CATALOG, "Configuration", "content"));
    }

    @Test
    public void aFreshObjectWithOnlyThoseReferencesIsDeletable() {
        // The end-to-end shape of the bug: every reference the live scan produced, filtered away.
        String target = "CommonModule.Probe";
        MetadataKind kind = MetadataKind.COMMON_MODULE;
        assertFalse(DeleteReferenceScope.blocksDelete(target, kind, "Configuration", "commonModules"));
        assertFalse(DeleteReferenceScope.blocksDelete(target, kind, "Configuration", "content"));
        assertFalse(DeleteReferenceScope.blocksDelete(target, kind, target, "source"));
    }

    // --- the filter must stay narrow ---------------------------------------

    @Test
    public void configurationDefaultRolesStillBlocks() {
        // Same referrer (Configuration), different feature: a real user the delete does not clean up.
        assertTrue(DeleteReferenceScope.blocksDelete(
                "Role.Probe", MetadataKind.ROLE, "Configuration", "defaultRoles"));
    }

    @Test
    public void configurationScalarReferenceStillBlocks() {
        assertTrue(DeleteReferenceScope.blocksDelete(
                "Language.Probe", MetadataKind.LANGUAGE, "Configuration", "defaultLanguage"));
    }

    @Test
    public void aForeignCollectionIsNotMembership() {
        // The right referrer and a real composition feature — but not THIS object's.
        assertTrue(DeleteReferenceScope.blocksDelete(
                "Role.Probe", MetadataKind.ROLE, "Configuration", "commonModules"));
    }

    @Test
    public void anotherObjectStillBlocks() {
        assertTrue(DeleteReferenceScope.blocksDelete(
                "Catalog.Probe", MetadataKind.CATALOG, "Document.Invoice", "registerRecords"));
    }

    @Test
    public void anotherObjectNamedLikeTheTargetPrefixStillBlocks() {
        // "CommonModule.Probe" must not swallow "CommonModule.ProbeHelper".
        assertTrue(DeleteReferenceScope.blocksDelete(
                "CommonModule.Probe", MetadataKind.COMMON_MODULE,
                "CommonModule.ProbeHelper", "calls"));
    }

    @Test
    public void aChildDeleteCountsSiblingsInsideItsOwnTopObject() {
        // A form of Catalog.X referencing Catalog.X.Attribute.A is a genuine user. The subtree rule
        // compares the FULL target FQN, so it cannot fire here.
        assertTrue(DeleteReferenceScope.blocksDelete(
                "Catalog.X.Attribute.A", MetadataKind.CATALOG, "Catalog.X", "dataPath"));
    }

    @Test
    public void aChildDeleteHasNoCompositionExemption() {
        assertTrue(DeleteReferenceScope.blocksDelete(
                "Catalog.X.Attribute.A", MetadataKind.CATALOG, "Configuration", "content"));
    }

    // --- fail-safe direction: every uncertainty keeps the old refusal -------

    @Test
    public void unknownKindKeepsBlocking() {
        assertTrue(DeleteReferenceScope.blocksDelete(
                "Mystery.Probe", null, "Configuration", "content"));
    }

    @Test
    public void blankFeatureNameKeepsBlocking() {
        assertTrue(DeleteReferenceScope.blocksDelete(
                "Catalog.Probe", MetadataKind.CATALOG, "Configuration", "  "));
        assertTrue(DeleteReferenceScope.blocksDelete(
                "Catalog.Probe", MetadataKind.CATALOG, "Configuration", null));
    }

    @Test
    public void blankTargetKeepsBlocking() {
        assertTrue(DeleteReferenceScope.blocksDelete(null, MetadataKind.CATALOG, null, "content"));
        assertTrue(DeleteReferenceScope.blocksDelete("", MetadataKind.CATALOG, "", "content"));
    }

    // --- spelling tolerance -------------------------------------------------

    @Test
    public void featureNameMatchesRegardlessOfCase() {
        // The mapping spells the .mdo element name; for XDTOPackage the EMF accessor is
        // getXDTOPackages(), so the feature name may reach us as xDTOPackages.
        assertFalse(DeleteReferenceScope.blocksDelete(
                "XDTOPackage.Probe", MetadataKind.XDTO_PACKAGE, "Configuration", "xDTOPackages"));
        assertFalse(DeleteReferenceScope.blocksDelete(
                "XDTOPackage.Probe", MetadataKind.XDTO_PACKAGE, "Configuration", "xdtoPackages"));
    }

    @Test
    public void configurationReferrerMatchesRegardlessOfCase() {
        assertFalse(DeleteReferenceScope.blocksDelete(
                "Catalog.Probe", MetadataKind.CATALOG, "configuration", "content"));
    }

    // --- helpers ------------------------------------------------------------

    @Test
    public void topLevelFqnIsExactlyTwoSegments() {
        assertTrue(DeleteReferenceScope.isTopLevelFqn("Catalog.Goods"));
        assertFalse(DeleteReferenceScope.isTopLevelFqn("Catalog"));
        assertFalse(DeleteReferenceScope.isTopLevelFqn("Catalog.Goods.Attribute.Code"));
        assertFalse(DeleteReferenceScope.isTopLevelFqn(null));
        assertFalse(DeleteReferenceScope.isTopLevelFqn("   "));
    }

    @Test
    public void kindOfFqnReadsTheLeadingSegment() {
        assertSame(MetadataKind.COMMON_MODULE, DeleteReferenceScope.kindOfFqn("CommonModule.Probe"));
        assertSame(MetadataKind.CATALOG, DeleteReferenceScope.kindOfFqn("Catalog.X.Attribute.A"));
    }

    @Test
    public void kindOfFqnNeverThrows() {
        // An unresolvable kind must degrade into "filter nothing", not into a failed delete.
        assertNull(DeleteReferenceScope.kindOfFqn("NoSuchKind.Probe"));
        assertNull(DeleteReferenceScope.kindOfFqn("Catalog"));
        assertNull(DeleteReferenceScope.kindOfFqn(null));
        assertNull(DeleteReferenceScope.kindOfFqn(".Leading"));
    }
}
