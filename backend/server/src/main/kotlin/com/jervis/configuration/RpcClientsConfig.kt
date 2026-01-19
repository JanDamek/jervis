package com.jervis.configuration

import com.jervis.common.client.IAtlassianClient
import com.jervis.common.client.IJoernClient
import com.jervis.common.client.ITikaClient
import com.jervis.common.client.IWhisperClient
import io.ktor.client.request.url
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.withService
import kotlinx.serialization.ExperimentalSerializationApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@OptIn(ExperimentalSerializationApi::class)
class RpcClientsConfig(
    private val ktorClientFactory: KtorClientFactory,
) {
    @Bean
    fun tikaClient(): ITikaClient {
        val client = ktorClientFactory.getHttpClient("tika")
        return client
            .rpc()
            .withService<ITikaClient>()
    }

    @Bean
    fun joernClient(): IJoernClient {
        val client = ktorClientFactory.getHttpClient("joern")
        return client
            .rpc()
            .withService<IJoernClient>()
    }

    @Bean
    fun whisperClient(): IWhisperClient {
        val client = ktorClientFactory.getHttpClient("whisper")
        return client
            .rpc()
            .withService<IWhisperClient>()
    }

    @Bean
    fun atlassianClient(): IAtlassianClient {
        val client = ktorClientFactory.getHttpClient("atlassian")
        return client
            .rpc()
            .withService<IAtlassianClient>()
    }
}
