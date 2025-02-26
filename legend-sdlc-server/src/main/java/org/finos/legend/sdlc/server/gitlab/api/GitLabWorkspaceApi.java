// Copyright 2020 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.finos.legend.sdlc.server.gitlab.api;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.impl.utility.Iterate;
import org.finos.legend.sdlc.domain.model.project.workspace.Workspace;
import org.finos.legend.sdlc.domain.model.project.workspace.WorkspaceType;
import org.finos.legend.sdlc.domain.model.revision.Revision;
import org.finos.legend.sdlc.server.domain.api.revision.RevisionApi;
import org.finos.legend.sdlc.server.domain.api.workspace.WorkspaceApi;
import org.finos.legend.sdlc.server.error.LegendSDLCServerException;
import org.finos.legend.sdlc.server.gitlab.GitLabConfiguration;
import org.finos.legend.sdlc.server.gitlab.GitLabProjectId;
import org.finos.legend.sdlc.server.gitlab.auth.GitLabUserContext;
import org.finos.legend.sdlc.server.gitlab.tools.GitLabApiTools;
import org.finos.legend.sdlc.server.gitlab.tools.PagerTools;
import org.finos.legend.sdlc.server.project.ProjectFileAccessProvider;
import org.finos.legend.sdlc.server.tools.BackgroundTaskProcessor;
import org.finos.legend.sdlc.server.tools.CallUntil;
import org.gitlab4j.api.CommitsApi;
import org.gitlab4j.api.Constants;
import org.gitlab4j.api.Constants.StateEvent;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.MergeRequestApi;
import org.gitlab4j.api.Pager;
import org.gitlab4j.api.RepositoryApi;
import org.gitlab4j.api.models.Branch;
import org.gitlab4j.api.models.Commit;
import org.gitlab4j.api.models.CommitAction;
import org.gitlab4j.api.models.CommitRef;
import org.gitlab4j.api.models.CommitRef.RefType;
import org.gitlab4j.api.models.CompareResults;
import org.gitlab4j.api.models.Diff;
import org.gitlab4j.api.models.MergeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.core.Response.Status;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class GitLabWorkspaceApi extends GitLabApiWithFileAccess implements WorkspaceApi
{
    private static final Logger LOGGER = LoggerFactory.getLogger(GitLabWorkspaceApi.class);

    private final RevisionApi revisionApi;

    @Inject
    public GitLabWorkspaceApi(GitLabConfiguration gitLabConfiguration, GitLabUserContext userContext, RevisionApi revisionApi, BackgroundTaskProcessor backgroundTaskProcessor)
    {
        super(gitLabConfiguration, userContext, backgroundTaskProcessor);
        this.revisionApi = revisionApi;
    }

    @Override
    public List<Workspace> getWorkspaces(String projectId, Set<WorkspaceType> workspaceTypes)
    {
        return Iterate.flatCollect(workspaceTypes, type -> getWorkspacesByAccessType(projectId, type, ProjectFileAccessProvider.WorkspaceAccessType.WORKSPACE), Lists.mutable.empty());
    }

    @Override
    public List<Workspace> getWorkspacesWithConflictResolution(String projectId)
    {
        return getWorkspacesByAccessType(projectId, WorkspaceType.USER, ProjectFileAccessProvider.WorkspaceAccessType.CONFLICT_RESOLUTION);
    }

    @Override
    public List<Workspace> getBackupWorkspaces(String projectId)
    {
        return getWorkspacesByAccessType(projectId, WorkspaceType.USER, ProjectFileAccessProvider.WorkspaceAccessType.BACKUP);
    }

    private List<Workspace> getWorkspacesByAccessType(String projectId, WorkspaceType workspaceType, ProjectFileAccessProvider.WorkspaceAccessType workspaceAccessType)
    {
        LegendSDLCServerException.validateNonNull(projectId, "projectId may not be null");
        LegendSDLCServerException.validateNonNull(projectId, "workspaceAccessType may not be null");
        try
        {
            GitLabProjectId gitLabProjectId = parseProjectId(projectId);
            String branchPrefix = getWorkspaceBranchName("", workspaceType, workspaceAccessType);
            Pager<Branch> pager = getGitLabApi().getRepositoryApi().getBranches(gitLabProjectId.getGitLabId(), "^" + branchPrefix, ITEMS_PER_PAGE);
            return PagerTools.stream(pager)
                    .filter(branch -> (branch != null) && (branch.getName() != null) && branch.getName().startsWith(branchPrefix))
                    .map(branch -> workspaceBranchToWorkspace(projectId, branch, workspaceType, workspaceAccessType))
                    .collect(PagerTools.listCollector(pager));
        }
        catch (Exception e)
        {
            throw buildException(e,
                    () -> "User " + getCurrentUser() + " is not allowed to get " + workspaceType.getLabel() + " " + workspaceAccessType.getLabelPlural() + " for project " + projectId,
                    () -> "Unknown project: " + projectId,
                    () -> "Error getting " + workspaceType.getLabel() + " " + workspaceAccessType.getLabelPlural() + " for project " + projectId);
        }
    }

    @Override
    public List<Workspace> getAllWorkspaces(String projectId, Set<WorkspaceType> workspaceTypes)
    {
        return Iterate.flatCollect(workspaceTypes, type -> getAllWorkspacesByAccessType(projectId, type, ProjectFileAccessProvider.WorkspaceAccessType.WORKSPACE), Lists.mutable.empty());
    }

    private List<Workspace> getAllWorkspacesByAccessType(String projectId, WorkspaceType workspaceType, ProjectFileAccessProvider.WorkspaceAccessType workspaceAccessType)
    {
        LegendSDLCServerException.validateNonNull(projectId, "projectId may not be null");
        LegendSDLCServerException.validateNonNull(workspaceType, "workspaceType may not be null");
        LegendSDLCServerException.validateNonNull(workspaceAccessType, "workspaceAccessType may not be null");
        try
        {
            GitLabProjectId gitLabProjectId = parseProjectId(projectId);
            String branchPrefix = getWorkspaceBranchNamePrefix(workspaceType, workspaceAccessType) + BRANCH_DELIMITER;
            Pager<Branch> pager = getGitLabApi().getRepositoryApi().getBranches(gitLabProjectId.getGitLabId(), "^" + branchPrefix, ITEMS_PER_PAGE);
            return PagerTools.stream(pager)
                    .filter(branch -> (branch != null) && (branch.getName() != null) && branch.getName().startsWith(branchPrefix))
                    .map(branch -> workspaceBranchToWorkspace(projectId, branch, workspaceType, workspaceAccessType))
                    .collect(PagerTools.listCollector(pager));
        }
        catch (Exception e)
        {
            throw buildException(e,
                    () -> "User " + getCurrentUser() + " is not allowed to get " + workspaceType.getLabel() + " " + workspaceAccessType.getLabelPlural() + " for project " + projectId,
                    () -> "Unknown project: " + projectId,
                    () -> "Error getting " + workspaceType.getLabel() + " " + workspaceAccessType.getLabelPlural() + " for project " + projectId);
        }
    }

    @Override
    public Workspace getWorkspace(String projectId, String workspaceId, WorkspaceType workspaceType)
    {
        return getWorkspaceByAccessType(projectId, workspaceId, workspaceType, ProjectFileAccessProvider.WorkspaceAccessType.WORKSPACE);
    }

    @Override
    public Workspace getWorkspaceWithConflictResolution(String projectId, String workspaceId, WorkspaceType workspaceType)
    {
        return getWorkspaceByAccessType(projectId, workspaceId, workspaceType, ProjectFileAccessProvider.WorkspaceAccessType.CONFLICT_RESOLUTION);
    }

    @Override
    public Workspace getBackupWorkspace(String projectId, String workspaceId, WorkspaceType workspaceType)
    {
        return this.getWorkspaceByAccessType(projectId, workspaceId, workspaceType, ProjectFileAccessProvider.WorkspaceAccessType.BACKUP);
    }

    private Workspace getWorkspaceByAccessType(String projectId, String workspaceId, WorkspaceType workspaceType, ProjectFileAccessProvider.WorkspaceAccessType workspaceAccessType)
    {
        LegendSDLCServerException.validateNonNull(projectId, "projectId may not be null");
        LegendSDLCServerException.validateNonNull(workspaceId, "workspaceId may not be null");
        LegendSDLCServerException.validateNonNull(projectId, "workspaceAccessType may not be null");
        try
        {
            GitLabProjectId gitLabProjectId = parseProjectId(projectId);
            Branch branch = getGitLabApi().getRepositoryApi().getBranch(gitLabProjectId.getGitLabId(), getWorkspaceBranchName(workspaceId, workspaceType, workspaceAccessType));
            return workspaceBranchToWorkspace(projectId, branch, workspaceType, workspaceAccessType);
        }
        catch (Exception e)
        {
            throw buildException(e,
                    () -> "User " + getCurrentUser() + " is not allowed to get " + workspaceType.getLabel() + " " + workspaceAccessType.getLabel() + "  " + workspaceId + " in project " + projectId,
                    () -> "Unknown " + workspaceType.getLabel() + " " + workspaceAccessType.getLabel() + " (" + workspaceId + ") or project (" + projectId + ")",
                    () -> "Error getting " + workspaceType.getLabel() + " " + workspaceAccessType.getLabel() + " " + workspaceId + " in project " + projectId);
        }
    }

    @Override
    public boolean isWorkspaceOutdated(String projectId, String workspaceId, WorkspaceType workspaceType)
    {
        return this.isWorkspaceOutdatedByAccessType(projectId, workspaceId, workspaceType, ProjectFileAccessProvider.WorkspaceAccessType.WORKSPACE);
    }

    @Override
    public boolean isWorkspaceWithConflictResolutionOutdated(String projectId, String workspaceId, WorkspaceType workspaceType)
    {
        return this.isWorkspaceOutdatedByAccessType(projectId, workspaceId, workspaceType, ProjectFileAccessProvider.WorkspaceAccessType.CONFLICT_RESOLUTION);
    }

    @Override
    public boolean isBackupWorkspaceOutdated(String projectId, String workspaceId, WorkspaceType workspaceType)
    {
        return this.isWorkspaceOutdatedByAccessType(projectId, workspaceId, workspaceType, ProjectFileAccessProvider.WorkspaceAccessType.BACKUP);
    }

    private boolean isWorkspaceOutdatedByAccessType(String projectId, String workspaceId, WorkspaceType workspaceType, ProjectFileAccessProvider.WorkspaceAccessType workspaceAccessType)
    {
        LegendSDLCServerException.validateNonNull(projectId, "projectId may not be null");
        LegendSDLCServerException.validateNonNull(workspaceId, "workspaceId may not be null");
        LegendSDLCServerException.validateNonNull(workspaceType, "workspaceType may not be null");
        LegendSDLCServerException.validateNonNull(workspaceAccessType, "workspaceAccessType may not be null");

        GitLabProjectId gitLabProjectId = parseProjectId(projectId);
        String workspaceBranchName = getBranchName(workspaceId, workspaceType, workspaceAccessType, gitLabProjectId);
        GitLabApi gitLabApi = getGitLabApi();
        RepositoryApi repositoryApi = gitLabApi.getRepositoryApi();

        // Get the workspace branch
        Branch workspaceBranch;
        try
        {
            workspaceBranch = withRetries(() -> repositoryApi.getBranch(gitLabProjectId.getGitLabId(), workspaceBranchName));
        }
        catch (Exception e)
        {
            throw buildException(e,
                    () -> "User " + getCurrentUser() + " is not allowed to access " + workspaceType.getLabel() + " " + workspaceAccessType.getLabel() + " " + workspaceId + " in project " + projectId,
                    () -> "Unknown " + workspaceType.getLabel() + " " + workspaceAccessType.getLabel() + " in project " + projectId + ": " + workspaceId,
                    () -> "Error accessing " + workspaceType.getLabel() + " " + workspaceAccessType.getLabel() + " " + workspaceId + " in project " + projectId);
        }
        String workspaceRevisionId = workspaceBranch.getCommit().getId();

        // Get HEAD of master
        Branch defaultBranch;
        String defaultBranchName = getDefaultBranch(gitLabProjectId);

        try
        {
            defaultBranch = withRetries(() -> repositoryApi.getBranch(gitLabProjectId.getGitLabId(), defaultBranchName));
        }
        catch (Exception e)
        {
            throw buildException(e,
                    () -> "User " + getCurrentUser() + " is not allowed to access the latest revision in project " + projectId,
                    () -> "Unknown project: " + projectId,
                    () -> "Error accessing latest revision for project " + projectId);
        }
        String defaultBranchRevisionId = defaultBranch.getCommit().getId();
        CommitsApi commitsApi = gitLabApi.getCommitsApi();

        // Check if the workspace does not have the latest revision of the project, i.e. it is outdated
        try
        {
            if (defaultBranchRevisionId.equals(workspaceRevisionId))
            {
                return false;
            }
            Pager<CommitRef> headCommitRefsPager = withRetries(() -> commitsApi.getCommitRefs(gitLabProjectId.getGitLabId(), defaultBranchRevisionId, RefType.BRANCH, ITEMS_PER_PAGE));
            Stream<CommitRef> headCommitRefs = PagerTools.stream(headCommitRefsPager);
            // This will check if the branch contains the master HEAD commit by looking up the list of references the commit is pushed to
            return headCommitRefs.noneMatch(cr -> workspaceBranchName.equals(cr.getName()));
        }
        catch (Exception e)
        {
            throw buildException(e,
                    () -> "User " + getCurrentUser() + " is not allowed to check if " + workspaceType.getLabel() + " " + workspaceAccessType.getLabel() + " " + workspaceId + " in project " + projectId + " is outdated",
                    () -> "Unknown revision (" + defaultBranchRevisionId + "), or " + workspaceType.getLabel() + " " + workspaceAccessType.getLabel() + " (" + workspaceId + ") or project (" + projectId + ")",
                    () -> "Error checking if " + workspaceType.getLabel() + " " + workspaceAccessType.getLabel() + " " + workspaceId + " of project " + projectId + " is outdated");
        }
    }

    @Override
    public boolean isWorkspaceInConflictResolutionMode(String projectId, String workspaceId, WorkspaceType workspaceType)
    {
        LegendSDLCServerException.validateNonNull(projectId, "projectId may not be null");
        LegendSDLCServerException.validateNonNull(workspaceId, "workspaceId may not be null");
        GitLabProjectId gitLabProjectId = parseProjectId(projectId);
        RepositoryApi repositoryApi = getGitLabApi().getRepositoryApi();
        ProjectFileAccessProvider.WorkspaceAccessType workspaceAccessType = ProjectFileAccessProvider.WorkspaceAccessType.WORKSPACE;
        try
        {
            withRetries(() -> repositoryApi.getBranch(gitLabProjectId.getGitLabId(), getWorkspaceBranchName(workspaceId, workspaceType, workspaceAccessType)));
        }
        catch (Exception e)
        {
            throw buildException(e,
                    () -> "User " + getCurrentUser() + " is not allowed to get " + workspaceType.getLabel() + " " + workspaceAccessType.getLabel() + " " + workspaceId + " in project " + projectId,
                    () -> "Unknown " + workspaceType.getLabel() + " " + workspaceAccessType.getLabel() + " (" + workspaceId + ") or project (" + projectId + ")",
                    () -> "Error getting " + workspaceType.getLabel() + " " + workspaceAccessType.getLabel() + " is in conflict resolution mode for " + workspaceId + " in project " + projectId);
        }
        Branch conflictBranch;
        ProjectFileAccessProvider.WorkspaceAccessType conflictResolutionWorkspaceType = ProjectFileAccessProvider.WorkspaceAccessType.CONFLICT_RESOLUTION;
        try
        {
            conflictBranch = repositoryApi.getBranch(gitLabProjectId.getGitLabId(), getWorkspaceBranchName(workspaceId, workspaceType, conflictResolutionWorkspaceType));
            return conflictBranch != null;
        }
        catch (Exception e)
        {
            if (GitLabApiTools.isNotFoundGitLabApiException(e))
            {
                return false;
            }
            throw buildException(e,
                    () -> "User " + getCurrentUser() + " is not allowed to check if " + workspaceType.getLabel() + " " + workspaceAccessType.getLabel() + " is in conflict resolution mode for " + workspaceType.getLabel() + " " + workspaceAccessType.getLabel() + " " + workspaceId + " in project " + projectId,
                    () -> "Unknown " + workspaceType.getLabel() + " " + workspaceAccessType.getLabel() + " (" + workspaceId + ") or project (" + projectId + ")",
                    () -> "Error checking if " + workspaceType.getLabel() + " " + workspaceAccessType.getLabel() + " is in conflict resolution mode for " + workspaceId + " in project " + projectId);
        }
    }

    /**
     * When we create a new workspace, we also should clean left-over backup and conflict resolution workspaces with the same name
     */
    @Override
    public Workspace newWorkspace(String projectId, String workspaceId, WorkspaceType workspaceType)
    {
        LegendSDLCServerException.validateNonNull(projectId, "projectId may not be null");
        LegendSDLCServerException.validateNonNull(workspaceId, "workspaceId may not be null");

        validateWorkspaceId(workspaceId);
        if (this.getProjectConfiguration(projectId) ==  null)
        {
            throw new LegendSDLCServerException("Project structure has not been set up", Status.CONFLICT);
        }

        GitLabProjectId gitLabProjectId = parseProjectId(projectId);
        RepositoryApi repositoryApi = getGitLabApi().getRepositoryApi();
        // Delete backup workspace with the same name if exists
        Branch backupBranch = null;
        ProjectFileAccessProvider.WorkspaceAccessType backupWorkspaceType = ProjectFileAccessProvider.WorkspaceAccessType.BACKUP;
        try
        {
            backupBranch = withRetries(() -> repositoryApi.getBranch(gitLabProjectId.getGitLabId(), getWorkspaceBranchName(workspaceId, workspaceType, backupWorkspaceType)));
        }
        catch (Exception e)
        {
            if (!GitLabApiTools.isNotFoundGitLabApiException(e))
            {
                LOGGER.error("Error accessing {} {} in project {}", workspaceType.getLabel() + " " + backupWorkspaceType.getLabel(), workspaceId, projectId, e);
            }
        }
        if (backupBranch != null)
        {
            LOGGER.debug("Cleaning up left-over {} {} in project {}", workspaceType.getLabel() + " " + backupWorkspaceType.getLabel(), workspaceId, projectId);
            try
            {
                boolean deleted = GitLabApiTools.deleteBranchAndVerify(repositoryApi, gitLabProjectId.getGitLabId(), getWorkspaceBranchName(workspaceId, workspaceType, backupWorkspaceType), 20, 1_000);
                if (!deleted)
                {
                    LOGGER.error("Failed to delete {} {} in project {}", workspaceType.getLabel() + " " + backupWorkspaceType.getLabel(), workspaceId, projectId);
                }
            }
            catch (Exception e)
            {
                // unfortunate, but this should not throw error
                LOGGER.error("Error deleting {} {} in project {}", workspaceType.getLabel() + " " + backupWorkspaceType.getLabel(), workspaceId, projectId, e);
            }
        }
        // Delete workspace with conflict resolution with the same name if exists
        Branch conflictResolutionBranch = null;
        ProjectFileAccessProvider.WorkspaceAccessType conflictResolutionWorkspaceType = ProjectFileAccessProvider.WorkspaceAccessType.CONFLICT_RESOLUTION;
        try
        {
            conflictResolutionBranch = withRetries(() -> repositoryApi.getBranch(gitLabProjectId.getGitLabId(), getWorkspaceBranchName(workspaceId, workspaceType, conflictResolutionWorkspaceType)));
        }
        catch (Exception e)
        {
            if (!GitLabApiTools.isNotFoundGitLabApiException(e))
            {
                LOGGER.error("Error accessing {} {} in project {}", workspaceType.getLabel() + " " + conflictResolutionWorkspaceType.getLabel(), workspaceId, projectId, e);
            }
        }
        if (conflictResolutionBranch != null)
        {
            LOGGER.debug("Cleaning up left-over {} {} in project {}", workspaceType.getLabel() + " " + conflictResolutionWorkspaceType.getLabel(), workspaceId, projectId);
            try
            {
                boolean deleted = GitLabApiTools.deleteBranchAndVerify(repositoryApi, gitLabProjectId.getGitLabId(), getWorkspaceBranchName(workspaceId, workspaceType, conflictResolutionWorkspaceType), 20, 1_000);
                if (!deleted)
                {
                    LOGGER.error("Failed to delete {} {} in project {}", workspaceType.getLabel() + " " + conflictResolutionWorkspaceType.getLabel(), workspaceId, projectId);
                }
            }
            catch (Exception e)
            {
                // unfortunate, but this should not throw error
                LOGGER.error("Error deleting {} {} in project {}", workspaceType.getLabel() + " " + conflictResolutionWorkspaceType.getLabel(), workspaceId, projectId, e);
            }
        }
        // Create new workspace
        Branch branch;
        ProjectFileAccessProvider.WorkspaceAccessType workspaceAccessType = ProjectFileAccessProvider.WorkspaceAccessType.WORKSPACE;
        try
        {
            branch = GitLabApiTools.createBranchFromSourceBranchAndVerify(repositoryApi, gitLabProjectId.getGitLabId(), getWorkspaceBranchName(workspaceId, workspaceType, workspaceAccessType), getDefaultBranch(gitLabProjectId), 30, 1_000);
        }
        catch (Exception e)
        {
            throw buildException(e,
                    () -> "User " + getCurrentUser() + " is not allowed to create workspace " + workspaceId + " in project " + projectId,
                    () -> "Unknown project: " + projectId,
                    () -> "Error creating " + workspaceType.getLabel() + " " + workspaceAccessType.getLabel() + " " + workspaceId + " in project " + projectId);
        }
        if (branch == null)
        {
            throw new LegendSDLCServerException("Failed to create " + workspaceType.getLabel() + " " + workspaceAccessType.getLabel() + " " + workspaceId + " in project " + projectId);
        }
        return workspaceBranchToWorkspace(projectId, branch, workspaceType, workspaceAccessType);
    }

    /**
     * When we delete a workspace, we also need to remember to delete the conflict resolution and backup workspaces
     */
    @Override
    public void deleteWorkspace(String projectId, String workspaceId, WorkspaceType workspaceType)
    {
        LegendSDLCServerException.validateNonNull(projectId, "projectId may not be null");
        LegendSDLCServerException.validateNonNull(workspaceId, "workspaceId may not be null");

        GitLabProjectId gitLabProjectId = parseProjectId(projectId);
        RepositoryApi repositoryApi = getGitLabApi().getRepositoryApi();
        // Delete workspace
        boolean workspaceDeleted;
        ProjectFileAccessProvider.WorkspaceAccessType workspaceAccessType = ProjectFileAccessProvider.WorkspaceAccessType.WORKSPACE;
        try
        {
            workspaceDeleted = GitLabApiTools.deleteBranchAndVerify(repositoryApi, gitLabProjectId.getGitLabId(), getWorkspaceBranchName(workspaceId, workspaceType, workspaceAccessType), 20, 1_000);
        }
        catch (Exception e)
        {
            throw buildException(e,
                    () -> "User " + getCurrentUser() + " is not allowed to delete " + workspaceType.getLabel() + " " + workspaceAccessType.getLabel() + " " + workspaceId + " in project " + projectId,
                    () -> "Unknown " + workspaceType.getLabel() + " " + workspaceAccessType.getLabel() + " (" + workspaceId + ") or project (" + projectId + ")",
                    () -> "Error deleting " + workspaceType.getLabel() + " " + workspaceAccessType.getLabel() + " " + workspaceId + " in project " + projectId);
        }
        if (!workspaceDeleted)
        {
            throw new LegendSDLCServerException("Failed to delete " + workspaceType.getLabel() + " " + workspaceAccessType.getLabel() + " " + workspaceId + " in project " + projectId);
        }
        // Delete conflict resolution workspace
        ProjectFileAccessProvider.WorkspaceAccessType conflictResolutionWorkspaceType = ProjectFileAccessProvider.WorkspaceAccessType.CONFLICT_RESOLUTION;
        try
        {
            boolean deleted = GitLabApiTools.deleteBranchAndVerify(repositoryApi, gitLabProjectId.getGitLabId(), getWorkspaceBranchName(workspaceId, workspaceType, conflictResolutionWorkspaceType), 20, 1_000);
            if (!deleted)
            {
                LOGGER.error("Failed to delete {} {} in project {}", workspaceType.getLabel() + " " + conflictResolutionWorkspaceType.getLabel(), workspaceId, projectId);
            }
        }
        catch (Exception e)
        {
            // unfortunate, but this should not throw error
            LOGGER.error("Error deleting {} {} in project {}", workspaceType.getLabel() + " " + conflictResolutionWorkspaceType.getLabel(), workspaceId, projectId, e);
        }
        // Delete backup workspace
        ProjectFileAccessProvider.WorkspaceAccessType backupWorkspaceType = ProjectFileAccessProvider.WorkspaceAccessType.BACKUP;
        try
        {
            boolean deleted = GitLabApiTools.deleteBranchAndVerify(repositoryApi, gitLabProjectId.getGitLabId(), getWorkspaceBranchName(workspaceId, workspaceType, backupWorkspaceType), 20, 1_000);
            if (!deleted)
            {
                LOGGER.error("Failed to delete {} {} in project {}", workspaceType.getLabel() + " " + backupWorkspaceType.getLabel(), workspaceId, projectId);
            }
        }
        catch (Exception e)
        {
            // unfortunate, but this should not throw error
            LOGGER.error("Error deleting {} {} in project {}", workspaceType.getLabel() + " " + backupWorkspaceType.getLabel(), workspaceId, projectId, e);
        }
    }

    /**
     * There are 4 possible outcome for this method:
     * 1. NO_OP: If the workspace is already branching from the HEAD of master, nothing is needed.
     * 2. UPDATED: If the workspace is not branching from the HEAD of master, and we successfully rebase the branch to master HEAD.
     * 3. CONFLICT: If the workspace is not branching from the HEAD of master, and we failed to rebase the branch to master HEAD due to merge conflicts.
     * 4. ERROR
     * <p>
     * The procedure goes like the followings:
     * 1. Check if the current workspace is already up to date:
     * - If yes, return NO_OP
     * - If no, proceed
     * 2. Create a temp branch to attempt to rebase:
     * - If rebase succeeded, return UPDATED
     * - If rebase failed, further check if we need to enter conflict resolution mode.
     * This check makes sure the conflict that causes rebase to fail does not come from intermediate
     * commits by squashing these commits and attempt to do another rebase there. If this still fails
     * it means the workspace in overall truly has merge conflicts while updating, so entering conflict resolution mode
     */
    @Override
    public WorkspaceUpdateReport updateWorkspace(String projectId, String workspaceId, WorkspaceType workspaceType)
    {
        LegendSDLCServerException.validateNonNull(projectId, "projectId may not be null");
        LegendSDLCServerException.validateNonNull(workspaceId, "workspaceId may not be null");

        LOGGER.info("Updating workspace {} in project {} to latest revision", workspaceId, projectId);
        GitLabProjectId gitLabProjectId = parseProjectId(projectId);
        ProjectFileAccessProvider.WorkspaceAccessType workspaceAccessType = ProjectFileAccessProvider.WorkspaceAccessType.WORKSPACE;
        String workspaceBranchName = getBranchName(workspaceId, workspaceType, workspaceAccessType, gitLabProjectId);
        GitLabApi gitLabApi = getGitLabApi();
        RepositoryApi repositoryApi = gitLabApi.getRepositoryApi();

        // Get the workspace branch
        Branch workspaceBranch;
        try
        {
            workspaceBranch = withRetries(() -> repositoryApi.getBranch(gitLabProjectId.getGitLabId(), workspaceBranchName));
        }
        catch (Exception e)
        {
            throw buildException(e,
                    () -> "User " + getCurrentUser() + " is not allowed to access " + workspaceType.getLabel() + " " + workspaceAccessType.getLabel() + " " + workspaceId + " in project " + projectId,
                    () -> "Unknown " + workspaceType.getLabel() + " " + workspaceAccessType.getLabel() + " in project " + projectId + ": " + workspaceId,
                    () -> "Error accessing " + workspaceType.getLabel() + " " + workspaceAccessType.getLabel() + " " + workspaceId + " in project " + projectId);
        }
        String currentWorkspaceRevisionId = workspaceBranch.getCommit().getId();
        LOGGER.info("Found latest revision of {} {} in project {}: {}", workspaceType.getLabel() + " " + workspaceAccessType.getLabel(), workspaceId, projectId, currentWorkspaceRevisionId);

        // Determine the revision to update to
        Branch masterBranch;
        String defaultBranch = getDefaultBranch(gitLabProjectId);
        try
        {
            masterBranch = withRetries(() -> repositoryApi.getBranch(gitLabProjectId.getGitLabId(), defaultBranch));
        }
        catch (Exception e)
        {
            throw buildException(e,
                    () -> "User " + getCurrentUser() + " is not allowed to access the latest revision in project " + projectId,
                    () -> "Unknown project: " + projectId,
                    () -> "Error accessing latest revision for project " + projectId);
        }

        String masterRevisionId = masterBranch.getCommit().getId();
        LOGGER.info("Found latest revision of project {}: {}", projectId, masterRevisionId);
        CommitsApi commitsApi = gitLabApi.getCommitsApi();
        // Check if the workspace already has the latest revision
        try
        {
            boolean isAlreadyLatest = false;
            // This will check if the branch contains the master HEAD commit by looking up the list of references the commit is pushed to
            if (masterRevisionId.equals(currentWorkspaceRevisionId))
            {
                isAlreadyLatest = true;
            }
            else
            {
                Pager<CommitRef> masterHeadCommitRefPager = withRetries(() -> commitsApi.getCommitRefs(gitLabProjectId.getGitLabId(), masterRevisionId, RefType.BRANCH, ITEMS_PER_PAGE));
                Stream<CommitRef> masterHeadCommitRefs = PagerTools.stream(masterHeadCommitRefPager);
                if (masterHeadCommitRefs.anyMatch(cr -> workspaceBranchName.equals(cr.getName())))
                {
                    isAlreadyLatest = true;
                }
            }
            if (isAlreadyLatest)
            {
                // revision is already in the workspace, no update necessary, hence NO_OP
                LOGGER.info("Workspace {} in project {} already has revision {}, no update necessary", workspaceId, projectId, masterRevisionId);
                return createWorkspaceUpdateReport(WorkspaceUpdateReportStatus.NO_OP, masterRevisionId, currentWorkspaceRevisionId);
            }
        }
        catch (Exception e)
        {
            throw buildException(e,
                    () -> "User " + getCurrentUser() + " is not allowed to access revision " + masterRevisionId + " in project " + projectId,
                    () -> "Unknown revision in project " + projectId + ": " + masterRevisionId,
                    () -> "Error accessing revision " + masterRevisionId + " of project " + projectId);
        }

        // Temp branch for checking for merge conflicts
        String tempBranchName = newUserTemporaryBranchName();
        Branch tempBranch;
        try
        {
            tempBranch = GitLabApiTools.createBranchAndVerify(repositoryApi, gitLabProjectId.getGitLabId(), tempBranchName, currentWorkspaceRevisionId, 30, 1_000);
        }
        catch (Exception e)
        {
            throw buildException(e,
                    () -> "User " + getCurrentUser() + " is not allowed to create temporary workspace " + tempBranchName + " in project " + projectId,
                    () -> "Unknown project: " + projectId,
                    () -> "Error creating temporary workspace " + tempBranchName + " in project " + projectId);
        }
        if (tempBranch == null)
        {
            throw new LegendSDLCServerException("Failed to create temporary workspace " + tempBranchName + " in project " + projectId + " from revision " + currentWorkspaceRevisionId);
        }

        // Attempt to rebase the temporary branch on top of master
        boolean rebaseSucceeded = this.attemptToRebaseWorkspaceUsingTemporaryBranch(projectId, workspaceId, workspaceType, tempBranchName, masterRevisionId);
        // If fail to rebase, there could be 2 possible reasons:
        // 1. At least one of the intermediate commits on the workspace branch causes rebase to fail
        //      -> we need to squash the workspace branch and try rebase again
        //      to do this, we first check if we even need to squash by checking the number of commits on the workspace branch
        // 2. There are merge conflicts, so we enter conflict resolution route
        if (!rebaseSucceeded)
        {

            String workspaceCreationRevisionId;
            try
            {
                workspaceCreationRevisionId = withRetries(() -> repositoryApi.getMergeBase(gitLabProjectId.getGitLabId(), Arrays.asList(defaultBranch, currentWorkspaceRevisionId)).getId());
            }
            catch (Exception e)
            {
                throw buildException(e,
                        () -> "User " + getCurrentUser() + " is not allowed to get merged base revision for workspace " + workspaceId + " from project " + projectId,
                        () -> "Could not find revision " + currentWorkspaceRevisionId + " from project " + projectId,
                        () -> "Failed to fetch merged base revision for workspace " + workspaceId + " from project " + projectId);
            }
            // Small optimization step to make sure we need squashing.
            // If there are less than 2 commits (not including the base commit), there is no point in squashing
            List<Revision> latestTwoRevisionsOnWorkspaceBranch = this.revisionApi.getWorkspaceRevisionContext(projectId, workspaceId, workspaceType).getRevisions(null, null, null, 2);
            Set<String> latestTwoRevisionOnWorkspaceBranchIds = latestTwoRevisionsOnWorkspaceBranch.stream().map(Revision::getId).collect(Collectors.toSet());
            if (latestTwoRevisionOnWorkspaceBranchIds.contains(workspaceCreationRevisionId))
            {
                LOGGER.debug("Failed to rebase branch {}, but the branch does not have enough commits to perform squashing. Proceeding to conflict resolution...", workspaceBranchName);
                return this.createConflictResolution(projectId, workspaceId, workspaceType, masterRevisionId);
            }
            else
            {
                LOGGER.debug("Failed to rebase branch {}. Performing squashing commits and re-attempting rebase...", workspaceBranchName);
            }

            WorkspaceUpdateReport rebaseUpdateAttemptReport = this.attemptToSquashAndRebaseWorkspace(projectId, workspaceId, workspaceType, masterRevisionId, currentWorkspaceRevisionId, workspaceCreationRevisionId);
            return WorkspaceUpdateReportStatus.UPDATED.equals(rebaseUpdateAttemptReport.getStatus()) ? rebaseUpdateAttemptReport : this.createConflictResolution(projectId, workspaceId, workspaceType, masterRevisionId);
        }
        String updatedCurrentWorkspaceRevisionId = this.revisionApi.getWorkspaceRevisionContext(projectId, workspaceId, workspaceType).getCurrentRevision().getId();
        return createWorkspaceUpdateReport(WorkspaceUpdateReportStatus.UPDATED, masterRevisionId, updatedCurrentWorkspaceRevisionId);
    }

    /**
     * This method is called when we failed to rebase the workspace branch on top of master branch. This implies that either
     * the whole change is causing a merge conflict or one of the intermediate commits have conflicts with the change in master branch
     * <p>
     * To handle the latter case, this method will squash all commits on the workspace branch and attempt rebase again.
     * If this fails, it implies that rebase fails because of merge conflicts, which means we have to enter conflict resolution route.
     * <p>
     * NOTE: based on the nature of this method, we have an optimization here where we check for the number of commits on the current
     * branch, if it is less than 2 (not counting the base), it means no squashing is needed and we can just immediately tell that there
     * is merge conflict and the workspace update should enter conflict resolution route
     * <p>
     * So following is the summary of this method:
     * 1. Create a temp branch to do the squashing on that branch
     * 3. Attempt to rebase the temp branch on master:
     * - If succeeded: re-create current workspace branch on top of the temp branch
     * - If failed -> implies conflict resolution is needed
     *
     * @return a workspace update report that might have status as UPDATED or CONFLICT.
     */
    private WorkspaceUpdateReport attemptToSquashAndRebaseWorkspace(String projectId, String workspaceId, WorkspaceType workspaceType, String masterRevisionId, String currentWorkspaceRevisionId, String workspaceCreationRevisionId)
    {
        GitLabProjectId gitLabProjectId = parseProjectId(projectId);
        RepositoryApi repositoryApi = getGitLabApi().getRepositoryApi();
        // Create temp branch for rebasing
        String tempBranchName = newUserTemporaryBranchName();
        Branch tempBranch;
        try
        {
            tempBranch = GitLabApiTools.createBranchAndVerify(repositoryApi, gitLabProjectId.getGitLabId(), tempBranchName, workspaceCreationRevisionId, 30, 1_000);
        }
        catch (Exception e)
        {
            throw buildException(e,
                    () -> "User " + getCurrentUser() + " is not allowed to create temporary workspace " + tempBranchName + " in project " + projectId,
                    () -> "Unknown project: " + projectId,
                    () -> "Error creating temporary workspace " + tempBranchName + " in project " + projectId);
        }
        if (tempBranch == null)
        {
            throw new LegendSDLCServerException("Failed to create temporary workspace " + tempBranchName + " in project " + projectId + " from revision " + workspaceCreationRevisionId);
        }
        CompareResults comparisonResult;
        try
        {
            comparisonResult = withRetries(() -> repositoryApi.compare(gitLabProjectId.getGitLabId(), workspaceCreationRevisionId, currentWorkspaceRevisionId, true));
        }
        catch (Exception e)
        {
            throw buildException(e,
                    () -> "User " + getCurrentUser() + " is not allowed to get comparison information from revision " + workspaceCreationRevisionId + "  to revision " + currentWorkspaceRevisionId + " on project" + projectId,
                    () -> "Could not find revisions " + workspaceCreationRevisionId + " ," + currentWorkspaceRevisionId + " on project" + projectId,
                    () -> "Failed to fetch comparison information from revision " + workspaceCreationRevisionId + "  to revision " + currentWorkspaceRevisionId + " on project" + projectId);
        }
        // Create a new commit on temp branch that squashes all changes on the concerned workspace branch
        CommitsApi commitsApi = getGitLabApi().getCommitsApi();
        ProjectFileAccessProvider.WorkspaceAccessType workspaceAccessType = ProjectFileAccessProvider.WorkspaceAccessType.WORKSPACE;
        ProjectFileAccessProvider.FileAccessContext workspaceFileAccessContext = getProjectFileAccessProvider().getWorkspaceFileAccessContext(projectId, workspaceId, workspaceType, workspaceAccessType);
        Commit squashedCommit;
        try
        {
            List<CommitAction> commitActions = Lists.mutable.empty();
            comparisonResult.getDiffs().forEach(diff ->
            {
                if (diff.getDeletedFile())
                {
                    // DELETE
                    commitActions.add(new CommitAction().withAction(CommitAction.Action.DELETE).withFilePath(diff.getOldPath()));
                }
                else if (diff.getRenamedFile())
                {
                    // MOVE
                    //
                    // tl;dr;
                    // split this into a delete and a create to make sure the moved entity has the content of the entity
                    // in workspace HEAD revision
                    //
                    // Since we use comparison API to compute the diff, Git has a smart algorithm to calculate file move
                    // as it is a heuristics such that if file content is only slightly different, Git can conclude that the
                    // diff was of type RENAME.
                    //
                    // The problem with this is when we compare the workspace BASE revision and workspace HEAD revision
                    // Assume that we actually renamed an entity, we also want to update the entity path, not just its location
                    // the location part is correctly identified by Git, and the change in content is captured as a diff string
                    // in comparison result (which shows the change in the path)
                    // but when we create CommitAction, there is no way for us to apply this patch on top of the entity content
                    // so if we just create action of type MOVE for CommitAction, we will have the content of the old entity
                    // which has the wrong path, and thus this entity if continue to exist in the workspace will throw off
                    // our path and entity location validation check
                    commitActions.add(new CommitAction()
                            .withAction(CommitAction.Action.DELETE)
                            .withFilePath(diff.getOldPath())
                    );
                    commitActions.add(new CommitAction()
                            .withAction(CommitAction.Action.CREATE)
                            .withFilePath(diff.getNewPath())
                            .withEncoding(Constants.Encoding.BASE64)
                            .withContent(encodeBase64(workspaceFileAccessContext.getFile(diff.getNewPath()).getContentAsBytes()))
                    );
                }
                else if (diff.getNewFile())
                {
                    // CREATE
                    commitActions.add(new CommitAction()
                            .withAction(CommitAction.Action.CREATE)
                            .withFilePath(diff.getNewPath())
                            .withEncoding(Constants.Encoding.BASE64)
                            .withContent(encodeBase64(workspaceFileAccessContext.getFile(diff.getNewPath()).getContentAsBytes()))
                    );
                }
                else
                {
                    // UPDATE
                    commitActions.add(new CommitAction()
                            .withAction(CommitAction.Action.UPDATE)
                            .withFilePath(diff.getOldPath())
                            .withEncoding(Constants.Encoding.BASE64)
                            .withContent(encodeBase64(workspaceFileAccessContext.getFile(diff.getOldPath()).getContentAsBytes()))
                    );
                }
            });
            squashedCommit = commitsApi.createCommit(gitLabProjectId.getGitLabId(), tempBranchName, "aggregated changes for workspace " + workspaceId, null, null, getCurrentUser(), commitActions);
        }
        catch (Exception e)
        {
            throw buildException(e,
                    () -> "User " + getCurrentUser() + " is not allowed to create commit on temporary workspace " + tempBranchName + " of project " + projectId,
                    () -> "Unknown project: " + projectId + " or temporary workspace " + tempBranchName,
                    () -> "Failed to create commit in temporary workspace " + tempBranchName + " of project " + projectId);
        }
        // Attempt to rebase the temporary branch on top of master
        boolean attemptRebaseResult = this.attemptToRebaseWorkspaceUsingTemporaryBranch(projectId, workspaceId, workspaceType, tempBranchName, masterRevisionId);
        // If rebasing failed, this implies there are conflicts, otherwise, the workspace should be updated
        if (!attemptRebaseResult)
        {
            return createWorkspaceUpdateReport(WorkspaceUpdateReportStatus.CONFLICT, null, null);
        }
        return createWorkspaceUpdateReport(WorkspaceUpdateReportStatus.UPDATED, masterRevisionId, squashedCommit.getId());
    }

    /**
     * This method attempts to rebase the workspace branch on top of master by using a temp branch. Detailed procedure outlined below:
     * 1. Create a new merge request (MR) that merges temp branch into master branch so that we can use gitlab rebase functionality
     * 2. Call rebase.
     * 3. Continuously check the rebase status of the merge request:
     * - If failed -> return `false`
     * - If succeeded, proceed
     * 4. Re-create workspace branch on top of the rebased temp branch.
     * 5. Cleanup: remove the temp branch and the MR
     * 6. Return `true`
     *
     * @return a boolean flag indicating if the attempted rebase succeeded.
     */
    private boolean attemptToRebaseWorkspaceUsingTemporaryBranch(String projectId, String workspaceId, WorkspaceType workspaceType, String tempBranchName, String masterRevisionId)
    {
        GitLabProjectId gitLabProjectId = parseProjectId(projectId);
        GitLabApi gitLabApi = getGitLabApi();
        RepositoryApi repositoryApi = gitLabApi.getRepositoryApi();
        // Create merge request to rebase
        MergeRequestApi mergeRequestApi = getGitLabApi().getMergeRequestApi();
        String title = "Update workspace " + workspaceId;
        String message = "Update workspace " + workspaceId + " up to revision " + masterRevisionId;
        MergeRequest mergeRequest;
        try
        {
            mergeRequest = mergeRequestApi.createMergeRequest(gitLabProjectId.getGitLabId(), tempBranchName, getDefaultBranch(gitLabProjectId), title, message, null, null, null, null, false, false);
        }
        catch (Exception e)
        {
            throw buildException(e,
                    () -> "User " + getCurrentUser() + " is not allowed to create merge request in project " + projectId,
                    () -> "Unknown branch in project " + projectId + ": " + tempBranchName,
                    () -> "Error creating merge request in project " + projectId);
        }
        // Attempt to rebase the merge request
        try
        {
            mergeRequestApi.rebaseMergeRequest(gitLabProjectId.getGitLabId(), mergeRequest.getIid());
            // Check rebase status
            // This only throws when we have 403, so we need to keep polling till we know the result
            // See https://docs.gitlab.com/ee/api/merge_requests.html#rebase-a-merge-request
            CallUntil<MergeRequest, GitLabApiException> rebaseStatusCallUntil = CallUntil.callUntil(
                    () -> withRetries(() -> mergeRequestApi.getRebaseStatus(gitLabProjectId.getGitLabId(), mergeRequest.getIid())),
                    mr -> !mr.getRebaseInProgress(),
                    600,
                    1000L);
            if (!rebaseStatusCallUntil.succeeded())
            {
                LOGGER.warn("Timeout waiting for merge request " + mergeRequest.getIid() + " in project " + projectId + " to finish rebasing");
                return false;
            }
            // Check if there is merge conflict
            if (rebaseStatusCallUntil.getResult().getMergeError() != null)
            {
                return false;
            }
            // if there are no merge conflicts, proceed with the update
            else
            {
                // Create backup branch
                Branch backupBranch;
                ProjectFileAccessProvider.WorkspaceAccessType backupWorkspaceAccessType = ProjectFileAccessProvider.WorkspaceAccessType.BACKUP;
                ProjectFileAccessProvider.WorkspaceAccessType workspaceAccessType = ProjectFileAccessProvider.WorkspaceAccessType.WORKSPACE;
                try
                {
                    backupBranch = GitLabApiTools.createBranchFromSourceBranchAndVerify(repositoryApi, gitLabProjectId.getGitLabId(),
                            getWorkspaceBranchName(workspaceId, workspaceType, backupWorkspaceAccessType),
                            getWorkspaceBranchName(workspaceId, workspaceType, workspaceAccessType),
                            30, 1_000);
                }
                catch (Exception e)
                {
                    throw buildException(e,
                            () -> "User " + getCurrentUser() + " is not allowed to create " + workspaceType.getLabel() + " " + backupWorkspaceAccessType.getLabel() + " " + workspaceAccessType.getLabel() + " " + workspaceId + " in project " + projectId,
                            () -> "Unknown project: " + projectId,
                            () -> "Error creating " + workspaceType.getLabel() + " " + backupWorkspaceAccessType.getLabel() + " " + workspaceAccessType.getLabel() + " " + workspaceId + " in project " + projectId);
                }
                if (backupBranch == null)
                {
                    throw new LegendSDLCServerException("Failed to create " + workspaceType.getLabel() + " " + backupWorkspaceAccessType.getLabel() + " " + workspaceAccessType.getLabel() + " " + workspaceId + " from " + workspaceType.getLabel() + " " + workspaceAccessType.getLabel() + " " + workspaceId + " in project " + projectId);
                }
                // Delete original branch
                boolean originalBranchDeleted;
                try
                {
                    originalBranchDeleted = GitLabApiTools.deleteBranchAndVerify(repositoryApi, gitLabProjectId.getGitLabId(), getWorkspaceBranchName(workspaceId, workspaceType, workspaceAccessType), 20, 1_000);
                }
                catch (Exception e)
                {
                    throw buildException(e,
                            () -> "Error while attempting to update the workspace " + workspaceId + " in project " + projectId + ": user " + getCurrentUser() + " is not allowed to delete workspace",
                            () -> "Error while attempting to update the workspace " + workspaceId + " in project " + projectId + ": unknown workspace or project",
                            () -> "Error while attempting to update the workspace " + workspaceId + " in project " + projectId + ": error deleting workspace");
                }
                if (!originalBranchDeleted)
                {
                    throw new LegendSDLCServerException("Failed to delete " + workspaceType.getLabel() + " " + workspaceAccessType.getLabel() + " " + workspaceId + " in project " + projectId);
                }
                // Create new workspace branch off the temp branch head
                Branch newWorkspaceBranch;
                try
                {
                    newWorkspaceBranch = GitLabApiTools.createBranchFromSourceBranchAndVerify(repositoryApi, gitLabProjectId.getGitLabId(),
                            getWorkspaceBranchName(workspaceId, workspaceType, workspaceAccessType),
                            tempBranchName,
                            30, 1_000);
                }
                catch (Exception e)
                {
                    throw buildException(e,
                            () -> "Error while attempting to update the workspace " + workspaceId + " in project " + projectId + ": user " + getCurrentUser() + " is not allowed to create workspace",
                            () -> "Error while attempting to update the workspace " + workspaceId + " in project " + projectId + ": unknown project: " + projectId,
                            () -> "Error while attempting to update the workspace " + workspaceId + " in project " + projectId + ": error creating workspace");
                }
                if (newWorkspaceBranch == null)
                {
                    throw new LegendSDLCServerException("Failed to create " + workspaceType.getLabel() + " " + workspaceAccessType.getLabel() + " " + workspaceId + " from temporary workspace " + tempBranchName + " in project " + projectId);
                }
                // Delete backup branch
                try
                {
                    boolean deleted = GitLabApiTools.deleteBranchAndVerify(repositoryApi, gitLabProjectId.getGitLabId(), getWorkspaceBranchName(workspaceId, workspaceType, backupWorkspaceAccessType), 20, 1_000);
                    if (!deleted)
                    {
                        LOGGER.error("Failed to delete {} {} in project {}", workspaceType.getLabel() + " " + backupWorkspaceAccessType.getLabel() + " " + workspaceAccessType.getLabel(), workspaceId, projectId);
                    }
                }
                catch (Exception e)
                {
                    // unfortunate, but this should not throw error
                    LOGGER.error("Error deleting {} {} in project {}", workspaceType.getLabel() + " " + backupWorkspaceAccessType.getLabel() + " " + workspaceAccessType.getLabel(), workspaceId, projectId);
                }
            }
        }
        catch (Exception e)
        {
            throw buildException(e,
                    () -> "User " + getCurrentUser() + " is not allowed to rebase merge request " + mergeRequest.getIid() + " in project " + projectId,
                    () -> "Unknown merge request ( " + mergeRequest.getIid() + " ) or project ( " + projectId + " )",
                    () -> "Error rebasing merge request " + mergeRequest.getIid() + " in project " + projectId);
        }
        finally
        {
            // Try to close merge request
            try
            {
                mergeRequestApi.updateMergeRequest(gitLabProjectId.getGitLabId(), mergeRequest.getIid(), null, title, null, null, StateEvent.CLOSE, null, null, null, null, null, null);
            }
            catch (Exception closeEx)
            {
                // if we fail, log the error but we don't throw it
                LOGGER.error("Could not close merge request {} for project {}: {}", mergeRequest.getIid(), projectId, mergeRequest.getWebUrl(), closeEx);
            }
            // Delete temporary branch in the background
            submitBackgroundRetryableTask(() -> waitForPipelinesDeleteBranchAndVerify(gitLabApi, gitLabProjectId, tempBranchName), 5000L, "delete " + tempBranchName);
        }
        return true;
    }

    /**
     * This method will mark create a conflict resolution branch from a given workspace branch. Assume we have workspace branch `w1`, this method will:
     * 1. Create resolution branch from `master` branch (check if that's the latest)
     * 2. Get all the changes of workspace branch `w1`
     * 3. Copy and replace those changes to resolution branch `w1` and create a new commit out of that.
     */
    private WorkspaceUpdateReport createConflictResolution(String projectId, String workspaceId, WorkspaceType workspaceType, String masterRevisionId)
    {
        // Check if conflict resolution is happening, if it is, it means conflict resolution branch already existed, so we will
        // scrap that branch and create a new one.
        GitLabProjectId gitLabProjectId = parseProjectId(projectId);
        RepositoryApi repositoryApi = getGitLabApi().getRepositoryApi();
        Branch previousConflictResolutionBranch = null;
        ProjectFileAccessProvider.WorkspaceAccessType conflictResolutionWorkspaceType = ProjectFileAccessProvider.WorkspaceAccessType.CONFLICT_RESOLUTION;
        try
        {
            previousConflictResolutionBranch = withRetries(() -> repositoryApi.getBranch(gitLabProjectId.getGitLabId(), getWorkspaceBranchName(workspaceId, workspaceType, conflictResolutionWorkspaceType)));
        }
        catch (Exception e)
        {
            if (!GitLabApiTools.isNotFoundGitLabApiException(e))
            {
                LOGGER.error("Error updating {} {} in project {}", workspaceType.getLabel() + " " + conflictResolutionWorkspaceType.getLabel(), workspaceId, projectId);
            }
        }
        // Delete conflict resolution workspace
        if (previousConflictResolutionBranch != null)
        {
            LOGGER.debug("Conflict resolution already happened in workspace {} in project {}, but we will recreate this conflict resolution workspace to make sure it's up to date", workspaceId, projectId);
            boolean conflictResolutionBranchDeleted;
            try
            {
                conflictResolutionBranchDeleted = GitLabApiTools.deleteBranchAndVerify(repositoryApi, gitLabProjectId.getGitLabId(), getWorkspaceBranchName(workspaceId, workspaceType, conflictResolutionWorkspaceType), 20, 1_000);
            }
            catch (Exception e)
            {
                throw buildException(e,
                        () -> "User " + getCurrentUser() + " is not allowed to delete " + workspaceType.getLabel() + " " + conflictResolutionWorkspaceType.getLabel() + " " + workspaceId + " in project " + projectId,
                        () -> "Unknown " + workspaceType.getLabel() + " " + conflictResolutionWorkspaceType.getLabel() + " (" + workspaceId + ") or project (" + projectId + ")",
                        () -> "Error deleting " + workspaceType.getLabel() + " " + conflictResolutionWorkspaceType.getLabel() + " " + workspaceId + " in project " + projectId);
            }
            if (!conflictResolutionBranchDeleted)
            {
                throw new LegendSDLCServerException("Failed to delete " + workspaceType.getLabel() + " " + conflictResolutionWorkspaceType.getLabel() + " " + workspaceId + " in project " + projectId);
            }
        }
        // Create conflict resolution workspace
        Branch conflictResolutionBranch;
        String defaultBranch = getDefaultBranch(gitLabProjectId);
        try
        {
            conflictResolutionBranch = GitLabApiTools.createBranchFromSourceBranchAndVerify(repositoryApi, gitLabProjectId.getGitLabId(), getWorkspaceBranchName(workspaceId, workspaceType, conflictResolutionWorkspaceType), defaultBranch, 30, 1_000);
        }
        catch (Exception e)
        {
            throw buildException(e,
                    () -> "User " + getCurrentUser() + " is not allowed to create workspace " + workspaceId + " in project " + projectId,
                    () -> "Unknown project: " + projectId,
                    () -> "Error creating workspace " + workspaceId + " in project " + projectId);
        }
        if (conflictResolutionBranch == null)
        {
            throw new LegendSDLCServerException("Failed to create " + workspaceType.getLabel() + " " + conflictResolutionWorkspaceType.getLabel() + " " + workspaceId + " in project " + projectId);
        }
        // Get the changes of the current workspace
        String currentWorkspaceRevisionId = this.revisionApi.getWorkspaceRevisionContext(projectId, workspaceId, workspaceType).getCurrentRevision().getId();
        String workspaceCreationRevisionId;

        try
        {
            workspaceCreationRevisionId = withRetries(() -> repositoryApi.getMergeBase(gitLabProjectId.getGitLabId(), Arrays.asList(defaultBranch, currentWorkspaceRevisionId)).getId());
        }
        catch (Exception e)
        {
            throw buildException(e,
                    () -> "User " + getCurrentUser() + " is not allowed to get merged base revision for revisions " + defaultBranch + ", " + currentWorkspaceRevisionId + " from project " + projectId,
                    () -> "Could not find revisions " + defaultBranch + ", " + currentWorkspaceRevisionId + " from project " + projectId,
                    () -> "Failed to fetch merged base information for revisions " + defaultBranch + ", " + currentWorkspaceRevisionId + " from project " + projectId);
        }
        CompareResults comparisonResult;
        try
        {
            comparisonResult = withRetries(() -> repositoryApi.compare(gitLabProjectId.getGitLabId(), workspaceCreationRevisionId, currentWorkspaceRevisionId, true));
        }
        catch (Exception e)
        {
            throw buildException(e,
                    () -> "User " + getCurrentUser() + " is not allowed to get comparison information from revision " + workspaceCreationRevisionId + "  to revision " + currentWorkspaceRevisionId + " on project" + projectId,
                    () -> "Could not find revisions " + workspaceCreationRevisionId + " ," + currentWorkspaceRevisionId + " on project" + projectId,
                    () -> "Failed to fetch comparison information from revision " + workspaceCreationRevisionId + "  to revision " + currentWorkspaceRevisionId + " on project" + projectId);
        }
        List<Diff> diffs = comparisonResult.getDiffs();

        // Create a new commit on conflict resolution branch
        CommitsApi commitsApi = getGitLabApi().getCommitsApi();
        ProjectFileAccessProvider.FileAccessContext projectFileAccessContext = getProjectFileAccessProvider().getProjectFileAccessContext(projectId);
        ProjectFileAccessProvider.WorkspaceAccessType workspaceAccessType = ProjectFileAccessProvider.WorkspaceAccessType.WORKSPACE;
        ProjectFileAccessProvider.FileAccessContext workspaceFileAccessContext = getProjectFileAccessProvider().getWorkspaceFileAccessContext(projectId, workspaceId, workspaceType, workspaceAccessType);
        try
        {
            List<CommitAction> commitActions = Lists.mutable.empty();
            // NOTE: we are bringing the diffs from the workspace and applying those diffs to the project HEAD. Now, the project
            // HEAD could potentially differ greatly from the workspace base revision. This means that when we create the commit
            // action from the diff, we must be careful about the action type.
            // Take for example DELETE: if according to the diff, we delete file A, but at project HEAD, A is already deleted
            // in one of the commits between workspace base and project HEAD, such DELETE commit action will fail, this should then be
            // a NO_OP. Then again, we will have to be careful when CREATE, MOVE, and UPDATE.
            diffs.forEach(diff ->
            {
                if (diff.getDeletedFile())
                {
                    // Ensure the file to delete exists at project HEAD
                    ProjectFileAccessProvider.ProjectFile fileToDelete = projectFileAccessContext.getFile(diff.getOldPath());
                    if (fileToDelete != null)
                    {
                        commitActions.add(new CommitAction()
                                .withAction(CommitAction.Action.DELETE)
                                .withFilePath(diff.getOldPath())
                        );
                    }
                }
                else if (diff.getRenamedFile())
                {
                    // split a MOVE into a DELETE followed by an CREATE/UPDATE to handle cases when
                    // file to be moved is already deleted at project HEAD
                    // and file to be moved to is already created at project HEAD
                    ProjectFileAccessProvider.ProjectFile fileToDelete = projectFileAccessContext.getFile(diff.getOldPath());
                    ProjectFileAccessProvider.ProjectFile fileToReplace = projectFileAccessContext.getFile(diff.getNewPath());
                    if (fileToDelete != null)
                    {
                        commitActions.add(new CommitAction()
                                .withAction(CommitAction.Action.DELETE)
                                .withFilePath(diff.getOldPath())
                        );
                    }
                    commitActions.add(new CommitAction()
                            .withAction(fileToReplace == null ? CommitAction.Action.CREATE : CommitAction.Action.UPDATE)
                            .withFilePath(diff.getNewPath())
                            .withEncoding(Constants.Encoding.BASE64)
                            .withContent(encodeBase64(workspaceFileAccessContext.getFile(diff.getNewPath()).getContentAsBytes()))
                    );
                }
                else if (diff.getNewFile())
                {
                    // If the file to be created already exists at project HEAD, change this to an UPDATE
                    ProjectFileAccessProvider.ProjectFile fileToCreate = projectFileAccessContext.getFile(diff.getOldPath());
                    commitActions.add(new CommitAction()
                            .withAction(fileToCreate == null ? CommitAction.Action.CREATE : CommitAction.Action.UPDATE)
                            .withFilePath(diff.getNewPath())
                            .withEncoding(Constants.Encoding.BASE64)
                            .withContent(encodeBase64(workspaceFileAccessContext.getFile(diff.getNewPath()).getContentAsBytes()))
                    );
                }
                else
                {
                    // File was updated
                    // If the file to be updated is deleted at project HEAD, change this to a CREATE
                    ProjectFileAccessProvider.ProjectFile fileToUpdate = projectFileAccessContext.getFile(diff.getOldPath());
                    commitActions.add(new CommitAction()
                            .withAction(fileToUpdate == null ? CommitAction.Action.CREATE : CommitAction.Action.UPDATE)
                            .withFilePath(diff.getOldPath())
                            .withEncoding(Constants.Encoding.BASE64)
                            .withContent(encodeBase64(workspaceFileAccessContext.getFile(diff.getOldPath()).getContentAsBytes()))
                    );
                }
            });
            commitsApi.createCommit(gitLabProjectId.getGitLabId(), this.getWorkspaceBranchName(workspaceId, workspaceType, conflictResolutionWorkspaceType),
                    "aggregated changes for conflict resolution", null, null, getCurrentUser(), commitActions);
        }
        catch (Exception e)
        {
            throw buildException(e,
                    () -> "User " + getCurrentUser() + " is not allowed to create commit on " + workspaceType.getLabel() + " " + conflictResolutionWorkspaceType.getLabel() + workspaceId + " of project " + projectId,
                    () -> "Unknown project: " + projectId + " or " + workspaceType.getLabel() + " " + conflictResolutionWorkspaceType.getLabel() + " " + workspaceId,
                    () -> "Failed to create commit in " + workspaceType.getLabel() + " " + conflictResolutionWorkspaceType.getLabel() + workspaceId + " of project" + projectId);
        }

        return createWorkspaceUpdateReport(WorkspaceUpdateReportStatus.CONFLICT, masterRevisionId, conflictResolutionBranch.getCommit().getId());
    }

    private WorkspaceUpdateReport createWorkspaceUpdateReport(WorkspaceUpdateReportStatus status, String workspaceMergeBaseRevisionId, String workspaceRevisionId)
    {
        return new WorkspaceUpdateReport()
        {
            @Override
            public WorkspaceUpdateReportStatus getStatus()
            {
                return status;
            }

            @Override
            public String getWorkspaceMergeBaseRevisionId()
            {
                return workspaceMergeBaseRevisionId;
            }

            @Override
            public String getWorkspaceRevisionId()
            {
                return workspaceRevisionId;
            }
        };
    }

    private static Workspace workspaceBranchToWorkspace(String projectId, Branch branch, WorkspaceType workspaceType, ProjectFileAccessProvider.WorkspaceAccessType workspaceAccessType)
    {
        return (branch == null) ? null : fromWorkspaceBranchName(projectId, branch.getName(), workspaceType, workspaceAccessType);
    }

    private static void validateWorkspaceId(String idString)
    {
        validateWorkspaceId(idString, null);
    }

    private static void validateWorkspaceId(String idString, Status errorStatus)
    {
        if (!isValidWorkspaceId(idString))
        {
            throw new LegendSDLCServerException("Invalid workspace id: \"" + idString + "\". A workspace id must be a non-empty string consisting of characters from the following set: {a-z, A-Z, 0-9, _, ., -}. The id may not contain \"..\" and may not start or end with '.' or '-'.", (errorStatus == null) ? Status.BAD_REQUEST : errorStatus);
        }
    }

    private static boolean isValidWorkspaceId(String string)
    {
        if ((string == null) || string.isEmpty())
        {
            return false;
        }

        if (!isValidWorkspaceStartEndChar(string.charAt(0)))
        {
            return false;
        }
        int lastIndex = string.length() - 1;
        for (int i = 1; i < lastIndex; i++)
        {
            char c = string.charAt(i);
            boolean isValid = isValidWorkspaceStartEndChar(c) || (c == '-') || ((c == '.') && (string.charAt(i - 1) != '.'));
            if (!isValid)
            {
                return false;
            }
        }
        return isValidWorkspaceStartEndChar(string.charAt(lastIndex));
    }

    private static boolean isValidWorkspaceStartEndChar(char c)
    {
        return (c == '_') || (('a' <= c) && (c <= 'z')) || (('A' <= c) && (c <= 'Z')) || (('0' <= c) && (c <= '9'));
    }
}
