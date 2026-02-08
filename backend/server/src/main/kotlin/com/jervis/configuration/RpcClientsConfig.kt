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
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.time.Duration.Companion.seconds

@Configuration
@OptIn(ExperimentalSerializationApi::class)
class RpcClientsConfig(
    private val endpoints: EndpointProperties,
) {
    private var _tikaClient: ITikaClient? = null
    private var _knowledgeService: KnowledgeService? = null
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
        }

    @Bean
    fun tikaClient(): ITikaClient =
        object : ITikaClient {
            override suspend fun process(request: com.jervis.common.dto.TikaProcessRequest) = getTika().process(request)
        }

    @Bean
    fun atlassianClient(): IAtlassianClient =
        object : IAtlassianClient {
            override suspend fun getMyself(request: AtlassianMyselfRequest) = getAtlassian().getMyself(request)
            override suspend fun searchJiraIssues(request: JiraSearchRequest) = getAtlassian().searchJiraIssues(request)
            override suspend fun getJiraIssue(request: JiraIssueRequest) = getAtlassian().getJiraIssue(request)
            override suspend fun searchConfluencePages(request: ConfluenceSearchRequest) = getAtlassian().searchConfluencePages(request)
            override suspend fun getConfluencePage(request: ConfluencePageRequest) = getAtlassian().getConfluencePage(request)
            override suspend fun downloadJiraAttachment(request: JiraAttachmentDownloadRequest) = getAtlassian().downloadJiraAttachment(request)
            override suspend fun downloadConfluenceAttachment(request: ConfluenceAttachmentDownloadRequest) = getAtlassian().downloadConfluenceAttachment(request)
        }

    @Bean
    fun bugTrackerClient(): IBugTrackerClient =
        object : IBugTrackerClient {
            override suspend fun getUser(request: BugTrackerUserRequest) = getBugTracker().getUser(request)
            override suspend fun searchIssues(request: BugTrackerSearchRequest) = getBugTracker().searchIssues(request)
            override suspend fun getIssue(request: BugTrackerIssueRequest) = getBugTracker().getIssue(request)
            override suspend fun listProjects(request: BugTrackerProjectsRequest) = getBugTracker().listProjects(request)
        }

    @Bean
    fun githubBugTrackerClient(): IBugTrackerClient =
        object : IBugTrackerClient {
            override suspend fun getUser(request: BugTrackerUserRequest) = getGitHubBugTracker().getUser(request)
            override suspend fun searchIssues(request: BugTrackerSearchRequest) = getGitHubBugTracker().searchIssues(request)
            override suspend fun getIssue(request: BugTrackerIssueRequest) = getGitHubBugTracker().getIssue(request)
            override suspend fun listProjects(request: BugTrackerProjectsRequest) = getGitHubBugTracker().listProjects(request)
        }

    @Bean
    fun gitlabBugTrackerClient(): IBugTrackerClient =
        object : IBugTrackerClient {
            override suspend fun getUser(request: BugTrackerUserRequest) = getGitLabBugTracker().getUser(request)
            override suspend fun searchIssues(request: BugTrackerSearchRequest) = getGitLabBugTracker().searchIssues(request)
            override suspend fun getIssue(request: BugTrackerIssueRequest) = getGitLabBugTracker().getIssue(request)
            override suspend fun listProjects(request: BugTrackerProjectsRequest) = getGitLabBugTracker().listProjects(request)
        }

    @Bean
    fun wikiClient(): IWikiClient =
        object : IWikiClient {
            override suspend fun getUser(request: WikiUserRequest) = getWiki().getUser(request)
            override suspend fun searchPages(request: WikiSearchRequest) = getWiki().searchPages(request)
            override suspend fun getPage(request: WikiPageRequest) = getWiki().getPage(request)
            override suspend fun listSpaces(request: WikiSpacesRequest) = getWiki().listSpaces(request)
        }

    @Bean
    fun rpcReconnectHandler(): RpcReconnectHandler =
        object : RpcReconnectHandler {
            override suspend fun reconnectTika() {
                _tikaClient = createRpcClient(endpoints.tika.baseUrl)
            }
            override suspend fun reconnectKnowledgebase() {
                _knowledgeService = KnowledgeServiceRestClient(endpoints.knowledgebase.baseUrl)
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
