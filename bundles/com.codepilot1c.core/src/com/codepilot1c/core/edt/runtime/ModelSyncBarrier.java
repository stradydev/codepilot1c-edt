package com.codepilot1c.core.edt.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.core.logging.VibeLogger;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Brings the EDT model in line with the files on disk before an infobase update.
 *
 * <p>Feedback {@code 2026-10-05-update-infobase-applies-previous-state.md}: an agent writes a
 * {@code .bsl} file directly on disk (not through an EDT editor), immediately calls
 * {@code update_infobase}, gets {@code updated:true, schema_applied:true} — and the infobase receives
 * the PRE-edit module text; a second update with no further edits applies the change. EDT exports
 * the infobase from its BM model, and an external write reaches that model only after the workspace
 * notices the file (a resource refresh producing a delta) and the BM resource-sync has processed it.
 * Nothing in the update path forced either step, so the export could run on the previous state.</p>
 *
 * <p>The barrier therefore: (1) {@code refreshLocal(DEPTH_INFINITE)} on the target project and the
 * projects whose model the export depends on or that share the push (its base configuration when the
 * target is an extension, and its own extensions), then (2) waits for
 * {@link IBmModelManager#waitModelSynchronization(IProject)} on each of them. EDT's wait is
 * unbounded, and a refresh can block on a workspace lock, so the whole sequence runs on a daemon
 * thread and is bounded by a timeout. A timeout or a refresh failure never fails the update — it is
 * reported in the payload ({@code model_sync}, {@code model_sync_warning}) so the caller does not
 * trust {@code updated:true} for a state that may be stale.</p>
 */
public class ModelSyncBarrier {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(ModelSyncBarrier.class);

    /**
     * Default bound on refresh + BM sync (ms). Same {@code Long.getLong} override convention as
     * {@code codepilot1c.edt.export.wait.ms} in the metadata service.
     */
    public static final long DEFAULT_TIMEOUT_MS =
            Long.getLong("codepilot1c.edt.update.modelsync.wait.ms", 60_000L); //$NON-NLS-1$

    /** Outcome status values written to {@code model_sync.status}. */
    public static final String STATUS_SYNCED = "synced"; //$NON-NLS-1$
    public static final String STATUS_PARTIAL = "partial"; //$NON-NLS-1$
    public static final String STATUS_TIMED_OUT = "timed_out"; //$NON-NLS-1$
    public static final String STATUS_SKIPPED = "skipped"; //$NON-NLS-1$

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "codepilot1c-model-sync-barrier"); //$NON-NLS-1$
        thread.setDaemon(true);
        return thread;
    });

    /** The EDT/workspace side effects, separated so the orchestration is unit-testable. */
    public interface Operations {

        /** Projects to refresh + wait for, target first. Empty when nothing resolves. */
        List<String> scope(String projectName);

        /** {@code refreshLocal(DEPTH_INFINITE)} of one project. */
        void refresh(String projectName) throws Exception;

        /** Blocks until EDT's BM model of the project has processed pending resource changes. */
        void waitModelSynchronization(String projectName) throws Exception;
    }

    /**
     * What the barrier achieved: the projects it covered, the ones whose refresh or wait failed (with
     * reason), whether the bound elapsed and in which stage, and how long it took.
     */
    public record Outcome(String status, List<String> projects, List<String> failures, String stalledAt,
            long elapsedMs, long timeoutMs) {

        public Outcome {
            projects = projects == null ? List.of() : List.copyOf(projects);
            failures = failures == null ? List.of() : List.copyOf(failures);
        }

        /** True only when every project in scope was refreshed and its model sync completed in time. */
        public boolean synced() {
            return STATUS_SYNCED.equals(status);
        }
    }

    private final Operations operations;
    private final long timeoutMs;

    public ModelSyncBarrier() {
        this(new EdtOperations(), DEFAULT_TIMEOUT_MS);
    }

    public ModelSyncBarrier(Operations operations, long timeoutMs) {
        this.operations = operations;
        this.timeoutMs = timeoutMs;
    }

    /** {@link #awaitModelInSync(String, long)} with this barrier's configured bound. */
    public Outcome awaitModelInSync(String projectName) {
        return awaitModelInSync(projectName, timeoutMs);
    }

    /**
     * Refreshes the scope and waits for the BM model to catch up, bounded by {@code timeoutMs}. Never
     * throws; every failure mode becomes a non-{@link #STATUS_SYNCED} outcome.
     */
    public Outcome awaitModelInSync(String projectName, long timeoutMs) {
        long started = System.nanoTime();
        List<String> scope;
        try {
            scope = operations.scope(projectName);
        } catch (RuntimeException | LinkageError e) {
            // LinkageError: no EDT/Eclipse runtime (headless unit tests).
            LOG.debug("model-sync barrier: scope unresolved for %s: %s", projectName, detail(e)); //$NON-NLS-1$
            return new Outcome(STATUS_SKIPPED, List.of(), List.of("scope: " + detail(e)), null, //$NON-NLS-1$
                    elapsedMs(started), timeoutMs);
        }
        if (scope == null || scope.isEmpty()) {
            return new Outcome(STATUS_SKIPPED, List.of(), List.of(), null, elapsedMs(started), timeoutMs);
        }
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<String> stage = new AtomicReference<>();
        CompletableFuture<Void> work = CompletableFuture.runAsync(() -> {
            // Refresh everything first so all deltas are queued, then wait — the waits then overlap
            // with BM processing of the other projects instead of serializing refresh/wait pairs.
            for (String project : scope) {
                stage.set("refresh:" + project); //$NON-NLS-1$
                try {
                    operations.refresh(project);
                } catch (Exception | LinkageError e) {
                    failures.add("refresh " + project + ": " + detail(e)); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            for (String project : scope) {
                stage.set("model_sync:" + project); //$NON-NLS-1$
                try {
                    operations.waitModelSynchronization(project);
                } catch (Exception | LinkageError e) {
                    failures.add("model_sync " + project + ": " + detail(e)); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            stage.set(null);
        }, EXECUTOR);
        try {
            work.get(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // The worker keeps waiting on its daemon thread; EDT's wait has no cancel handle and is
            // harmless to leave running. The caller proceeds, but is told the model may be behind.
            String stalledAt = stage.get();
            LOG.warn("model-sync barrier: timed out after %dms for %s (stalled at %s)", //$NON-NLS-1$
                    Long.valueOf(timeoutMs), projectName, stalledAt);
            return new Outcome(STATUS_TIMED_OUT, scope, snapshot(failures), stalledAt, elapsedMs(started),
                    timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(STATUS_TIMED_OUT, scope, snapshot(failures), "interrupted", //$NON-NLS-1$
                    elapsedMs(started), timeoutMs);
        } catch (ExecutionException e) {
            failures.add("barrier: " + detail(e.getCause() != null ? e.getCause() : e)); //$NON-NLS-1$
        }
        List<String> failed = snapshot(failures);
        String status = failed.isEmpty() ? STATUS_SYNCED : STATUS_PARTIAL;
        long elapsed = elapsedMs(started);
        LOG.info("model-sync barrier: %s for %s in %dms (projects=%s, failures=%s)", //$NON-NLS-1$
                status, projectName, Long.valueOf(elapsed), scope, failed);
        return new Outcome(status, scope, failed, null, elapsed, timeoutMs);
    }

    /**
     * Writes {@code model_sync} (always, so absence never has to be read as a value) and, when the
     * model may be behind the disk, {@code model_sync_warning}. Pure; public for tests.
     */
    public static void annotate(JsonObject result, Outcome outcome) {
        if (result == null || outcome == null) {
            return;
        }
        JsonObject json = new JsonObject();
        json.addProperty("status", outcome.status()); //$NON-NLS-1$
        JsonArray projects = new JsonArray();
        outcome.projects().forEach(projects::add);
        json.add("projects", projects); //$NON-NLS-1$
        json.addProperty("elapsed_ms", outcome.elapsedMs()); //$NON-NLS-1$
        if (!outcome.failures().isEmpty()) {
            JsonArray failures = new JsonArray();
            outcome.failures().forEach(failures::add);
            json.add("failures", failures); //$NON-NLS-1$
        }
        if (outcome.stalledAt() != null) {
            json.addProperty("stalled_at", outcome.stalledAt()); //$NON-NLS-1$
            json.addProperty("timeout_ms", outcome.timeoutMs()); //$NON-NLS-1$
        }
        result.add("model_sync", json); //$NON-NLS-1$
        String warning = warning(outcome);
        if (warning != null) {
            result.addProperty("model_sync_warning", warning); //$NON-NLS-1$
        }
    }

    /** The caller-facing warning, or {@code null} when the model is known to be current. */
    static String warning(Outcome outcome) {
        if (outcome == null) {
            return null;
        }
        switch (outcome.status()) {
            case STATUS_TIMED_OUT:
                return "EDT did not finish syncing its model with the files on disk within " //$NON-NLS-1$
                        + outcome.timeoutMs() + "ms (stalled at " + outcome.stalledAt() + "). The update " //$NON-NLS-1$ //$NON-NLS-2$
                        + "ran anyway and MAY have applied the previous module/metadata state for files " //$NON-NLS-1$
                        + "changed outside EDT. Re-run update_infobase before trusting a test result."; //$NON-NLS-1$
            case STATUS_PARTIAL:
                return "Refreshing or syncing part of the project set failed (see model_sync.failures). " //$NON-NLS-1$
                        + "Files changed outside EDT in those projects MAY not be in this update."; //$NON-NLS-1$
            case STATUS_SKIPPED:
                return outcome.failures().isEmpty() ? null
                        : "Could not determine which projects to refresh before the update (see " //$NON-NLS-1$
                                + "model_sync.failures); files changed outside EDT MAY not be in this update."; //$NON-NLS-1$
            default:
                return null;
        }
    }

    /**
     * The projects whose model must be current before {@code anchor} is pushed: the anchor itself, its
     * base configuration when it is an extension (an extension's export resolves against the parent
     * model), and its own extensions (they share the infobase and the push). Order: anchor, parent,
     * extensions in map order; no duplicates. Pure; public for tests.
     *
     * @param extensionParents extension project name &#x2192; parent project name
     */
    public static List<String> refreshScope(String anchor, Map<String, String> extensionParents) {
        if (anchor == null || anchor.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> scope = new LinkedHashSet<>();
        scope.add(anchor);
        if (extensionParents != null) {
            String parent = extensionParents.get(anchor);
            if (parent != null && !parent.isBlank()) {
                scope.add(parent);
            }
            for (Map.Entry<String, String> entry : extensionParents.entrySet()) {
                if (anchor.equals(entry.getValue()) && entry.getKey() != null && !entry.getKey().isBlank()) {
                    scope.add(entry.getKey());
                }
            }
        }
        return List.copyOf(scope);
    }

    private static List<String> snapshot(List<String> failures) {
        synchronized (failures) {
            return List.copyOf(failures);
        }
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static String detail(Throwable t) {
        if (t == null) {
            return ""; //$NON-NLS-1$
        }
        String message = t.getMessage();
        return message != null && !message.isBlank() ? message : t.getClass().getSimpleName();
    }

    /** The real EDT/workspace implementation. */
    static final class EdtOperations implements Operations {

        private final EdtRuntimeGateway gateway = new EdtRuntimeGateway();

        @Override
        public List<String> scope(String projectName) {
            IProject anchor = gateway.resolveProject(projectName);
            // A non-existing project yields an empty scope BEFORE any EDT service is touched, so an
            // unknown name never waits on a service tracker; the update path reports it itself.
            if (anchor == null || !anchor.exists() || !anchor.isOpen()) {
                return List.of();
            }
            IV8ProjectManager v8ProjectManager = gateway.peekV8ProjectManager();
            Map<String, String> parents = v8ProjectManager == null
                    ? Map.of()
                    : InfobaseSiblingResolver.extensionParents(v8ProjectManager);
            List<String> scope = new ArrayList<>();
            for (String name : refreshScope(anchor.getName(), parents)) {
                IProject project = workspaceProject(name);
                if (project != null && project.exists() && project.isOpen()) {
                    scope.add(name);
                }
            }
            return scope;
        }

        @Override
        public void refresh(String projectName) throws Exception {
            IProject project = workspaceProject(projectName);
            if (project != null && project.exists() && project.isOpen()) {
                project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
            }
        }

        @Override
        public void waitModelSynchronization(String projectName) {
            IProject project = workspaceProject(projectName);
            if (project == null || !project.exists()) {
                return;
            }
            VibeCorePlugin plugin = VibeCorePlugin.getDefault();
            IBmModelManager modelManager = plugin == null ? null : plugin.getBmModelManager();
            if (modelManager == null) {
                throw new IllegalStateException("IBmModelManager is not available"); //$NON-NLS-1$
            }
            modelManager.waitModelSynchronization(project);
        }

        private static IProject workspaceProject(String name) {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            return workspace == null || name == null ? null : workspace.getRoot().getProject(name);
        }
    }
}
