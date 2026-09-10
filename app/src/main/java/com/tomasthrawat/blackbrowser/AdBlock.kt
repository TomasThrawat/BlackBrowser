package com.tomasthrawat.blackbrowser

import android.content.Context
import android.net.Uri

/**
 * Persists whether the ad blocker is active. Defaults to ON.
 */
object AdBlockPrefs {
    private const val PREFS_NAME = "adblock_prefs"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}

/**
 * Offline, host + path based ad/tracker blocker.
 * Fully local: no network calls, no remote list fetch, nothing bundled from the internet at build time.
 */
object AdBlocker {

    // A request is blocked if its host equals one of these, or is a subdomain of one of these.
    private val blockedHosts: Set<String> = setOf(
        // Google / Alphabet ad & measurement network
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "google-analytics.com",
        "googletagmanager.com",
        "googletagservices.com",
        "adservice.google.com",
        "adservice.google.co.uk",
        "adservice.google.de",
        "adservice.google.fr",
        "adservice.google.ca",
        "adservice.google.com.au",
        "app-measurement.com",

        // Major ad exchanges / SSPs / DSPs
        "adnxs.com",
        "adsrvr.org",
        "casalemedia.com",
        "contextweb.com",
        "criteo.com",
        "criteo.net",
        "media.net",
        "mgid.com",
        "moatads.com",
        "openx.net",
        "outbrain.com",
        "taboola.com",
        "pubmatic.com",
        "rubiconproject.com",
        "smartadserver.com",
        "spotxchange.com",
        "teads.tv",
        "triplelift.com",
        "yieldmo.com",
        "sharethrough.com",
        "indexww.com",
        "adform.net",
        "adroll.com",
        "amazon-adsystem.com",
        "bidswitch.net",
        "yieldlab.net",
        "advertising.com",

        // Analytics / tracking / attribution
        "scorecardresearch.com",
        "quantserve.com",
        "quantcast.com",
        "comscore.com",
        "doubleverify.com",
        "adsafeprotected.com",
        "bluekai.com",
        "exelator.com",
        "agkn.com",
        "rlcdn.com",
        "tapad.com",
        "crwdcntrl.net",
        "demdex.net",
        "everesttech.net",
        "flashtalking.com",
        "adtechus.com",
        "zedo.com",

        // Aggressive / pop / redirect ad networks
        "bidvertiser.com",
        "propellerads.com",
        "popads.net",
        "popcash.net",
        "exoclick.com",
        "juicyads.com",
        "trafficjunky.com",
        "revcontent.com",

        // Mobile / in-app ad SDK endpoints (also fire inside WebView pages)
        "vungle.com",
        "chartboost.com",
        "applovin.com",
        "unityads.unity3d.com",
        "ironsrc.com",
        "inmobi.com",
        "adcolony.com",
        "startapp.com",
        "mopub.com",

        // Social ad/tracking subdomains only (the main site itself is never blocked)
        "an.facebook.com",
        "ads-twitter.com",
        "ads-api.twitter.com",
        "analytics.twitter.com",
        "analytics.tiktok.com",
        "ads.tiktok.com",
        "ads.reddit.com",
        "alb.reddit.com",
        "bat.bing.com",
        "ads.microsoft.com"
    )

    // Betting/gambling brand block: matched as a host substring so any mirror domain, TLD
    // variant, or subdomain of the brand is caught (e.g. 1xbet.com, 1xbet.ug, m.1xbet.ke,
    // 1xlite-europe.com) without needing to hardcode every rotating mirror.
    private val blockedHostSubstrings: List<String> = listOf(
        "1xbet",
        "1xlite"
    )

    // Path/query fragments, checked with slash boundaries so normal words are never matched.
    private val blockedPatterns: List<String> = listOf(
        "/ads/", "/ad/", "/adserver/", "/adserving/", "/pagead/", "/adframe",
        "/advert/", "/advertisement/", "/banners/", "/banner_ads/", "/popads",
        "/popunder", "/adsystem/", "/admanager/", "/adtrack", "/adsync",
        "/prebid", "/vast.xml", "/vast?", "/openrtb"
    )

    fun shouldBlock(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        if (blockedHosts.any { host == it || host.endsWith(".$it") }) return true
        if (blockedHostSubstrings.any { host.contains(it) }) return true

        val fullUrl = uri.toString().lowercase()
        return blockedPatterns.any { fullUrl.contains(it) }
    }
}
