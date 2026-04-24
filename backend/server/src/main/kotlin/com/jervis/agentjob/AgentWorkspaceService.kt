package com.jervis.agentjob

import com.jervis.common.types.AgentJobId
import com.jervis.git.service.GitRepositoryService
import com.jervis.infrastructure.storage.DirectoryStructureService
import com.jervis.project.ProjectDocument
import com.jervis.project.ProjectResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

/**
 * Prepares per-agent-job git workspaces on top of the shared per-project
 * base clone. Every AGENT_JOB record that needs code access asks this
 * service for its own `git worktree` — isolated working tree + own
 * branch, sharing the base `.git/` with the maintained project clone.
 *
 * Concurrency model:
 *  - The shared project clone (`git/`) is managed by [GitRepositoryService.ensureAgentWorkspaceReady]
 *    which guarantees it's present and on the default branch.
 *  - Adding / removing worktrees under `git/` mutates `.git/index` and
 *    `.git/worktrees/`. Git already locks `.git/index.lock` atomically
 *    (rename-based, safe on NFS/Longhorn PVCs), so two concurrent
 *    `git worktree add` invocations serialise at the filesystem level.
 *  - When the loser hits "index.lock exists" we back off and retry
 *    (exponential, max 5 attempts). No Mongo distributed lock — the
 *    native git lock is correct and self-clearing.
 *
 * Lifecycle:
 *  1. Dispatcher: [prepareWorktreeForJob] — creates branch from remote default,
 *     adds worktree, returns writable path that the K8s Job will mount.
 *  2. Watcher on Job completion: [releaseWorktreeForJob] — removes the
 *     worktree (the branch ref stays, the push is already on remote).
 *  3. [fetchAfterJobCompletion] — refreshes the base clone so subsequent
 *     planners see the new remote branch / commits (also picks up any
 *     concurrent work from other agents or humans).
 *
 * This service does NOT decide which branch to use or how to name it —
 * it takes `branchName` from the caller (the dispatcher reads
 * `ProjectRules.branchNaming`).
 */
