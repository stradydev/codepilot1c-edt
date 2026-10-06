/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationContextProvider;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationSettings;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferences;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.Section;
import com.codepilot1c.core.edt.runtime.lease.InfobaseLease;
import com.codepilot1c.core.edt.runtime.lease.InfobaseLeaseGuard;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Per-branch infobase association surgery WITHOUT a checkout — backing service of the
 * {@code manage_associations} tool.
 *
 * <p>Grounded against EDT 2025.2.x: the git context provider builds the association context as
 * {@code InfobaseAssociationContext.of(repository.getFullBranch())} — a single segment holding
 * the full ref ({@code refs/heads/<branch>}), and {@code of(String)} is public, so the exact
 * context of ANY branch is synthesizable byte-for-byte. The association manager API is fully
 * context-parameterized ({@code getAssociationContexts} / {@code getAssociation} /
 * {@code associate} / {@code dissociate} / {@code setDefaultInfobase}).</p>
 *
 * <p>Deliberate division of labor: this service NEVER creates or repairs {@code ibases.v8i}
 * registry rows — registry lifecycle belongs to {@code connect_infobase}. Here only EXISTING
 * registry rows are attached to / detached from branch contexts.</p>
 */
public class EdtInfobaseAssociationService {

    private static final VibeLogger.CategoryLogger LOG =
            VibeLogger.forClass(EdtInfobaseAssociationService.class);

    private static final String BRANCH_REF_PREFIX = "refs/heads/"; //$NON-NLS-1$

    private final EdtRuntimeGateway gateway;
    private final InfobaseLeaseGuard leaseGuard;

    public EdtInfobaseAssociationService() {
        this(new EdtRuntimeGateway());
    }

    public EdtInfobaseAssociationService(EdtRuntimeGateway gateway) {
        this(gateway, InfobaseLeaseGuard.fromEnvironment());
    }

    public EdtInfobaseAssociationService(EdtRuntimeGateway gateway, InfobaseLeaseGuard leaseGuard) {
        this.gateway = gateway;
        this.leaseGuard = leaseGuard;
    }

    public record InfobaseView(String name, String uuid, String connection, boolean isDefault) {
    }

    public record ContextView(String context, String branch, boolean current, List<InfobaseView> infobases) {
    }

    public record BindOutcome(String context, String infobaseName, String uuid, boolean setDefault) {
    }

    public record CopyOutcome(String fromContext, String toContext, List<String> copied, String defaultName) {
    }

    /**
     * Result of a dissociate call.
     *
     * @param context          the branch context the infobases were detached from
     * @param removed          names detached from that context
     * @param registryRestored names whose FLAT REGISTRY row vanished across the platform
     *                         {@code dissociate} call and was put back by the guard in
     *                         {@link #dissociate} — normally empty (see that method's contract)
     * @param registryLost     names whose registry row vanished AND could not be re-registered:
     *                         the registry is now incomplete and a later {@code bind} of that name
     *                         will fail
     */
    public record DissociateOutcome(String context, List<String> removed,
            List<String> registryRestored, List<String> registryLost) {

        public DissociateOutcome(String context, List<String> removed) {
            this(context, removed, List.of(), List.of());
        }

        /** True when the registry came out of the call exactly as it went in. */
        public boolean registryIntact() {
            return registryRestored.isEmpty() && registryLost.isEmpty();
        }
    }

    /** Flat-registry row of a dissociate target, captured BEFORE the platform call. */
    private record RegistrySnapshot(IInfobaseManager manager, InfobaseReference row, String name,
            String identity, UUID uuid, String folder) {
    }

    /** Verdict of the post-dissociate registry check. */
    private enum RegistryGuard {
        /** The row is still registered (the normal outcome) — or there was nothing to guard. */
        INTACT,
        /** The row had vanished and was successfully re-registered. */
        RESTORED,
        /** The row had vanished and could NOT be re-registered — the registry is incomplete. */
        LOST
    }

    // -- list -------------------------------------------------------------------------------

