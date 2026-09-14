/*******************************************************************************
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Copyright (C) 2026 codepilot1c-edt contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License v3.0 as published by the
 * Free Software Foundation.
 ******************************************************************************/
package com.codepilot1c.core.edt.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferenceException;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferences;
import com._1c.g5.v8.dt.platform.services.model.Group;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.ModelFactory;
import com._1c.g5.v8.dt.platform.services.model.Section;
import com.codepilot1c.core.edt.runtime.lease.InfobaseLeaseGuard;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.workspace.ManageAssociationsTool;

/**
 * Registry visibility + survival contract of {@code manage_associations}, from the 2026-09-14
 * feedback "dissociate removes the EDT registry entry — bind cannot restore it".
 *
 * <p>Two distinct defects live here:</p>
 * <ol>
 *   <li><b>The real one.</b> {@code IInfobaseManager.getAll()} returns only the registry
 *       resource's TOP-LEVEL sections (bytecode-verified on services.core 21.0.0 / 2025.2.x), so a
 *       row carrying {@code Folder=/STAGE} in {@code ibases.v8i} — a child of a {@link Group} — was
 *       invisible to the raw sweep {@code resolveRegistryRow} used to do, and {@code bind} answered
 *       INFOBASE_NOT_FOUND for an infobase that was registered the whole time. EDT's own lookups
 *       flatten via {@code InfobaseReferences.asPlainList} for exactly this reason.</li>
 *   <li><b>The guard.</b> {@code dissociate} must leave the flat registry exactly as it found it.
 *       The platform call provably cannot drop a row (bytecode-verified: it only rewrites the
 *       per-project association settings), but the recovery cost for a {@code kind=server} row is
 *       asymmetric — nobody without the server credentials can re-create it — so the row is
 *       snapshotted, verified and restored, and a failed restore is reported loudly.</li>
 * </ol>
 */
public class EdtInfobaseAssociationRegistryGuardTest {

    /** The exact live shape from the feedback: a kind=server row inside the /STAGE folder. */
    private static final String IB_NAME = "BF-12442_BF-13761"; //$NON-NLS-1$
    private static final String SRVR = "bastion-stage.erpdev.team"; //$NON-NLS-1$

    // -- defect 1: foldered registry rows must be visible to bind ---------------------------

    @Test
    public void bindResolvesAnInfobaseNestedInAV8iFolder() {
        InfobaseReference row = serverRow();
        Group folder = ModelFactory.eINSTANCE.createGroup();
        folder.setName("STAGE"); //$NON-NLS-1$
        folder.setUuid(UUID.randomUUID());
        folder.addSubsection(row);

        FakeRegistry registry = new FakeRegistry(folder);
        EdtInfobaseAssociationService service = service(registry, new PassiveAssociationManager());

        EdtInfobaseAssociationService.BindOutcome outcome =
                service.bind("Accounting management", "BF-14128", IB_NAME, null, false); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("REGRESSION: a registry row nested in an ibases.v8i folder must be " //$NON-NLS-1$
                + "resolvable — getAll() returns only top-level sections, so the sweep has to " //$NON-NLS-1$
                + "flatten Groups the way EDT's own findInfobaseByName does. Without this, bind " //$NON-NLS-1$
                + "reports INFOBASE_NOT_FOUND for an infobase that is registered.", //$NON-NLS-1$
                IB_NAME, outcome.infobaseName());
    }

    @Test
    public void aGenuinelyAbsentInfobaseStillReportsNotFound() {
        FakeRegistry registry = new FakeRegistry();
        EdtInfobaseAssociationService service = service(registry, new PassiveAssociationManager());
        try {
            service.bind("Accounting management", "BF-14128", IB_NAME, null, false); //$NON-NLS-1$ //$NON-NLS-2$
            fail("expected INFOBASE_NOT_FOUND for an unregistered infobase"); //$NON-NLS-1$
        } catch (EdtToolException expected) {
            assertEquals(EdtToolErrorCode.INFOBASE_NOT_FOUND, expected.getCode());
        }
    }

    // -- defect 2: the registry-survival guard -----------------------------------------------

