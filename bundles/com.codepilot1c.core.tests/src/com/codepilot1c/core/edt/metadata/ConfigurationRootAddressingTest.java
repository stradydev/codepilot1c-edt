package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Language;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Role;

/**
 * Behavioural regression for addressing the configuration ROOT.
 *
 * <p>Background: codepilot1c-feedback
 * {@code 2026-09-11-update-metadata-cannot-address-configuration-root}. The root is a singleton with
 * no {@code <Name>} segment, and both spellings a caller can reach for were refused at a DIFFERENT
 * stage with a different error — the bare {@code Configuration} was read as a parent still waiting
 * for its child part ({@code METADATA_PARENT_NOT_FOUND}), and {@code Configuration.<ConfigName>} died
 * as an unsupported kind. So {@code synonym}, {@code version} and {@code defaultRoles} — per-release
 * edits on an extension, not edge cases — had no path through MCP at all.</p>
 *
 * <p>The fix is a RESOLVER change, not a property feature: once {@code resolveByFqn} hands back the
 * {@code Configuration} object, the generic writer behind {@code update_metadata} treats it like any
 * other {@code MdObject}. These tests therefore drive the real write path
 * ({@link EdtMetadataService#applyObjectChanges}) over factory-built EMF objects and assert what
 * lands in the MODEL — a source-contract test would have passed while the live call still failed.</p>
 */
public class ConfigurationRootAddressingTest {

    private static final MdClassFactory FACTORY = MdClassFactory.eINSTANCE;

    private final EdtMetadataService service = new EdtMetadataService();

    // --- addressing -----------------------------------------------------------------------------

    @Test
    public void theBareReservedTokenResolvesTheRootItself() {
        Configuration configuration = configuration("MCPapi"); //$NON-NLS-1$

        assertSame("the bare token must address the configuration itself, not a parent awaiting a name", //$NON-NLS-1$
                configuration, service.resolveByFqn(configuration, "Configuration")); //$NON-NLS-1$
    }

    @Test
    public void theTokenIsCaseInsensitiveAndHasTheRussianSpelling() {
        Configuration configuration = configuration("MCPapi"); //$NON-NLS-1$

        assertSame(configuration, service.resolveByFqn(configuration, "configuration")); //$NON-NLS-1$
        assertSame(configuration, service.resolveByFqn(configuration, "CONFIGURATION")); //$NON-NLS-1$
        assertSame(configuration, service.resolveByFqn(configuration, " Configuration ")); //$NON-NLS-1$
        assertSame("every other kind token accepts its Russian name — this one must too", //$NON-NLS-1$
                configuration, service.resolveByFqn(configuration, "Конфигурация")); //$NON-NLS-1$
    }

    @Test
    public void theNameSegmentFormStaysRefusedAndIsExplained() {
        // The configuration's own <name> is NOT an FQN name part; accepting a second spelling for a
        // singleton buys nothing, so the refusal is where the caller learns the right one.
        Configuration configuration = configuration("MCPapi"); //$NON-NLS-1$

        assertNull(service.resolveByFqn(configuration, "Configuration.MCPapi")); //$NON-NLS-1$
        String message = service.metadataNotFoundMessage("Configuration.MCPapi"); //$NON-NLS-1$
        assertTrue("the message must name the spelling that works:\n" + message, //$NON-NLS-1$
                message.contains("'Configuration'")); //$NON-NLS-1$
        assertTrue("and must not read as 'this configuration has no such object':\n" + message, //$NON-NLS-1$
                message.contains("singleton")); //$NON-NLS-1$
    }

