package com.tomasthrawat.blackbrowser

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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
            SEARCH_URL + encodeQuery(input)
        }
    }

    private fun encodeQuery(input: String): String =
        URLEncoder.encode(input, StandardCharsets.UTF_8.name()).replace("+", "%20")
}
