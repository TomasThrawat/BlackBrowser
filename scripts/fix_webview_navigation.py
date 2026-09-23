#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/main/java/com/tomasthrawat/blackbrowser/MainActivity.kt")
text = path.read_text(encoding="utf-8")

# BB_WEBVIEW_SESSION_FIX_V2
# V1 is already present in MainActivity on this branch. Apply the remaining targeted
# fixes once, then exit so the legacy V1 patch block below is not re-run.
if "BB_WEBVIEW_SESSION_FIX_V1" in text and "BB_WEBVIEW_SESSION_FIX_V2" not in text:
    old_about = """                if (url.scheme == "intent") {
                    return handleIntentScheme(url.toString())
                }
                // Blob URLs are created and consumed inside the current WebView origin.
                // Sending them to ACTION_VIEW would bypass the WebView download path.
                if (url.scheme == "blob") {
                    return false
                }
                if (url.scheme != "http" && url.scheme != "https") {
                    return handleExternalScheme(url.toString())
                }
"""
    new_about = """                if (url.scheme == "intent") {
                    return handleIntentScheme(url.toString())
                }
                // BB_WEBVIEW_SESSION_FIX_V2
                // Cloudflare Turnstile and other embedded browser flows may use
                // about:blank/about:srcdoc as internal WebView documents/frames.
                // Chromium owns these URLs, so never hand them to ACTION_VIEW.
                val internalAboutUrl = url.toString().lowercase()
                if (internalAboutUrl == "about:blank" ||
                    internalAboutUrl.startsWith("about:srcdoc")
                ) {
                    return false
                }
                // Blob URLs are created and consumed inside the current WebView origin.
                // Sending them to ACTION_VIEW would bypass the WebView download path.
                if (url.scheme == "blob") {
                    return false
                }
                if (url.scheme != "http" && url.scheme != "https") {
                    return handleExternalScheme(url.toString())
                }
"""
    old_finish = """                if (url != null && inFlightAppNavigationUrls[view] == url) {
                    inFlightAppNavigationUrls.remove(view)
                }
                val tab = tabs.find { it.webView === view } ?: return
"""
    new_finish = """                if (url != null && inFlightAppNavigationUrls[view] == url) {
                    inFlightAppNavigationUrls.remove(view)
                }
                // Google stores browser preferences such as SafeSearch in cookies.
                // Flush after the Google settings document finishes so preference writes
                // are persisted before a WebView/activity restart can race them.
                if (url != null) {
                    val finishedUri = runCatching { Uri.parse(url) }.getOrNull()
                    if (finishedUri != null && isGoogleSettingsUrl(finishedUri)) {
                        runCatching { CookieManager.getInstance().flush() }
                    }
                }
                val tab = tabs.find { it.webView === view } ?: return
"""
    if old_about not in text:
        raise SystemExit("V2 navigation anchor not found")
    if old_finish not in text:
        raise SystemExit("V2 page-finish anchor not found")
    text = text.replace(old_about, new_about, 1)
    text = text.replace(old_finish, new_finish, 1)
    path.write_text(text, encoding="utf-8")
    print("BlackBrowser WebView session fix V2 applied.")
    raise SystemExit(0)

if "BB_WEBVIEW_SESSION_FIX_V1" in text:
    print("BlackBrowser WebView session fix already applied.")
    raise SystemExit(0)

old_cookie = """        if (isIncognito) {
            // Private tab: no disk/RAM cache. Note this WebView engine shares one
            // cookie/session store across the whole app process, so this gives
            // "no history + no cache" rather than full multi-profile isolation.
            wv.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        }
"""
new_cookie = """        // BB_WEBVIEW_SESSION_FIX_V1
        // Keep cookies available before the first navigation. They are required by Google
        // settings and challenge/session flows such as Cloudflare.
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(!isIncognito)
        cookieManager.setAcceptThirdPartyCookies(wv, !isIncognito)

        if (isIncognito) {
            // Private tab: no disk/RAM cache. Note this WebView engine shares one
            // cookie/session store across the whole app process, so this gives
            // "no history + no cache" rather than full multi-profile isolation.
            wv.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        }
"""
if old_cookie not in text:
    raise SystemExit("Expected WebView cookie block not found.")
text = text.replace(old_cookie, new_cookie, 1)

