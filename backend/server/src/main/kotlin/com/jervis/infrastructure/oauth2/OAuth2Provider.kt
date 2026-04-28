package com.jervis.infrastructure.oauth2

/**
 * OAuth2 provider enumeration.
 * Defines supported OAuth2 service providers.
 *
 * Note: Microsoft is intentionally absent. Microsoft / Microsoft 365 / Teams
 * never use server-side OAuth2 against Azure AD — every Microsoft data
 * path goes through the O365 browser-pool pod (browser session login by
 * the user via noVNC; the pod scrapes UI and, where needed, proxies Graph
 * with the browser-stored token). Server never talks to
 * `login.microsoftonline.com` or `graph.microsoft.com`.
 */
enum class OAuth2Provider {
    GITHUB,
    GITLAB,
    BITBUCKET,
    ATLASSIAN,
    SLACK,
    GMAIL,
}