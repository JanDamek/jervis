package com.jervis.util

import com.jervis.domain.atlassian.AttachmentType

/**
 * HelpersExtensions class.
 * <p>
 * This class is a part of the application's core functionality.
 * It was created to provide features such as...
 * </p>
 *
 * @author damekjan
 * @version 1.0
 * @since 16.12.2025
 */
fun String?.toAttachmentType(): AttachmentType =
    when {
        this?.startsWith("image/") == true -> AttachmentType.IMAGE
        this == "application/pdf" -> AttachmentType.DOCUMENT
        this?.startsWith("text/") == true -> AttachmentType.DOCUMENT
        else -> AttachmentType.UNKNOWN
    }