old_nav = """                // Link clicks and JS/meta redirects land here (unlike loadUrlHonest's
                // app-initiated loads), so the UA has to be corrected for the new
                // destination here too, before letting the load through. See loadUrlHonest's
                // comment: identity-provider hosts -- and the hop right after one -- must keep
                // the disguised UA and never replay a cached redirect-chain response; every
                // other host keeps the plain UA and LOAD_DEFAULT.
                val isGoogleSettingsNavigation = isGoogleSettingsUrl(url)
                val needsUaSpoof = !isGoogleSettingsNavigation && hostNeedsUaSpoof(url.host)
                val needsFreshLoad = !isGoogleSettingsNavigation &&
                    (needsUaSpoof || (view?.cameFromIdentityProvider() == true))
                // A POST navigation -- e.g. the form submit Google's account-chooser step
                // does the moment an account is tapped -- carries a body that
                // WebResourceRequest never exposes; there is no way to read it back out to
                // replay it. loadUrlHonest() below always issues loadUrl(), which is always a
                // GET, so taking the load over ourselves for a POST silently drops that body
                // (the selected-account/CSRF data) and the server just re-renders the same
                // chooser page -- "pick an account -> page reloads -> pick it again", forever.
                // Only replay through loadUrlHonest for GET/method-less navigations, where no
                // body exists to lose; for POST, apply the same UA/cache fix in place instead
                // and let WebView finish the POST it already has, accepting the smaller
                // reload-current-document risk described below only for this one case.
                val isPost = request.method?.equals("POST", ignoreCase = true) == true
                if (needsFreshLoad && !isPost) {
                    // Setting userAgentString here and then returning false (letting WebView
                    // finish the navigation it already decided on) hits a known WebView/Chromium
                    // quirk: changing the UA while a navigation is in flight reloads the CURRENT
                    // document instead of completing the new one (Chromium's own
                    // AwSettingsTest#testUpdatingUserAgentWhileLoadingCausesReload is named after
                    // exactly this). That's what silently turned "open Gmail from search" into
                    // "stay on the Google search results page" -- mail.google.com was allowed
                    // through but never actually loaded. Take the load over ourselves the same
                    // way app-initiated navigations already do, instead of handing WebView an
                    // in-flight request whose UA just changed under it.
                    view?.loadUrlHonest(url.toString())
                    return true
                }
                if (needsUaSpoof) {
                    val targetUa = computeUserAgent(url.host, forceSpoof = true)
                    if (view?.settings?.userAgentString != targetUa) {
                        view?.settings?.userAgentString = targetUa
                    }
                }
                if (view?.settings?.cacheMode != if (needsFreshLoad) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT) {
                    view?.settings?.cacheMode =
                        if (needsFreshLoad) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
                }
                AppFileLogger.trace(
                    this@MainActivity,
                    "NAV_ALLOW",
                    "fresh=" + needsFreshLoad +
                        " uaSpoof=" + needsUaSpoof +
                        " host=" + AppFileLogger.safeString(url.host) +
                        " url=" + AppFileLogger.safeUrl(url.toString())
                )
                return false
"""
new_nav = """                // BB_WEBVIEW_SESSION_FIX_V1
                // Never mutate User-Agent or cacheMode during a link/redirect navigation.
                // Android WebView can reload the current document when its User-Agent changes
                // during a load. A challenge provider can also see that as a changed client.
                // Let Google SafeSearch/settings submissions, including POST forms, proceed
                // exactly as WebView issued them so their request body and session state survive.
                AppFileLogger.trace(
                    this@MainActivity,
                    "NAV_ALLOW",
                    "host=" + AppFileLogger.safeString(url.host) +
                        " method=" + AppFileLogger.safeString(request.method) +
                        " url=" + AppFileLogger.safeUrl(url.toString())
                )
                return false
"""
if old_nav not in text:
    raise SystemExit("Expected main-frame navigation policy block not found.")
text = text.replace(old_nav, new_nav, 1)

old_popup_setup = """                val popup = WebView(this@MainActivity)
                popup.settings.javaScriptEnabled = true
                popup.settings.domStorageEnabled = true
                attachDownloadListener(popup)
"""
new_popup_setup = """                val popup = WebView(this@MainActivity)
                popup.settings.javaScriptEnabled = true
                popup.settings.domStorageEnabled = true
                // Keep popup challenge/login flows on the same cookie-capable session policy.
                val popupCookieManager = CookieManager.getInstance()
                popupCookieManager.setAcceptCookie(true)
                popupCookieManager.setAcceptThirdPartyCookies(popup, true)
                popup.settings.userAgentString = WebSettings.getDefaultUserAgent(this@MainActivity)
                attachDownloadListener(popup)
"""
if old_popup_setup not in text:
    raise SystemExit("Expected popup setup block not found.")
text = text.replace(old_popup_setup, new_popup_setup, 1)

