package com.jervis.configuration

import com.jervis.common.client.IBugTrackerClient
import com.jervis.common.client.ICodingClient
import com.jervis.common.client.IJoernClient
import com.jervis.common.client.ITikaClient
import com.jervis.common.client.IWhisperClient
import com.jervis.common.client.IWikiClient
import com.jervis.configuration.properties.EndpointProperties
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.encodedPath
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.pingInterval
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
    private var _bugTrackerClient: IBugTrackerClient? = null
    private var _wikiClient: IWikiClient? = null
    private var _aiderClient: ICodingClient? = null
    private var _codingEngineClient: ICodingClient? = null
    private var _junieClient: ICodingClient? = null

    @Bean
    fun tikaClient(): ITikaClient = object : ITikaClient {
        override suspend fun process(request: com.jervis.common.dto.TikaProcessRequest) = 
            getTika().process(request)
    }

    @Bean
    fun joernClient(): IJoernClient = object : IJoernClient {
        override suspend fun run(request: com.jervis.common.dto.JoernQueryDto) = 
            getJoern().run(request)
    }

    @Bean
    fun whisperClient(): IWhisperClient = object : IWhisperClient {
        override suspend fun transcribe(request: com.jervis.common.dto.WhisperRequestDto) = 
            getWhisper().transcribe(request)
    }

    @Bean
    fun wikiClient(): IWikiClient = object : IWikiClient {
        override suspend fun getUser(request: com.jervis.common.dto.wiki.WikiUserRequest) = getWiki().getUser(request)
        override suspend fun searchPages(request: com.jervis.common.dto.wiki.WikiSearchRequest) = getWiki().searchPages(request)
        override suspend fun getPage(request: com.jervis.common.dto.wiki.WikiPageRequest) = getWiki().getPage(request)
        override suspend fun listSpaces(request: com.jervis.common.dto.wiki.WikiSpacesRequest) = getWiki().listSpaces(request)
        override suspend fun downloadAttachment(request: com.jervis.common.dto.wiki.WikiAttachmentRequest) = getWiki().downloadAttachment(request)
    }

    @Bean
    fun bugTrackerClient(): IBugTrackerClient = object : IBugTrackerClient {
        override suspend fun getUser(request: com.jervis.common.dto.bugtracker.BugTrackerUserRequest) = getBugTracker().getUser(request)
        override suspend fun searchIssues(request: com.jervis.common.dto.bugtracker.BugTrackerSearchRequest) = getBugTracker().searchIssues(request)
        override suspend fun getIssue(request: com.jervis.common.dto.bugtracker.BugTrackerIssueRequest) = getBugTracker().getIssue(request)
        override suspend fun listProjects(request: com.jervis.common.dto.bugtracker.BugTrackerProjectsRequest) = getBugTracker().listProjects(request)
        override suspend fun downloadAttachment(request: com.jervis.common.dto.bugtracker.BugTrackerAttachmentRequest) = getBugTracker().downloadAttachment(request)
    }

    @Bean
    fun aiderClient(): ICodingClient = object : ICodingClient {
        override suspend fun execute(request: com.jervis.common.client.CodingRequest) = 
            getAider().execute(request)
    }

    @Bean
    fun codingEngineClient(): ICodingClient = object : ICodingClient {
        override suspend fun execute(request: com.jervis.common.client.CodingRequest) = 
            getCodingEngine().execute(request)
    }

    @Bean
    fun junieClient(): ICodingClient = object : ICodingClient {
        override suspend fun execute(request: com.jervis.common.client.CodingRequest) = 
            getJunie().execute(request)
    }

    @Bean
    fun rpcReconnectHandler(): RpcReconnectHandler = object : RpcReconnectHandler {
        override suspend fun reconnectTika() { _tikaClient = createRpcClient(endpoints.tika.baseUrl) }
        override suspend fun reconnectJoern() { _joernClient = createRpcClient(endpoints.joern.baseUrl) }
        override suspend fun reconnectWhisper() { _whisperClient = createRpcClient(endpoints.whisper.baseUrl) }
        override suspend fun reconnectAtlassian() { 
            _bugTrackerClient = createRpcClient(endpoints.atlassian.baseUrl)
            _wikiClient = createRpcClient(endpoints.atlassian.baseUrl)
        }
        override suspend fun reconnectAider() { _aiderClient = createRpcClient(endpoints.aider.baseUrl) }
        override suspend fun reconnectCodingEngine() { _codingEngineClient = createRpcClient(endpoints.coding.baseUrl) }
        override suspend fun reconnectJunie() { _junieClient = createRpcClient(endpoints.junie.baseUrl) }
    }

    private fun getTika(): ITikaClient = _tikaClient ?: synchronized(this) {
        _tikaClient ?: createRpcClient<ITikaClient>(endpoints.tika.baseUrl).also { _tikaClient = it }
    }

    private fun getJoern(): IJoernClient = _joernClient ?: synchronized(this) {
        _joernClient ?: createRpcClient<IJoernClient>(endpoints.joern.baseUrl).also { _joernClient = it }
    }

    private fun getWhisper(): IWhisperClient = _whisperClient ?: synchronized(this) {
        _whisperClient ?: createRpcClient<IWhisperClient>(endpoints.whisper.baseUrl).also { _whisperClient = it }
    }

    private fun getBugTracker(): IBugTrackerClient = _bugTrackerClient ?: synchronized(this) {
        _bugTrackerClient ?: createRpcClient<IBugTrackerClient>(endpoints.atlassian.baseUrl).also { _bugTrackerClient = it }
    }

    private fun getWiki(): IWikiClient = _wikiClient ?: synchronized(this) {
        _wikiClient ?: createRpcClient<IWikiClient>(endpoints.atlassian.baseUrl).also { _wikiClient = it }
    }

    private fun getAider(): ICodingClient = _aiderClient ?: synchronized(this) {
        _aiderClient ?: createRpcClient<ICodingClient>(endpoints.aider.baseUrl).also { _aiderClient = it }
    }

    private fun getCodingEngine(): ICodingClient = _codingEngineClient ?: synchronized(this) {
        _codingEngineClient ?: createRpcClient<ICodingClient>(endpoints.coding.baseUrl).also { _codingEngineClient = it }
    }

    private fun getJunie(): ICodingClient = _junieClient ?: synchronized(this) {
        _junieClient ?: createRpcClient<ICodingClient>(endpoints.junie.baseUrl).also { _junieClient = it }
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
