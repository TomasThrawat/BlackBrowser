package com.tomasthrawat.blackbrowser

import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainFrameRetryGuardTest {
    @Test fun sameUrlIsSuppressedAfterFiveHundredSeriesError() {
        var now = 1_000L
        val guard = MainFrameRetryGuard(cooldownMillis = 5_000L) { now }
        val url = Uri.parse("https://example.com/page")

        guard.record5xx(url.toString())
        assertTrue(guard.shouldSuppress(url))

        now += 4_999L
        assertTrue(guard.shouldSuppress(url))

        now += 2L
        assertFalse(guard.shouldSuppress(url))
    }

    @Test fun differentUrlIsNeverSuppressed() {
        val guard = MainFrameRetryGuard { 1_000L }
        guard.record5xx("https://example.com/page-a")
        assertFalse(guard.shouldSuppress(Uri.parse("https://example.com/page-b")))
    }

    @Test fun aDifferentPageStartClearsTheSuppression() {
        val guard = MainFrameRetryGuard { 1_000L }
        val failed = Uri.parse("https://example.com/page-a")
        guard.record5xx(failed.toString())
        guard.onPageStarted("https://example.com/page-b")
        assertFalse(guard.shouldSuppress(failed))
    }
}
