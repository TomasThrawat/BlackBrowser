package com.tomasthrawat.blackbrowser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleRateLimitPolicyTest {
    @Test fun recognizesGoogleSorry429() {
        assertTrue(
            GoogleRateLimitPolicy.isGoogleRateLimit(
                "https://www.google.com/sorry/index?continue=redacted",
                429
            )
        )
    }

    @Test fun ignoresSuccessfulGoogleSorryPage() {
        assertFalse(
            GoogleRateLimitPolicy.isGoogleRateLimit(
                "https://www.google.com/sorry/index",
                200
            )
        )
    }

    @Test fun ignoresNonGoogle429() {
        assertFalse(
            GoogleRateLimitPolicy.isGoogleRateLimit(
                "https://example.com/sorry/index",
                429
            )
        )
    }

    @Test fun ignoresGoogleSearch429OutsideSorryPath() {
        assertFalse(
            GoogleRateLimitPolicy.isGoogleRateLimit(
                "https://www.google.com/search?q=redacted",
                429
            )
        )
    }

}
