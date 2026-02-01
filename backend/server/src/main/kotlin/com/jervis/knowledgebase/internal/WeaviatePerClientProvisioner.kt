package com.jervis.knowledgebase.internal

import com.jervis.configuration.properties.WeaviateProperties
import com.jervis.types.ClientId
import io.weaviate.client.Config
import io.weaviate.client.WeaviateClient
import io.weaviate.client.v1.schema.model.DataType
import io.weaviate.client.v1.schema.model.Property
import io.weaviate.client.v1.schema.model.WeaviateClass
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class WeaviatePerClientProvisioner(
    private val weaviateProperties: WeaviateProperties,
) {
    private val logger = KotlinLogging.logger {}

    private fun client(): WeaviateClient =
        WeaviateClient(
            Config(
                weaviateProperties.scheme,
                "${weaviateProperties.host}:${weaviateProperties.port}",
            ),
        )

    fun ensureClientCollections(clientId: ClientId) {
        val className = WeaviateClassNameUtil.classFor(clientId)
        val client = client()

        ensureClass(client, className)
    }

    private fun ensureClass(
        client: WeaviateClient,
        className: String,
    ) {
        val schema =
            client
                .schema()
                .getter()
                .run()
                .result
        val exists = schema?.classes?.any { it.className == className } == true
        if (exists) {
            logger.debug { "Weaviate class already exists: $className" }
            return
        }

        val properties =
            listOf(
                // Core RAG props
                Property
                    .builder()
                    .name("content")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                Property
                    .builder()
                    .name("clientId")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                Property
                    .builder()
                    .name("projectId")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                Property
                    .builder()
                    .name("scope")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                Property
                    .builder()
                    .name("kind")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                Property
                    .builder()
                    .name("sourceUrn")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                Property
                    .builder()
                    .name("fileName")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                Property
                    .builder()
                    .name("path")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                Property
                    .builder()
                    .name("language")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                Property
                    .builder()
                    .name("sourcePath")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                Property
                    .builder()
                    .name("chunkIndex")
                    .dataType(listOf(DataType.INT))
                    .build(),
                Property
                    .builder()
                    .name("totalChunks")
                    .dataType(listOf(DataType.INT))
                    .build(),
                Property
                    .builder()
                    .name("entityTypes")
                    .dataType(listOf(DataType.TEXT_ARRAY))
                    .build(),
                Property
                    .builder()
                    .name("contentHash")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                Property
                    .builder()
                    .name("graphRefs")
                    .dataType(listOf(DataType.TEXT_ARRAY))
                    .build(),
                Property
                    .builder()
                    .name("graphAreas")
                    .dataType(listOf(DataType.TEXT_ARRAY))
                    .build(),
                Property
                    .builder()
                    .name("graphRootRef")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                Property
                    .builder()
                    .name("graphPrimaryArea")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                Property
                    .builder()
                    .name("graphRelationships")
                    .dataType(listOf(DataType.TEXT_ARRAY))
                    .build(),
                Property
                    .builder()
                    .name("mainNodeKey")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                Property
                    .builder()
                    .name("knowledgeId")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                // Backward-compat for existing KnowledgeService
                Property
                    .builder()
                    .name("knowledgeType")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                Property
                    .builder()
                    .name("knowledgeSeverity")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                Property
                    .builder()
                    .name("documentId")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                Property
                    .builder()
                    .name("documentTitle")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                Property
                    .builder()
                    .name("documentLocation")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                Property
                    .builder()
                    .name("relatedDocuments")
                    .dataType(listOf(DataType.TEXT_ARRAY))
                    .build(),
            )

        val clazz =
            WeaviateClass
                .builder()
                .className(className)
                .properties(properties)
                .vectorIndexType("hnsw")
                .vectorizer("none")
                .build()

        val created =
            client
                .schema()
                .classCreator()
                .withClass(clazz)
                .run()
        if (created.hasErrors()) {
            error("Failed to create Weaviate class $className: ${created.error}")
        } else {
            logger.info { "Created Weaviate class: $className" }
        }
    }
}
