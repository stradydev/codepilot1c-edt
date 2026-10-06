/*******************************************************************************
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Copyright (C) 2026 codepilot1c-edt contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License v3.0 as published by the
 * Free Software Foundation.
 ******************************************************************************/
package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.NumberValue;
import com._1c.g5.v8.dt.mcore.StringValue;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogCodeType;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogPredefined;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogPredefinedItem;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

/**
 * Feedback 2026-10-06 (dev-stack-1, BF-10233): there was no path to ADD one predefined item to a catalog
 * with ~100 existing ones. predefined_ops must be additive — the existing items stay untouched.
 */
public class CatalogPredefinedOpsTest {

    private static Catalog alertsTypes() {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("AlertsTypes"); //$NON-NLS-1$
        catalog.setCodeType(CatalogCodeType.STRING);
        catalog.setCodeLength(9);
        CatalogPredefined predefined = MdClassFactory.eINSTANCE.createCatalogPredefined();
        predefined.getItems().add(item("AFL_CasinoMapping", "000000025")); //$NON-NLS-1$ //$NON-NLS-2$
        predefined.getItems().add(item("AFL_CommissionError", "000000071")); //$NON-NLS-1$ //$NON-NLS-2$
        catalog.setPredefined(predefined);
        return catalog;
    }

    private static CatalogPredefinedItem item(String name, String code) {
        CatalogPredefinedItem item = MdClassFactory.eINSTANCE.createCatalogPredefinedItem();
        item.setName(name);
        StringValue value = McoreFactory.eINSTANCE.createStringValue();
        value.setValue(code);
        item.setCode(value);
        return item;
    }

    @Test
    public void addAppendsOneItemAndKeepsTheExistingOnes() {
        Catalog catalog = alertsTypes();

        CatalogPredefinedOps.apply(catalog, List.of(Map.of("op", "add", //$NON-NLS-1$ //$NON-NLS-2$
                "name", "PS_MerchantStatementsImportResult", //$NON-NLS-1$ //$NON-NLS-2$
                "description", "Merchant statements import result", "code", "000000103"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        List<CatalogPredefinedItem> items = catalog.getPredefined().getItems();
        assertEquals("REGRESSION: additive — the two existing items must survive", 3, items.size()); //$NON-NLS-1$
        assertEquals("AFL_CasinoMapping", items.get(0).getName()); //$NON-NLS-1$
        CatalogPredefinedItem added = items.get(2);
        assertEquals("PS_MerchantStatementsImportResult", added.getName()); //$NON-NLS-1$
        assertEquals("Merchant statements import result", added.getDescription()); //$NON-NLS-1$
        assertTrue("a String-coded catalog gets a StringValue code", added.getCode() instanceof StringValue); //$NON-NLS-1$
        assertEquals("000000103", ((StringValue) added.getCode()).getValue()); //$NON-NLS-1$
        assertNotNull("a fresh item needs an id", added.getId()); //$NON-NLS-1$
    }

    @Test
    public void setAndDeleteWorkOnOneItemOnly() {
        Catalog catalog = alertsTypes();
        CatalogPredefinedOps.apply(catalog, List.of(
                Map.of("op", "set", "name", "afl_casinomapping", "description", "Changed"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                Map.of("op", "delete", "name", "AFL_CommissionError"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        List<CatalogPredefinedItem> items = catalog.getPredefined().getItems();
        assertEquals(1, items.size());
        assertEquals("Changed", items.get(0).getDescription()); //$NON-NLS-1$

        CatalogPredefinedOps.apply(catalog, List.of(Map.of("op", "delete", "name", "AFL_CasinoMapping"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertNull("deleting the last item must not leave an empty <predefined/>", catalog.getPredefined()); //$NON-NLS-1$
    }

    @Test
    public void refusesDuplicatesBadCodesAndUnknownNames() {
        expectRefusal(Map.of("op", "add", "name", "AFL_CasinoMapping"), "already exists"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        expectRefusal(Map.of("op", "add", "name", "X1", "code", "000000025"), "already used"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        expectRefusal(Map.of("op", "add", "name", "X2", "code", "0000000001"), "longer than"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        expectRefusal(Map.of("op", "add", "name", "1bad"), "not a valid"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        expectRefusal(Map.of("op", "delete", "name", "Nope"), "not found"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    @Test
    public void numberCodedCatalogGetsANumberValueAndAMissingPredefinedIsCreated() {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setCodeType(CatalogCodeType.NUMBER);
        assertNull(catalog.getPredefined());
        CatalogPredefinedOps.apply(catalog, List.of(Map.of("op", "add", "name", "First", "code", "7"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        CatalogPredefinedItem item = catalog.getPredefined().getItems().get(0);
        assertTrue(item.getCode() instanceof NumberValue);
        assertEquals("7", ((NumberValue) item.getCode()).getValue().toPlainString()); //$NON-NLS-1$
    }

    @Test
    public void validatorRefusesShapesTheMutatorWouldReject() {
        new UpdateMetadataRequest("p", "Catalog.AlertsTypes", //$NON-NLS-1$ //$NON-NLS-2$
                Map.of("predefined_ops", List.of(Map.of("op", "add", "name", "X")))).validate(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        expectInvalid(Map.of("bogus", Map.of())); //$NON-NLS-1$
        expectInvalid(Map.of("predefined_ops", List.of(Map.of("op", "upsert", "name", "X")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        expectInvalid(Map.of("predefined_ops", List.of(Map.of("op", "add")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static void expectRefusal(Map<String, Object> op, String fragment) {
        try {
            CatalogPredefinedOps.apply(alertsTypes(), List.of(op));
            fail("expected a refusal for " + op); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(fragment));
        }
    }

    private static void expectInvalid(Map<String, Object> changes) {
        try {
            new UpdateMetadataRequest("p", "Catalog.AlertsTypes", changes).validate(); //$NON-NLS-1$ //$NON-NLS-2$
            fail("expected the validator to refuse " + changes); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.INVALID_METADATA_CHANGE, e.getCode());
        }
    }
}
