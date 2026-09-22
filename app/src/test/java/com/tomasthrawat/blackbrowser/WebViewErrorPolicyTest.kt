package com.tomasthrawat.blackbrowser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewErrorPolicyTest {
    @Test fun playLogSubresourceError_isNotLogged() {
        assertFalse(WebViewErrorPolicy.shouldLogNonMainFrameError("play.google.com", "/log"))
        assertFalse(WebViewErrorPolicy.shouldLogNonMainFrameError("PLAY.GOOGLE.COM.", "/LOG"))
    }

    @Test fun otherPlayGooglePaths_areLogged() {
        assertTrue(WebViewErrorPolicy.shouldLogNonMainFrameError("play.google.com", "/store"))
    }

    @Test fun samePathOnOtherHosts_isLogged() {
        assertTrue(WebViewErrorPolicy.shouldLogNonMainFrameError("example.com", "/log"))
    }

    @Test fun missingUrlParts_areLoggedConservatively() {
        assertTrue(WebViewErrorPolicy.shouldLogNonMainFrameError(null, "/log"))
        assertTrue(WebViewErrorPolicy.shouldLogNonMainFrameError("play.google.com", null))
    }
}
