package com.tomasthrawat.blackbrowser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockTest {
    private fun block(host: String, path: String = "/", query: String? = null): Boolean =
        AdBlocker.shouldBlockParts(host, path, query)

    @Test fun exactAndParentDomains_areBlocked() {
        assertTrue(block("doubleclick.net"))
        assertTrue(block("ad.doubleclick.net", "/resource.js"))
        assertFalse(block("example.com", "/resource.js"))
    }

    @Test fun gamblingHostTokens_useLabelBoundaries() {
        assertTrue(block("1xbet.com"))
        assertTrue(block("m.1xbet.ke"))
        assertTrue(block("1xlite-europe.com"))
        assertFalse(block("not1xbet.example"))
    }

    @Test fun pathRules_doNotScanHostOrWholeUrl() {
        assertTrue(block("example.com", "/ads/banner.js"))
        assertFalse(block("example.com", "/path/adsorption/banner.js"))
        assertFalse(block("ads.example.org", "/content.js"))
    }

    @Test fun queryDependentRules_requireQuery() {
        assertTrue(block("example.com", "/click.php", "id=1"))
        assertFalse(block("example.com", "/click.php"))
        assertTrue(block("example.com", "/collect", "x=1"))
        assertFalse(block("example.com", "/collector", "x=1"))
    }

    @Test fun cloudflareException_isChallengeOnly() {
        assertTrue(
            AdBlocker.isCloudflareChallengeParts(
                "challenges.cloudflare.com",
                "/turnstile/v0/api.js"
            )
        )
        assertTrue(
            AdBlocker.isCloudflareChallengeParts(
                "example.com",
                "/cdn-cgi/challenge-platform/h/b/orchestrate/jsch/v1"
            )
        )
        assertFalse(
            AdBlocker.isCloudflareChallengeParts(
                "example.com",
                "/cdn-cgi/other"
            )
        )
        assertFalse(
            AdBlocker.isCloudflareChallengeParts(
                "cdn.cloudflare.com",
                "/library.js"
            )
        )
    }

    @Test fun googleAdTraffic_isBlockableButSearchIsNot() {
        assertTrue(block("adservice.google.com", "/pagead/test"))
        assertFalse(block("www.google.com", "/search", "q=test"))
    }

    @Test fun popupTrust_isLimitedToKnownIdentityProvidersOrSameOrigin() {
        assertTrue(
            AdBlocker.isTrustedPopupDestinationParts("accounts.google.com", null)
        )
        assertTrue(
            AdBlocker.isTrustedPopupDestinationParts("login.microsoftonline.com", null)
        )
        assertFalse(
            AdBlocker.isTrustedPopupDestinationParts("evil.example", null)
        )
        assertTrue(
            AdBlocker.isTrustedPopupDestinationParts("www.example.com", "example.com")
        )
        assertFalse(
            AdBlocker.isTrustedPopupDestinationParts("evil-example.com", "example.com")
        )
    }

    @Test fun cosmeticCss_containsNetworkIndependentAdSelectors() {
        val css = AdBlocker.cosmeticHideCss()
        assertTrue(css.contains("adsbygoogle"))
        assertTrue(css.contains("data-ad"))
        assertTrue(css.contains("taboola"))
    }
}
