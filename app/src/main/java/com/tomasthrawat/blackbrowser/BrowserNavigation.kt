package com.tomasthrawat.blackbrowser

import android.net.Uri

object BrowserNavigation {
    private const val SEARCH_URL = "https://www.google.com/search?q="

    fun toUrl(rawInput: String): String {
        val input = rawInput.trim()
        if (input.isEmpty()) return ""

        val looksLikeUrl = input.contains(".") && !input.contains(" ")
        return if (looksLikeUrl) {
            when {
                input.startsWith("http://", ignoreCase = true) ||
                    input.startsWith("https://", ignoreCase = true) -> input
                else -> "https://" + input
            }
        } else {
            SEARCH_URL + Uri.encode(input)
        }
    }
}
