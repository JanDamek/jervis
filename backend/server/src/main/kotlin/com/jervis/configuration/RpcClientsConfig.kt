package com.jervis.configuration

import com.jervis.common.client.IAtlassianClient
import com.jervis.common.client.IBugTrackerClient
import com.jervis.common.client.ITikaClient
import com.jervis.common.client.IWikiClient
import com.jervis.common.dto.atlassian.*
import com.jervis.common.dto.bugtracker.*
import com.jervis.common.dto.wiki.*
import com.jervis.configuration.properties.EndpointProperties
import com.jervis.knowledgebase.KnowledgeService
import com.jervis.knowledgebase.model.EvidencePack
import com.jervis.knowledgebase.model.FullIngestRequest
import com.jervis.knowledgebase.model.FullIngestResult
import com.jervis.knowledgebase.model.IngestRequest
import com.jervis.knowledgebase.model.IngestResult
import com.jervis.knowledgebase.model.RetrievalRequest
import com.jervis.knowledgebase.service.graphdb.model.GraphNode
import com.jervis.knowledgebase.service.graphdb.model.TraversalSpec
import com.jervis.common.types.ClientId
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.pingInterval
import io.ktor.http.encodedPath
import kotlinx.rpc.annotations.Rpc
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.serialization.cbor.cbor
import kotlinx.rpc.withService
import kotlinx.serialization.ExperimentalSerializationApi
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.time.Duration.Companion.seconds

