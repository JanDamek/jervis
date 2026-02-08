package com.jervis.configuration

interface RpcReconnectHandler {
    suspend fun reconnectTika()
    suspend fun reconnectKnowledgebase()
}
