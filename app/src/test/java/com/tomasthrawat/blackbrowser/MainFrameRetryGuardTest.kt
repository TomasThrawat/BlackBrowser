package com.tomasthrawat.blackbrowser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainFrameRetryGuardTest {
    @Test fun sameUrlIsSuppressedAfterServerRetryBlock() {
        var now = 1_000L
        val guard = MainFrameRetryGuard(cooldownMillis = 5_000L) { now }
        val url = "https://example.com/page"

        guard.recordServerRetryBlock(url)
        assertTrue(guard.shouldSuppress(url))

        now += 4_999L
        assertTrue(guard.shouldSuppress(url))

        now += 2L
        assertFalse(guard.shouldSuppress(url))
    }

    @Test fun differentUrlIsNeverSuppressed() {
        val guard = MainFrameRetryGuard { 1_000L }
        guard.recordServerRetryBlock("https://example.com/page-a")
        assertFalse(guard.shouldSuppress("https://example.com/page-b"))
    }

    @Test fun aDifferentPageStartClearsTheSuppression() {
        val guard = MainFrameRetryGuard { 1_000L }
        val failed = "https://example.com/page-a"
        guard.recordServerRetryBlock(failed)
        guard.onPageStarted("https://example.com/page-b")
        assertFalse(guard.shouldSuppress(failed))
    }
}
