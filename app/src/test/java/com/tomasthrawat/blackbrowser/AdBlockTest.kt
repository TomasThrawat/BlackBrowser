package com.tomasthrawat.blackbrowser

import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockTest {
    @Test fun exactAndParentDomains_areBlocked() {
        assertTrue(AdBlocker.shouldBlock(Uri.parse("https://doubleclick.net/")))
        assertTrue(AdBlocker.shouldBlock(Uri.parse("https://ad.doubleclick.net/resource.js")))
        assertFalse(AdBlocker.shouldBlock(Uri.parse("https://example.com/resource.js")))
    }

    @Test fun gamblingHostTokens_useLabelBoundaries() {
        assertTrue(AdBlocker.shouldBlock(Uri.parse("https://1xbet.com/")))
        assertTrue(AdBlocker.shouldBlock(Uri.parse("https://m.1xbet.ke/")))
        assertTrue(AdBlocker.shouldBlock(Uri.parse("https://1xlite-europe.com/")))
        assertFalse(AdBlocker.shouldBlock(Uri.parse("https://not1xbet.example/")))
    }

    @Test fun pathRules_doNotScanHostOrWholeUrl() {
        assertTrue(AdBlocker.shouldBlock(Uri.parse("https://example.com/ads/banner.js")))
        assertFalse(AdBlocker.shouldBlock(Uri.parse("https://example.com/path/adsorption/banner.js")))
        assertFalse(AdBlocker.shouldBlock(Uri.parse("https://ads.example.org/content.js")))
    }

    @Test fun queryDependentRules_requireQuery() {
        assertTrue(AdBlocker.shouldBlock(Uri.parse("https://example.com/click.php?id=1")))
        assertFalse(AdBlocker.shouldBlock(Uri.parse("https://example.com/click.php")))
        assertTrue(AdBlocker.shouldBlock(Uri.parse("https://example.com/collect?x=1")))
        assertFalse(AdBlocker.shouldBlock(Uri.parse("https://example.com/collector?x=1")))
    }

    @Test fun cloudflareException_isChallengeOnly() {
        assertTrue(AdBlocker.isCloudflareChallenge(Uri.parse("https://challenges.cloudflare.com/turnstile/v0/api.js")))
        assertTrue(AdBlocker.isCloudflareChallenge(Uri.parse("https://example.com/cdn-cgi/challenge-platform/h/b/orchestrate/jsch/v1")))
        assertFalse(AdBlocker.isCloudflareChallenge(Uri.parse("https://example.com/cdn-cgi/other")))
        assertFalse(AdBlocker.isCloudflareChallenge(Uri.parse("https://cdn.cloudflare.com/library.js")))
    }

    @Test fun googleAdTraffic_isBlockableButSearchIsNot() {
        assertTrue(AdBlocker.shouldBlock(Uri.parse("https://adservice.google.com/pagead/test")))
        assertFalse(AdBlocker.shouldBlock(Uri.parse("https://www.google.com/search?q=test")))
    }

    @Test fun popupTrust_isLimitedToKnownIdentityProvidersOrSameOrigin() {
        assertTrue(AdBlocker.isTrustedPopupDestination(
            Uri.parse("https://accounts.google.com/signin"),
            null
        ))
        assertTrue(AdBlocker.isTrustedPopupDestination(
            Uri.parse("https://login.microsoftonline.com/common"),
            null
        ))
        assertFalse(AdBlocker.isTrustedPopupDestination(
            Uri.parse("https://evil.example"),
            null
        ))
        assertTrue(AdBlocker.isTrustedPopupDestination(
            Uri.parse("https://www.example.com/login"),
            "https://example.com/start"
        ))
        assertFalse(AdBlocker.isTrustedPopupDestination(
            Uri.parse("https://evil-example.com/login"),
            "https://example.com/start"
        ))
    }

    @Test fun cosmeticCss_containsNetworkIndependentAdSelectors() {
        val css = AdBlocker.cosmeticHideCss()
        assertTrue(css.contains("adsbygoogle"))
        assertTrue(css.contains("data-ad"))
        assertTrue(css.contains("taboola"))
    }
}
