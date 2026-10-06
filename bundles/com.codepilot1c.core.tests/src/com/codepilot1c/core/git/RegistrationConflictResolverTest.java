/*******************************************************************************
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Copyright (C) 2026 codepilot1c-edt contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License v3.0 as published by the
 * Free Software Foundation.
 ******************************************************************************/
package com.codepilot1c.core.git;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/**
 * Feedback 2026-07-21 cases 4 and 7: two branches registered new top-level objects at the same anchor
 * of a {@code Configuration.mdo} flat list; the merge conflicts and no BM-API path can repair a
 * conflict-marked file. The resolver keeps the union of registration-only hunks and refuses anything
 * else.
 */
public class RegistrationConflictResolverTest {

    private static final String HEAD = "<mdclass:Configuration>\n" //$NON-NLS-1$
            + "  <commonModules>CommonModule.CM_APIs</commonModules>\n"; //$NON-NLS-1$
    private static final String TAIL = "  <roles>Role.Admin</roles>\n</mdclass:Configuration>\n"; //$NON-NLS-1$

    private static RegistrationConflictResolver.Result resolve(String text) {
        return RegistrationConflictResolver.resolve(text, r -> true);
    }

    @Test
    public void case7UnionOfTwoNewCommonModules() {
        String text = HEAD
                + "<<<<<<< HEAD\n" //$NON-NLS-1$
                + "  <commonModules>CommonModule.CM_Common</commonModules>\n" //$NON-NLS-1$
                + "=======\n" //$NON-NLS-1$
                + "  <commonModules>CommonModule.CM_Integration_BankStatements</commonModules>\n" //$NON-NLS-1$
                + ">>>>>>> origin/master\n" //$NON-NLS-1$
                + TAIL;

        RegistrationConflictResolver.Result result = resolve(text);

        assertFalse(result.refusal(), result.refused());
        assertEquals(HEAD
                + "  <commonModules>CommonModule.CM_Common</commonModules>\n" //$NON-NLS-1$
                + "  <commonModules>CommonModule.CM_Integration_BankStatements</commonModules>\n" //$NON-NLS-1$
                + TAIL, result.resolvedText());
        assertEquals(List.of("CommonModule.CM_Common"), result.keptFromOurs()); //$NON-NLS-1$
        assertEquals(List.of("CommonModule.CM_Integration_BankStatements"), result.keptFromTheirs()); //$NON-NLS-1$
        assertEquals(1, result.hunks());
    }

    @Test
    public void unionGroupsPerListAndDeduplicatesSharedLines() {
        String text = "<<<<<<< HEAD\n" //$NON-NLS-1$
                + "  <roles>Role.A</roles>\n" //$NON-NLS-1$
                + "  <roles>Role.Shared</roles>\n" //$NON-NLS-1$
                + "  <catalogs>Catalog.C1</catalogs>\n" //$NON-NLS-1$
                + "=======\n" //$NON-NLS-1$
                + "  <roles>Role.Shared</roles>\n" //$NON-NLS-1$
                + "  <catalogs>Catalog.C2</catalogs>\n" //$NON-NLS-1$
                + "  <roles>Role.B</roles>\n" //$NON-NLS-1$
                + ">>>>>>> dev\n"; //$NON-NLS-1$

        RegistrationConflictResolver.Result result = resolve(text);

        assertEquals("  <roles>Role.A</roles>\n  <roles>Role.Shared</roles>\n  <roles>Role.B</roles>\n" //$NON-NLS-1$
                + "  <catalogs>Catalog.C1</catalogs>\n  <catalogs>Catalog.C2</catalogs>\n", result.resolvedText()); //$NON-NLS-1$
    }

    @Test
    public void refusesHunkWithANonRegistrationLine() {
        String text = "<<<<<<< HEAD\n" //$NON-NLS-1$
                + "  <roles>Role.A</roles>\n" //$NON-NLS-1$
                + "  <synonym><key>en</key></synonym>\n" //$NON-NLS-1$
                + "=======\n" //$NON-NLS-1$
                + "  <roles>Role.B</roles>\n" //$NON-NLS-1$
                + ">>>>>>> dev\n"; //$NON-NLS-1$

        RegistrationConflictResolver.Result result = resolve(text);

        assertTrue(result.refused());
        assertNull("a refusal must carry no text to write", result.resolvedText()); //$NON-NLS-1$
        assertTrue(result.refusal(), result.refusal().contains("<synonym>")); //$NON-NLS-1$
    }

