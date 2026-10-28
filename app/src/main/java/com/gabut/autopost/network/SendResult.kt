package com.gabut.autopost.network

/** Outcome of one Discord REST call. The caller decides when to retry; this type never retries. */
sealed interface SendResult {

    /**
     * 2xx. [nextAllowedAtMs] is the wall-clock time Discord asked us not to exceed the bucket
     * before, derived from `X-RateLimit-Remaining` / `X-RateLimit-Reset-After`; null when absent.
     */
    data class Success(val code: Int, val nextAllowedAtMs: Long? = null) : SendResult

    /** 429. [isGlobal] means the whole bot token is blocked, not just this route. */
    data class RateLimited(val retryAfterMs: Long, val isGlobal: Boolean) : SendResult

    /** 401/403. D18: never retried automatically - a revoked token only adds to the ban budget. */
    data class AuthFailed(val code: Int) : SendResult

    /** Transport-level failure (offline, DNS, timeout). Retryable with backoff. */
    data object NetworkError : SendResult

    /** Any other status we do not model. */
    data class Other(val code: Int) : SendResult
}