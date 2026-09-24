package com.tomasthrawat.blackbrowser

/**
 * Suppresses one tight, non-user main-frame GET retry after a server retry-blocking response.
 *
 * A successful page completion clears the pending failure so a transient 429/5xx cannot
 * keep suppressing a healthy document for the rest of the cooldown window.
 */
internal class MainFrameRetryGuard(
    private val cooldownMillis: Long = 5_000L,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    private var failedUrl: String? = null
    private var failedAtMillis: Long = 0L

    fun recordServerRetryBlock(url: String) {
        if (url.isBlank()) return
        failedUrl = url
        failedAtMillis = nowMillis()
    }

    fun onPageStarted(url: String) {
        if (url != failedUrl) {
            clear()
        }
    }

    fun onPageFinished(url: String) {
        if (url == failedUrl) {
            clear()
        }
    }

    fun shouldSuppress(url: String, hasUserGesture: Boolean = false): Boolean {
        if (hasUserGesture || url != failedUrl) return false

        val age = nowMillis() - failedAtMillis
        if (age < 0L || age > cooldownMillis) {
            clear()
            return false
        }

        // Suppress at most one automatic retry for each observed 429/5xx.
        // A later retry can only be suppressed after a new server error is observed.
        clear()
        return true
    }

    private fun clear() {
        failedUrl = null
        failedAtMillis = 0L
    }
}