    /**
     * All persisted association contexts of the project (plus the CURRENT provider context when
     * it has nothing persisted yet), each with its bound infobases and default marker.
     */
    public List<ContextView> listContexts(String projectName) {
        IProject project = resolveProject(projectName);
        IInfobaseAssociationManager manager = associationManager();
        Collection<InfobaseAssociationContext> contexts;
        try {
            contexts = manager.getAssociationContexts(project);
        } catch (RuntimeException e) {
            throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Failed to enumerate association contexts: " + detail(e), e); //$NON-NLS-1$
        }
        InfobaseAssociationContext current = currentContext(project);
        List<ContextView> views = new ArrayList<>();
        boolean currentSeen = false;
        if (contexts != null) {
            for (InfobaseAssociationContext ctx : contexts) {
                if (ctx == null) {
                    continue;
                }
                boolean isCurrent = current != null && current.equals(ctx);
                currentSeen |= isCurrent;
                views.add(view(project, manager, ctx, isCurrent));
            }
        }
        if (current != null && !currentSeen) {
            // Fresh branch: the provider already reports it, but nothing is persisted yet.
            views.add(view(project, manager, current, true));
        }
        return views;
    }

    private ContextView view(IProject project, IInfobaseAssociationManager manager,
            InfobaseAssociationContext ctx, boolean isCurrent) {
        String value = contextValue(ctx);
        List<InfobaseView> infobases = new ArrayList<>();
        try {
            Optional<IInfobaseAssociation> assoc = manager.getAssociation(project, ctx);
            if (assoc.isPresent()) {
                InfobaseReference defaultRef = assoc.get().getDefaultInfobase();
                String defaultIdentity = InfobaseIdentity.identityOf(defaultRef);
                Collection<InfobaseReference> refs = assoc.get().getInfobases();
                if (refs != null) {
                    for (InfobaseReference ref : refs) {
                        if (ref == null) {
                            continue;
                        }
                        String identity = InfobaseIdentity.identityOf(ref);
                        boolean isDefault = defaultRef != null
                                && (Objects.equals(defaultRef.getUuid(), ref.getUuid())
                                        || InfobaseIdentity.matches(identity, defaultIdentity));
                        infobases.add(new InfobaseView(ref.getName(),
                                ref.getUuid() == null ? null : ref.getUuid().toString(),
                                identity, isDefault));
                    }
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("manage_associations: failed to read association for context '%s': %s", //$NON-NLS-1$
                    value, e.getMessage());
        }
        return new ContextView(value, InfobaseLeaseGuard.branchFromContext(value), isCurrent, infobases);
    }

    // -- bind -------------------------------------------------------------------------------

    /**
     * Attaches an EXISTING registry infobase (by name and/or path) to the context of
     * {@code branch} — no checkout needed. Registry rows are never created here.
     */
    public BindOutcome bind(String projectName, String branch, String infobaseName,
            String databasePath, boolean setDefault) {
        IProject project = resolveProject(projectName);
        InfobaseAssociationContext ctx = contextForBranch(branch);
        InfobaseReference row = resolveRegistryRow(infobaseName, databasePath);
        enforceLease(shortBranch(branch), row);
        IInfobaseAssociationManager manager = associationManager();
        InfobaseAssociationSettings settings = new InfobaseAssociationSettings(false, ctx);
        try {
            manager.associate(project, row, settings);
        } catch (RuntimeException e) {
            // "already associated" is an idempotent re-bind ONLY when this project holds it.
            if (!isAlreadyConnected(e)) {
                throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                        "Failed to associate infobase with branch context: " + detail(e), e); //$NON-NLS-1$
            }
            requireHeldByThisProject(project, manager, ctx, row, e);
            LOG.info("manage_associations bind: already associated — proceeding (%s)", e.getMessage()); //$NON-NLS-1$
        }
        InfobaseReference effective = row;
        if (setDefault) {
            effective = setDefaultWithAdoption(project, manager, ctx, row, settings);
        }
        return new BindOutcome(contextValue(ctx), effective.getName(),
                effective.getUuid() == null ? null : effective.getUuid().toString(), setDefault);
    }

    // -- copy -------------------------------------------------------------------------------

    /** Copies the association set (and optionally the default) from one branch context to another. */
    public CopyOutcome copy(String projectName, String fromBranch, String toBranch, boolean setDefault) {
        IProject project = resolveProject(projectName);
        InfobaseAssociationContext fromCtx = contextForBranch(fromBranch);
        InfobaseAssociationContext toCtx = contextForBranch(toBranch);
        if (contextValue(fromCtx).equals(contextValue(toCtx))) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "from_branch and to_branch are the same context"); //$NON-NLS-1$
        }
        IInfobaseAssociationManager manager = associationManager();
        IInfobaseAssociation source = associationOrThrow(project, manager, fromCtx, fromBranch);
        Collection<InfobaseReference> refs = source.getInfobases();
        if (refs == null || refs.isEmpty()) {
            throw new EdtToolException(EdtToolErrorCode.INFOBASE_ASSOCIATION_NOT_FOUND,
                    "Branch '" + fromBranch + "' has no bound infobases to copy"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        InfobaseReference defaultRef = source.getDefaultInfobase();
        enforceLease(shortBranch(toBranch), defaultRef != null ? defaultRef : refs.iterator().next());
        InfobaseAssociationSettings settings = new InfobaseAssociationSettings(false, toCtx);
        List<String> copied = new ArrayList<>();
        for (InfobaseReference ref : refs) {
            if (ref == null) {
                continue;
            }
            try {
                manager.associate(project, ref, settings);
            } catch (RuntimeException e) {
                if (!isAlreadyConnected(e)) {
                    throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                            "Failed to copy association of '" + ref.getName() + "': " + detail(e), e); //$NON-NLS-1$ //$NON-NLS-2$
                }
                requireHeldByThisProject(project, manager, toCtx, ref, e);
            }
            copied.add(ref.getName());
        }
        String defaultName = null;
        if (setDefault && defaultRef != null) {
            defaultName = setDefaultWithAdoption(project, manager, toCtx, defaultRef, settings).getName();
        }
        return new CopyOutcome(contextValue(fromCtx), contextValue(toCtx), copied, defaultName);
    }

