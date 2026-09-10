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
        "adservice.google.co.jp",
        "adservice.google.es",
        "adservice.google.it",
        "adservice.google.nl",
        "adservice.google.pl",
        "adservice.google.co.in",
        "adservice.google.com.br",
        "app-measurement.com",
        "analytics.google.com",
        "2mdn.net",

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
        "spotx.tv",
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
        "adskeeper.co.uk",
        "smartyads.com",
        "adition.com",
        "adyoulike.com",
        "adtelligent.com",
        "innovid.com",
        "freewheel.tv",
        "springserve.com",
        "onaudience.com",
        "adcash.com",
        "smaato.com",
        "sonobi.com",
        "gumgum.com",
        "33across.com",
        "sovrn.com",
        "lijit.com",
        "loopme.com",
        "undertone.com",
        "connatix.com",
        "kargo.com",
        "mediamath.com",
        "mathtag.com",
        "district-m.net",
        "improvedigital.com",
        "smartclip.net",
        "bidr.io",
        "turn.com",
        "themoneytizer.com",
        "adhese.com",
        "serving-sys.com",

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
        "adjust.com",
        "appsflyer.com",
        "hotjar.com",
        "mixpanel.com",
        "segment.io",
        "segment.com",
        "fullstory.com",
        "mouseflow.com",
        "clicktale.net",
        "crazyegg.com",
        "optimizely.com",
        "connect.facebook.net",
        "amplitude.com",
        "heap.io",
        "heapanalytics.com",
        "kissmetrics.com",
        "chartbeat.com",
        "parsely.com",
        "krxd.net",
        "newrelic.com",
        "nr-data.net",

        // Aggressive / pop / redirect / cloaking ad networks
        "bidvertiser.com",
        "propellerads.com",
        "popads.net",
        "popcash.net",
        "exoclick.com",
        "juicyads.com",
        "trafficjunky.com",
        "revcontent.com",
        "adsterra.com",
        "hilltopads.net",
        "clickadu.com",
        "trafficstars.com",
        "exdynsrv.com",
        "galaksion.com",
        "evadav.com",
        "clickadilla.com",
        "richads.com",
        "zeropark.com",
        "monetag.com",
        "propush.me",
        "adnium.com",
        "trafficfactory.biz",
        "acscdn.com",
        "srtk.net",
        "clickaine.com",
        "chagnougroalry.net",

        // Crypto ad networks (banner ads for casino/gambling brands like the bc.game
        // one above, common on manga/novel piracy sites) and forced app-install /
        // "continue in safe mode" redirect gates.
        "a-ads.com",
        "tukrd.com",
        "izy0.com",

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
        "tapjoy.com",
        "flurry.com",
        "pubnative.net",
        "fyber.com",
        "verve.com",

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
        "ads.microsoft.com",
        "mc.yandex.ru",
        "an.yandex.ru",
        "px.ads.linkedin.com",
        "snap.licdn.com",
        "tr.snapchat.com",
        "ads.pinterest.com",
        "ct.pinterest.com",
        "ads.linkedin.com",
        "ads.yahoo.com",
        "adtech.yahooinc.com",
        "ads.snapchat.com"
    )

    // Betting/gambling brand block: matched as a host substring so any mirror domain, TLD
    // variant, or subdomain of the brand is caught (e.g. 1xbet.com, 1xbet.ug, m.1xbet.ke,
    // 1xlite-europe.com) without needing to hardcode every rotating mirror. Kept to brand
    // names specific enough that they won't collide with unrelated real words/domains.
    private val blockedHostSubstrings: List<String> = listOf(
        "1xbet",
        "1xlite",
        "melbet",
        "betwinner",
        "mostbet",
        "pin-up",
        "vavada",
        "joycasino",
        "azino777",
        "slotozal",
        "playfortuna",
        "casinox",
        "riobet",
        "parimatch",
        "leonbets",
        "1xslots",
        "bc.game"
    )

    // Path/query fragments, checked with slash boundaries so normal words are never matched.
    private val blockedPatterns: List<String> = listOf(
        "/ads/",
        "/ad/",
        "/adserver/",
        "/adserving/",
        "/pagead/",
        "/adframe",
        "/advert/",
        "/advertisement/",
        "/banners/",
        "/banner_ads/",
        "/popads",
        "/popunder",
        "/adsystem/",
        "/admanager/",
        "/adtrack",
        "/adsync",
        "/prebid",
        "/vast.xml",
        "/vast?",
        "/openrtb",
        "/adchoices",
        "/sponsored-ads/",
        "/native_ads/",
        "/aff_click",
        "/affiliate/click",
        "/click.php?",
        "/adserv/",
        "/adserver.php",
        "/ad_frame",
        "/adsbygoogle.js",
        "/gpt.js",
        "/pubads",
        "/fbevents.js",
        "/collect?",
        "/beacon.js",
        "/track.php",
        "/redirect.php?",
        "/interstitial",
        "/adx.php",
        "/rtb/",
        "/adserve/",
        "/adtracking/",
        "/adroll",
        "/pop.js",
        "/popup.js",
        "/smartlink",
        "/ad-popup"
    )

    fun shouldBlock(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        if (blockedHosts.any { host == it || host.endsWith(".$it") }) return true
        if (blockedHostSubstrings.any { host.contains(it) }) return true

        val fullUrl = uri.toString().lowercase()
        return blockedPatterns.any { fullUrl.contains(it) }
    }

    // Popup destinations (window.open results) are only auto-forwarded into the visible tab
    // when they land on one of these well-known identity-provider hosts — the "sign in with
    // ..." case onCreateWindow exists for in the first place — or stay on the same site that
    // opened them. Everything else is treated as an unwanted popup/redirect (ad network,
    // gambling affiliate, click-hijack overlay, etc.) and blocked, since those destinations
    // rotate constantly and can never be fully enumerated in a static blocklist the way
    // regular ad/tracker resource hosts above can.
    private val trustedPopupHosts: Set<String> = setOf(
        "accounts.google.com",
        "appleid.apple.com",
        "www.facebook.com",
        "m.facebook.com",
        "facebook.com",
        "github.com",
        "login.microsoftonline.com",
        "login.live.com",
        "login.windows.net",
        "api.twitter.com",
        "twitter.com",
        "x.com",
        "login.yahoo.com",
        "discord.com",
        "login.salesforce.com",
        "id.atlassian.com",
        "login.okta.com",
        "auth0.com"
    )

    private fun registrableDomain(host: String): String {
        val parts = host.split(".")
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
    }

    // True when [destination] is either a known identity-provider host above, or shares a
    // registrable domain with [openerUrl] (the page that called window.open). Anything else
    // gates onCreateWindow forwarding closed.
    fun isTrustedPopupDestination(destination: Uri, openerUrl: String?): Boolean {
        val destHost = destination.host?.lowercase() ?: return false
        if (trustedPopupHosts.any { destHost == it || destHost.endsWith(".$it") }) return true

        val openerHost = openerUrl?.let { Uri.parse(it).host?.lowercase() }
        if (openerHost.isNullOrEmpty()) return false
        return destHost == openerHost || registrableDomain(destHost) == registrableDomain(openerHost)
    }

    // Cosmetic filtering: hides leftover ad containers/iframes served from the page's own
    // domain, so ads that survive the network-level host/path block above (native ads, in-feed
    // sponsored blocks, AMP ad slots) are still hidden. Selectors are deliberately specific
    // (ad network names, "advert", "sponsor-", data-ad-* attributes, exact ".ad"/".ads" class
    // tokens) rather than a bare substring, which would also match unrelated words like
    // "gradient" or "header".
    fun cosmeticHideCss(): String = """
        .adsbygoogle, ins.adsbygoogle,
        .ad, .ads, #ad, #ads,
        [data-ad], [data-ad-slot], [data-ad-client], [data-ad-format], [data-ad-unit],
        [id*="div-gpt-ad"], [class*="gpt-ad"], [id*="google_ads_iframe"],
        [class*="advert"], [id*="advert"],
        [class*="sponsor-"], [id*="sponsor-"], [class*="-sponsor"],
        [class*="native-ad"], [id*="native-ad"],
        [class*="taboola"], [id*="taboola"],
        [class*="outbrain"], [id*="outbrain"],
        [class*="mgid"], [id*="mgid"],
        amp-ad, amp-embed[type="doubleclick"], amp-sticky-ad,
        iframe[src*="doubleclick.net"], iframe[src*="googlesyndication.com"],
        iframe[src*="googleadservices.com"], iframe[src*="amazon-adsystem.com"],
        iframe[src*="adnxs.com"], iframe[src*="openx.net"], iframe[src*="pubmatic.com"],
        .ad-slot, .ad-wrapper, .ad_unit, .ad-container, .banner-ad, .banner-ads,
        .adSlot, .dfp-ad, .dfp-slot
        { display: none !important; visibility: hidden !important; height: 0 !important; }
    """.trimIndent()
}
