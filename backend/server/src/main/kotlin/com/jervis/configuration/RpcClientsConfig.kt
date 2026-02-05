package com.jervis.configuration

import com.jervis.common.client.IAtlassianClient
import com.jervis.common.client.IBugTrackerClient
import com.jervis.common.client.ICodingClient
import com.jervis.common.client.IGitHubClient
import com.jervis.common.client.IGitLabClient
import com.jervis.common.client.IJoernClient
import com.jervis.common.client.ITikaClient
import com.jervis.common.client.IWhisperClient
import com.jervis.common.client.IWikiClient
import com.jervis.dto.connection.ServiceCapabilitiesDto
import com.jervis.common.dto.CodingRequest
import com.jervis.common.dto.atlassian.AtlassianMyselfRequest
import com.jervis.common.dto.atlassian.AtlassianUserDto
import com.jervis.common.dto.atlassian.ConfluenceAttachmentDownloadRequest
import com.jervis.common.dto.atlassian.ConfluencePageRequest
import com.jervis.common.dto.atlassian.ConfluencePageResponse
import com.jervis.common.dto.atlassian.ConfluenceSearchRequest
import com.jervis.common.dto.atlassian.ConfluenceSearchResponse
import com.jervis.common.dto.atlassian.JiraAttachmentDownloadRequest
import com.jervis.common.dto.atlassian.JiraIssueRequest
import com.jervis.common.dto.atlassian.JiraIssueResponse
import com.jervis.common.dto.atlassian.JiraSearchRequest
import com.jervis.common.dto.atlassian.JiraSearchResponse
import com.jervis.common.dto.bugtracker.BugTrackerProjectsRequest
import com.jervis.common.dto.bugtracker.BugTrackerProjectsResponse
import com.jervis.common.dto.wiki.WikiSpacesRequest
import com.jervis.common.dto.wiki.WikiSpacesResponse
import com.jervis.common.types.ClientId
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
    private var _joernClient: IJoernClient? = null
    private var _whisperClient: IWhisperClient? = null
    private var _aiderClient: ICodingClient? = null
    private var _codingEngineClient: ICodingClient? = null
    private var _junieClient: ICodingClient? = null
    private var _atlassianClient: IAtlassianClient? = null
    private var _bugTrackerClient: IBugTrackerClient? = null
    private var _wikiClient: IWikiClient? = null
    private var _gitHubClient: IGitHubClient? = null
    private var _gitLabClient: IGitLabClient? = null
    private var _knowledgeService: KnowledgeService? = null

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
    fun joernClient(): IJoernClient =
        object : IJoernClient {
            override suspend fun run(request: com.jervis.common.dto.JoernQueryDto) = getJoern().run(request)
        }

    @Bean
    fun whisperClient(): IWhisperClient =
        object : IWhisperClient {
            override suspend fun transcribe(request: com.jervis.common.dto.WhisperRequestDto) = getWhisper().transcribe(request)
        }

    @Bean
    fun atlassianClient(): IAtlassianClient =
        object : IAtlassianClient {
            override suspend fun getCapabilities(): ServiceCapabilitiesDto = getAtlassianClient().getCapabilities()

            override suspend fun getMyself(request: AtlassianMyselfRequest): AtlassianUserDto = getAtlassianClient().getMyself(request)

            override suspend fun searchJiraIssues(request: JiraSearchRequest): JiraSearchResponse =
                getAtlassianClient().searchJiraIssues(request)

            override suspend fun getJiraIssue(request: JiraIssueRequest): JiraIssueResponse = getAtlassianClient().getJiraIssue(request)

            override suspend fun searchConfluencePages(request: ConfluenceSearchRequest): ConfluenceSearchResponse =
                getAtlassianClient().searchConfluencePages(request)

            override suspend fun getConfluencePage(request: ConfluencePageRequest): ConfluencePageResponse =
                getAtlassianClient().getConfluencePage(request)

            override suspend fun downloadJiraAttachment(request: JiraAttachmentDownloadRequest): ByteArray? =
                getAtlassianClient().downloadJiraAttachment(request)

            override suspend fun downloadConfluenceAttachment(request: ConfluenceAttachmentDownloadRequest): ByteArray? =
                getAtlassianClient().downloadConfluenceAttachment(request)
        }

    @Bean
    fun gitHubRpcClient(): IGitHubClient =
        object : IGitHubClient {
            override suspend fun getCapabilities(): ServiceCapabilitiesDto = getGitHubClient().getCapabilities()
        }

    @Bean
    fun gitLabRpcClient(): IGitLabClient =
        object : IGitLabClient {
            override suspend fun getCapabilities(): ServiceCapabilitiesDto = getGitLabClient().getCapabilities()
        }

    @Bean
    fun bugTrackerClient(): IBugTrackerClient =
        object : IBugTrackerClient {
            override suspend fun getUser(request: com.jervis.common.dto.bugtracker.BugTrackerUserRequest) =
                getBugTrackerClient().getUser(request)

            override suspend fun searchIssues(request: com.jervis.common.dto.bugtracker.BugTrackerSearchRequest) =
                getBugTrackerClient().searchIssues(request)

            override suspend fun getIssue(request: com.jervis.common.dto.bugtracker.BugTrackerIssueRequest) =
                getBugTrackerClient().getIssue(request)

            override suspend fun listProjects(request: BugTrackerProjectsRequest): BugTrackerProjectsResponse =
                getBugTrackerClient().listProjects(request)
        }

    @Bean
    fun wikiClient(): IWikiClient =
        object : IWikiClient {
            override suspend fun getUser(request: com.jervis.common.dto.wiki.WikiUserRequest) = getWikiClient().getUser(request)

            override suspend fun searchPages(request: com.jervis.common.dto.wiki.WikiSearchRequest) = getWikiClient().searchPages(request)

            override suspend fun getPage(request: com.jervis.common.dto.wiki.WikiPageRequest) = getWikiClient().getPage(request)

            override suspend fun listSpaces(request: WikiSpacesRequest): WikiSpacesResponse = getWikiClient().listSpaces(request)
        }

    @Bean
    fun aiderClient(): ICodingClient =
        object : ICodingClient {
            override suspend fun execute(request: CodingRequest) = getAider().execute(request)
        }

    @Bean
    fun codingEngineClient(): ICodingClient =
        object : ICodingClient {
            override suspend fun execute(request: CodingRequest) = getCodingEngine().execute(request)
        }

    @Bean
    fun junieClient(): ICodingClient =
        object : ICodingClient {
            override suspend fun execute(request: CodingRequest) = getJunie().execute(request)
        }

    @Bean
    fun rpcReconnectHandler(): RpcReconnectHandler =
        object : RpcReconnectHandler {
            override suspend fun reconnectTika() {
                _tikaClient = createRpcClient(endpoints.tika.baseUrl)
            }

            override suspend fun reconnectJoern() {
                _joernClient = createRpcClient(endpoints.joern.baseUrl)
            }

            override suspend fun reconnectWhisper() {
                _whisperClient = createRpcClient(endpoints.whisper.baseUrl)
            }

            override suspend fun reconnectAtlassian() {
                _atlassianClient = createRpcClient(endpoints.atlassian.baseUrl)
                _bugTrackerClient = createRpcClient(endpoints.atlassian.baseUrl)
                _wikiClient = createRpcClient(endpoints.atlassian.baseUrl)
            }

            override suspend fun reconnectGitHub() {
                _gitHubClient = createRpcClient(endpoints.github.baseUrl)
            }

            override suspend fun reconnectGitLab() {
                _gitLabClient = createRpcClient(endpoints.gitlab.baseUrl)
            }

            override suspend fun reconnectAider() {
                _aiderClient = createRpcClient(endpoints.aider.baseUrl)
            }

            override suspend fun reconnectCodingEngine() {
                _codingEngineClient = createRpcClient(endpoints.coding.baseUrl)
            }

            override suspend fun reconnectJunie() {
                _junieClient = createRpcClient(endpoints.junie.baseUrl)
            }

            override suspend fun reconnectKnowledgebase() {
                _knowledgeService = KnowledgeServiceRestClient(endpoints.knowledgebase.baseUrl)
            }
        }

    private fun getTika(): ITikaClient =
        _tikaClient ?: synchronized(this) {
            _tikaClient ?: createRpcClient<ITikaClient>(endpoints.tika.baseUrl).also { _tikaClient = it }
        }

    private fun getJoern(): IJoernClient =
        _joernClient ?: synchronized(this) {
            _joernClient ?: createRpcClient<IJoernClient>(endpoints.joern.baseUrl).also { _joernClient = it }
        }

    private fun getWhisper(): IWhisperClient =
        _whisperClient ?: synchronized(this) {
            _whisperClient ?: createRpcClient<IWhisperClient>(endpoints.whisper.baseUrl).also { _whisperClient = it }
        }

    private fun getAtlassianClient(): IAtlassianClient =
        _atlassianClient ?: synchronized(this) {
            _atlassianClient ?: createRpcClient<IAtlassianClient>(endpoints.atlassian.baseUrl).also {
                _atlassianClient = it
            }
        }

    private fun getBugTrackerClient(): IBugTrackerClient =
        _bugTrackerClient ?: synchronized(this) {
            _bugTrackerClient ?: createRpcClient<IBugTrackerClient>(endpoints.atlassian.baseUrl).also {
                _bugTrackerClient = it
            }
        }

    private fun getWikiClient(): IWikiClient =
        _wikiClient ?: synchronized(this) {
            _wikiClient ?: createRpcClient<IWikiClient>(endpoints.atlassian.baseUrl).also {
                _wikiClient = it
            }
        }

    private fun getGitHubClient(): IGitHubClient =
        _gitHubClient ?: synchronized(this) {
            _gitHubClient ?: createRpcClient<IGitHubClient>(endpoints.github.baseUrl).also {
                _gitHubClient = it
            }
        }

    private fun getGitLabClient(): IGitLabClient =
        _gitLabClient ?: synchronized(this) {
            _gitLabClient ?: createRpcClient<IGitLabClient>(endpoints.gitlab.baseUrl).also {
                _gitLabClient = it
            }
        }

    private fun getAider(): ICodingClient =
        _aiderClient ?: synchronized(this) {
            _aiderClient ?: createRpcClient<ICodingClient>(endpoints.aider.baseUrl).also { _aiderClient = it }
        }

    private fun getCodingEngine(): ICodingClient =
        _codingEngineClient ?: synchronized(this) {
            _codingEngineClient ?: createRpcClient<ICodingClient>(endpoints.coding.baseUrl).also {
                _codingEngineClient = it
            }
        }

    private fun getJunie(): ICodingClient =
        _junieClient ?: synchronized(this) {
            _junieClient ?: createRpcClient<ICodingClient>(endpoints.junie.baseUrl).also { _junieClient = it }
        }

    private fun getKnowledgeService(): KnowledgeService =
        _knowledgeService ?: synchronized(this) {
            _knowledgeService
                ?: KnowledgeServiceRestClient(endpoints.knowledgebase.baseUrl).also { _knowledgeService = it }
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
