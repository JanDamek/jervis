package com.jervis.common.http

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import mu.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * Paginate using GitHub-style Link header (`Link: <url>; rel="next"`).
 */
suspend fun <T> paginateViaLinkHeader(
    httpClient: HttpClient,
    initialUrl: String,
    provider: String,
    context: String,
    requestBuilder: HttpRequestBuilder.() -> Unit,
    deserialize: (String) -> List<T>,
    maxPages: Int = 10,
): List<T> {
    val allItems = mutableListOf<T>()
    var nextUrl: String? = initialUrl
    var page = 0

    while (nextUrl != null && page < maxPages) {
        val url = nextUrl
        val response = httpClient.get(url) {
            if (page == 0) requestBuilder()
            // For subsequent pages, Link header URL already has all params
        }
        val body = response.checkProviderResponse(provider, "$context (page ${page + 1})")
        allItems.addAll(deserialize(body))

        nextUrl = parseLinkHeaderNext(response)
        page++

        if (nextUrl != null) {
            log.debug { "$provider $context: fetching page ${page + 1}" }
        }
    }

    if (page >= maxPages && nextUrl != null) {
        log.warn { "$provider $context: hit max pages limit ($maxPages), some results may be truncated" }
    }

    return allItems
}

/**
 * Paginate using offset-based pagination (GitLab-style `x-next-page` / `x-total-pages` headers).
 */
suspend fun <T> paginateViaOffset(
    provider: String,
    context: String,
    fetchPage: suspend (page: Int, perPage: Int) -> Pair<List<T>, HttpResponse>,
    perPage: Int = 100,
    maxPages: Int = 10,
): List<T> {
    val allItems = mutableListOf<T>()
    var currentPage = 1

    while (currentPage <= maxPages) {
        val (items, response) = fetchPage(currentPage, perPage)
        allItems.addAll(items)

        val nextPage = response.headers["x-next-page"]?.toIntOrNull()
        if (nextPage == null || items.size < perPage) {
            break
        }

        currentPage = nextPage
        log.debug { "$provider $context: fetching page $currentPage" }
    }

    if (currentPage > maxPages) {
        log.warn { "$provider $context: hit max pages limit ($maxPages), some results may be truncated" }
    }

    return allItems
}

/**
 * Parse `Link` header to find `rel="next"` URL.
 * Format: `<https://api.github.com/user/repos?page=2>; rel="next"`
 */
private fun parseLinkHeaderNext(response: HttpResponse): String? {
    val linkHeader = response.headers["Link"] ?: return null
    return linkHeader
        .split(",")
        .map { it.trim() }
        .firstOrNull { it.contains("""rel="next"""") }
        ?.substringAfter("<")
        ?.substringBefore(">")
}
