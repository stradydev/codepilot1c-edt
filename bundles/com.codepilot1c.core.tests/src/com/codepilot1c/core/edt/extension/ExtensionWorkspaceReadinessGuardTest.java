package com.codepilot1c.core.edt.extension;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com.codepilot1c.core.edt.ast.ProjectReadinessChecker;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Feedback 2026-09-11 §1: a failed {@code create} that lands inside the workspace start-up window
 * makes {@code DefaultContextsStartJob} trip over the deleted project and CLEAN_IMPORT every other
 * project, wiping the base project's bound-infobase records. The guard keeps the operation out of
 * that window, using the same derived-data readiness signal {@code get_workspace_state} reports.
 */
public class ExtensionWorkspaceReadinessGuardTest {

    @Test
    public void settledWorkspacePassesThrough() {
        IProject base = project("Accounting management"); //$NON-NLS-1$
        IProject other = project("yaxunit"); //$NON-NLS-1$
        Map<String, ProjectReadinessChecker.State> states = new HashMap<>();
        states.put("Accounting management", ProjectReadinessChecker.State.READY); //$NON-NLS-1$
        states.put("yaxunit", ProjectReadinessChecker.State.READY); //$NON-NLS-1$

        guard(states).ensureWorkspaceInitialized(base, new IProject[]{base, other});
    }

    @Test
    public void indexingBaseProjectBlocks() {
        IProject base = project("Accounting management"); //$NON-NLS-1$
        Map<String, ProjectReadinessChecker.State> states = new HashMap<>();
        states.put("Accounting management", ProjectReadinessChecker.State.BUILDING); //$NON-NLS-1$

        MetadataOperationException e = expectBlocked(guard(states), base, new IProject[]{base});
        assertEquals(MetadataOperationCode.PROJECT_NOT_READY, e.getCode());
        assertTrue(e.getMessage(), e.isRecoverable());
        assertTrue(e.getMessage(), e.getMessage().contains("still initializing")); //$NON-NLS-1$
        assertTrue(e.getMessage(), e.getMessage().contains("Accounting management")); //$NON-NLS-1$
    }

    /** Cold EDT right after a restart: contexts are not started yet, so there is no IDtProject. */
    @Test
    public void coldWorkspaceBlocks() {
        IProject base = project("Accounting management"); //$NON-NLS-1$
        Map<String, ProjectReadinessChecker.State> states = new HashMap<>();
        states.put("Accounting management", ProjectReadinessChecker.State.NOT_AVAILABLE); //$NON-NLS-1$

        MetadataOperationException e = expectBlocked(guard(states), base, new IProject[]{base});
        assertEquals(MetadataOperationCode.PROJECT_NOT_READY, e.getCode());
        assertTrue(e.isRecoverable());
    }

    @Test
    public void anyOtherProjectStillIndexingBlocks() {
        IProject base = project("Accounting management"); //$NON-NLS-1$
        IProject other = project("BSL_Analyzer"); //$NON-NLS-1$
        Map<String, ProjectReadinessChecker.State> states = new HashMap<>();
        states.put("Accounting management", ProjectReadinessChecker.State.READY); //$NON-NLS-1$
        states.put("BSL_Analyzer", ProjectReadinessChecker.State.BUILDING); //$NON-NLS-1$

        MetadataOperationException e = expectBlocked(guard(states), base, new IProject[]{base, other});
        assertTrue(e.getMessage(), e.getMessage().contains("BSL_Analyzer")); //$NON-NLS-1$
    }

    /** A non-EDT project reports NOT_AVAILABLE forever; it must not wedge the tool shut. */
    @Test
    public void unavailableSiblingProjectDoesNotBlock() {
        IProject base = project("Accounting management"); //$NON-NLS-1$
        IProject plain = project("Servers"); //$NON-NLS-1$
        Map<String, ProjectReadinessChecker.State> states = new HashMap<>();
        states.put("Accounting management", ProjectReadinessChecker.State.READY); //$NON-NLS-1$
        states.put("Servers", ProjectReadinessChecker.State.NOT_AVAILABLE); //$NON-NLS-1$

        guard(states).ensureWorkspaceInitialized(base, new IProject[]{base, plain});
    }

    /** A probe that itself fails yields no verdict, mirroring get_workspace_state's UNKNOWN. */
    @Test
    public void probeFailureDoesNotBlock() {
        IProject base = project("Accounting management"); //$NON-NLS-1$
        ProjectReadinessChecker exploding = new ProjectReadinessChecker(null) {
            @Override
            public Result check(IProject project) {
                throw new IllegalStateException("IDerivedDataManagerProvider is unavailable"); //$NON-NLS-1$
            }
        };

        new ExtensionWorkspaceReadinessGuard(exploding)
                .ensureWorkspaceInitialized(base, new IProject[]{base});
    }

    private static MetadataOperationException expectBlocked(
            ExtensionWorkspaceReadinessGuard guard, IProject base, IProject[] projects) {
        try {
            guard.ensureWorkspaceInitialized(base, projects);
        } catch (MetadataOperationException e) {
            return e;
        }
        fail("the guard must refuse to create while the workspace is initializing"); //$NON-NLS-1$
        return null;
    }

    private static ExtensionWorkspaceReadinessGuard guard(Map<String, ProjectReadinessChecker.State> states) {
        return new ExtensionWorkspaceReadinessGuard(new ProjectReadinessChecker(null) {
            @Override
            public Result check(IProject project) {
                ProjectReadinessChecker.State state = states.get(project.getName());
                return new Result(state == null ? State.UNKNOWN : state, "state=" + state); //$NON-NLS-1$
            }
        });
    }

    private static IProject project(String name) {
        return (IProject) Proxy.newProxyInstance(
                IProject.class.getClassLoader(),
                new Class<?>[]{IProject.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "exists", "isOpen" -> Boolean.TRUE; //$NON-NLS-1$ //$NON-NLS-2$
                    case "getName", "toString" -> name; //$NON-NLS-1$ //$NON-NLS-2$
                    case "equals" -> Boolean.valueOf(args[0] == proxy); //$NON-NLS-1$
                    case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy)); //$NON-NLS-1$
                    default -> null;
                });
    }
}