    @Test
    public void dissociateRestoresARegistryRowThatVanishedAcrossThePlatformCall() {
        InfobaseReference row = serverRow();
        Group folder = ModelFactory.eINSTANCE.createGroup();
        folder.setName("STAGE"); //$NON-NLS-1$
        folder.setUuid(UUID.randomUUID());
        folder.addSubsection(row);

        FakeRegistry registry = new FakeRegistry(folder);
        String folderBefore = row.getFolder();
        // Models the reported symptom: dissociate returns cleanly, but the row is gone from the
        // flat registry afterwards, so the reverse bind can no longer resolve it.
        DroppingAssociationManager association =
                new DroppingAssociationManager(row, () -> registry.removeAll());

        EdtInfobaseAssociationService service = service(registry, association.proxy);
        EdtInfobaseAssociationService.DissociateOutcome outcome =
                service.dissociate("Accounting management", "BF-14128", IB_NAME); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(List.of(IB_NAME), outcome.removed());
        assertEquals("REGRESSION: a registry row dropped across dissociate must be put back — " //$NON-NLS-1$
                + "a kind=server row cannot be re-created without server credentials the caller " //$NON-NLS-1$
                + "does not hold", List.of(IB_NAME), outcome.registryRestored()); //$NON-NLS-1$
        assertEquals(List.of(), outcome.registryLost());
        assertEquals("the restore must re-register the SAME row object (all of srvr/ref/uuid/name " //$NON-NLS-1$
                + "intact), not a freshly minted one", 1, registry.added.size()); //$NON-NLS-1$
        assertSame(row, registry.added.get(0));
        assertEquals("the row must go back into the v8i folder it came from", //$NON-NLS-1$
                folderBefore == null || folderBefore.isBlank() ? null : folderBefore,
                registry.addedFolders.get(0));

        // The point of the whole exercise: the reverse bind works again.
        assertEquals(IB_NAME, service.bind("Accounting management", "BF-14128", //$NON-NLS-1$ //$NON-NLS-2$
                IB_NAME, null, false).infobaseName());
    }

    @Test
    public void dissociateLeavesAnUntouchedRegistryAlone() {
        InfobaseReference row = serverRow();
        FakeRegistry registry = new FakeRegistry(row);
        DroppingAssociationManager association =
                new DroppingAssociationManager(row, () -> { /* platform leaves the registry alone */ });

        EdtInfobaseAssociationService.DissociateOutcome outcome =
                service(registry, association.proxy)
                        .dissociate("Accounting management", "BF-14128", IB_NAME); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(List.of(IB_NAME), outcome.removed());
        assertTrue("the guard must not touch a registry that came out intact", //$NON-NLS-1$
                outcome.registryIntact());
        assertEquals("no re-registration may happen on the normal path", //$NON-NLS-1$
                0, registry.added.size());
    }

    @Test
    public void aFailedRegistryRestoreIsReportedInsteadOfASilentSuccess() {
        InfobaseReference row = serverRow();
        FakeRegistry registry = new FakeRegistry(row);
        registry.addFails = true;
        DroppingAssociationManager association =
                new DroppingAssociationManager(row, () -> registry.removeAll());

        EdtInfobaseAssociationService.DissociateOutcome outcome =
                service(registry, association.proxy)
                        .dissociate("Accounting management", "BF-14128", IB_NAME); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(List.of(IB_NAME), outcome.removed());
        assertEquals("REGRESSION: when the registry row cannot be put back the caller MUST be " //$NON-NLS-1$
                + "told — an unrecoverable kind=server entry reported as a clean success is the " //$NON-NLS-1$
                + "worst possible outcome", List.of(IB_NAME), outcome.registryLost()); //$NON-NLS-1$
        assertEquals(List.of(), outcome.registryRestored());
    }

    // -- the tool surface --------------------------------------------------------------------

    @Test
    public void toolReportsARestoredRegistryRowAndStillSucceeds() {
        ToolResult result = new ManageAssociationsTool(
                new FixedOutcomeService(new EdtInfobaseAssociationService.DissociateOutcome(
                        "refs/heads/BF-14128", List.of(IB_NAME), List.of(IB_NAME), List.of()))) //$NON-NLS-1$
                                .execute(Map.of("action", "dissociate", //$NON-NLS-1$ //$NON-NLS-2$
                                        "project_name", "Accounting management", //$NON-NLS-1$ //$NON-NLS-2$
                                        "branch", "BF-14128", //$NON-NLS-1$ //$NON-NLS-2$
                                        "infobase_name", IB_NAME)) //$NON-NLS-1$
                                .join();

        assertTrue(result.getContent(), result.isSuccess());
        assertTrue("the compensated platform defect must be visible to the caller: " //$NON-NLS-1$
                + result.getContent(), result.getContent().contains("registry_restored")); //$NON-NLS-1$
    }

