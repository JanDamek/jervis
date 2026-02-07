package com.jervis.common.ratelimit

object ProviderRateLimits {
    val GITHUB = RateLimitConfig(maxRequestsPerSecond = 10, maxRequestsPerMinute = 80)
    val GITLAB = RateLimitConfig(maxRequestsPerSecond = 20, maxRequestsPerMinute = 300)
    val ATLASSIAN = RateLimitConfig(maxRequestsPerSecond = 10, maxRequestsPerMinute = 100)
}
