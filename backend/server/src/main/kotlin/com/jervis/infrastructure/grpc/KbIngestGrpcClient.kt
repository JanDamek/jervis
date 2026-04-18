package com.jervis.infrastructure.grpc

import com.jervis.contracts.common.RequestContext
import com.jervis.contracts.common.Scope
import com.jervis.contracts.knowledgebase.CpgIngestRequest as ProtoCpgIngestRequest
import com.jervis.contracts.knowledgebase.CpgIngestResult
import com.jervis.contracts.knowledgebase.GitBranchInfo
import com.jervis.contracts.knowledgebase.GitClassInfo
import com.jervis.contracts.knowledgebase.GitCommitIngestRequest as ProtoGitCommitIngestRequest
import com.jervis.contracts.knowledgebase.GitCommitIngestResult
import com.jervis.contracts.knowledgebase.GitCommitInfo
import com.jervis.contracts.knowledgebase.GitFileContent
import com.jervis.contracts.knowledgebase.GitFileInfo
import com.jervis.contracts.knowledgebase.GitStructureIngestRequest as ProtoGitStructureIngestRequest
import com.jervis.contracts.knowledgebase.GitStructureIngestResult
import com.jervis.contracts.knowledgebase.KnowledgeIngestServiceGrpcKt
import com.jervis.contracts.knowledgebase.PurgeRequest as ProtoPurgeRequest
import com.jervis.contracts.knowledgebase.PurgeResult
import com.jervis.knowledgebase.model.CpgIngestRequest
import com.jervis.knowledgebase.model.GitCommitIngestRequest
import com.jervis.knowledgebase.model.GitStructureIngestRequest
import io.grpc.ManagedChannel
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.UUID

// Kotlin-side client for jervis.knowledgebase.KnowledgeIngestService. Only
// the Kotlin-only RPCs are wrapped here (IngestGitStructure, IngestGitCommits,
// IngestCpg) — the rest stay on FastAPI until the corresponding Phase 2
// slice migrates their callers (orchestrator, MCP, correction).
@Component
class KbIngestGrpcClient(
    @Qualifier(GrpcChannels.KNOWLEDGEBASE_CHANNEL) channel: ManagedChannel,
) {
    private val stub = KnowledgeIngestServiceGrpcKt.KnowledgeIngestServiceCoroutineStub(channel)

    private fun ctx(clientId: String = ""): RequestContext =
        RequestContext.newBuilder()
            .setScope(Scope.newBuilder().setClientId(clientId).build())
            .setRequestId(UUID.randomUUID().toString())
            .setIssuedAtUnixMs(System.currentTimeMillis())
            .build()

    suspend fun ingestGitStructure(request: GitStructureIngestRequest): GitStructureIngestResult {
        val builder = ProtoGitStructureIngestRequest.newBuilder()
            .setCtx(ctx(request.clientId))
            .setClientId(request.clientId)
            .setProjectId(request.projectId)
            .setRepositoryIdentifier(request.repositoryIdentifier)
            .setBranch(request.branch)
            .setDefaultBranch(request.defaultBranch)
        request.branches.forEach { b ->
            builder.addBranches(
                GitBranchInfo.newBuilder()
                    .setName(b.name)
                    .setIsDefault(b.isDefault)
                    .setStatus(b.status)
                    .setLastCommitHash(b.lastCommitHash)
                    .build(),
            )
        }
        request.files.forEach { f ->
            builder.addFiles(
                GitFileInfo.newBuilder()
                    .setPath(f.path)
                    .setExtension(f.extension)
                    .setLanguage(f.language)
                    .setSizeBytes(f.sizeBytes)
                    .build(),
            )
        }
        request.classes.forEach { c ->
            builder.addClasses(
                GitClassInfo.newBuilder()
                    .setName(c.name)
                    .setQualifiedName(c.qualifiedName)
                    .setFilePath(c.filePath)
                    .setVisibility(c.visibility)
                    .setIsInterface(c.isInterface)
                    .addAllMethods(c.methods)
                    .build(),
            )
        }
        request.fileContents.forEach { fc ->
            builder.addFileContents(
                GitFileContent.newBuilder()
                    .setPath(fc.path)
                    .setContent(fc.content)
                    .build(),
            )
        }
        builder.putAllMetadata(request.metadata)
        return stub.ingestGitStructure(builder.build())
    }

    suspend fun ingestGitCommits(request: GitCommitIngestRequest): GitCommitIngestResult {
        val builder = ProtoGitCommitIngestRequest.newBuilder()
            .setCtx(ctx(request.clientId))
            .setClientId(request.clientId)
            .setProjectId(request.projectId)
            .setRepositoryIdentifier(request.repositoryIdentifier)
            .setBranch(request.branch)
            .setDiffContent(request.diffContent ?: "")
        request.commits.forEach { c ->
            builder.addCommits(
                GitCommitInfo.newBuilder()
                    .setHash(c.hash)
                    .setMessage(c.message)
                    .setAuthor(c.author)
                    .setDate(c.date)
                    .setBranch(c.branch)
                    .setParentHash(c.parentHash ?: "")
                    .addAllFilesModified(c.filesModified)
                    .addAllFilesCreated(c.filesCreated)
                    .addAllFilesDeleted(c.filesDeleted)
                    .build(),
            )
        }
        return stub.ingestGitCommits(builder.build())
    }

    suspend fun ingestCpg(request: CpgIngestRequest): CpgIngestResult =
        stub.ingestCpg(
            ProtoCpgIngestRequest.newBuilder()
                .setCtx(ctx(request.clientId))
                .setClientId(request.clientId)
                .setProjectId(request.projectId)
                .setBranch(request.branch)
                .setWorkspacePath(request.workspacePath)
                .build(),
        )

    suspend fun purge(sourceUrn: String, clientId: String = ""): PurgeResult =
        stub.purge(
            ProtoPurgeRequest.newBuilder()
                .setCtx(ctx(clientId))
                .setSourceUrn(sourceUrn)
                .setClientId(clientId)
                .build(),
        )
}
