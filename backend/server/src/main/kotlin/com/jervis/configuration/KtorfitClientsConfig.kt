package com.jervis.configuration

import com.jervis.common.client.IAtlassianClient
import com.jervis.common.client.IJoernClient
import com.jervis.common.client.ITikaClient
import com.jervis.common.client.IWhisperClient
import com.jervis.common.client.createIAtlassianClient
import com.jervis.common.client.createIJoernClient
import com.jervis.common.client.createITikaClient
import com.jervis.common.client.createIWhisperClient
import de.jensklingenberg.ktorfit.Ktorfit
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class KtorfitClientsConfig(
    private val ktorClientFactory: KtorClientFactory,
) {
    private fun ktorfit(endpointName: String): Ktorfit {
        val httpClient = ktorClientFactory.getHttpClient(endpointName)
        return Ktorfit
            .Builder()
            .httpClient(httpClient)
            .build()
    }

    @Bean
    fun tikaClient(): ITikaClient = ktorfit("tika").createITikaClient()

    @Bean
    fun joernClient(): IJoernClient = ktorfit("joern").createIJoernClient()

    @Bean
    fun whisperClient(): IWhisperClient = ktorfit("whisper").createIWhisperClient()

    @Bean
    fun atlassianClient(): IAtlassianClient = ktorfit("atlassian").createIAtlassianClient()
}
