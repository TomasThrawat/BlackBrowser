package com.tomasthrawat.blackbrowser

/** Prevents duplicate local fallback rendering for the same Google 429 navigation. */
internal class GoogleRateLimitFallbackGuard(
    private val cooldownMillis: Long = 2_000L,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    private var handledUrl: String? = null
    private var handledAtMillis: Long = 0L

    fun shouldShow(url: String): Boolean {
        if (url.isBlank()) return false
        val now = nowMillis()
        val age = now - handledAtMillis
        if (url != handledUrl || age < 0L || age > cooldownMillis) {
            handledUrl = url
            handledAtMillis = now
            return true
        }
        return false
    }

    fun onPageStarted(url: String) {
        if (!url.startsWith("data:") && url != handledUrl) {
            handledUrl = null
            handledAtMillis = 0L
        }
    }
}