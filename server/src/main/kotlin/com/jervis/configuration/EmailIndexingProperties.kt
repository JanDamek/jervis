package com.jervis.configuration

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "email.indexing")
data class EmailIndexingProperties(
    val linkExtraction: LinkExtractionConfig = LinkExtractionConfig(),
    val attachmentProcessing: AttachmentProcessingConfig = AttachmentProcessingConfig(),
) {
    data class LinkExtractionConfig(
        val enabled: Boolean = true,
        val maxLinksPerEmail: Int = 10,
    )

    data class AttachmentProcessingConfig(
        val enabled: Boolean = true,
        val maxAttachmentSizeMb: Int = 10,
    )
}