    @Test
    public void toolFailsLoudlyWhenTheRegistryRowCouldNotBeRestored() {
        ToolResult result = new ManageAssociationsTool(
                new FixedOutcomeService(new EdtInfobaseAssociationService.DissociateOutcome(
                        "refs/heads/BF-14128", List.of(IB_NAME), List.of(), List.of(IB_NAME)))) //$NON-NLS-1$
                                .execute(Map.of("action", "dissociate", //$NON-NLS-1$ //$NON-NLS-2$
                                        "project_name", "Accounting management", //$NON-NLS-1$ //$NON-NLS-2$
                                        "branch", "BF-14128", //$NON-NLS-1$ //$NON-NLS-2$
                                        "infobase_name", IB_NAME)) //$NON-NLS-1$
                                .join();

        // ToolResult.failure carries its payload in errorMessage, not content.
        String reported = result.getErrorMessage();
        assertFalse("REGRESSION: an incomplete registry must never be reported as success — " //$NON-NLS-1$
                + reported, result.isSuccess());
        assertTrue(reported, reported.contains("registry_lost")); //$NON-NLS-1$
        assertTrue("the payload must still name what WAS detached: " + reported, //$NON-NLS-1$
                reported.contains(IB_NAME));
        assertTrue("the hint must warn that a kind=server re-registration needs credentials: " //$NON-NLS-1$
                + reported, reported.contains("server credentials")); //$NON-NLS-1$
    }

    // ---- support -------------------------------------------------------------------------------

    private static InfobaseReference serverRow() {
        InfobaseReference row = InfobaseReferences.newServerInfobaseReference(SRVR, IB_NAME);
        row.setName(IB_NAME);
        row.setUuid(UUID.fromString("eb7c8174-dcb4-48d1-abca-5704dae61211")); //$NON-NLS-1$
        return row;
    }

    private static EdtInfobaseAssociationService service(FakeRegistry registry,
            IInfobaseAssociationManager association) {
        return new TestableAssociationService(new StubGateway(association, registry.proxy));
    }

    /** Stateful stand-in for the flat registry: {@code getAll()} yields TOP-LEVEL sections only. */
    private static final class FakeRegistry implements InvocationHandler {
        final List<Section> top = new ArrayList<>();
        final List<Section> added = new ArrayList<>();
        final List<String> addedFolders = new ArrayList<>();
        boolean addFails;

        private final IInfobaseManager proxy = (IInfobaseManager) Proxy.newProxyInstance(
                IInfobaseManager.class.getClassLoader(),
                new Class<?>[] { IInfobaseManager.class }, this);

        FakeRegistry(Section... sections) {
            for (Section section : sections) {
                top.add(section);
            }
        }

        void removeAll() {
            top.clear();
        }

        @Override
        public Object invoke(Object p, Method method, Object[] args) throws InfobaseReferenceException {
            switch (method.getName()) {
                case "getAll": //$NON-NLS-1$
                    return new ArrayList<>(top);
                case "isPersistenceSupported": //$NON-NLS-1$
                    return Boolean.TRUE;
                case "add": {
                    if (args == null || args.length != 2 || !(args[0] instanceof Section section)) {
                        return null; // the 3-arg overload is not used here
                    }
                    if (addFails) {
                        throw new InfobaseReferenceException("registry write refused"); //$NON-NLS-1$
                    }
                    added.add(section);
                    addedFolders.add((String) args[1]);
                    top.add(section);
                    return null;
                }
                default:
                    return defaultReturn(method);
            }
        }
    }

    /**
     * Association manager whose {@code dissociate} succeeds and then runs {@code sideEffect} —
     * the knob that models "the registry row disappeared across the platform call".
     */
    private static final class DroppingAssociationManager implements InvocationHandler {
        private final InfobaseReference entry;
        private final Runnable sideEffect;

        final IInfobaseAssociationManager proxy = (IInfobaseAssociationManager) Proxy.newProxyInstance(
                IInfobaseAssociationManager.class.getClassLoader(),
                new Class<?>[] { IInfobaseAssociationManager.class }, this);

        DroppingAssociationManager(InfobaseReference entry, Runnable sideEffect) {
            this.entry = entry;
            this.sideEffect = sideEffect;
        }

