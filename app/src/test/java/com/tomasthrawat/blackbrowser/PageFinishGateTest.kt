package com.tomasthrawat.blackbrowser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageFinishGateTest {
    @Test fun duplicateFinishIsIgnored() {
        val gate = PageFinishGate<Any>()
        val key = Any()
        assertTrue(gate.shouldProcessPageFinished(key, "https://example.com/"))
        assertFalse(gate.shouldProcessPageFinished(key, "https://example.com/"))
    }

    @Test fun sameUrlReloadIsAcceptedAgain() {
        val gate = PageFinishGate<Any>()
        val key = Any()
        assertTrue(gate.shouldProcessPageFinished(key, "https://example.com/"))
        assertFalse(gate.shouldProcessPageFinished(key, "https://example.com/"))
        gate.onPageStarted(key, "https://example.com/")
        assertTrue(gate.shouldProcessPageFinished(key, "https://example.com/"))
        assertFalse(gate.shouldProcessPageFinished(key, "https://example.com/"))
    }

    @Test fun staleFinishIsIgnoredAfterNewNavigationStarts() {
        val gate = PageFinishGate<Any>()
        val key = Any()
        gate.onPageStarted(key, "https://example.com/a")
        gate.onPageStarted(key, "https://example.com/b")
        assertFalse(gate.shouldProcessPageFinished(key, "https://example.com/a"))
        assertTrue(gate.shouldProcessPageFinished(key, "https://example.com/b"))
    }

    @Test fun differentUntrackedUrlStartsNewCycle() {
        val gate = PageFinishGate<Any>()
        val key = Any()
        assertTrue(gate.shouldProcessPageFinished(key, "https://example.com/a"))
        assertTrue(gate.shouldProcessPageFinished(key, "https://example.com/b"))
        assertFalse(gate.shouldProcessPageFinished(key, "https://example.com/b"))
    }
}
