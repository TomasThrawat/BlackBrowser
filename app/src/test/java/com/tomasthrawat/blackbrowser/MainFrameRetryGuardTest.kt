package com.tomasthrawat.blackbrowser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainFrameRetryGuardTest {
    @Test fun sameUrlIsSuppressedOnceAfterServerRetryBlock() {
        val guard = MainFrameRetryGuard(cooldownMillis = 5_000L) { 1_000L }
        val url = "https://example.com/page"

        guard.recordServerRetryBlock(url)

        assertTrue(guard.shouldSuppress(url))
        assertFalse(guard.shouldSuppress(url))
    }

    @Test fun differentUrlIsNeverSuppressed() {
        val guard = MainFrameRetryGuard { 1_000L }
        guard.recordServerRetryBlock("https://example.com/page-a")

        assertFalse(guard.shouldSuppress("https://example.com/page-b"))
    }

    @Test fun userGestureIsNeverSuppressed() {
        val guard = MainFrameRetryGuard { 1_000L }
        val url = "https://example.com/page"
        guard.recordServerRetryBlock(url)

        assertFalse(guard.shouldSuppress(url, hasUserGesture = true))
        assertTrue(guard.shouldSuppress(url))
    }

    @Test fun successfulPageFinishClearsStaleSuppression() {
        val guard = MainFrameRetryGuard { 1_000L }
        val url = "https://example.com/page"
        guard.recordServerRetryBlock(url)

        guard.onPageStarted(url)
        guard.onPageFinished(url)

        assertFalse(guard.shouldSuppress(url))
    }

    @Test fun differentPageStartClearsSuppression() {
        val guard = MainFrameRetryGuard { 1_000L }
        val failed = "https://example.com/page-a"
        guard.recordServerRetryBlock(failed)

        guard.onPageStarted("https://example.com/page-b")

        assertFalse(guard.shouldSuppress(failed))
    }
}