@Service
class AgentWorkspaceService(
    private val directoryStructureService: DirectoryStructureService,
    private val gitRepositoryService: GitRepositoryService,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Materialise a worktree for a single agent Job.
     *
     * Steps (idempotent for the base, not for the worktree path):
     *  1. Ensure the base clone exists and is on default branch (delegated).
     *  2. `git worktree add <worktree-path> -b <branchName> origin/<defaultBranch>`
     *     — creates the new branch from the up-to-date remote tip.
     *  3. Returns the worktree path the dispatcher mounts into the K8s Job.
     *
     * Retry: the `worktree add` step retries up to 5 times on
     * `.git/index.lock exists` conflicts (exponential backoff starting
     * at 500 ms). Any other error fails fast.
     *
     * @throws IllegalStateException if the worktree path already exists
     *         (previous Job was not cleaned up — caller must call
     *         [releaseWorktreeForJob] before retrying).
     */
    suspend fun prepareWorktreeForJob(
        project: ProjectDocument,
        resource: ProjectResource,
        agentJobId: AgentJobId,
        branchName: String,
    ): Path {
        val worktreePath = directoryStructureService.agentJobWorkspaceDir(
            project.clientId,
            project.id,
            agentJobId,
        )
        if (Files.exists(worktreePath)) {
            throw IllegalStateException(
                "Worktree path already exists: $worktreePath — release it before dispatching a new Job for id=$agentJobId",
            )
        }

        val baseDir = gitRepositoryService.ensureAgentWorkspaceReady(project, resource)
        val defaultBranch = detectDefaultBranchInBase(baseDir)

        logger.info {
            "prepareWorktreeForJob | project=${project.name} resource=${resource.resourceIdentifier} " +
                "job=$agentJobId base=$baseDir worktree=$worktreePath branch=$branchName from=origin/$defaultBranch"
        }

        Files.createDirectories(worktreePath.parent)
        addWorktreeWithRetry(
            baseDir = baseDir,
            worktreePath = worktreePath,
            branchName = branchName,
            from = "origin/$defaultBranch",
        )

        return worktreePath
    }

    /**
     * Remove the worktree once the K8s Job is done. The branch and any
     * commits already pushed to origin remain — the user merges them
     * (or not) in the production repo manually.
     *
     * `--force` is used because the Job may leave local modifications
     * (untracked files, uncommitted staged changes) if it crashed;
     * those are irrelevant once the desired commits have reached
     * origin.
     */
    suspend fun releaseWorktreeForJob(
        project: ProjectDocument,
        agentJobId: AgentJobId,
    ) {
        val worktreePath = directoryStructureService.agentJobWorkspaceDir(
            project.clientId,
            project.id,
            agentJobId,
        )
        val baseDir = directoryStructureService.projectGitDir(project)

        if (!Files.exists(worktreePath)) {
            logger.info { "releaseWorktreeForJob | nothing to remove for job=$agentJobId (path missing)" }
            return
        }

        logger.info { "releaseWorktreeForJob | job=$agentJobId base=$baseDir worktree=$worktreePath" }

        withContext(Dispatchers.IO) {
            val result = gitRepositoryService.executeGitCommand(
                command = listOf("git", "worktree", "remove", "--force", worktreePath.toString()),
                workingDir = baseDir,
            )
            if (!result.success) {
                logger.warn {
                    "git worktree remove failed for job=$agentJobId — falling back to manual cleanup. git output: ${result.output}"
                }
                // Force-delete the dir even if `git worktree remove` bailed; stale
                // `.git/worktrees/<name>` metadata is cleaned by `git worktree prune`
                // on the next fetch cycle.
                deleteRecursively(worktreePath)
                gitRepositoryService.executeGitCommand(
                    command = listOf("git", "worktree", "prune"),
                    workingDir = baseDir,
                )
            }
        }
    }

    /**
     * Refresh the base clone after a Job commits + pushes. Without this,
     * subsequent planners would not see the branch the Job just created
     * on origin, and the base clone would drift behind reality.
     */
    suspend fun fetchAfterJobCompletion(
        project: ProjectDocument,
        resource: ProjectResource,
    ) {
        val baseDir = gitRepositoryService.ensureAgentWorkspaceReady(project, resource)
        withContext(Dispatchers.IO) {
            val result = gitRepositoryService.executeGitCommand(
                command = listOf("git", "fetch", "--all", "--prune"),
                workingDir = baseDir,
            )
            if (!result.success) {
                logger.warn {
                    "fetchAfterJobCompletion | base=$baseDir fetch failed: ${result.output}"
                }
            } else {
                logger.debug { "fetchAfterJobCompletion | base=$baseDir fetched" }
            }
        }
    }

    private suspend fun addWorktreeWithRetry(
        baseDir: Path,
        worktreePath: Path,
        branchName: String,
        from: String,
    ) {
        val command = listOf(
            "git", "worktree", "add",
            worktreePath.toString(),
            "-b", branchName,
            from,
        )
        val maxAttempts = 5
        var attempt = 0
        while (true) {
            val result = withContext(Dispatchers.IO) {
                gitRepositoryService.executeGitCommand(command = command, workingDir = baseDir)
            }
            if (result.success) {
                logger.debug { "git worktree add succeeded on attempt ${attempt + 1}" }
                return
            }
            val looksLikeLockContention = result.output.contains("index.lock") ||
                result.output.contains("cannot lock ref") ||
                result.output.contains("unable to create") && result.output.contains(".lock")
            if (!looksLikeLockContention || attempt >= maxAttempts - 1) {
                throw IllegalStateException(
                    "git worktree add failed (attempt ${attempt + 1}/$maxAttempts): ${result.output.trim()}",
                )
            }
            val backoff = 500L * (1L shl attempt)
            logger.debug { "git worktree add: lock contention, retrying in ${backoff}ms (attempt ${attempt + 1})" }
            delay(backoff)
            attempt++
        }
    }

    private suspend fun detectDefaultBranchInBase(baseDir: Path): String = withContext(Dispatchers.IO) {
        val result = gitRepositoryService.executeGitCommand(
            command = listOf("git", "symbolic-ref", "refs/remotes/origin/HEAD", "--short"),
            workingDir = baseDir,
        )
        if (result.success) {
            // output is "origin/<branch>"
            result.output.trim().removePrefix("origin/")
        } else {
            // Fallback: attempt `main`, then `master`.
            val mainCheck = gitRepositoryService.executeGitCommand(
                command = listOf("git", "show-ref", "--verify", "--quiet", "refs/remotes/origin/main"),
                workingDir = baseDir,
            )
            if (mainCheck.success) "main" else "master"
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