    @Test
    public void aBareTypeTokenOfAnyOtherKindIsStillAParentError() {
        // The reserved token must not turn "Catalog" into an address for the catalog collection —
        // a bare type token is an error everywhere else and stays one.
        Configuration configuration = configuration("MCPapi"); //$NON-NLS-1$
        try {
            service.resolveByFqn(configuration, "Catalog"); //$NON-NLS-1$
            fail("a bare non-root type token must stay a parent error"); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.METADATA_PARENT_NOT_FOUND, e.getCode());
        }
    }

    @Test
    public void theRootsExportTargetIsTheTokenItself() {
        // The second half of the fix: a mutation that writes BM but names no valid export target
        // never reaches Configuration.mdo. "Configuration" is the string forceExport already uses.
        assertEquals("Configuration", service.extractTopLevelFqn("Configuration")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Configuration", service.extractTopLevelFqn("Конфигурация")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Catalog.Goods", service.extractTopLevelFqn("Catalog.Goods.Attribute.Price")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- the properties the feedback note needs -------------------------------------------------

    @Test
    public void versionIsWrittenThroughTheGenericSetter() {
        Configuration configuration = configuration("MCPapi"); //$NON-NLS-1$

        apply(configuration, set(Map.of("version", "1.0.0"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("1.0.0", configuration.getVersion()); //$NON-NLS-1$
    }

    @Test
    public void synonymLandsUnderTheRequestedLanguage() {
        Configuration configuration = configuration("MCPapi"); //$NON-NLS-1$

        apply(configuration, set(Map.of("synonym", Map.of("en", "MCP API")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("MCP API", configuration.getSynonym().get("en")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aPlainStringSynonymLandsUnderTheConfigurationDefaultLanguage() {
        Configuration configuration = configuration("MCPapi"); //$NON-NLS-1$
        Language english = FACTORY.createLanguage();
        english.setName("English"); //$NON-NLS-1$
        english.setLanguageCode("en"); //$NON-NLS-1$
        configuration.getLanguages().add(english);
        configuration.setDefaultLanguage(english);

        apply(configuration, set(Map.of("synonym", "MCP API"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("MCP API", configuration.getSynonym().get("en")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void namePrefixAndPurposeGoThroughTheSameGenericPath() {
        // Named in the feedback note as typical root edits; no per-property code exists for them,
        // which is exactly the point — they work because the resolver now yields the root.
        Configuration configuration = configuration("MCPapi"); //$NON-NLS-1$

        apply(configuration, set(Map.of("namePrefix", "MCP"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("MCP", configuration.getNamePrefix()); //$NON-NLS-1$
    }

    @Test
    public void defaultRolesAreClearedByUnset() {
        // The note's third item: "снять назначение Role.MCP_DefaultRole". defaultRoles is a
        // many-valued non-containment reference and the generic unset already clears such a list —
        // no children_ops-style per-element operation is needed to drop the sole assignment.
        Configuration configuration = configuration("MCPapi"); //$NON-NLS-1$
        Role role = role(configuration, "MCP_DefaultRole"); //$NON-NLS-1$
        configuration.getDefaultRoles().add(role);

        apply(configuration, unset("defaultRoles")); //$NON-NLS-1$

        assertTrue("the assignment must be gone, not merely re-pointed", //$NON-NLS-1$
                configuration.getDefaultRoles().isEmpty());
    }

    @Test
    public void defaultRolesCanBeReplacedWholesale() {
        Configuration configuration = configuration("MCPapi"); //$NON-NLS-1$
        Role oldRole = role(configuration, "MCP_DefaultRole"); //$NON-NLS-1$
        Role newRole = role(configuration, "FullAccess"); //$NON-NLS-1$
        configuration.getDefaultRoles().add(oldRole);

        apply(configuration, set(Map.of("defaultRoles", List.of("Role.FullAccess")))); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(1, configuration.getDefaultRoles().size());
        assertSame(newRole, configuration.getDefaultRoles().get(0));
    }

    @Test
    public void anEmptyListAlsoClearsTheAssignment() {
        Configuration configuration = configuration("MCPapi"); //$NON-NLS-1$
        configuration.getDefaultRoles().add(role(configuration, "MCP_DefaultRole")); //$NON-NLS-1$

        apply(configuration, set(Map.of("defaultRoles", List.of()))); //$NON-NLS-1$

        assertTrue(configuration.getDefaultRoles().isEmpty());
    }

    // --- no silent drops -------------------------------------------------------------------------

    @Test
    public void amisspelledRootPropertyFailsLoud() {
        Configuration configuration = configuration("MCPapi"); //$NON-NLS-1$
        try {
            apply(configuration, set(Map.of("versionNumber", "1.0.0"))); //$NON-NLS-1$ //$NON-NLS-2$
            fail("an unknown root property must be refused, not swallowed"); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.INVALID_METADATA_CHANGE, e.getCode());
            assertTrue(e.getMessage(), e.getMessage().contains("versionNumber")); //$NON-NLS-1$
        }
    }

    @Test
    public void renamingTheConfigurationStaysRefused() {
        Configuration configuration = configuration("MCPapi"); //$NON-NLS-1$
        try {
            apply(configuration, set(Map.of("name", "Renamed"))); //$NON-NLS-1$ //$NON-NLS-2$
            fail("update_metadata never renames — the root is no exception"); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.INVALID_METADATA_CHANGE, e.getCode());
        }
        assertEquals("MCPapi", configuration.getName()); //$NON-NLS-1$
    }

    // --- the token itself -------------------------------------------------------------------------

    @Test
    public void theTokenHelperAgreesWithTheResolver() {
        assertTrue(ConfigurationRootFqn.isRootFqn("Configuration")); //$NON-NLS-1$
        assertTrue(ConfigurationRootFqn.isRootFqn("конфигурация")); //$NON-NLS-1$
        assertTrue("a trailing segment is the root MEANT, not a different object", //$NON-NLS-1$
                ConfigurationRootFqn.hasRootTypeToken("Configuration.MCPapi")); //$NON-NLS-1$
        assertTrue(!ConfigurationRootFqn.isRootFqn("Configuration.MCPapi")); //$NON-NLS-1$
        assertTrue(!ConfigurationRootFqn.isRootFqn(null));
        assertTrue(!ConfigurationRootFqn.hasRootTypeToken("Catalog.Goods")); //$NON-NLS-1$
    }

    // --- helpers ----------------------------------------------------------------------------------

    private Configuration configuration(String name) {
        Configuration created = FACTORY.createConfiguration();
        created.setName(name);
        return created;
    }

    private Role role(Configuration configuration, String name) {
        Role created = FACTORY.createRole();
        created.setName(name);
        configuration.getRoles().add(created);
        return created;
    }

    private Map<String, Object> set(Map<String, Object> setChanges) {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("set", setChanges); //$NON-NLS-1$
        return changes;
    }

    private Map<String, Object> unset(String... fields) {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("unset", List.of(fields)); //$NON-NLS-1$
        return changes;
    }

    private void apply(Configuration configuration, Map<String, Object> changes) {
        service.applyObjectChanges(
                configuration, configuration, changes, ConfigurationRootFqn.TOKEN,
                null, Map.of(), fqn -> { /* no co-edited top object on a root property write */ });
    }
}
