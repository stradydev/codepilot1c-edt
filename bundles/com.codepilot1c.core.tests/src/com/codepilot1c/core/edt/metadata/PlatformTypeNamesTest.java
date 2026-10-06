package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Tests for {@link PlatformTypeNames} — the pure half of the "UUID form attribute" fix
 * (codepilot1c-feedback/2026-10-06-apply-form-recipe-uuid-attribute-type.md).
 *
 * <p>What was broken: {@code apply_form_recipe attributes=[{name, action:"add", type:"UUID"}]}
 * failed with {@code Type not found in BM: UUID} (same for {@code УникальныйИдентификатор}). The
 * pre-resolve step treated a BM-namespace miss as final unless the name was on a hardcoded
 * primitive allow-list (String/Number/Date/Boolean/ValueStorage + collection built-ins), so the
 * resolver ladder that can resolve any platform type (TypeProviderService, configuration scan)
 * never ran. The ladder itself needs a live EDT and is not covered here.</p>
 */
public class PlatformTypeNamesTest {

    // --- the reported case -----------------------------------------------------

    @Test
    public void uuidBmMissIsNotFinal() {
        assertFalse(PlatformTypeNames.isBmMissFinal("UUID")); //$NON-NLS-1$
        assertFalse(PlatformTypeNames.isBmMissFinal("УникальныйИдентификатор")); //$NON-NLS-1$
        assertFalse(PlatformTypeNames.isBmMissFinal("uuid")); //$NON-NLS-1$
    }

    @Test
    public void uuidIsACanonicalPrimitiveInBothSpellings() {
        assertEquals("UUID", PlatformTypeNames.canonicalSimpleTypeName("UUID")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("UUID", PlatformTypeNames.canonicalSimpleTypeName("уникальныйИдентификатор")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- any bare platform type falls through, not only the allow-listed ones ---

    @Test
    public void bareUnlistedPlatformTypesAreNotBmAuthoritative() {
        for (String type : List.of("BinaryData", "ДвоичныеДанные", "StandardPeriod", "FormattedString", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "Picture", "TypeDescription")) { //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(type, PlatformTypeNames.isBmMissFinal(type));
        }
    }

    @Test
    public void previouslyAllowListedTypesStillFallThrough() {
        for (String type : List.of("String", "String(100)", "Number(15,2)", "Date", "Boolean", "Булево", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                "ValueStorage", "ValueTable", "ТаблицаЗначений", "FixedMap")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            assertFalse(type, PlatformTypeNames.isBmMissFinal(type));
        }
    }

    // --- metadata references keep failing fast --------------------------------

    @Test
    public void qualifiedMetadataReferenceMissIsFinal() {
        assertTrue(PlatformTypeNames.isBmMissFinal("CatalogRef.Missing")); //$NON-NLS-1$
        assertTrue(PlatformTypeNames.isBmMissFinal("СправочникСсылка.Нет")); //$NON-NLS-1$
        assertTrue(PlatformTypeNames.isBmMissFinal("DefinedType.Money")); //$NON-NLS-1$
    }

    @Test
    public void blankQueryIsNotFinal() {
        assertFalse(PlatformTypeNames.isBmMissFinal(null));
        assertFalse(PlatformTypeNames.isBmMissFinal("  ")); //$NON-NLS-1$
    }

    // --- primitive classification ----------------------------------------------

    @Test
    public void simpleTokenCoversUuidAndValueStorageButNotReferences() {
        assertTrue(PlatformTypeNames.isSimpleTypeToken("UUID")); //$NON-NLS-1$
        assertTrue(PlatformTypeNames.isSimpleTypeToken("ХранилищеЗначения")); //$NON-NLS-1$
        assertTrue(PlatformTypeNames.isSimpleTypeToken("Boolean")); //$NON-NLS-1$
        assertFalse(PlatformTypeNames.isSimpleTypeToken("CatalogRef.Goods")); //$NON-NLS-1$
        assertFalse(PlatformTypeNames.isSimpleTypeToken("String.Foo")); //$NON-NLS-1$
        assertFalse(PlatformTypeNames.isSimpleTypeToken("ValueTable")); //$NON-NLS-1$
        assertFalse(PlatformTypeNames.isSimpleTypeToken("")); //$NON-NLS-1$
    }

    @Test
    public void builtInNamesAreNotPrimitives() {
        assertNull(PlatformTypeNames.canonicalSimpleTypeName("ValueTable")); //$NON-NLS-1$
        assertEquals("ValueTable", PlatformTypeNames.canonicalPlatformBuiltInTypeName("value_table")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- edt_field_type_candidates ordering --------------------------------------

    @Test
    public void primitivesComeFirstAndOtherwiseKeepProviderOrder() {
        FieldTypeCandidate ref1 = candidate("CatalogRef.A", false); //$NON-NLS-1$
        FieldTypeCandidate number = candidate("Number", true); //$NON-NLS-1$
        FieldTypeCandidate ref2 = candidate("CatalogRef.B", false); //$NON-NLS-1$
        FieldTypeCandidate uuid = candidate("UUID", true); //$NON-NLS-1$

        List<FieldTypeCandidate> ordered = PlatformTypeNames.primitivesFirst(List.of(ref1, number, ref2, uuid));

        assertEquals(List.of(number, uuid, ref1, ref2), ordered);
    }

    @Test
    public void primitivesFirstToleratesEmptyAndNull() {
        assertTrue(PlatformTypeNames.primitivesFirst(null).isEmpty());
        assertTrue(PlatformTypeNames.primitivesFirst(List.of()).isEmpty());
    }

    private static FieldTypeCandidate candidate(String name, boolean simple) {
        return new FieldTypeCandidate(name, "", name, "", "", simple); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
