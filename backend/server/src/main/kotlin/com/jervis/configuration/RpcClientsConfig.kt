package com.jervis.configuration

import com.jervis.common.client.IAtlassianClient
import com.jervis.common.client.IJoernClient
import com.jervis.common.client.ITikaClient
import com.jervis.common.client.IWhisperClient
import io.ktor.client.HttpClient
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
        val rpcClient = client.rpc()
        return rpcClient.withService<ITikaClient>()
    }

    @Bean
    fun joernClient(): IJoernClient {
        val client = ktorClientFactory.getHttpClient("joern")
        val rpcClient = client.rpc()
        return rpcClient.withService<IJoernClient>()
    }

    @Bean
    fun whisperClient(): IWhisperClient {
        val client = ktorClientFactory.getHttpClient("whisper")
        val rpcClient = client.rpc()
        return rpcClient.withService<IWhisperClient>()
    }

    @Bean
    fun atlassianClient(): IAtlassianClient {
        val client = ktorClientFactory.getHttpClient("atlassian")
        val rpcClient = client.rpc()
        return rpcClient.withService<IAtlassianClient>()
    }
}
