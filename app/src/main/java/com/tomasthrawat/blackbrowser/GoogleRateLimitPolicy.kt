package com.tomasthrawat.blackbrowser

import java.net.URI
import java.util.Locale

internal object GoogleRateLimitPolicy {
    fun isGoogleRateLimit(url: String, statusCode: Int): Boolean {
        if (statusCode != 429 || url.isBlank()) return false
        return runCatching {
            val uri = URI(url)
            val host = uri.host?.lowercase(Locale.US) ?: return@runCatching false
            val path = uri.path.orEmpty().lowercase(Locale.US)
            val isGoogleHost =
                host == "google.com" ||
                    host == "www.google.com" ||
                    host.endsWith(".google.com") ||
                    host.startsWith("google.") ||
                    host.startsWith("www.google.")
            isGoogleHost && (path == "/sorry" || path.startsWith("/sorry/"))
        }.getOrDefault(false)
    }
}
