package com.jervis.configuration

interface RpcReconnectHandler {
    suspend fun reconnectKnowledgebase()
}
