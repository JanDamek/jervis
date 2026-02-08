package com.jervis.configuration

interface RpcReconnectHandler {
    suspend fun reconnectTika()
    suspend fun reconnectJoern()
    suspend fun reconnectWhisper()
    suspend fun reconnectKnowledgebase()
}