old_popup_ua = """                        if (hostNeedsUaSpoof(destUrl.host)) {
                            v?.settings?.userAgentString =
                                computeUserAgent(destUrl.host, forceSpoof = true)
                            v?.settings?.cacheMode = WebSettings.LOAD_NO_CACHE
                            return false
                        }

"""
new_popup_ua = """                        // BB_WEBVIEW_SESSION_FIX_V1
                        // Keep popup User-Agent and cache policy stable across redirects.
"""
if old_popup_ua not in text:
    raise SystemExit("Expected popup UA/cache block not found.")
text = text.replace(old_popup_ua, new_popup_ua, 1)

old_load = """    private fun WebView.loadUrlHonest(url: String) {
        // The refresh button is the explicit way to reload the current document. Avoid issuing
        // another identical top-level request from address-bar/history/popup dispatch when that
        // exact URL is already what this WebView is displaying.
        if (url == this.url) return
        if (inFlightAppNavigationUrls[this] == url) return
        inFlightAppNavigationUrls[this] = url

        val parsedUrl = runCatching { Uri.parse(url) }.getOrNull()
        val host = parsedUrl?.host
        val isGoogleSettingsNavigation = parsedUrl?.let { isGoogleSettingsUrl(it) } == true

        if (isGoogleSettingsNavigation) {
            val targetUa = computeUserAgent(host, forceSpoof = false)
            if (settings.userAgentString != targetUa) {
                settings.userAgentString = targetUa
            }
            if (settings.cacheMode != WebSettings.LOAD_DEFAULT) {
                settings.cacheMode = WebSettings.LOAD_DEFAULT
            }
            AppFileLogger.trace(
                this@MainActivity,
                "APP_NAVIGATION",
                "googleSettings=true url=" + AppFileLogger.safeUrl(url) +
                    " ua=" + AppFileLogger.safeString(settings.userAgentString)
            )
            loadUrl(url)
            return
        }

        // Identity-provider hosts (uaSpoofHosts) -- and the hop right after one -- serve
        // short-lived state tokens (e.g. Google's sign-in "dsh" param) on every redirect hop.
        // Two things had to stay consistent for exactly these hops: no cached response (a
        // stale one makes the token look expired) and the same disguised UA the identity
        // provider itself saw (dropping back to the plain WebView UA one hop later reads, to
        // the server, as a different client mid-flow). Getting either wrong on its own was
        // enough to make the provider bounce the flow back to itself in a loop; every other
        // host keeps LOAD_DEFAULT and the plain UA so normal browsing is unaffected.
        val needsIdentityNoCache = hostNeedsUaSpoof(host) || cameFromIdentityProvider()
        val needsUaSpoof = hostNeedsUaSpoof(host)
        val targetUa = computeUserAgent(host, forceSpoof = needsUaSpoof)
        if (settings.userAgentString != targetUa) {
            settings.userAgentString = targetUa
        }
        val targetCache = if (needsIdentityNoCache) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        if (settings.cacheMode != targetCache) {
            settings.cacheMode = targetCache
        }
        AppFileLogger.trace(
            this@MainActivity,
            "APP_NAVIGATION",
            "url=" + AppFileLogger.safeUrl(url) +
                " host=" + AppFileLogger.safeString(host) +
                " uaSpoof=" + needsUaSpoof +
                " noCache=" + needsIdentityNoCache +
                " desktopMode=" + DesktopModePrefs.isEnabled(this@MainActivity)
        )
        loadUrl(url)
    }
"""
new_load = """    private fun WebView.loadUrlHonest(url: String) {
        // BB_WEBVIEW_SESSION_FIX_V1
        // Navigation does not silently change WebView identity or cache policy. The only
        // intentional User-Agent change remains the user's explicit Desktop Site toggle.
        if (url == this.url) return
        if (inFlightAppNavigationUrls[this] == url) return
        inFlightAppNavigationUrls[this] = url

        AppFileLogger.trace(
            this@MainActivity,
            "APP_NAVIGATION",
            "url=" + AppFileLogger.safeUrl(url) +
                " ua=" + AppFileLogger.safeString(settings.userAgentString) +
                " cache=" + settings.cacheMode +
                " desktopMode=" + DesktopModePrefs.isEnabled(this@MainActivity)
        )
        loadUrl(url)
    }
"""
if old_load not in text:
    raise SystemExit("Expected loadUrlHonest block not found.")
text = text.replace(old_load, new_load, 1)

# Ensure the changed source really contains all four intended protections.
required = [
    "BB_WEBVIEW_SESSION_FIX_V1",
    "cookieManager.setAcceptThirdPartyCookies(wv, !isIncognito)",
    "popupCookieManager.setAcceptThirdPartyCookies(popup, true)",
    "private fun WebView.loadUrlHonest(url: String)"
]
for item in required:
    if item not in text:
        raise SystemExit("Post-patch verification failed: " + item)

path.write_text(text, encoding="utf-8")
print("WebView session/navigation fix applied and verified.")