        @Override
        public Object invoke(Object p, Method method, Object[] args) {
            switch (method.getName()) {
                case "getAssociation": //$NON-NLS-1$
                    return Optional.of(newAssociationProxy(List.of(entry)));
                case "dissociate": //$NON-NLS-1$
                    sideEffect.run();
                    return null;
                case "associate": //$NON-NLS-1$
                    return null;
                default:
                    return defaultReturn(method);
            }
        }
    }

    /** Association manager that accepts everything and holds nothing (bind-only scenarios). */
    private static final class PassiveAssociationManager implements InvocationHandler {
        private final IInfobaseAssociationManager proxy = (IInfobaseAssociationManager) Proxy.newProxyInstance(
                IInfobaseAssociationManager.class.getClassLoader(),
                new Class<?>[] { IInfobaseAssociationManager.class }, this);

        @Override
        public Object invoke(Object p, Method method, Object[] args) {
            return "associate".equals(method.getName()) ? null : defaultReturn(method); //$NON-NLS-1$
        }
    }

    private static EdtInfobaseAssociationService service(FakeRegistry registry,
            PassiveAssociationManager association) {
        return service(registry, association.proxy);
    }

    /** Service whose dissociate is pre-canned, so the tool's JSON contract can be asserted alone. */
    private static final class FixedOutcomeService extends EdtInfobaseAssociationService {
        private final DissociateOutcome outcome;

        FixedOutcomeService(DissociateOutcome outcome) {
            super(new EdtRuntimeGateway(), new InfobaseLeaseGuard(null, "test-stack", null)); //$NON-NLS-1$
            this.outcome = outcome;
        }

        @Override
        public DissociateOutcome dissociate(String projectName, String branch, String infobaseName) {
            return outcome;
        }
    }

    private static final class TestableAssociationService extends EdtInfobaseAssociationService {
        TestableAssociationService(EdtRuntimeGateway gateway) {
            super(gateway, new InfobaseLeaseGuard(null, "test-stack", null)); //$NON-NLS-1$
        }

        @Override
        protected IProject resolveProject(String projectName) {
            return newProjectProxy(projectName);
        }

        @Override
        protected void settleBeforeSetDefaultRetry() {
            // no pause in unit tests
        }
    }

    private static final class StubGateway extends EdtRuntimeGateway {
        private final IInfobaseAssociationManager associationManager;
        private final IInfobaseManager infobaseManager;

        StubGateway(IInfobaseAssociationManager associationManager, IInfobaseManager infobaseManager) {
            this.associationManager = associationManager;
            this.infobaseManager = infobaseManager;
        }

        @Override
        public IInfobaseAssociationManager getInfobaseAssociationManager() {
            return associationManager;
        }

        @Override
        public IInfobaseManager getInfobaseManager() {
            return infobaseManager;
        }
    }

    private static IInfobaseAssociation newAssociationProxy(List<InfobaseReference> refs) {
        return (IInfobaseAssociation) Proxy.newProxyInstance(
                IInfobaseAssociation.class.getClassLoader(),
                new Class<?>[] { IInfobaseAssociation.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getInfobases" -> refs; //$NON-NLS-1$
                    case "getDefaultInfobase" -> null; //$NON-NLS-1$
                    default -> defaultReturn(method);
                });
    }

    private static IProject newProjectProxy(String projectName) {
        return (IProject) Proxy.newProxyInstance(
                IProject.class.getClassLoader(),
                new Class<?>[] { IProject.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> projectName; //$NON-NLS-1$
                    case "exists", "isOpen" -> Boolean.TRUE; //$NON-NLS-1$ //$NON-NLS-2$
                    case "equals" -> Boolean.valueOf(proxy == args[0]); //$NON-NLS-1$
                    case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy)); //$NON-NLS-1$
                    case "toString" -> "StubProject[" + projectName + "]"; //$NON-NLS-1$ //$NON-NLS-2$
                    default -> defaultReturn(method);
                });
    }

    private static Object defaultReturn(Method method) {
        Class<?> ret = method.getReturnType();
        if (ret == boolean.class) {
            return Boolean.FALSE;
        }
        if (ret.isPrimitive()) {
            return Integer.valueOf(0);
        }
        if (ret == Optional.class) {
            return Optional.empty();
        }
        if (ret == List.class) {
            return List.of();
        }
        return null;
    }
}
