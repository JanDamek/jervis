package com.jervis.qualifier

import com.jervis.common.types.SourceUrn
import com.jervis.dto.filtering.FilterSourceType

/**
 * Utility for KB-done routing (source type extraction).
 */
object KbDoneRouter {
    fun extractSourceType(sourceUrn: SourceUrn): FilterSourceType {
        val urn = sourceUrn.value
        return when {
            urn.startsWith("email::") -> FilterSourceType.EMAIL
            urn.startsWith("jira::") -> FilterSourceType.JIRA
            urn.startsWith("github-issue::") || urn.startsWith("gitlab-issue::") || urn.startsWith("git::") -> FilterSourceType.GIT
            urn.startsWith("confluence::") -> FilterSourceType.WIKI
            urn.startsWith("chat::") -> FilterSourceType.CHAT
            urn.startsWith("meeting::") -> FilterSourceType.MEETING
            else -> FilterSourceType.ALL
        }
    }
}
