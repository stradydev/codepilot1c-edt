package com.codepilot1c.core.tools.workspace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.codepilot1c.core.edt.runtime.EdtProjectResolver;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com.codepilot1c.core.edt.runtime.InfobaseSiblingResolver;
import com.codepilot1c.core.edt.runtime.ModelSyncBarrier;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * {@code edt_update_infobase} must bring the EDT model in line with the disk BEFORE it applies — and
 * before the {@code skip_if_current} equality read, which would otherwise answer EQUAL off a stale
 * model (feedback 2026-10-05-update-infobase-applies-previous-state). EDT side effects are faked; the
 * real race (external .bsl write → update) still needs live validation.
 */
public class EdtUpdateInfobaseModelSyncTest {

    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    @Before
    @After
    public void reset() {
        EdtUpdateInfobaseTool.clearInFlightUpdatesForTest();
    }

    @Test
    public void syncRunsBeforeEqualityCheckAndUpdate() throws Exception {
        RecordingOps ops = new RecordingOps(calls);
        ToolResult result = tool(new ModelSyncBarrier(ops, 5_000L)).execute(Map.of(
                "project_name", "Demo", //$NON-NLS-1$ //$NON-NLS-2$
                "skip_if_current", Boolean.TRUE)).join(); //$NON-NLS-1$

        assertTrue(result.getContent(), result.isSuccess());
        assertEquals(List.of("refresh:Demo", "sync:Demo", "equality", "update"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                calls.subList(0, 4));
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertEquals("synced", json.getAsJsonObject("model_sync").get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(json.has("model_sync_warning")); //$NON-NLS-1$
        assertTrue(json.get("updated").getAsBoolean()); //$NON-NLS-1$
    }

    @Test
    public void timedOutSyncStillUpdatesButWarns() throws Exception {
        RecordingOps ops = new RecordingOps(calls);
        CountDownLatch never = new CountDownLatch(1);
        ops.syncGate = never;
        try {
            ToolResult result = tool(new ModelSyncBarrier(ops, 200L)).execute(Map.of(
                    "project_name", "Demo")).join(); //$NON-NLS-1$ //$NON-NLS-2$

            assertTrue(result.getContent(), result.isSuccess());
            assertTrue(calls.contains("update")); //$NON-NLS-1$
            JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
            assertEquals("timed_out", json.getAsJsonObject("model_sync").get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(json.has("model_sync_warning")); //$NON-NLS-1$
        } finally {
            never.countDown();
        }
    }

    @Test
    public void dryRunDoesNotTouchTheWorkspace() throws Exception {
        RecordingOps ops = new RecordingOps(calls);
        ToolResult result = tool(new ModelSyncBarrier(ops, 5_000L)).execute(Map.of(
                "project_name", "Demo", //$NON-NLS-1$ //$NON-NLS-2$
                "dry_run", Boolean.TRUE)).join(); //$NON-NLS-1$

        assertTrue(result.isSuccess());
        assertFalse(calls.contains("refresh:Demo")); //$NON-NLS-1$
        assertFalse(JsonParser.parseString(result.getContent()).getAsJsonObject().has("model_sync")); //$NON-NLS-1$
    }

    @Test
    public void asyncJobAlsoSyncsBeforeUpdate() throws Exception {
        RecordingOps ops = new RecordingOps(calls);
        ToolResult accepted = tool(new ModelSyncBarrier(ops, 5_000L)).execute(Map.of(
                "project_name", "Demo", //$NON-NLS-1$ //$NON-NLS-2$
                "async", Boolean.TRUE)).join(); //$NON-NLS-1$
        assertTrue(accepted.isSuccess());
        String jobId = JsonParser.parseString(accepted.getContent()).getAsJsonObject().get("job_id").getAsString(); //$NON-NLS-1$

        BackgroundJobRegistry registry = BackgroundJobRegistry.getInstance();
        BackgroundJobRegistry.JobStatus status = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            status = registry.getStatus(jobId).orElseThrow();
            if (status.getState() == BackgroundJobRegistry.JobState.DONE
                    || status.getState() == BackgroundJobRegistry.JobState.FAILED) {
                break;
            }
            Thread.sleep(20);
        }
        assertEquals(BackgroundJobRegistry.JobState.DONE, status.getState());
        assertTrue(calls.indexOf("sync:Demo") >= 0); //$NON-NLS-1$
        assertTrue(calls.indexOf("sync:Demo") < calls.indexOf("update")); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject json = JsonParser.parseString(status.getResult()).getAsJsonObject();
        assertEquals("synced", json.getAsJsonObject("model_sync").get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void failureAfterTheBarrierStillCarriesModelSync() throws Exception {
        // live 2026-10-06: the main configuration was applied, then an attached extension failed
        // with an NPE — the error payload, built from scratch, used to drop model_sync.
        RecordingOps ops = new RecordingOps(calls);
        ToolResult result = tool(new ModelSyncBarrier(ops, 5_000L),
                new IllegalStateException("extension upload failed")).execute(Map.of( //$NON-NLS-1$
                        "project_name", "Demo")).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getErrorMessage()).getAsJsonObject();
        assertEquals("UPDATE_FAILED", json.get("error_code").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("synced", json.getAsJsonObject("model_sync").get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private EdtUpdateInfobaseTool tool(ModelSyncBarrier barrier) throws Exception {
        return tool(barrier, null);
    }

    private EdtUpdateInfobaseTool tool(ModelSyncBarrier barrier, RuntimeException updateFailure) throws Exception {
        File workspaceRoot = Files.createTempDirectory("edt-update-model-sync").toFile(); //$NON-NLS-1$
        RecordingRuntimeService runtime = new RecordingRuntimeService(calls);
        runtime.updateFailure = updateFailure;
        return new EdtUpdateInfobaseTool(new NullInfobaseResolver(), runtime,
                new NoSiblings(runtime), barrier) {
            @Override
            protected File getWorkspaceRoot() {
                return workspaceRoot;
            }
        };
    }

    private static final class RecordingOps implements ModelSyncBarrier.Operations {
        private final List<String> calls;
        volatile CountDownLatch syncGate;

        RecordingOps(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public List<String> scope(String projectName) {
            return List.of(projectName);
        }

        @Override
        public void refresh(String projectName) {
            calls.add("refresh:" + projectName); //$NON-NLS-1$
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

    private static final class NullInfobaseResolver extends EdtProjectResolver {
        @Override
        public InfobaseReference resolveInfobase(String projectName, File workspaceRoot) {
            return null;
        }
    }

    private static final class NoSiblings extends InfobaseSiblingResolver {
        NoSiblings(EdtRuntimeService runtime) {
            super(null, runtime);
        }

        @Override
        public List<Sibling> siblingsOf(String projectName) {
            return List.of();
        }
    }

    private static final class RecordingRuntimeService extends EdtRuntimeService {
        private final List<String> calls;
        RuntimeException updateFailure;

        RecordingRuntimeService(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public InfobaseReference resolveDefaultInfobase(String projectName) {
            return null;
        }

        @Override
        public String readInfobaseEqualityState(String projectName) {
            // Only the pre-check counts for ordering; the post-update read comes after "update".
            if (!calls.contains("update")) { //$NON-NLS-1$
                calls.add("equality"); //$NON-NLS-1$
            }
            return "NOT_EQUAL"; //$NON-NLS-1$
        }

        @Override
        public ResolvedRuntimeInfo describeUpdateRuntime(String projectName) {
            return null;
        }

        @Override
        public void checkUpdateLease(String projectName) {
            // no lease guard in unit tests
        }

        @Override
        public UpdateInfobaseStatus updateInfobaseWithStatus(String projectName, boolean keepConnected,
                org.eclipse.core.runtime.IProgressMonitor monitor) {
            calls.add("update"); //$NON-NLS-1$
            if (updateFailure != null) {
                throw updateFailure;
            }
            return new UpdateInfobaseStatus(true, false);
        }
    }
}
