package com.tomasthrawat.blackbrowser

/**
 * Suppresses a tight same-URL main-frame GET loop after a server-side 5xx response.
 *
 * App-initiated loadUrl()/reload() calls do not use this class directly; it is consulted only
 * from WebViewClient.shouldOverrideUrlLoading(), so an explicit browser reload remains possible.
 */
internal class MainFrameRetryGuard(
    private val cooldownMillis: Long = 5_000L,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    private var failedUrl: String? = null
    private var failedAtMillis: Long = 0L

    fun record5xx(url: String) {
        if (url.isBlank()) return
        failedUrl = url
        failedAtMillis = nowMillis()
    }

    fun onPageStarted(url: String) {
        if (url != failedUrl) {
            failedUrl = null
            failedAtMillis = 0L
        }
    }

    fun shouldSuppress(url: String): Boolean {
        if (url != failedUrl) return false
        val age = nowMillis() - failedAtMillis
        if (age < 0L || age > cooldownMillis) {
            failedUrl = null
            failedAtMillis = 0L
            return false
        }
        return true
    }
}
