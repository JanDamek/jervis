package com.jervis.configuration

import com.jervis.common.client.IAtlassianClient
import com.jervis.common.client.IJoernClient
import com.jervis.common.client.ITikaClient
import com.jervis.common.client.IWhisperClient
import com.jervis.common.dto.JoernQueryDto
import com.jervis.common.dto.JoernResultDto
import com.jervis.common.dto.TikaProcessRequest
import com.jervis.common.dto.TikaProcessResult
import com.jervis.common.dto.WhisperRequestDto
import com.jervis.common.dto.WhisperResultDto
import com.jervis.common.dto.atlassian.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.withService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class RpcClientsConfig(
    private val ktorClientFactory: KtorClientFactory,
) {
    @Bean
    fun tikaClient(): ITikaClient {
        val client = ktorClientFactory.getHttpClient("tika")
        return object : ITikaClient {
            override suspend fun process(request: TikaProcessRequest): TikaProcessResult =
                client.post("api/tika/process") { setBody(request) }.body()
        }
    }

    @Bean
    fun joernClient(): IJoernClient {
        val client = ktorClientFactory.getHttpClient("joern")
        return object : IJoernClient {
            override suspend fun run(request: JoernQueryDto): JoernResultDto =
                client.post("api/joern/run") { setBody(request) }.body()
        }
    }

    @Bean
    fun whisperClient(): IWhisperClient {
        val client = ktorClientFactory.getHttpClient("whisper")
        return object : IWhisperClient {
            override suspend fun transcribe(request: WhisperRequestDto): WhisperResultDto =
                client.post("api/whisper/transcribe") { setBody(request) }.body()
        }
    }

    @Bean
    fun atlassianClient(): IAtlassianClient {
        val client = ktorClientFactory.getHttpClient("atlassian")
        return object : IAtlassianClient {
            override suspend fun getMyself(request: AtlassianMyselfRequest): AtlassianUserDto =
                client.post("api/atlassian/myself") { setBody(request) }.body()

            override suspend fun searchJiraIssues(request: JiraSearchRequest): JiraSearchResponse =
                client.post("api/atlassian/jira/search") { setBody(request) }.body()

            override suspend fun getJiraIssue(request: JiraIssueRequest): JiraIssueResponse =
                client.post("api/atlassian/jira/issue") { setBody(request) }.body()

            override suspend fun searchConfluencePages(request: ConfluenceSearchRequest): ConfluenceSearchResponse =
                client.post("api/atlassian/confluence/search") { setBody(request) }.body()

            override suspend fun getConfluencePage(request: ConfluencePageRequest): ConfluencePageResponse =
                client.post("api/atlassian/confluence/page") { setBody(request) }.body()

            override suspend fun downloadJiraAttachment(request: JiraAttachmentDownloadRequest): ByteArray? =
                client.post("api/atlassian/jira/attachment") { setBody(request) }.body()

            override suspend fun downloadConfluenceAttachment(request: ConfluenceAttachmentDownloadRequest): ByteArray? =
                client.post("api/atlassian/confluence/attachment") { setBody(request) }.body()
        }
    }
}
