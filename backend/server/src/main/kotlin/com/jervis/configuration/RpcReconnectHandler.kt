package com.jervis.configuration

interface RpcReconnectHandler {
    suspend fun reconnectTika()

    suspend fun reconnectJoern()

    suspend fun reconnectWhisper()

    suspend fun reconnectAtlassian()

    suspend fun reconnectGitHub()

    suspend fun reconnectGitLab()

    suspend fun reconnectAider()

    suspend fun reconnectCodingEngine()

    suspend fun reconnectJunie()

    suspend fun reconnectKnowledgebase()
}
