package com.tomasthrawat.blackbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserNavigationTest {
    @Test fun blankInput_returnsEmpty() {
        assertEquals("", BrowserNavigation.toUrl("   "))
    }

    @Test fun domainWithoutScheme_getsHttps() {
        assertEquals("https://example.com", BrowserNavigation.toUrl(" example.com "))
    }

    @Test fun existingScheme_isPreserved() {
        assertEquals("https://example.com/path", BrowserNavigation.toUrl("https://example.com/path"))
        assertEquals("http://example.com/path", BrowserNavigation.toUrl("http://example.com/path"))
    }

    @Test fun plainText_becomesSearch() {
        val result = BrowserNavigation.toUrl("hello world")
        assertEquals("https://www.google.com/search?q=hello%20world", result)
        assertTrue(result.startsWith("https://www.google.com/search?q="))
        assertTrue(result.contains("hello%20world"))
    }

    @Test fun searchQuery_specialCharacters_areEncoded() {
        assertEquals(
            "https://www.google.com/search?q=C%2B%2B%20android",
            BrowserNavigation.toUrl("C++ android")
        )
    }

    @Test fun equivalentGoogleSearchUrls_ignoreTransientParameters() {
        val base = "https://www.google.com/search?q=pubg"
        val generated = "https://www.google.com/search?q=pubg&sca_esv=abc&sxsrf=xyz&ei=123&biw=360&bih=712&oq=pubg&gs_lp=abc&sclient=mobile-gws-wiz-hp&sei=456"
        assertTrue(BrowserNavigation.areEquivalentGoogleSearchUrls(base, generated))
    }

    @Test fun equivalentGoogleSearchUrls_keepSearchModeParameters() {
        val web = "https://www.google.com/search?q=cats"
        val images = "https://www.google.com/search?q=cats&tbm=isch"
        assertTrue(!BrowserNavigation.areEquivalentGoogleSearchUrls(web, images))
    }

    @Test fun equivalentGoogleSearchUrls_ignoreNonGoogleUrls() {
        assertTrue(!BrowserNavigation.areEquivalentGoogleSearchUrls(
            "https://example.com/search?q=cats",
            "https://example.com/search?q=cats&ei=123"
        ))
    }

    @Test fun googleSearchUrl_buildsStandardQueryUrl() {
        assertEquals(
            "https://www.google.com/search?q=hello%20world",
            BrowserNavigation.googleSearchUrl("hello world")
        )
    }
}
