package com.jervis.infrastructure.config

interface RpcReconnectHandler {
    suspend fun reconnectKnowledgebase()
}
