package com.codepilot1c.core.edt.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import org.junit.Test;

import com.google.gson.JsonObject;

/**
 * Behavior of the refresh + BM-sync barrier that runs before {@code update_infobase} (feedback
 * 2026-10-05-update-infobase-applies-previous-state). The EDT side effects are faked; what is tested
 * is the scope, the ordering, the bound and what the caller is told. Whether refresh + BM sync really
 * closes the live race is a live-validation question, not something these tests can show.
 */
public class ModelSyncBarrierTest {

    // --- refreshScope ---

    @Test
    public void scopeOfPlainConfigurationIsItself() {
        assertEquals(List.of("Accounting management"), //$NON-NLS-1$
                ModelSyncBarrier.refreshScope("Accounting management", Map.of())); //$NON-NLS-1$
    }

    @Test
    public void scopeOfConfigurationIncludesItsExtensionsOnly() {
        Map<String, String> parents = new LinkedHashMap<>();
        parents.put("ExtA", "Base"); //$NON-NLS-1$ //$NON-NLS-2$
        parents.put("Foreign", "OtherBase"); //$NON-NLS-1$ //$NON-NLS-2$
        parents.put("ExtB", "Base"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("Base", "ExtA", "ExtB"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                ModelSyncBarrier.refreshScope("Base", parents)); //$NON-NLS-1$
    }

    @Test
    public void scopeOfExtensionIncludesItsParentButNotCoExtensions() {
        Map<String, String> parents = new LinkedHashMap<>();
        parents.put("ExtA", "Base"); //$NON-NLS-1$ //$NON-NLS-2$
        parents.put("ExtB", "Base"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("ExtA", "Base"), //$NON-NLS-1$ //$NON-NLS-2$
                ModelSyncBarrier.refreshScope("ExtA", parents)); //$NON-NLS-1$
    }

    @Test
    public void scopeOfBlankAnchorIsEmptyAndNullMapTolerated() {
        assertTrue(ModelSyncBarrier.refreshScope(" ", Map.of()).isEmpty()); //$NON-NLS-1$
        assertTrue(ModelSyncBarrier.refreshScope(null, null).isEmpty());
        assertEquals(List.of("Base"), ModelSyncBarrier.refreshScope("Base", null)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- awaitModelInSync ---

    @Test
    public void refreshesEveryProjectBeforeWaitingAndReportsSynced() {
        FakeOps ops = new FakeOps(List.of("Base", "ExtA")); //$NON-NLS-1$ //$NON-NLS-2$
        ModelSyncBarrier.Outcome outcome = new ModelSyncBarrier(ops, 5_000L).awaitModelInSync("Base"); //$NON-NLS-1$

        assertTrue(outcome.synced());
        assertEquals(List.of("refresh:Base", "refresh:ExtA", "sync:Base", "sync:ExtA"), ops.calls); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals(List.of("Base", "ExtA"), outcome.projects()); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject result = new JsonObject();
        ModelSyncBarrier.annotate(result, outcome);
        assertEquals("synced", result.getAsJsonObject("model_sync").get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(result.has("model_sync_warning")); //$NON-NLS-1$
    }

    @Test
    public void refreshFailureStillWaitsAndReportsPartialWithWarning() {
        FakeOps ops = new FakeOps(List.of("Base")); //$NON-NLS-1$
        ops.refreshFailure = new IllegalStateException("resource tree locked"); //$NON-NLS-1$
        ModelSyncBarrier.Outcome outcome = new ModelSyncBarrier(ops, 5_000L).awaitModelInSync("Base"); //$NON-NLS-1$

        assertEquals(ModelSyncBarrier.STATUS_PARTIAL, outcome.status());
        assertTrue(ops.calls.contains("sync:Base")); //$NON-NLS-1$
        assertTrue(outcome.failures().get(0).contains("resource tree locked")); //$NON-NLS-1$
        JsonObject result = new JsonObject();
        ModelSyncBarrier.annotate(result, outcome);
        assertTrue(result.has("model_sync_warning")); //$NON-NLS-1$
        assertEquals(1, result.getAsJsonObject("model_sync").getAsJsonArray("failures").size()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void stuckModelSyncIsBoundedAndNamesTheStalledStage() throws Exception {
        FakeOps ops = new FakeOps(List.of("Base")); //$NON-NLS-1$
        CountDownLatch never = new CountDownLatch(1);
        ops.syncGate = never;
        try {
            long t0 = System.currentTimeMillis();
            ModelSyncBarrier.Outcome outcome = new ModelSyncBarrier(ops, 200L).awaitModelInSync("Base"); //$NON-NLS-1$
            long elapsed = System.currentTimeMillis() - t0;

            assertEquals(ModelSyncBarrier.STATUS_TIMED_OUT, outcome.status());
            assertEquals("model_sync:Base", outcome.stalledAt()); //$NON-NLS-1$
            assertTrue("barrier returned after " + elapsed + "ms", elapsed < 5_000L); //$NON-NLS-1$ //$NON-NLS-2$
            JsonObject result = new JsonObject();
            ModelSyncBarrier.annotate(result, outcome);
            JsonObject sync = result.getAsJsonObject("model_sync"); //$NON-NLS-1$
            assertEquals("model_sync:Base", sync.get("stalled_at").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(200L, sync.get("timeout_ms").getAsLong()); //$NON-NLS-1$
            String warning = result.get("model_sync_warning").getAsString(); //$NON-NLS-1$
            assertTrue(warning.contains("previous")); //$NON-NLS-1$
            assertTrue(warning.contains("200ms")); //$NON-NLS-1$
        } finally {
            never.countDown();
        }
    }

    @Test
    public void emptyScopeIsSkippedSilently() {
        FakeOps ops = new FakeOps(List.of());
        ModelSyncBarrier.Outcome outcome = new ModelSyncBarrier(ops, 5_000L).awaitModelInSync("Missing"); //$NON-NLS-1$

        assertEquals(ModelSyncBarrier.STATUS_SKIPPED, outcome.status());
        assertTrue(ops.calls.isEmpty());
        assertNull(ModelSyncBarrier.warning(outcome));
        JsonObject result = new JsonObject();
        ModelSyncBarrier.annotate(result, outcome);
        assertEquals("skipped", result.getAsJsonObject("model_sync").get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void unresolvableScopeIsSkippedWithWarning() {
        FakeOps ops = new FakeOps(List.of());
        ops.scopeFailure = new IllegalStateException("Workspace is closed"); //$NON-NLS-1$
        ModelSyncBarrier.Outcome outcome = new ModelSyncBarrier(ops, 5_000L).awaitModelInSync("Base"); //$NON-NLS-1$

        assertEquals(ModelSyncBarrier.STATUS_SKIPPED, outcome.status());
        JsonObject result = new JsonObject();
        ModelSyncBarrier.annotate(result, outcome);
        assertTrue(result.has("model_sync_warning")); //$NON-NLS-1$
    }

    /** Records refresh/sync calls in order; optional failure injection and a blocking sync. */
    static final class FakeOps implements ModelSyncBarrier.Operations {

        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        private final List<String> scope;
        volatile RuntimeException scopeFailure;
        volatile RuntimeException refreshFailure;
        volatile CountDownLatch syncGate;

        FakeOps(List<String> scope) {
            this.scope = scope;
        }

        @Override
        public List<String> scope(String projectName) {
            if (scopeFailure != null) {
                throw scopeFailure;
            }
            return scope;
        }

        @Override
        public void refresh(String projectName) {
            calls.add("refresh:" + projectName); //$NON-NLS-1$
            if (refreshFailure != null) {
                throw refreshFailure;
            }
        }

        @Override
        public void waitModelSynchronization(String projectName) throws Exception {
            CountDownLatch gate = syncGate;
            if (gate != null) {
                gate.await();
            }
            calls.add("sync:" + projectName); //$NON-NLS-1$
        }
    }
}
