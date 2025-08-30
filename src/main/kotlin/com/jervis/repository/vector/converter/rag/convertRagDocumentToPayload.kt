package com.jervis.repository.vector.converter.rag

import com.jervis.domain.rag.RagDocument
import io.qdrant.client.grpc.JsonWithInt

/** Bridge: keep legacy function name but delegate to POJO conversion */
internal fun RagDocument.convertRagDocumentToPayload(): Map<String, JsonWithInt.Value> =
    this.convertRagDocumentToProperties().toQdrantPayload()
