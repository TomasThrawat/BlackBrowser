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
        assertTrue(result.startsWith("https://www.google.com/search?q="))
        assertTrue(result.contains("hello%20world"))
    }
}
