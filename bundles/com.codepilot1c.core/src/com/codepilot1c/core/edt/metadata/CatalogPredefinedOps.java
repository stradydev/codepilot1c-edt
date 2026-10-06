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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.eclipse.emf.common.util.EList;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.NumberValue;
import com._1c.g5.v8.dt.mcore.StringValue;
import com._1c.g5.v8.dt.mcore.Value;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogCodeType;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogPredefined;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogPredefinedItem;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

/**
 * {@code update_metadata changes.predefined_ops} for a Catalog: ADDITIVE item-level edits of the predefined
 * items, never a whole-list replace (feedback 2026-10-06 catalog-predefined-item-add-no-tool).
 *
 * <pre>
 * {op:"add",    name, description?, code?, is_folder?, parent?}
 * {op:"set",    name, new_name?, description?, code?}
 * {op:"delete", name}
 * </pre>
 *
 * Names resolve case-insensitively across the whole item tree (folders included). All ops are validated
 * against the evolving state, so an invalid op aborts the caller's transaction before anything persists.
 */
public final class CatalogPredefinedOps {

    /** Supported {@code op} values — shared with the request validator. */
    public static final Set<String> OPS = Set.of("add", "set", "delete"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}_][\\p{L}\\p{Nd}_]*"); //$NON-NLS-1$

    private CatalogPredefinedOps() {
    }

    /** Applies the ops in order; returns a one-line summary per op. */
    public static List<String> apply(Catalog catalog, List<Map<String, Object>> ops) {
        List<String> applied = new ArrayList<>();
        if (ops == null || ops.isEmpty()) {
            return applied;
        }
        CatalogPredefined predefined = catalog.getPredefined();
        if (predefined == null) {
            predefined = MdClassFactory.eINSTANCE.createCatalogPredefined();
            catalog.setPredefined(predefined);
        }
        for (Map<String, Object> op : ops) {
            String kind = lower(str(op, "op")); //$NON-NLS-1$
            String name = str(op, "name"); //$NON-NLS-1$
            if (kind == null || !OPS.contains(kind)) {
                throw fail("predefined_ops item needs op: add | set | delete, got: " + op); //$NON-NLS-1$
            }
            if (name == null || name.isBlank()) {
                throw fail("predefined_ops " + kind + " needs name"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            switch (kind) {
                case "add" -> applied.add(add(catalog, predefined, op, name.trim())); //$NON-NLS-1$
                case "set" -> applied.add(set(catalog, predefined, op, name.trim())); //$NON-NLS-1$
                default -> applied.add(delete(predefined, name.trim()));
            }
        }
        return applied;
    }

    private static String add(Catalog catalog, CatalogPredefined predefined, Map<String, Object> op, String name) {
        requireIdentifier(name);
        if (find(predefined.getItems(), name) != null) {
            throw fail("Predefined item '" + name + "' already exists in " + catalog.getName() //$NON-NLS-1$ //$NON-NLS-2$
                    + " — use op:set to change it"); //$NON-NLS-1$
        }
        EList<CatalogPredefinedItem> container = predefined.getItems();
        String parentName = str(op, "parent"); //$NON-NLS-1$
        if (parentName != null && !parentName.isBlank()) {
            CatalogPredefinedItem parent = find(predefined.getItems(), parentName.trim());
            if (parent == null || !parent.isIsFolder()) {
                throw fail("parent '" + parentName + "' is not an existing predefined folder"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            container = parent.getContent();
        }
        CatalogPredefinedItem item = MdClassFactory.eINSTANCE.createCatalogPredefinedItem();
        item.setId(UUID.randomUUID());
        item.setName(name);
        String description = str(op, "description"); //$NON-NLS-1$
        if (description != null) {
            item.setDescription(description);
        }
        if (Boolean.TRUE.equals(op.get("is_folder")) || "true".equalsIgnoreCase(str(op, "is_folder"))) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            item.setIsFolder(true);
        }
        String code = str(op, "code"); //$NON-NLS-1$
        if (code != null && !code.isBlank()) {
            item.setCode(codeValue(catalog, predefined, code.trim(), null));
        }
        container.add(item);
        return "added " + name + (code == null ? "" : " (code " + code.trim() + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    private static String set(Catalog catalog, CatalogPredefined predefined, Map<String, Object> op, String name) {
        CatalogPredefinedItem item = require(predefined, name);
        String newName = str(op, "new_name"); //$NON-NLS-1$
        if (newName != null && !newName.isBlank() && !newName.trim().equalsIgnoreCase(name)) {
            requireIdentifier(newName.trim());
            if (find(predefined.getItems(), newName.trim()) != null) {
                throw fail("Predefined item '" + newName.trim() + "' already exists"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            item.setName(newName.trim());
        }
        String description = str(op, "description"); //$NON-NLS-1$
        if (description != null) {
            item.setDescription(description);
        }
        String code = str(op, "code"); //$NON-NLS-1$
        if (code != null && !code.isBlank()) {
            item.setCode(codeValue(catalog, predefined, code.trim(), item));
        }
        return "set " + item.getName(); //$NON-NLS-1$
    }

    private static String delete(CatalogPredefined predefined, String name) {
        CatalogPredefinedItem item = require(predefined, name);
        if (item.isIsFolder() && !item.getContent().isEmpty()) {
            throw fail("Predefined folder '" + name + "' is not empty — delete its items first"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        org.eclipse.emf.ecore.util.EcoreUtil.remove(item);
        return "deleted " + name; //$NON-NLS-1$
    }

    /** Code typed per the catalog's code type; unique among predefined items, within code length. */
    private static Value codeValue(Catalog catalog, CatalogPredefined predefined, String code,
            CatalogPredefinedItem self) {
        for (CatalogPredefinedItem other : flatten(predefined.getItems())) {
            if (other != self && code.equals(codeText(other.getCode()))) {
                throw fail("Code '" + code + "' is already used by predefined item '" + other.getName() + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }
        int length = catalog.getCodeLength();
        if (length > 0 && code.length() > length) {
            throw fail("Code '" + code + "' is longer than the catalog code length " + length); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (catalog.getCodeType() == CatalogCodeType.NUMBER) {
            NumberValue value = McoreFactory.eINSTANCE.createNumberValue();
            try {
                value.setValue(new BigDecimal(code));
            } catch (NumberFormatException e) {
                throw fail("Catalog " + catalog.getName() + " has a Number code; '" + code + "' is not a number"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            return value;
        }
        StringValue value = McoreFactory.eINSTANCE.createStringValue();
        value.setValue(code);
        return value;
    }

    static String codeText(Value value) {
        if (value instanceof StringValue s) {
            return s.getValue();
        }
        if (value instanceof NumberValue n && n.getValue() != null) {
            return n.getValue().toPlainString();
        }
        return null;
    }

    private static CatalogPredefinedItem require(CatalogPredefined predefined, String name) {
        CatalogPredefinedItem item = find(predefined.getItems(), name);
        if (item == null) {
            throw fail("Predefined item '" + name + "' not found"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return item;
    }

    static CatalogPredefinedItem find(List<CatalogPredefinedItem> items, String name) {
        for (CatalogPredefinedItem item : flatten(items)) {
            if (item.getName() != null && item.getName().equalsIgnoreCase(name)) {
                return item;
            }
        }
        return null;
    }

    private static List<CatalogPredefinedItem> flatten(List<CatalogPredefinedItem> items) {
        List<CatalogPredefinedItem> all = new ArrayList<>();
        for (CatalogPredefinedItem item : items) {
            all.add(item);
            all.addAll(flatten(item.getContent()));
        }
        return all;
    }

    private static void requireIdentifier(String name) {
        if (!IDENTIFIER.matcher(name).matches()) {
            throw fail("'" + name + "' is not a valid predefined item name (identifier)"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static String str(Map<String, Object> op, String key) {
        Object value = op.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String lower(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static MetadataOperationException fail(String message) {
        return new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_CHANGE, message, false);
    }
}
