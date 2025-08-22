package com.jervis.service.agent

object AgentConstants {
    const val MAX_PLANNING_ITERATIONS: Int = 10

    object DefaultSteps {
        const val CONTEXT_ECHO: String = "context.echo"
        const val LANGUAGE_NORMALIZE: String = "language.normalize"
        const val USER_AWAIT: String = "user.await"
        const val RAG_QUERY: String = "rag.query"
        const val SCOPE_RESOLVE: String = "scope.resolve"
    }

    object AuditPrompts {
        const val SCOPE_DETECTION: String = "scope-detection"
    }
}
