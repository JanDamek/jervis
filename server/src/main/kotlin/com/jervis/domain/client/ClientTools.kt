package com.jervis.domain.client

data class ClientTools(
    val git: GitConn? = null,
    val jira: JiraConn? = null,
    val slack: SlackConn? = null,
    val teams: TeamsConn? = null,
    val email: EmailConn? = null,
)