    @Test
    public void refusesASingleValuedDefaultReference() {
        String text = "<<<<<<< HEAD\n  <defaultLanguage>Language.English</defaultLanguage>\n=======\n" //$NON-NLS-1$
                + "  <defaultLanguage>Language.Russian</defaultLanguage>\n>>>>>>> dev\n"; //$NON-NLS-1$
        assertTrue("a union of a single-valued property would write two values into one slot", //$NON-NLS-1$
                resolve(text).refused());
        assertTrue(RegistrationConflictResolver.isMultiValuedList("defaultRoles")); //$NON-NLS-1$
        assertTrue(RegistrationConflictResolver.isMultiValuedList("filterCriteria")); //$NON-NLS-1$
    }

    @Test
    public void diff3BaseDropsWhatASideDeleted() {
        String text = "<<<<<<< HEAD\n" //$NON-NLS-1$
                + "  <roles>Role.New</roles>\n" //$NON-NLS-1$
                + "||||||| base\n" //$NON-NLS-1$
                + "  <roles>Role.Old</roles>\n" //$NON-NLS-1$
                + "=======\n" //$NON-NLS-1$
                + "  <roles>Role.Old</roles>\n" //$NON-NLS-1$
                + "  <roles>Role.Other</roles>\n" //$NON-NLS-1$
                + ">>>>>>> dev\n"; //$NON-NLS-1$

        RegistrationConflictResolver.Result result = resolve(text);

        assertEquals("  <roles>Role.New</roles>\n  <roles>Role.Other</roles>\n", result.resolvedText()); //$NON-NLS-1$
        assertEquals(List.of("Role.Old"), result.droppedDeleted()); //$NON-NLS-1$
    }

    @Test
    public void dropsAOneSidedRegistrationWhoseObjectIsNotOnDisk() {
        String text = "<<<<<<< HEAD\n  <roles>Role.Shared</roles>\n  <roles>Role.Gone</roles>\n=======\n" //$NON-NLS-1$
                + "  <roles>Role.Shared</roles>\n  <roles>Role.Kept</roles>\n>>>>>>> dev\n"; //$NON-NLS-1$
        Set<String> onDisk = Set.of("Kept"); //$NON-NLS-1$

        RegistrationConflictResolver.Result result =
                RegistrationConflictResolver.resolve(text, r -> onDisk.contains(r.name()));

        assertEquals("a line both sides agree on is kept even unchecked; a one-sided dangling one is dropped", //$NON-NLS-1$
                "  <roles>Role.Shared</roles>\n  <roles>Role.Kept</roles>\n", result.resolvedText()); //$NON-NLS-1$
        assertEquals(List.of("Role.Gone"), result.droppedMissing()); //$NON-NLS-1$
    }

    @Test
    public void preservesCrlfAndRefusesAFileWithoutMarkers() {
        String text = "<a>\r\n<<<<<<< HEAD\r\n  <roles>Role.A</roles>\r\n=======\r\n  <roles>Role.B</roles>\r\n" //$NON-NLS-1$
                + ">>>>>>> dev\r\n</a>\r\n"; //$NON-NLS-1$
        assertEquals("<a>\r\n  <roles>Role.A</roles>\r\n  <roles>Role.B</roles>\r\n</a>\r\n", //$NON-NLS-1$
                resolve(text).resolvedText());
        assertTrue(resolve("<a/>\n").refused()); //$NON-NLS-1$
        assertTrue(resolve("<<<<<<< HEAD\n  <roles>Role.A</roles>\n=======\n").refused()); //$NON-NLS-1$
    }

    @Test
    public void objectExistenceIsCheckedByFolderAndMdclassKind() throws Exception {
        Path src = Files.createTempDirectory("reg-conflict-src"); //$NON-NLS-1$
        Path mdo = Files.createDirectories(src.resolve("CommonModules").resolve("CM_X")).resolve("CM_X.mdo"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Files.writeString(mdo, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
                + "<mdclass:CommonModule xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\">\n", //$NON-NLS-1$
                StandardCharsets.UTF_8);
        RegistrationConflictResolver.Registration commonModule =
                RegistrationConflictResolver.parse("  <commonModules>CommonModule.CM_X</commonModules>"); //$NON-NLS-1$
        RegistrationConflictResolver.Registration wrongKind =
                RegistrationConflictResolver.parse("  <catalogs>Catalog.CM_X</catalogs>"); //$NON-NLS-1$
        assertNotNull(commonModule);
        assertNotNull(wrongKind);
        assertTrue(GitService.registeredObjectExists(src, commonModule));
        assertFalse("a same-named object of another kind is not proof", //$NON-NLS-1$
                GitService.registeredObjectExists(src, wrongKind));
    }
}