    // -- dissociate -------------------------------------------------------------------------

    /**
     * Detaches infobase(s) from the branch context: the named one, or ALL bound ones when
     * {@code infobaseName} is null. Registry rows and lease files are left untouched — this is the
     * exact inverse of {@link #bind}, so the same entry can be reattached afterwards.
     *
     * <p>Registry survival is <em>enforced</em>, not assumed: each target's flat-registry row is
     * snapshotted before the platform call and re-registered (same row, same v8i folder) if it is
     * gone afterwards. On 2025.2.x the platform {@code dissociate} provably cannot drop it —
     * bytecode-verified on {@code InfobaseAssociationManager}: it re-serializes the association's
     * {@code Infobases}/{@code DefaultInfobase} properties into the per-project settings store and
     * fires the dissociation event, with no {@code IInfobaseManager} write on that path or in any
     * registered listener. The guard exists because the recovery cost is wildly asymmetric: a
     * {@code kind=server} row cannot be re-created without the infobase's server credentials, which
     * the caller who dissociated it generally does not hold (feedback 2026-09-14). A failed
     * restore is reported, never swallowed.</p>
     */
    public DissociateOutcome dissociate(String projectName, String branch, String infobaseName) {
        IProject project = resolveProject(projectName);
        InfobaseAssociationContext ctx = contextForBranch(branch);
        IInfobaseAssociationManager manager = associationManager();
        IInfobaseAssociation assoc = associationOrThrow(project, manager, ctx, branch);
        Collection<InfobaseReference> refs = assoc.getInfobases();
        List<InfobaseReference> targets = new ArrayList<>();
        if (refs != null) {
            for (InfobaseReference ref : refs) {
                if (ref != null && (infobaseName == null || infobaseName.equals(ref.getName()))) {
                    targets.add(ref);
                }
            }
        }
        if (targets.isEmpty()) {
            throw new EdtToolException(EdtToolErrorCode.INFOBASE_NOT_FOUND,
                    infobaseName == null
                            ? "Branch '" + branch + "' has no bound infobases" //$NON-NLS-1$ //$NON-NLS-2$
                            : "Infobase '" + infobaseName + "' is not bound under branch '" + branch + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        List<String> removed = new ArrayList<>();
        List<String> registryRestored = new ArrayList<>();
        List<String> registryLost = new ArrayList<>();
        for (InfobaseReference ref : targets) {
            RegistrySnapshot snapshot = snapshotRegistryRow(ref);
            try {
                manager.dissociate(project, ref, ctx);
                removed.add(ref.getName());
            } catch (RuntimeException e) {
                throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                        "Failed to dissociate '" + ref.getName() + "': " + detail(e), e); //$NON-NLS-1$ //$NON-NLS-2$
            }
            switch (ensureRegistryRowSurvived(snapshot)) {
                case RESTORED -> registryRestored.add(snapshot.name());
                case LOST -> registryLost.add(snapshot.name());
                default -> { /* INTACT — the normal path */ }
            }
        }
        return new DissociateOutcome(contextValue(ctx), removed, registryRestored, registryLost);
    }

    // -- registry guard ---------------------------------------------------------------------

    /**
     * Captures the flat-registry row backing {@code ref} so it can be put back should the platform
     * call drop it. Returns {@code null} when there is nothing to guard (registry unreadable, or
     * the reference has no registry row at all) — in that case the guard stays out of the way.
     */
    private RegistrySnapshot snapshotRegistryRow(InfobaseReference ref) {
        IInfobaseManager manager;
        try {
            manager = gateway.getInfobaseManager();
        } catch (RuntimeException e) {
            LOG.warn("manage_associations dissociate: registry unreadable, skipping the " //$NON-NLS-1$
                    + "registry-survival guard: %s", detail(e)); //$NON-NLS-1$
            return null;
        }
        if (manager == null) {
            return null;
        }
        try {
            InfobaseReference row = findRegistryRow(manager, InfobaseIdentity.identityOf(ref),
                    ref.getUuid(), ref.getName());
            if (row == null) {
                return null;
            }
            return new RegistrySnapshot(manager, row, row.getName(),
                    InfobaseIdentity.identityOf(row), row.getUuid(), row.getFolder());
        } catch (RuntimeException e) {
            LOG.warn("manage_associations dissociate: failed to snapshot the registry row of " //$NON-NLS-1$
                    + "'%s': %s", ref.getName(), detail(e)); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Verifies the snapshotted row is still registered and re-registers it (into the same v8i
     * folder) when it is not. {@code add(section, folder)} is EDT's own registration entry point —
     * the very one {@code connect_infobase} uses — and needs no credentials: the row object carries
     * everything it had (connection string, UUID, name), so a {@code kind=server} entry is restored
     * without its server login.
     */
    private RegistryGuard ensureRegistryRowSurvived(RegistrySnapshot snapshot) {
        if (snapshot == null || registryContains(snapshot)) {
            return RegistryGuard.INTACT;
        }
        LOG.warn("manage_associations dissociate: the flat registry row of '%s' disappeared across " //$NON-NLS-1$
                + "the platform call — re-registering it (folder=%s)", //$NON-NLS-1$
                snapshot.name(), snapshot.folder());
        try {
            String folder = snapshot.folder();
            snapshot.manager().add(snapshot.row(),
                    folder == null || folder.isBlank() ? null : folder);
        } catch (Exception | LinkageError e) {
            LOG.error(String.format("manage_associations dissociate: FAILED to re-register the " //$NON-NLS-1$
                    + "registry row of '%s' — the infobase registry is now incomplete", //$NON-NLS-1$
                    snapshot.name()), e);
            return RegistryGuard.LOST;
        }
        if (!registryContains(snapshot)) {
            LOG.error(String.format("manage_associations dissociate: re-registration of '%s' " //$NON-NLS-1$
                    + "reported success but the row is still not in the registry", snapshot.name())); //$NON-NLS-1$
            return RegistryGuard.LOST;
        }
        return RegistryGuard.RESTORED;
    }

    /** True when the snapshotted row is (still) present in the flat registry. */
    private static boolean registryContains(RegistrySnapshot snapshot) {
        try {
            return findRegistryRow(snapshot.manager(), snapshot.identity(), snapshot.uuid(),
                    snapshot.name()) != null;
        } catch (RuntimeException e) {
            // An unreadable registry is not evidence of a dropped row — do not "restore" blindly.
            LOG.warn("manage_associations dissociate: registry read-back failed for '%s': %s", //$NON-NLS-1$
                    snapshot.name(), detail(e));
            return true;
        }
    }

    /** The registry row matching a UUID / connection identity / name, or {@code null}. */
    private static InfobaseReference findRegistryRow(IInfobaseManager manager, String identity,
            UUID uuid, String name) {
        for (InfobaseReference row : registryRows(manager)) {
            if (uuid != null && uuid.equals(row.getUuid())) {
                return row;
            }
            String rowIdentity = InfobaseIdentity.identityOf(row);
            if (identity != null && rowIdentity != null) {
                if (InfobaseIdentity.matches(identity, rowIdentity)) {
                    return row;
                }
                continue;
            }
            if (name != null && name.equals(row.getName())) {
                return row;
            }
        }
        return null;
    }

    /**
     * Every infobase row of the flat registry, INCLUDING rows nested in {@code ibases.v8i} folders.
     *
     * <p>{@code IInfobaseManager.getAll()} returns only the registry resource's TOP-LEVEL sections
     * (bytecode-verified on services.core 21.0.0 / 2025.2.x: it filters
     * {@code getInfobasesResource().getContents()} to {@code Section} and never descends). A row
     * carrying {@code Folder=/Something} lives inside a {@link com._1c.g5.v8.dt.platform.services.model.Group}
     * section and is therefore ABSENT from that list — which is why EDT's own
     * {@code findInfobaseByName} / {@code findInfobaseByUuid} always pipe {@code getAll()} through
     * {@code InfobaseReferences.asPlainList} first. Sweeping the raw list instead is what made
     * {@code bind} answer INFOBASE_NOT_FOUND for a foldered server infobase that was sitting in the
     * registry the whole time (feedback 2026-09-14).</p>
     */
    private static List<InfobaseReference> registryRows(IInfobaseManager manager) {
        Collection<Section> top = manager.getAll();
        return top == null ? List.of() : InfobaseReferences.asPlainList(top);
    }

    // -- plumbing ---------------------------------------------------------------------------

    /**
     * Synthesizes the association context of a branch exactly the way EDT's git provider does:
     * a single-segment context holding the full ref. Accepts either a short name ({@code task-C})
     * or a full ref ({@code refs/heads/task-C}).
     */
    public static InfobaseAssociationContext contextForBranch(String branch) {
        if (branch == null || branch.isBlank()) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT, "branch is required"); //$NON-NLS-1$
        }
        return InfobaseAssociationContext.of(BRANCH_REF_PREFIX + shortBranch(branch));
    }

    /** Short branch name: strips a {@code refs/heads/} prefix if the caller passed a full ref. */
    public static String shortBranch(String branch) {
        String trimmed = branch == null ? "" : branch.trim(); //$NON-NLS-1$
        if (trimmed.toLowerCase(Locale.ROOT).startsWith(BRANCH_REF_PREFIX)) {
            trimmed = trimmed.substring(BRANCH_REF_PREFIX.length());
        }
        if (trimmed.isBlank()) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT, "branch is required"); //$NON-NLS-1$
        }
        return trimmed;
    }

    static String contextValue(InfobaseAssociationContext ctx) {
        return ctx == null ? "" : ctx.getContext().orElse(""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Finds the EXISTING registry row by display name and/or file path (both given — both must
     * match). Never creates rows: an unknown infobase is a typed error pointing at
     * {@code connect_infobase}, whose job registry lifecycle is.
     *
     * <p>Sweeps the FLATTENED registry ({@link #registryRows}) so rows nested in {@code ibases.v8i}
     * folders are found too — the raw {@code getAll()} list holds only top-level sections.</p>
     */
    protected InfobaseReference resolveRegistryRow(String infobaseName, String databasePath) {
        if ((infobaseName == null || infobaseName.isBlank())
                && (databasePath == null || databasePath.isBlank())) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "infobase_name or database_path is required"); //$NON-NLS-1$
        }
        IInfobaseManager manager;
        try {
            manager = gateway.getInfobaseManager();
        } catch (IllegalStateException e) {
            throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "IInfobaseManager service unavailable — EDT may not be fully initialized", e); //$NON-NLS-1$
        }
        String pathIdentity = null;
        if (databasePath != null && !databasePath.isBlank()) {
            pathIdentity = InfobaseIdentity.identityOf(
                    InfobaseReferences.newFileInfobaseReference(databasePath.trim()));
        }
        List<InfobaseReference> matches = new ArrayList<>();
        try {
            for (InfobaseReference row : registryRows(manager)) {
                if (infobaseName != null && !infobaseName.isBlank()
                        && !infobaseName.equals(row.getName())) {
                    continue;
                }
                if (pathIdentity != null
                        && !InfobaseIdentity.matches(pathIdentity, InfobaseIdentity.identityOf(row))) {
                    continue;
                }
                matches.add(row);
            }
        } catch (RuntimeException e) {
            throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Failed to read the infobase registry: " + detail(e), e); //$NON-NLS-1$
        }
        if (matches.isEmpty()) {
            throw new EdtToolException(EdtToolErrorCode.INFOBASE_NOT_FOUND,
                    "No registered infobase matches " //$NON-NLS-1$
                            + (infobaseName == null ? "" : "name '" + infobaseName + "' ") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            + (databasePath == null ? "" : "path '" + databasePath + "'") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            + ". manage_associations only attaches EXISTING registry entries " //$NON-NLS-1$
                            + "(folders included). Re-registering one needs connect_infobase — " //$NON-NLS-1$
                            + "credential-free for kind=file, but a kind=server entry (Srvr/Ref) " //$NON-NLS-1$
                            + "needs the infobase's server login, so its owner must do it."); //$NON-NLS-1$
        }
        if (matches.size() > 1) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "Ambiguous infobase: " + matches.size() //$NON-NLS-1$
                            + " registry entries match — pass both infobase_name and database_path"); //$NON-NLS-1$
        }
        return matches.get(0);
    }

    /**
     * Sets the context's default infobase robustly. Two live findings (polygon scenario 4,
     * 2026-07-02) make the naive {@code setDefaultInfobase(project, row, ctx)} fail:
     * <ul>
     *   <li>EDT resolves the reference against the association's OWN entry — whose name/UUID may
     *       differ from the registry row (e.g. a legacy {@code ID=null} v8i row: associate()
     *       persisted the entry under a freshly assigned UUID while the row still has none). So
     *       the association's matching entry is adopted (canonical identity compare) and THAT
     *       object is passed to EDT — the ctx-parameterized twin of
     *       {@code EdtInfobaseConnectService.adoptExistingAssociationName}.</li>
     *   <li>The very first write into a fresh context may not be visible to the immediate
     *       read-back (same race the connect arc retries) — settle briefly, re-adopt,
     *       re-associate (tolerating "already connected") and retry once.</li>
     * </ul>
     * Returns the reference that was actually made default.
     */
    private InfobaseReference setDefaultWithAdoption(IProject project,
            IInfobaseAssociationManager manager, InfobaseAssociationContext ctx,
            InfobaseReference row, InfobaseAssociationSettings settings) {
        InfobaseReference target = adoptFromAssociation(project, manager, ctx, row);
        if (trySetDefault(project, manager, ctx, target)) {
            return target;
        }
        // The very first write into a fresh context may not be visible to the immediate
        // read-back (same race the connect arc retries) — settle, re-adopt, re-associate
        // (tolerating "already connected") and retry once.
        LOG.warn("manage_associations: setDefaultInfobase failed right after associate " //$NON-NLS-1$
                + "(context=%s) — retrying once", contextValue(ctx)); //$NON-NLS-1$
        settleBeforeSetDefaultRetry();
        target = adoptFromAssociation(project, manager, ctx, row);
        try {
            manager.associate(project, target, settings);
        } catch (RuntimeException e) {
            // Expected when the first associate landed: "Infobase ... is already connected".
            LOG.info("manage_associations: retry re-associate reported '%s' — proceeding", //$NON-NLS-1$
                    e.getMessage());
        }
        target = adoptFromAssociation(project, manager, ctx, target);
        if (trySetDefault(project, manager, ctx, target)) {
            return target;
        }
        throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                "Associated, but failed to set default infobase for context " //$NON-NLS-1$
                        + contextValue(ctx) + " (see log for the underlying EDT errors)"); //$NON-NLS-1$
    }

    /**
     * One set-default attempt: the official API first, then the non-current-context workaround.
     *
     * <p>EDT quirk (bytecode-verified on services.core 21.0.0 / 2025.2.x):
     * {@code setDefaultInfobase(project, ref, ctx)} validates membership against the no-arg
     * {@code getAssociation(project)} — the CURRENT provider context — and uses the {@code ctx}
     * argument ONLY for the final {@code storeProperty("DefaultInfobase", uuid, ctx)} write. For
     * any non-current target context the validation can never pass, no matter what reference is
     * passed. When the TARGET context's association does contain the reference (verified via the
     * ctx-parameterized read), the broken validation is bypassed and the DefaultInfobase property
     * is written exactly the way the official method would — via the manager's own (private)
     * {@code storeProperty}, reflectively.</p>
     */
    private boolean trySetDefault(IProject project, IInfobaseAssociationManager manager,
            InfobaseAssociationContext ctx, InfobaseReference target) {
        try {
            manager.setDefaultInfobase(project, target, ctx);
            return true;
        } catch (RuntimeException official) {
            LOG.info("manage_associations: official setDefaultInfobase refused (context=%s): %s", //$NON-NLS-1$
                    contextValue(ctx), official.getMessage());
        }
        if (target.getUuid() == null || !associationContains(project, manager, ctx, target)) {
            return false;
        }
        try {
            java.lang.reflect.Method storeProperty = manager.getClass().getDeclaredMethod(
                    "storeProperty", IProject.class, String.class, String.class, //$NON-NLS-1$
                    InfobaseAssociationContext.class);
            storeProperty.setAccessible(true);
            storeProperty.invoke(manager, project, "DefaultInfobase", //$NON-NLS-1$
                    target.getUuid().toString(), ctx);
            LOG.info("manage_associations: DefaultInfobase for context %s written via the " //$NON-NLS-1$
                    + "storeProperty workaround (official API validates against the CURRENT " //$NON-NLS-1$
                    + "context only)", contextValue(ctx)); //$NON-NLS-1$
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite
                    ? ite.getCause() : e;
            LOG.warn("manage_associations: storeProperty workaround failed (context=%s): %s", //$NON-NLS-1$
                    contextValue(ctx), detail(cause));
            return false;
        }
    }

    /** True when the context's association (ctx-parameterized read) contains the reference. */
    private boolean associationContains(IProject project, IInfobaseAssociationManager manager,
            InfobaseAssociationContext ctx, InfobaseReference reference) {
        String identity = InfobaseIdentity.identityOf(reference);
        if (identity == null) {
            return false;
        }
        try {
            Optional<IInfobaseAssociation> assoc = manager.getAssociation(project, ctx);
            if (assoc.isPresent() && assoc.get().getInfobases() != null) {
                for (InfobaseReference candidate : assoc.get().getInfobases()) {
                    if (candidate != null
                            && InfobaseIdentity.matches(identity, InfobaseIdentity.identityOf(candidate))) {
                        return true;
                    }
                }
            }
        } catch (RuntimeException e) {
            return false;
        }
        return false;
    }

    /**
     * Returns the context association's own entry matching the reference's canonical connection
     * identity, or the reference itself when the association has no matching entry (or cannot be
     * read). EDT's {@code setDefaultInfobase} matches against the association entry, not the
     * registry row.
     */
    private InfobaseReference adoptFromAssociation(IProject project,
            IInfobaseAssociationManager manager, InfobaseAssociationContext ctx,
            InfobaseReference reference) {
        String identity = InfobaseIdentity.identityOf(reference);
        if (identity == null) {
            return reference;
        }
        try {
            Optional<IInfobaseAssociation> assoc = manager.getAssociation(project, ctx);
            if (assoc.isPresent() && assoc.get().getInfobases() != null) {
                for (InfobaseReference candidate : assoc.get().getInfobases()) {
                    if (candidate != null
                            && InfobaseIdentity.matches(identity, InfobaseIdentity.identityOf(candidate))) {
                        return candidate;
                    }
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("manage_associations: association read-back failed (context=%s): %s", //$NON-NLS-1$
                    contextValue(ctx), e.getMessage());
        }
        return reference;
    }

    /** Grace pause before the setDefault retry; overridable so unit tests do not pay it. */
    protected void settleBeforeSetDefaultRetry() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private IInfobaseAssociation associationOrThrow(IProject project,
            IInfobaseAssociationManager manager, InfobaseAssociationContext ctx, String branch) {
        Optional<IInfobaseAssociation> assoc;
        try {
            assoc = manager.getAssociation(project, ctx);
        } catch (RuntimeException e) {
            throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Failed to read association of branch '" + branch + "': " + detail(e), e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (assoc.isEmpty()) {
            throw new EdtToolException(EdtToolErrorCode.INFOBASE_ASSOCIATION_NOT_FOUND,
                    "Branch '" + branch + "' has no infobase association"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return assoc.get();
    }

    private void enforceLease(String branch, InfobaseReference reference) {
        if (leaseGuard == null || !leaseGuard.isEnabled()) {
            return;
        }
        String identity = InfobaseIdentity.identityOf(reference);
        InfobaseLeaseGuard.Decision decision = leaseGuard.checkOrAcquire(branch, identity, identity, null);
        if (decision.outcome() != InfobaseLeaseGuard.Outcome.DENIED) {
            return;
        }
        InfobaseLease holder = decision.lease();
        throw new EdtToolException(EdtToolErrorCode.EDT_LEASE_HELD,
                "lease_held: branch '" + branch + "' is leased by " //$NON-NLS-1$ //$NON-NLS-2$
                        + (holder == null ? "another stack" : holder.describeHolder()) //$NON-NLS-1$
                        + ". Release it on the holding stack (manage_leases action=release) " //$NON-NLS-1$
                        + "or steal a stale one (manage_leases action=take force=true), then retry."); //$NON-NLS-1$
    }

    private IInfobaseAssociationManager associationManager() {
        try {
            return gateway.getInfobaseAssociationManager();
        } catch (IllegalStateException e) {
            throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "IInfobaseAssociationManager service unavailable — EDT may not be fully initialized", e); //$NON-NLS-1$
        }
    }

    private InfobaseAssociationContext currentContext(IProject project) {
        try {
            IInfobaseAssociationContextProvider provider = gateway.peekInfobaseAssociationContextProvider();
            if (provider == null) {
                return null;
            }
            return provider.get(project);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Visible for testing (headless JUnit has no workspace to resolve against). */
    protected IProject resolveProject(String projectName) {
        if (projectName == null || projectName.isBlank()) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT, "project_name is required"); //$NON-NLS-1$
        }
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace() == null
                ? null : ResourcesPlugin.getWorkspace().getRoot();
        IProject project = root == null ? null : root.getProject(projectName);
        if (project == null || !project.exists()) {
            throw new EdtToolException(EdtToolErrorCode.PROJECT_NOT_FOUND,
                    "EDT project not found: " + projectName); //$NON-NLS-1$
        }
        return project;
    }

    /**
     * Turns a swallowed "already associated" refusal into a hard error unless THIS project's
     * association for {@code ctx} really holds the infobase.
     *
     * <p>EDT 2025.2.x {@code associate()} (bytecode-verified) first looks the infobase up across
     * ALL workspace projects in the same context ({@code getAssociation(ref, ctx)}) and, when a
     * different project holds it and the settings are not forced, throws
     * "Infobase {0} is already associated with project {1}" — writing nothing. Treating that as an
     * idempotent re-bind reported {@code success:true} for a bind that never landed, and the
     * follow-up set-default then failed because the context did not contain the infobase
     * (feedback 2026-09-24: server IB held by {@code Accounting management} on the same branch).
     * Forcing is not offered here: EDT's forced path silently dissociates the other project.</p>
     */
    private void requireHeldByThisProject(IProject project, IInfobaseAssociationManager manager,
            InfobaseAssociationContext ctx, InfobaseReference row, RuntimeException refusal) {
        if (associationContains(project, manager, ctx, row)) {
            return;
        }
        String holder = null;
        try {
            Optional<IInfobaseAssociation> other = manager.getAssociation(row, ctx);
            if (other.isPresent() && other.get().getProject() != null) {
                holder = other.get().getProject().getName();
            }
        } catch (RuntimeException e) {
            LOG.warn("manage_associations: holder lookup failed (context=%s): %s", //$NON-NLS-1$
                    contextValue(ctx), detail(e));
        }
        String holderText = holder == null ? "another project" : "project '" + holder + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        throw new EdtToolException(EdtToolErrorCode.INFOBASE_BOUND_TO_OTHER_PROJECT,
                "Infobase '" + row.getName() + "' is already associated with " + holderText //$NON-NLS-1$ //$NON-NLS-2$
                        + " under context " + contextValue(ctx) //$NON-NLS-1$
                        + " — EDT allows one project per infobase per branch context, so nothing was " //$NON-NLS-1$
                        + "written. Dissociate it from that project first (manage_associations " //$NON-NLS-1$
                        + "action=dissociate), or bind a different infobase. EDT: " + detail(refusal), //$NON-NLS-1$
                refusal);
    }

    private static boolean isAlreadyConnected(RuntimeException e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("already connected") || lower.contains("already associated"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String detail(Throwable t) {
        if (t == null) {
            return ""; //$NON-NLS-1$
        }
        String message = t.getMessage();
        return message != null && !message.isBlank() ? message : t.getClass().getSimpleName();
    }
}
