#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/main/java/com/tomasthrawat/blackbrowser/MainActivity.kt")
text = path.read_text(encoding="utf-8")

if "BB_WEBVIEW_SESSION_FIX_V3" in text:
    print("BlackBrowser WebView session fix V3 already applied.")
    raise SystemExit(0)

def replace_once(old: str, new: str, label: str) -> None:
    global text
    if old not in text:
        raise SystemExit(f"V3 anchor not found: {label}")
    text = text.replace(old, new, 1)

# Do not rewrite navigator.userAgent automatically. Cloudflare documents modified WebView
# behavior as a compatibility limitation; the only remaining UA change is the explicit
# user-controlled Desktop Site mode.
replace_once(
    """        applyUserAgentMetadata(wv)
        applyUserAgentDataOverride(wv)
        applyNavigatorUaPatch(wv)
""",
    """        applyUserAgentMetadata(wv)
        applyUserAgentDataOverride(wv)
        // BB_WEBVIEW_SESSION_FIX_V3
        // Automatic navigator.userAgent rewriting is intentionally disabled.
""",
    "disable automatic navigator UA patch"
)

# Stop host-based UA spoofing from leaking into explicit navigation/desktop toggles unless
# a caller explicitly asks for forceSpoof=true.
replace_once(
    """    private fun baseUserAgent(host: String?, forceSpoof: Boolean = false): String {
        val default = WebSettings.getDefaultUserAgent(this)
        if (!hostNeedsUaSpoof(host) && !forceSpoof) return default
        return default
            .replace("; wv", "")
            .replace("Version/4.0 ", "")
    }
""",
    """    private fun baseUserAgent(host: String?, forceSpoof: Boolean = false): String {
        val default = WebSettings.getDefaultUserAgent(this)
        if (!forceSpoof) return default
        return default
            .replace("; wv", "")
            .replace("Version/4.0 ", "")
    }
""",
    "remove automatic host UA spoofing"
)

# Keep Cloudflare's challenge infrastructure outside the app's navigation/blocking policy.
replace_once(
    """    private val inFlightAppNavigationUrls = java.util.WeakHashMap<WebView, String>()
    private val pageFinishGate = PageFinishGate<WebView>()

    private fun isGoogleSettingsUrl(uri: Uri): Boolean {
""",
    """    private val inFlightAppNavigationUrls = java.util.WeakHashMap<WebView, String>()
    private val pageFinishGate = PageFinishGate<WebView>()
    private val cloudflareChallengeViews =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<WebView, Boolean>())

    private fun isCloudflareChallengeUrl(rawUrl: String?): Boolean {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return false
        val query = uri.encodedQuery?.lowercase() ?: ""
        return AdBlocker.isCloudflareChallenge(uri) ||
            query.contains("__cf_chl") ||
            query.contains("cf_chl")
    }

    private fun isGoogleOriginUrl(rawUrl: String?): Boolean {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return host == "google.com" || host.endsWith(".google.com")
    }

    private fun isGoogleSettingsUrl(uri: Uri): Boolean {
""",
    "challenge state helpers"
)

replace_once(
    """                val url = request?.url ?: return false

                if (isGithubArtifactUiDownloadUrl(url)) {
""",
    """                val url = request?.url ?: return false

                val challengeUrl = isCloudflareChallengeUrl(url.toString())
                if (request.isForMainFrame && challengeUrl) {
                    if (view != null) cloudflareChallengeViews[view] = true
                    return false
                }
                if (view != null && cloudflareChallengeViews[view] == true) {
                    return false
                }

                if (isGithubArtifactUiDownloadUrl(url)) {
""",
    "challenge navigation passthrough"
)

replace_once(
    """                if (AdBlockPrefs.isEnabled(this@MainActivity) &&
                    AdBlocker.shouldBlock(url) &&
                    !isTrustedTopLevelNav
                ) {
""",
    """                val isGoogleSettingsNavigation =
                    request.isForMainFrame && isGoogleSettingsUrl(url)
                if (AdBlockPrefs.isEnabled(this@MainActivity) &&
                    AdBlocker.shouldBlock(url) &&
                    !isTrustedTopLevelNav &&
                    !isGoogleSettingsNavigation
                ) {
""",
    "Google settings navigation exemption"
)

replace_once(
    """                if (view != null && url != null) {
                    pageFinishGate.onPageStarted(view, url)
                }
""",
    """                if (view != null && url != null) {
                    pageFinishGate.onPageStarted(view, url)
                    if (isCloudflareChallengeUrl(url)) {
                        cloudflareChallengeViews[view] = true
                    }
                }
""",
    "challenge page-start tracking"
)

replace_once(
    """                if (url != null && inFlightAppNavigationUrls[view] == url) {
                    inFlightAppNavigationUrls.remove(view)
                }
                // Google stores browser preferences such as SafeSearch in cookies.
""",
    """                if (url != null && inFlightAppNavigationUrls[view] == url) {
                    inFlightAppNavigationUrls.remove(view)
                }
                if (url != null && isGoogleOriginUrl(url)) {
                    runCatching { CookieManager.getInstance().flush() }
                }
                if (url != null && !isCloudflareChallengeUrl(url)) {
                    cloudflareChallengeViews.remove(view)
                }
                // Google stores browser preferences such as SafeSearch in cookies.
""",
    "Google origin flush and challenge completion"
)

replace_once(
    """                if (AdBlockPrefs.isEnabled(this@MainActivity)) {
                    injectCosmeticCss(view)
                }
""",
    """                if (AdBlockPrefs.isEnabled(this@MainActivity) &&
                    cloudflareChallengeViews[view] != true &&
                    !isGoogleSettingsUrl(runCatching { Uri.parse(url) }.getOrNull() ?: Uri.EMPTY)
                ) {
                    injectCosmeticCss(view)
                }
""",
    "skip cosmetic filtering on sensitive flows"
)

replace_once(
    """                val url = request?.url
                // Popup trust is intentionally not reused for network resources.
""",
    """                val url = request?.url
                if (url != null && isCloudflareChallengeUrl(url.toString())) {
                    if (view != null) cloudflareChallengeViews[view] = true
                    return super.shouldInterceptRequest(view, request)
                }
                if (view != null && cloudflareChallengeViews[view] == true) {
                    return super.shouldInterceptRequest(view, request)
                }
                // Popup trust is intentionally not reused for network resources.
""",
    "challenge resource passthrough"
)

path.write_text(text, encoding="utf-8")
print("BlackBrowser WebView session fix V3 applied.")
