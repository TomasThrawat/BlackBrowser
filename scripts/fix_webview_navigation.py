#!/usr/bin/env python3
from pathlib import Path
import re

p = Path("app/src/main/java/com/tomasthrawat/blackbrowser/MainActivity.kt")
s = p.read_text(encoding="utf-8")
if "BB_WEBVIEW_SESSION_FIX_V1" in s:
    print("already fixed")
    raise SystemExit(0)

old = """        if (isIncognito) {
            // Private tab: no disk/RAM cache. Note this WebView engine shares one
            // cookie/session store across the whole app process, so this gives
            // "no history + no cache" rather than full multi-profile isolation.
            wv.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        }
"""
new = """        // BB_WEBVIEW_SESSION_FIX_V1
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
if old not in s:
    raise SystemExit("cookie anchor not found")
s = s.replace(old, new, 1)

# Stop changing UA/cache during link/redirect navigations.
pat = r'                // Link clicks and JS/meta redirects land here.*?                return false\n'
rep = '''                // BB_WEBVIEW_SESSION_FIX_V1
                // Never mutate User-Agent or cacheMode while WebView is processing a navigation.
                // Android restarts a load when its User-Agent changes, and challenge providers
                // can loop when the client identity changes between challenge and continuation.
                // Google SafeSearch/settings forms, including POST submissions, pass through
                // unchanged so WebView can preserve the original request body/session state.
                AppFileLogger.trace(
                    this@MainActivity,
                    "NAV_ALLOW",
                    "host=" + AppFileLogger.safeString(url.host) +
                        " method=" + AppFileLogger.safeString(request.method) +
                        " url=" + AppFileLogger.safeUrl(url.toString())
                )
                return false
'''
s, n = re.subn(pat, rep, s, count=1, flags=re.S)
if n != 1:
    raise SystemExit(f"navigation block replacement count={n}")

# Keep popup redirect identity stable and enable third-party cookies for popup flows.
anchor = '''                popup.settings.javaScriptEnabled = true
                popup.settings.domStorageEnabled = true
                attachDownloadListener(popup)
'''
repl = '''                popup.settings.javaScriptEnabled = true
                popup.settings.domStorageEnabled = true
                val popupCookieManager = CookieManager.getInstance()
                popupCookieManager.setAcceptCookie(true)
                popupCookieManager.setAcceptThirdPartyCookies(popup, true)
                popup.settings.userAgentString = WebSettings.getDefaultUserAgent(this@MainActivity)
                attachDownloadListener(popup)
'''
if anchor not in s:
    raise SystemExit("popup cookie anchor not found")
s = s.replace(anchor, repl, 1)

pat = r'''                        if (hostNeedsUaSpoof(destUrl.host)) {
                            v?.settings?.userAgentString =
                                computeUserAgent(destUrl.host, forceSpoof = true)
                            v?.settings?.cacheMode = WebSettings.LOAD_NO_CACHE
                            return false
                        }

'''
s, n = re.subn(pat, '''                        // BB_WEBVIEW_SESSION_FIX_V1: keep popup identity stable across redirects.
''', s, count=1)
if n != 1:
    raise SystemExit(f"popup UA replacement count={n}")

old_tail = '    private fun WebView.loadUrlHonest(url: String) {'
start = s.find(old_tail)
marker = '\n    // The legacy UA string above'
end = s.find(marker, start)
if start < 0 or end < 0:
    raise SystemExit("loadUrlHonest boundaries not found")
new_fun = '''    private fun WebView.loadUrlHonest(url: String) {
        // BB_WEBVIEW_SESSION_FIX_V1
        // Keep navigation identity/cache policy stable. Explicit Desktop Site changes are
        // handled only by the user action that toggles that mode.
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
'''
s = s[:start] + new_fun + s[end:]

p.write_text(s, encoding="utf-8")
print("patched", len(s))
