package com.tomasthrawat.blackbrowser

import java.util.Locale

internal object WebViewErrorPolicy {
    fun shouldLogNonMainFrameError(host: String?, path: String?): Boolean {
        val normalizedHost = host?.lowercase(Locale.ROOT)?.removeSuffix(".") ?: return true
        val normalizedPath = path?.lowercase(Locale.ROOT) ?: return true
        return normalizedHost != "play.google.com" || normalizedPath != "/log"
    }
}
