package com.codepilot1c.core.edt.extension;

import org.eclipse.core.resources.IProject;

import com.codepilot1c.core.edt.ast.ProjectReadinessChecker;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Refuses to start an extension-project creation while the EDT workspace is still coming up.
 *
 * <p>Motivation: a failed {@code IExtensionProjectManager.create} deletes the half-created project
 * again, and when that happens while {@code DefaultContextsStartJob} is still starting the workspace
 * contexts, the start job trips over the vanished resource and re-imports (CLEAN_IMPORT) every other
 * project — including the unrelated base project, whose bound-infobase application records get wiped
 * on the way. The blast radius of a bad {@code create} is therefore much wider inside that window
 * than outside it, so the window is simply not entered.</p>
 *
 * <p>The readiness signal is not a new one: it is {@link ProjectReadinessChecker}, the very same
 * derived-data probe that {@code get_workspace_state} reports as {@code index.is_indexing} and
 * per-project {@code BUILDING}.</p>
 */
public final class ExtensionWorkspaceReadinessGuard {

    private final ProjectReadinessChecker checker;

    public ExtensionWorkspaceReadinessGuard(ProjectReadinessChecker checker) {
        this.checker = checker;
    }

    /**
     * @param baseProject the configuration project the extension is created for; it must be fully
     *            started (a cold EDT reports it as {@code NOT_AVAILABLE}, an indexing one as
     *            {@code BUILDING})
     * @param workspaceProjects every project of the workspace; any of them still {@code BUILDING}
     *            means the background start/index wave has not settled yet
     * @throws MetadataOperationException recoverable {@code PROJECT_NOT_READY} while the workspace is
     *             still initializing
     */
    public void ensureWorkspaceInitialized(IProject baseProject, IProject[] workspaceProjects) {
        ProjectReadinessChecker.Result baseResult = check(baseProject);
        if (baseResult != null && !baseResult.isReady()) {
            throw notReady(baseProject.getName(), baseResult.getState().name(), baseResult.getMessage());
        }
        if (workspaceProjects == null) {
            return;
        }
        for (IProject project : workspaceProjects) {
            if (project == null || project.equals(baseProject) || !project.exists() || !project.isOpen()) {
                continue;
            }
            ProjectReadinessChecker.Result result = check(project);
            if (result != null && result.getState() == ProjectReadinessChecker.State.BUILDING) {
                throw notReady(project.getName(), result.getState().name(), result.getMessage());
            }
        }
    }

    /**
     * Mirrors the {@code get_workspace_state} probe: a readiness check that itself blows up yields no
     * verdict ({@code UNKNOWN}) rather than a false "still initializing", so a probe failure never
     * blocks the operation.
     */
    private ProjectReadinessChecker.Result check(IProject project) {
        try {
            return checker.check(project);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static MetadataOperationException notReady(String projectName, String state, String detail) {
        return new MetadataOperationException(
                MetadataOperationCode.PROJECT_NOT_READY,
                "workspace is still initializing, retry shortly (project " + projectName //$NON-NLS-1$
                        + " is " + state + ": " + detail //$NON-NLS-1$ //$NON-NLS-2$
                        + "). Creating an extension project now can make the failed rollback" //$NON-NLS-1$
                        + " re-import the whole workspace.", //$NON-NLS-1$
                true);
    }
}