@Configuration
@OptIn(ExperimentalSerializationApi::class)
class RpcClientsConfig(
    private val endpoints: EndpointProperties,
) {
    private val logger = LoggerFactory.getLogger(RpcClientsConfig::class.java)

    private var _tikaClient: ITikaClient? = null
    private var _knowledgeService: KnowledgeService? = null
    private var _knowledgeServiceRead: KnowledgeService? = null
    private var _pythonOrchestratorClient: PythonOrchestratorClient? = null

    // Provider-specific fine-grained RPC clients (used by indexers and services for data operations)
    private var _atlassianClient: IAtlassianClient? = null
    private var _bugTrackerClient: IBugTrackerClient? = null
    private var _githubBugTrackerClient: IBugTrackerClient? = null
    private var _gitlabBugTrackerClient: IBugTrackerClient? = null
    private var _wikiClient: IWikiClient? = null

    @Bean
    fun pythonOrchestratorClient(): PythonOrchestratorClient =
        _pythonOrchestratorClient
            ?: PythonOrchestratorClient(endpoints.orchestrator.baseUrl).also { _pythonOrchestratorClient = it }

    @Bean
    fun knowledgeService(): KnowledgeService =
        object : KnowledgeService {
            override suspend fun ingest(request: IngestRequest): IngestResult = getKnowledgeService().ingest(request)
            override suspend fun ingestFull(request: FullIngestRequest): FullIngestResult = getKnowledgeService().ingestFull(request)
            override suspend fun retrieve(request: RetrievalRequest): EvidencePack = getKnowledgeService().retrieve(request)
            override suspend fun traverse(
                clientId: ClientId,
                startKey: String,
                spec: TraversalSpec,
            ): List<GraphNode> = getKnowledgeService().traverse(clientId, startKey, spec)
            override suspend fun purge(sourceUrn: String): Boolean = getKnowledgeService().purge(sourceUrn)
        }

    /**
     * Read-only Knowledge Base client – uses the dedicated read instance
     * (jervis-knowledgebase-read) for retrieve/traverse operations.
     * Falls back to the write instance URL if knowledgebaseRead is not configured.
     */
    @Bean("knowledgeServiceRead")
    fun knowledgeServiceRead(): KnowledgeService =
        object : KnowledgeService {
            override suspend fun ingest(request: IngestRequest): IngestResult =
                throw UnsupportedOperationException("Read-only KB client cannot ingest")
            override suspend fun ingestFull(request: FullIngestRequest): FullIngestResult =
                throw UnsupportedOperationException("Read-only KB client cannot ingestFull")
            override suspend fun retrieve(request: RetrievalRequest): EvidencePack = getKnowledgeServiceRead().retrieve(request)
            override suspend fun traverse(
                clientId: ClientId,
                startKey: String,
                spec: TraversalSpec,
            ): List<GraphNode> = getKnowledgeServiceRead().traverse(clientId, startKey, spec)
            override suspend fun purge(sourceUrn: String): Boolean =
                throw UnsupportedOperationException("Read-only KB client cannot purge")
        }

    @Bean
    fun tikaClient(): ITikaClient =
        object : ITikaClient {
            override suspend fun process(request: com.jervis.common.dto.TikaProcessRequest) =
                withAutoReconnect({ _tikaClient = null }) { getTika().process(request) }
        }

    @Bean
    fun atlassianClient(): IAtlassianClient =
        object : IAtlassianClient {
            override suspend fun getMyself(request: AtlassianMyselfRequest) =
                withAutoReconnect({ _atlassianClient = null }) { getAtlassian().getMyself(request) }
            override suspend fun searchJiraIssues(request: JiraSearchRequest) =
                withAutoReconnect({ _atlassianClient = null }) { getAtlassian().searchJiraIssues(request) }
            override suspend fun getJiraIssue(request: JiraIssueRequest) =
                withAutoReconnect({ _atlassianClient = null }) { getAtlassian().getJiraIssue(request) }
            override suspend fun searchConfluencePages(request: ConfluenceSearchRequest) =
                withAutoReconnect({ _atlassianClient = null }) { getAtlassian().searchConfluencePages(request) }
            override suspend fun getConfluencePage(request: ConfluencePageRequest) =
                withAutoReconnect({ _atlassianClient = null }) { getAtlassian().getConfluencePage(request) }
            override suspend fun downloadJiraAttachment(request: JiraAttachmentDownloadRequest) =
                withAutoReconnect({ _atlassianClient = null }) { getAtlassian().downloadJiraAttachment(request) }
            override suspend fun downloadConfluenceAttachment(request: ConfluenceAttachmentDownloadRequest) =
                withAutoReconnect({ _atlassianClient = null }) { getAtlassian().downloadConfluenceAttachment(request) }
        }

    @Bean
    fun bugTrackerClient(): IBugTrackerClient =
        object : IBugTrackerClient {
            override suspend fun getUser(request: BugTrackerUserRequest) =
                withAutoReconnect({ _bugTrackerClient = null }) { getBugTracker().getUser(request) }
            override suspend fun searchIssues(request: BugTrackerSearchRequest) =
                withAutoReconnect({ _bugTrackerClient = null }) { getBugTracker().searchIssues(request) }
            override suspend fun getIssue(request: BugTrackerIssueRequest) =
                withAutoReconnect({ _bugTrackerClient = null }) { getBugTracker().getIssue(request) }
            override suspend fun listProjects(request: BugTrackerProjectsRequest) =
                withAutoReconnect({ _bugTrackerClient = null }) { getBugTracker().listProjects(request) }
        }

    @Bean
    fun githubBugTrackerClient(): IBugTrackerClient =
        object : IBugTrackerClient {
            override suspend fun getUser(request: BugTrackerUserRequest) =
                withAutoReconnect({ _githubBugTrackerClient = null }) { getGitHubBugTracker().getUser(request) }
            override suspend fun searchIssues(request: BugTrackerSearchRequest) =
                withAutoReconnect({ _githubBugTrackerClient = null }) { getGitHubBugTracker().searchIssues(request) }
            override suspend fun getIssue(request: BugTrackerIssueRequest) =
                withAutoReconnect({ _githubBugTrackerClient = null }) { getGitHubBugTracker().getIssue(request) }
            override suspend fun listProjects(request: BugTrackerProjectsRequest) =
                withAutoReconnect({ _githubBugTrackerClient = null }) { getGitHubBugTracker().listProjects(request) }
        }

    @Bean
    fun gitlabBugTrackerClient(): IBugTrackerClient =
        object : IBugTrackerClient {
            override suspend fun getUser(request: BugTrackerUserRequest) =
                withAutoReconnect({ _gitlabBugTrackerClient = null }) { getGitLabBugTracker().getUser(request) }
            override suspend fun searchIssues(request: BugTrackerSearchRequest) =
                withAutoReconnect({ _gitlabBugTrackerClient = null }) { getGitLabBugTracker().searchIssues(request) }
            override suspend fun getIssue(request: BugTrackerIssueRequest) =
                withAutoReconnect({ _gitlabBugTrackerClient = null }) { getGitLabBugTracker().getIssue(request) }
            override suspend fun listProjects(request: BugTrackerProjectsRequest) =
                withAutoReconnect({ _gitlabBugTrackerClient = null }) { getGitLabBugTracker().listProjects(request) }
        }

    @Bean
    fun wikiClient(): IWikiClient =
        object : IWikiClient {
            override suspend fun getUser(request: WikiUserRequest) =
                withAutoReconnect({ _wikiClient = null }) { getWiki().getUser(request) }
            override suspend fun searchPages(request: WikiSearchRequest) =
                withAutoReconnect({ _wikiClient = null }) { getWiki().searchPages(request) }
            override suspend fun getPage(request: WikiPageRequest) =
                withAutoReconnect({ _wikiClient = null }) { getWiki().getPage(request) }
            override suspend fun listSpaces(request: WikiSpacesRequest) =
                withAutoReconnect({ _wikiClient = null }) { getWiki().listSpaces(request) }
        }

    @Bean
    fun rpcReconnectHandler(): RpcReconnectHandler =
        object : RpcReconnectHandler {
            override suspend fun reconnectTika() {
                _tikaClient = createRpcClient(endpoints.tika.baseUrl)
            }
            override suspend fun reconnectKnowledgebase() {
                _knowledgeService = KnowledgeServiceRestClient(endpoints.knowledgebase.baseUrl)
                val readUrl = endpoints.knowledgebaseRead.baseUrl.ifBlank { endpoints.knowledgebase.baseUrl }
                _knowledgeServiceRead = KnowledgeServiceRestClient(readUrl)
            }
        }

    private fun getTika(): ITikaClient =
        _tikaClient ?: synchronized(this) {
            _tikaClient ?: createRpcClient<ITikaClient>(endpoints.tika.baseUrl).also { _tikaClient = it }
        }

    private fun getKnowledgeService(): KnowledgeService =
        _knowledgeService ?: synchronized(this) {
            _knowledgeService
                ?: KnowledgeServiceRestClient(endpoints.knowledgebase.baseUrl).also { _knowledgeService = it }
        }

    private fun getKnowledgeServiceRead(): KnowledgeService =
        _knowledgeServiceRead ?: synchronized(this) {
            val readUrl = endpoints.knowledgebaseRead.baseUrl.ifBlank { endpoints.knowledgebase.baseUrl }
            _knowledgeServiceRead
                ?: KnowledgeServiceRestClient(readUrl).also { _knowledgeServiceRead = it }
        }

    private fun atlassianUrl(): String =
        endpoints.providers["atlassian"] ?: throw IllegalStateException("No endpoint configured for atlassian")

    private fun getAtlassian(): IAtlassianClient =
        _atlassianClient ?: synchronized(this) {
            _atlassianClient ?: createRpcClient<IAtlassianClient>(atlassianUrl()).also { _atlassianClient = it }
        }

    private fun getBugTracker(): IBugTrackerClient =
        _bugTrackerClient ?: synchronized(this) {
            _bugTrackerClient ?: createRpcClient<IBugTrackerClient>(atlassianUrl()).also { _bugTrackerClient = it }
        }

    private fun githubUrl(): String =
        endpoints.providers["github"] ?: throw IllegalStateException("No endpoint configured for github")

    private fun getGitHubBugTracker(): IBugTrackerClient =
        _githubBugTrackerClient ?: synchronized(this) {
            _githubBugTrackerClient ?: createRpcClient<IBugTrackerClient>(githubUrl()).also { _githubBugTrackerClient = it }
        }

    private fun gitlabUrl(): String =
        endpoints.providers["gitlab"] ?: throw IllegalStateException("No endpoint configured for gitlab")

    private fun getGitLabBugTracker(): IBugTrackerClient =
        _gitlabBugTrackerClient ?: synchronized(this) {
            _gitlabBugTrackerClient ?: createRpcClient<IBugTrackerClient>(gitlabUrl()).also { _gitlabBugTrackerClient = it }
        }

    private fun getWiki(): IWikiClient =
        _wikiClient ?: synchronized(this) {
            _wikiClient ?: createRpcClient<IWikiClient>(atlassianUrl()).also { _wikiClient = it }
        }

    private suspend inline fun <T> withAutoReconnect(
        crossinline resetter: () -> Unit,
        crossinline block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (e: IllegalStateException) {
            if ("cancelled" in (e.message ?: "").lowercase()) {
                logger.warn("RpcClient cancelled, reconnecting: ${e.message}")
                resetter()
                block()
            } else throw e
        }

    private inline fun <@Rpc reified T : Any> createRpcClient(baseUrl: String): T {
        val cleanBaseUrl = baseUrl.trimEnd('/')
        val url = io.ktor.http.Url(cleanBaseUrl)
        return HttpClient(CIO) {
            install(WebSockets) {
                pingInterval = 20.seconds
            }
            installKrpc {
                serialization {
                    cbor()
                }
            }
        }.rpc {
            url {
                protocol = url.protocol
                host = url.host
                port = if (url.specifiedPort == 0) url.protocol.defaultPort else url.specifiedPort
                encodedPath = "rpc"
            }
        }.withService<T>()
    }
}
