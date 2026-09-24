package com.tomasthrawat.blackbrowser

import com.tomasthrawat.blackbrowser.R
import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Base64
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.webkit.ScriptHandler
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.provider.MediaStore
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import androidx.core.content.FileProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private data class Tab(
        val id: Int,
        val webView: WebView,
        var title: String,
        var url: String,
        val isIncognito: Boolean = false
    )

    private lateinit var webViewContainer: FrameLayout
    private lateinit var editUrl: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var btnAdBlock: ImageButton
    private lateinit var btnSetDefaultBrowser: ImageButton
    private lateinit var btnHistory: ImageButton
    private lateinit var btnDownloads: ImageButton
    private lateinit var btnTabsBox: TextView
    private lateinit var btnDesktopSite: ImageButton
    private lateinit var fullscreenContainer: FrameLayout

    // Holds whatever HTML5 <video> hands WebChromeClient.onShowCustomView() while a page's
    // own fullscreen/expand button is active, plus the callback WebView needs invoked once
    // fullscreen is left (from the page itself, the back button, or the system).
    private var fullscreenCustomView: View? = null
    private var fullscreenCustomViewCallback: WebChromeClient.CustomViewCallback? = null

    private val homeUrl = "https://www.google.com"

    private val tabs = mutableListOf<Tab>()
    private var currentTabIndex = 0
    private var nextTabId = 1

    // Real per-WebView User-Agent Client Hints, captured once before desktop mode ever touches
    // them, so toggling desktop mode off can restore the true values instead of re-deriving them.
    private val defaultUaMetadata = java.util.WeakHashMap<WebView, UserAgentMetadata>()

    // Holds the per-WebView document-start script that patches navigator.userAgentData
    // (see applyUserAgentDataOverride below) so it can be removed/replaced on toggle.
    private val uaScriptHandlers = java.util.WeakHashMap<WebView, ScriptHandler>()

    private val activeWebView: WebView
        get() = tabs[currentTabIndex].webView

    // Uploads triggered by a website's <input type="file"> element go through these.
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null
    private var cameraCaptureFile: File? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleFileChooserResult(result.resultCode, result.data)
    }

    private val defaultBrowserRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    private data class PendingDownload(
        val url: String,
        val userAgent: String,
        val contentDisposition: String,
        val mimeType: String,
        val referer: String?
    )

    private data class PendingBlobDownload(
        val webView: WebView,
        val url: String,
        val contentDisposition: String,
        val mimeType: String
    )

    private class BlobDownloadBridge(
        private val expectedToken: String,
        private val onComplete: (String) -> Unit,
        private val onError: (String) -> Unit
    ) {
        private var finished = false

        private fun acceptOnce(token: String): Boolean = synchronized(this) {
            if (finished || token != expectedToken) return@synchronized false
            finished = true
            true
        }

        @JavascriptInterface
        fun complete(token: String, dataUrl: String) {
            if (acceptOnce(token)) onComplete(dataUrl)
        }

        @JavascriptInterface
        fun fail(token: String, message: String) {
            if (acceptOnce(token)) onError(message)
        }
    }

    private var pendingDownload: PendingDownload? = null
    private var pendingBlobDownload: PendingBlobDownload? = null

    // Keep only downloads started by this app eligible for completion handling. DownloadManager
    // broadcasts are system-wide, so without this guard an unrelated app's download could enter
    // this receiver while BlackBrowser is running.
    private val appDownloadIds = mutableSetOf<Long>()

    // Serialize history writes so rapid navigation cannot race SharedPreferences read-modify-write cycles.
    private val historyExecutor = Executors.newSingleThreadExecutor()

    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id == -1L) return

                val owned = synchronized(appDownloadIds) {
                    appDownloadIds.remove(id)
                }
                if (!owned) return

                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                    ?: return
                val cursor = dm.query(DownloadManager.Query().setFilterById(id)) ?: return
                cursor.use {
                    if (!it.moveToFirst()) return@use

                    val statusIdx = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val reasonIdx = it.getColumnIndex(DownloadManager.COLUMN_REASON)
                    val mimeIdx = it.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
                    val status = if (statusIdx >= 0) it.getInt(statusIdx) else -1
                    val reason = if (reasonIdx >= 0) it.getInt(reasonIdx) else -1
                    val mime = if (mimeIdx >= 0) it.getString(mimeIdx) else null

                    AppFileLogger.log(
                        context,
                        "DOWNLOAD",
                        "complete id=" + id +
                            " status=" + status +
                            " reason=" + reason +
                            " mime=" + AppFileLogger.safeString(mime)
                    )

                    AppFileLogger.trace(
                        context,
                        "DOWNLOAD_COMPLETE",
                        "id=" + id + " status=" + status + " reason=" + reason +
                            " mime=" + AppFileLogger.safeString(mime)
                    )

                    if (status == DownloadManager.STATUS_FAILED) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.download_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (t: Throwable) {
                // Download completion must never terminate the browser process.
                AppFileLogger.logExceptionNow(
                    context,
                    "DOWNLOAD",
                    "download completion receiver failed",
                    t
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        AppFileLogger.initialize(this)
        AppFileLogger.installCrashHandler(this)
        AppFileLogger.log(this, "APP", "onCreate sdk=" + Build.VERSION.SDK_INT + " model=" + Build.MODEL)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        AppFileLogger.trace(
            this,
            "APP_CREATE",
            "desktopMode=" + DesktopModePrefs.isEnabled(this) +
                " adBlock=" + AdBlockPrefs.isEnabled(this) +
                " network=" + networkTransportSummary()
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPressed()
            }
        })

        // Loads the bundled extended blocklist (HaGeZi/1Hosts/oisd/StevenBlack merge, ~321k
        // domains) from assets. Done on a background thread since it parses a few MB of text;
        // shouldBlock() keeps working off the smaller starting set until this finishes.
        Thread {
            val complete = AdBlocker.loadExtendedBlocklist(applicationContext)
            AppFileLogger.trace(
                this@MainActivity,
                "ADBLOCK",
                "extendedBlocklistComplete=" + complete
            )
        }.start()

        webViewContainer = findViewById(R.id.webViewContainer)
        editUrl = findViewById(R.id.editUrl)
        progressBar = findViewById(R.id.progressBar)
        btnAdBlock = findViewById(R.id.btnAdBlock)
        btnSetDefaultBrowser = findViewById(R.id.btnSetDefaultBrowser)
        btnHistory = findViewById(R.id.btnHistory)
        btnDownloads = findViewById(R.id.btnDownloads)
        btnTabsBox = findViewById(R.id.btnTabsBox)
        btnDesktopSite = findViewById(R.id.btnDesktopSite)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)

        val btnReload: ImageButton = findViewById(R.id.btnReload)

        btnReload.setOnClickListener {
            activeWebView.reload()
        }

        btnSetDefaultBrowser.setOnClickListener {
            requestDefaultBrowser()
        }

        updateAdBlockIcon()
        btnAdBlock.setOnClickListener {
            val enabled = !AdBlockPrefs.isEnabled(this)
            AdBlockPrefs.setEnabled(this, enabled)
            updateAdBlockIcon()
            Toast.makeText(
                this,
                if (enabled) getString(R.string.adblock_on_toast) else getString(R.string.adblock_off_toast),
                Toast.LENGTH_SHORT
            ).show()
            activeWebView.reload()
        }

        updateDesktopSiteIcon()
        btnDesktopSite.setOnClickListener {
            val enabled = !DesktopModePrefs.isEnabled(this)
            DesktopModePrefs.setEnabled(this, enabled)
            updateDesktopSiteIcon()
            activeWebView.settings.userAgentString =
                computeUserAgent(runCatching { Uri.parse(activeWebView.url) }.getOrNull()?.host)
            applyUserAgentMetadata(activeWebView)
            applyUserAgentDataOverride(activeWebView)
            Toast.makeText(
                this,
                if (enabled) getString(R.string.desktop_mode_on_toast) else getString(R.string.desktop_mode_off_toast),
                Toast.LENGTH_SHORT
            ).show()
            activeWebView.reload()
        }

        btnHistory.setOnClickListener {
            showHistoryDialog()
        }

        btnDownloads.setOnClickListener {
            AppFileLogger.log(this, "DOWNLOADS_UI", "downloads button clicked")
            try {
                showDownloadsDialog()
            } catch (t: Throwable) {
                AppFileLogger.logExceptionNow(this, "DOWNLOADS_UI", "showDownloadsDialog crashed", t)
                Toast.makeText(this, getString(R.string.download_failed), Toast.LENGTH_SHORT).show()
            }
        }

        btnTabsBox.setOnClickListener {
            showTabsDialog()
        }

        editUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                loadFromInput()
                true
            } else {
                false
            }
        }

        addNewTab(intent?.dataString ?: homeUrl)
        checkWebViewChannel()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AppFileLogger.trace(this, "NEW_INTENT", "data=" + AppFileLogger.safeUrl(intent.dataString))
        intent.dataString?.let { addNewTab(it) }
    }

    override fun onPause() {
        AppFileLogger.trace(this, "ACTIVITY_PAUSE", "tab=" + currentTabIndex)
        super.onPause()
        // The activity losing foreground means no tab is actually being watched right
        // now either -- same reasoning as pausing a backgrounded tab in switchToTab().
        tabs.getOrNull(currentTabIndex)?.webView?.onPause()
    }

    override fun onResume() {
        AppFileLogger.trace(this, "ACTIVITY_RESUME", "tab=" + currentTabIndex)
        super.onResume()
        tabs.getOrNull(currentTabIndex)?.webView?.onResume()
        updateDefaultBrowserButtonVisibility()
    }

    override fun onStart() {
        AppFileLogger.trace(this, "ACTIVITY_START", "tab=" + currentTabIndex)
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            downloadCompleteReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        AppFileLogger.trace(this, "ACTIVITY_STOP", "tab=" + currentTabIndex)
        super.onStop()
        // Persist cookies to disk now (not just periodically) so a session/login started
        // right before the app is backgrounded or killed is not silently lost.
        CookieManager.getInstance().flush()
        try {
            unregisterReceiver(downloadCompleteReceiver)
        } catch (e: IllegalArgumentException) {
            // already unregistered
        }
    }

    override fun onDestroy() {
        AppFileLogger.trace(this, "ACTIVITY_DESTROY", "tab=" + currentTabIndex)
        AppFileLogger.log(this, "LIFECYCLE", "onDestroy")
        historyExecutor.shutdownNow()
        synchronized(appDownloadIds) {
            appDownloadIds.clear()
        }
        pendingDownload = null
        pendingBlobDownload = null
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        cameraCaptureFile?.delete()
        cameraCaptureFile = null
        cameraImageUri = null
        pageFinishGate.clear()
        tabs.forEach {
            runCatching { it.webView.stopLoading() }
            runCatching { it.webView.destroy() }
        }
        tabs.clear()
        inFlightAppNavigationUrls.clear()
        super.onDestroy()
    }

    // ---- Tabs ----

    private fun createWebView(isIncognito: Boolean = false): WebView {
        val wv = WebView(this)
        wv.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        // Pure black immediately, before any page (or its own background) has painted.
        wv.setBackgroundColor(Color.BLACK)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.loadWithOverviewMode = true
        wv.settings.useWideViewPort = true
        // Pinch-to-zoom with two fingers, via WebView's own built-in zoom handling.
        // displayZoomControls=false hides the on-screen +/- overlay Android draws by
        // default, so only the finger gesture itself is exposed to the user.
        wv.settings.setSupportZoom(true)
        wv.settings.builtInZoomControls = true
        wv.settings.displayZoomControls = false
        // offscreenPreRaster is intentionally left false here. Per WebSettings docs it should
        // only be enabled for the WebView actually visible on screen (it raises memory use per
        // instance); switchToTab() below flips it on/off as tabs become active/inactive instead
        // of leaving it on for every backgrounded tab's WebView.
        // Some login/redirect chains still serve a stray http:// sub-resource from an
        // otherwise https:// page; without this WebView silently drops it and the page can
        // get stuck instead of completing its redirect.
        wv.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        // BB_WEBVIEW_SESSION_FIX_V1
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
        // Needed so onCreateWindow below actually gets called for window.open() (sign-in
        // popups, ad pop-unders) instead of the request being silently dropped.
        wv.settings.setSupportMultipleWindows(true)
        // Blocks popups a script opens on its own (typical pop-under ad behavior) while still
        // allowing a real tap-triggered window.open() (typical "sign in with ..." button) to
        // reach onCreateWindow below.
        wv.settings.javaScriptCanOpenWindowsAutomatically = false

        // Google (and most other OAuth/identity providers) refuse to render "Continue with
        // Google" / sign-in buttons when the User-Agent identifies the page as running inside
        // an embedded WebView rather than a full browser -- they detect it via the "; wv)"
        // token and the "Version/4.0 " prefix Android's default WebView UA always includes,
        // and serve a blank/blocked state instead of the button. computeUserAgent() strips
        // both (and also applies the desktop-site swap below, if that toggle is on).
        // No destination is known yet at creation time -- loadUrlHonest() below
        // corrects this to the right host-specific UA the moment a real load happens.
        wv.settings.userAgentString = computeUserAgent(null)
        applyUserAgentMetadata(wv)
        applyUserAgentDataOverride(wv)

        // Keep the app chrome pure-black without forcing WebView pages through an automatic
        // color transformation. Search engines and dynamic pages must render their own CSS.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.settings, false)
        }

        // Third-party cookies are off by default per-WebView; most cross-domain sign-in
        // redirects (Google/Facebook/GitHub OAuth callbacks, etc.) depend on them to complete.
        // An incognito tab must not send or accept cookies at all -- otherwise it silently
        // reuses whatever session is already stored from a regular tab (stays logged in).
        wv.webViewClient = object : WebViewClient() {
            // Stops a same-tab redirect chain (meta-refresh, JS location change, a clicked
            // link, or the tail end of a popup forwarded below) from landing on a known
            // ad/tracker/gambling host, on top of the resource-level check in
            // shouldInterceptRequest further down.
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url ?: return false

                if (isGithubArtifactUiDownloadUrl(url)) {
                    AppFileLogger.logNow(
                        this@MainActivity,
                        "DOWNLOAD",
                        "github artifact UI link kept inside WebView url=" +
                            AppFileLogger.safeUrl(url.toString())
                    )
                    return false
                }

                if (isGithubArtifactDownloadUrl(url)) {
                    startDownloadFromWebResourceRequest(view, request, url)
                    return true
                }

                // A small subset of servers responds to an already-rendered page with a 5xx,
                // while the page/client immediately asks WebView for the exact same GET again.
                // That creates a tight navigation/reload loop. Suppress only that exact main-frame
                // GET for a short cooldown after a 429/5xx; app-initiated loadUrl/reload calls do not
                // pass through shouldOverrideUrlLoading and therefore remain available to the user.
                if (request.isForMainFrame &&
                    request.method.equals("GET", ignoreCase = true) &&
                    !request.hasGesture() &&
                    view?.let { mainFrameRetryGuardFor(it).shouldSuppress(url.toString()) } == true
                ) {
                    AppFileLogger.trace(
                        this@MainActivity,
                        "NAV_SUPPRESS_RETRY",
                        "mainFrame=true method=GET url=" + AppFileLogger.safeUrl(url.toString())
                    )
                    return true
                }

                AppFileLogger.trace(
                    this@MainActivity,
                    "NAV_INTERCEPT",
                    "mainFrame=" + request.isForMainFrame +
                        " method=" + AppFileLogger.safeString(request.method) +
                        " gesture=" + request.hasGesture() +
                        " url=" + AppFileLogger.safeUrl(url.toString()) +
                        " current=" + AppFileLogger.safeUrl(view?.url) +
                        " desktopMode=" + DesktopModePrefs.isEnabled(this@MainActivity) +
                        " ua=" + AppFileLogger.safeString(view?.settings?.userAgentString)
                )

                if (request.isForMainFrame && request.hasGesture()) {
                    view?.let { activeGoogleRateLimitFallbackUrls.remove(it) }
                }

                if (request.isForMainFrame &&
                    !request.hasGesture() &&
                    view != null &&
                    isGoogleSorryUrl(url.toString()) &&
                    activeGoogleRateLimitFallbackUrls.containsKey(view)
                ) {
                    AppFileLogger.trace(
                        this@MainActivity,
                        "NAV_SUPPRESS_RATE_LIMIT",
                        "mainFrame=true method=" + AppFileLogger.safeString(request.method) +
                            " url=" + AppFileLogger.safeUrl(url.toString())
                    )
                    return true
                }

                // Google Search may issue more than one main-frame GET while constructing the
                // results document. Let WebView/Google own that navigation; only server-confirmed
                // 429/5xx responses activate the retry guard below.
                // Same-tab OAuth/2FA redirect chains (Google/Apple/Microsoft/etc. sign-in
                // callbacks) must never be silently killed by the ad-block host list -- only
                // the popup path (onCreateWindow) used to be exempted via
                // isTrustedPopupDestination(); this top-level navigation path had no such
                // exemption, so a false-positive match on the ~321k-domain merged blocklist
                // during a real sign-in redirect looked like "the page never comes back".
                val isTrustedTopLevelNav = request.isForMainFrame &&
                    AdBlocker.isTrustedPopupDestination(url, null)
                if (AdBlockPrefs.isEnabled(this@MainActivity) &&
                    AdBlocker.shouldBlock(url) &&
                    !isTrustedTopLevelNav
                ) {
                    return true
                }
                // intent:// (Play Store "get the app" / deep-link buttons) and other
                // non-http(s) schemes (market:, tel:, mailto:, whatsapp:, geo:, ...) mean
                // nothing to WebView itself -- left alone they fail with
                // ERR_UNKNOWN_URL_SCHEME instead of reaching the target app or Play Store.
                if (url.scheme == "intent") {
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
                // Some https:// destinations (OAuth/deep-link callbacks -- e.g. "you're now
                // signed back into <the app that started this>") are claimed by another
                // installed app as a verified Android App Link, not by a browser. A WebView
                // never asks the OS about that on its own -- shouldOverrideUrlLoading only
                // decides whether *this* WebView renders the URL, so left alone the callback
                // just loads as an ordinary web page here and the user is stuck looking at
                // the site's own fallback (e.g. a sign-in page) instead of landing back in
                // the app that asked for the sign-in. Real browsers (Chrome included) check
                // for exactly this before rendering; do the same.

                // BB_WEBVIEW_SESSION_FIX_V1
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
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?
            ): Boolean {
                AppFileLogger.logNow(
                    this@MainActivity,
                    "WEBVIEW_RENDER",
                    "rendererGone didCrash=" + detail?.didCrash() +
                        " priorityAtExit=" + detail?.rendererPriorityAtExit() +
                        " url=" + AppFileLogger.safeUrl(view?.url)
                )
                AppFileLogger.traceNow(
                    this@MainActivity,
                    "RENDERER_GONE",
                    "didCrash=" + detail?.didCrash() +
                        " priority=" + detail?.rendererPriorityAtExit() +
                        " url=" + AppFileLogger.safeUrl(view?.url)
                )

                val crashedView = view ?: return true
                val tabIndex = tabs.indexOfFirst { it.webView === crashedView }
                if (tabIndex !in tabs.indices) {
                    try {
                        crashedView.destroy()
                    } catch (_: Throwable) {
                    }
                    return true
                }

                val oldTab = tabs[tabIndex]
                val restoreUrl = oldTab.url.ifBlank { homeUrl }

                webViewContainer.removeView(crashedView)
                try {
                    crashedView.stopLoading()
                } catch (_: Throwable) {
                }
                try {
                    crashedView.destroy()
                } catch (_: Throwable) {
                }

                val replacement = try {
                    createWebView(oldTab.isIncognito)
                } catch (t: Throwable) {
                    AppFileLogger.logExceptionNow(
                        this@MainActivity,
                        "WEBVIEW_RENDER",
                        "failed to recreate renderer WebView",
                        t
                    )
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.webview_renderer_recovery_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                    tabs.removeAt(tabIndex)
                    currentTabIndex = currentTabIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
                    if (tabs.isEmpty()) {
                        addNewTab()
                    } else {
                        switchToTab(currentTabIndex)
                    }
                    updateTabsBoxCount()
                    return true
                }

                tabs[tabIndex] = oldTab.copy(webView = replacement)
                replacement.loadUrlHonest(restoreUrl)
                updateTabsBoxCount()

                if (tabIndex == currentTabIndex) {
                    CookieManager.getInstance().setAcceptCookie(!oldTab.isIncognito)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(
                        replacement,
                        !oldTab.isIncognito
                    )
                    replacement.onResume()
                    replacement.settings.offscreenPreRaster = true
                    webViewContainer.removeAllViews()
                    webViewContainer.addView(replacement)
                    editUrl.setText(restoreUrl)
                    updateDefaultBrowserButtonVisibility()
                }

                return true
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                val shouldLogError = request?.isForMainFrame == true ||
                    WebViewErrorPolicy.shouldLogNonMainFrameError(
                        request?.url?.host,
                        request?.url?.path
                    )
                if (shouldLogError) {
                    AppFileLogger.log(
                        this@MainActivity,
                        "WEBVIEW_ERROR",
                        "mainFrame=" + request?.isForMainFrame +
                            " url=" + AppFileLogger.safeUrl(request?.url?.toString()) +
                            " code=" + error?.errorCode +
                            " description=" + error?.description
                    )
                    AppFileLogger.trace(
                        this@MainActivity,
                        "RESOURCE_ERROR",
                        "mainFrame=" + request?.isForMainFrame +
                            " code=" + error?.errorCode +
                            " description=" + AppFileLogger.safeString(error?.description?.toString()) +
                            " url=" + AppFileLogger.safeUrl(request?.url?.toString())
                    )
                }
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true && view != null &&
                    inFlightAppNavigationUrls[view] == request.url.toString()
                ) {
                    inFlightAppNavigationUrls.remove(view)
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                val statusCode = errorResponse?.statusCode ?: -1
                val isMainFrameGet = request?.isForMainFrame == true &&
                    request.method.equals("GET", ignoreCase = true)
                if (isMainFrameGet && (statusCode == 429 || statusCode in 500..599)) {
                    view?.let {
                        mainFrameRetryGuardFor(it).recordServerRetryBlock(request.url.toString())
                    }
                }

                val googleRateLimitedUrl = request?.url?.toString()
                    ?.takeIf { isMainFrameGet }
                    ?.takeIf { GoogleRateLimitPolicy.isGoogleRateLimit(it, statusCode) }

                AppFileLogger.trace(
                    this@MainActivity,
                    "HTTP_ERROR",
                    "mainFrame=" + request?.isForMainFrame +
                        " status=" + statusCode +
                        " reason=" + AppFileLogger.safeString(errorResponse?.reasonPhrase) +
                        " mime=" + AppFileLogger.safeString(errorResponse?.mimeType) +
                        " url=" + AppFileLogger.safeUrl(request?.url?.toString())
                )

                if (googleRateLimitedUrl != null && view != null) {
                    val alreadyShowingFallback =
                        activeGoogleRateLimitFallbackUrls[view] == googleRateLimitedUrl
                    activeGoogleRateLimitFallbackUrls[view] = googleRateLimitedUrl
                    if (!alreadyShowingFallback) {
                        AppFileLogger.trace(
                            this@MainActivity,
                            "GOOGLE_RATE_LIMIT_FALLBACK",
                            "status=429 url=" + AppFileLogger.safeUrl(googleRateLimitedUrl)
                        )
                        showGoogleRateLimitFallback(view)
                    } else {
                        AppFileLogger.trace(
                            this@MainActivity,
                            "GOOGLE_RATE_LIMIT_FALLBACK_DUPLICATE",
                            "status=429 url=" + AppFileLogger.safeUrl(googleRateLimitedUrl)
                        )
                    }
                }

                super.onReceivedHttpError(view, request, errorResponse)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                AppFileLogger.log(this@MainActivity, "WEBVIEW", "pageStarted url=" + AppFileLogger.safeUrl(url))
                AppFileLogger.trace(
                    this@MainActivity,
                    "PAGE_STARTED",
                    "url=" + AppFileLogger.safeUrl(url) +
                        " ua=" + AppFileLogger.safeString(view?.settings?.userAgentString) +
                        " cache=" + view?.settings?.cacheMode
                )
                if (view != null && url != null) {
                    val rateLimitFallbackUrl = activeGoogleRateLimitFallbackUrls[view]
                    if (rateLimitFallbackUrl != null && isGoogleSorryUrl(url)) {
                        AppFileLogger.trace(
                            this@MainActivity,
                            "PAGE_RATE_LIMIT_REDIRECT_SUPPRESSED",
                            "url=" + AppFileLogger.safeUrl(url) +
                                " fallbackFor=" + AppFileLogger.safeUrl(rateLimitFallbackUrl)
                        )
                        showGoogleRateLimitFallback(view)
                        return
                    }
                    if (!isLocalRateLimitFallbackUrl(url) && !isGoogleSorryUrl(url)) {
                        activeGoogleRateLimitFallbackUrls.remove(view)
                    }
                    pageFinishGate.onPageStarted(view, url)
                    mainFrameRetryGuardFor(view).onPageStarted(url)
                }
                super.onPageStarted(view, url, favicon)
                // Once Chromium has actually started the main-frame navigation, the original
                // app dispatch has happened. Release the guard so a later deliberate navigation
                // is not blocked, including redirect chains that never finish on the original URL.
                if (view != null && url != null && inFlightAppNavigationUrls[view] == url) {
                    inFlightAppNavigationUrls.remove(view)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (view == null) return
                if (url != null) {
                    mainFrameRetryGuardFor(view).onPageFinished(url)
                }
                if (!pageFinishGate.shouldProcessPageFinished(view, url)) return
                AppFileLogger.log(this@MainActivity, "WEBVIEW", "pageFinished url=" + AppFileLogger.safeUrl(url))
                AppFileLogger.trace(
                    this@MainActivity,
                    "PAGE_FINISHED",
                    "url=" + AppFileLogger.safeUrl(url) +
                        " title=" + AppFileLogger.safeTitle(view.title) +
                        " ua=" + AppFileLogger.safeString(view.settings.userAgentString) +
                        " cookies=" + CookieManager.getInstance().hasCookies()
                )
                if (url != null && inFlightAppNavigationUrls[view] == url) {
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
                tab.url = url ?: tab.url
                tab.title = view?.title?.takeIf { it.isNotBlank() } ?: tab.url
                if (tabs.getOrNull(currentTabIndex)?.webView === view) {
                    editUrl.setText(tab.url)
                }
                if (!tab.isIncognito) {
                    // HistoryStore.add() does a synchronous SharedPreferences read + JSONArray
                    // parse/rebuild + apply() on every page load; push that off the main thread
                    // so it can't jank a page-load-heavy session (same pattern as
                    // loadExtendedBlocklist() above). Snapshot title/url first since `tab` is
                    // mutable and could change before the thread runs.
                    val historyTitle = tab.title
                    val historyUrl = tab.url
                    runCatching { historyExecutor.execute { HistoryStore.add(applicationContext, historyTitle, historyUrl) } }
                }

                if (AdBlockPrefs.isEnabled(this@MainActivity)) {
                    injectCosmeticCss(view)
                }
            }

            private fun showGoogleRateLimitFallback(view: WebView) {
                runCatching { view.stopLoading() }
                val html = """
                    <!doctype html>
                    <html lang="ar">
                    <head>
                      <meta name="viewport" content="width=device-width,initial-scale=1">
                      <meta name="color-scheme" content="dark">
                      <title>Google مؤقتًا غير متاح</title>
                      <style>
                        :root { color-scheme: dark; }
                        html, body {
                          margin: 0;
                          padding: 0;
                          min-height: 100%;
                          background: #000;
                          color: #fff;
                          font-family: sans-serif;
                        }
                        body {
                          display: flex;
                          align-items: center;
                          justify-content: center;
                          min-height: 100vh;
                        }
                        main {
                          width: min(88vw, 520px);
                          box-sizing: border-box;
                          padding: 28px 24px;
                          text-align: center;
                        }
                        h1 {
                          margin: 0 0 12px;
                          font-size: 22px;
                        }
                        p {
                          margin: 0 0 20px;
                          color: #bdbdbd;
                          line-height: 1.6;
                        }
                        button {
                          border: 0;
                          border-radius: 12px;
                          padding: 12px 18px;
                          background: #fff;
                          color: #000;
                          font-size: 15px;
                        }
                      </style>
                    </head>
                    <body>
                      <main>
                        <h1>Google أوقف الطلب مؤقتًا</h1>
                        <p>تم الوصول إلى حد مؤقت لطلبات البحث. لن يعيد BlackBrowser إرسال الطلب تلقائيًا حتى لا يدخل في حلقة تكرار.</p>
                        <button type="button" onclick="history.back()">الرجوع للصفحة السابقة</button>
                      </main>
                    </body>
                    </html>
                """.trimIndent()

                runCatching {
                    view.loadDataWithBaseURL(
                        "https://blackbrowser.invalid/rate-limit-fallback",
                        html,
                        "text/html",
                        "UTF-8",
                        "https://blackbrowser.invalid/rate-limit-fallback"
                    )
                }.onFailure { throwable ->
                    AppFileLogger.logExceptionNow(
                        this@MainActivity,
                        "WEBVIEW_RATE_LIMIT",
                        "failed to render Google rate-limit fallback",
                        throwable
                    )
                }
            }

            private fun injectCosmeticCss(view: WebView?) {
                if (view == null) return
                val css = org.json.JSONObject.quote(AdBlocker.cosmeticHideCss())
                val script = "(function(){try{" +
                    "var parent=document.head||document.documentElement;" +
                    "if(!parent)return;" +
                    "var style=document.createElement('style');" +
                    "style.type='text/css';" +
                    "style.textContent=$css;" +
                    "parent.appendChild(style);" +
                    "}catch(e){}})();"
                try {
                    view.evaluateJavascript(script, null)
                } catch (t: Throwable) {
                    AppFileLogger.logExceptionNow(
                        this@MainActivity,
                        "WEBVIEW_CSS",
                        "cosmetic CSS injection failed",
                        t
                    )
                }
            }

            private fun isGoogleCaptchaResource(uri: Uri?): Boolean {
                val host = uri?.host?.lowercase() ?: return false
                val path = uri.path?.lowercase() ?: ""
                return host == "recaptcha.net" ||
                    host.endsWith(".recaptcha.net") ||
                    path == "/recaptcha" ||
                    path.startsWith("/recaptcha/")
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url
                // Popup trust is intentionally not reused for network resources. A host that is
                // trusted as a sign-in/window.open destination must not automatically bypass the
                // network blocklist for all of its images/scripts/XHRs.
                val adBlockOn = AdBlockPrefs.isEnabled(this@MainActivity)
                val blockReason = if (adBlockOn) url?.let { AdBlocker.blockingReason(it) } else null
                val isCloudflareChallenge = url != null && AdBlocker.isCloudflareChallenge(url)
                val isGoogleCaptcha = url != null && isGoogleCaptchaResource(url)
                val willBlock = url != null &&
                    adBlockOn &&
                    blockReason != null &&
                    !isCloudflareChallenge &&
                    !isGoogleCaptcha

                // shouldInterceptRequest is on the WebView networking hot path. Logging every
                // image/script/XHR/font request creates substantial file I/O on busy pages.
                // Keep diagnostics only for actual blocked requests.
                if (willBlock) {
                    AppFileLogger.trace(
                        this@MainActivity,
                        "RESOURCE_BLOCKED",
                        "reason=" + AppFileLogger.safeString(blockReason) +
                            " mainFrame=" + request?.isForMainFrame +
                            " method=" + AppFileLogger.safeString(request?.method) +
                            " url=" + AppFileLogger.safeUrl(url?.toString())
                    )
                    return WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress == 0 || newProgress == 25 || newProgress == 50 ||
                    newProgress == 75 || newProgress == 100
                ) {
                    AppFileLogger.trace(
                        this@MainActivity,
                        "PROGRESS",
                        "progress=" + newProgress + " url=" + AppFileLogger.safeUrl(view?.url)
                    )
                }
                super.onProgressChanged(view, newProgress)
                if (tabs.getOrNull(currentTabIndex)?.webView === view) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress in 1..99) ProgressBar.VISIBLE else ProgressBar.GONE
                }
            }

            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                AppFileLogger.trace(
                    this@MainActivity,
                    "CONSOLE",
                    "level=" + consoleMessage?.messageLevel() +
                        " source=" + AppFileLogger.safeUrl(consoleMessage?.sourceId()) +
                        " line=" + consoleMessage?.lineNumber() +
                        " message=" + safeConsoleText(consoleMessage?.message())
                )
                return super.onConsoleMessage(consoleMessage)
            }

            // Handles every window.open() request in one place: ad/tracker pop-unders are
            // resolved and swallowed before they ever open, while a legitimate popup (sign-in
            // flows are the common case) has its destination followed in the current tab
            // instead of vanishing into a WebView that is never attached to any screen — that
            // silent drop is what used to look like "the site doesn't redirect after sign-in".
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                // A window.open() the page fired on its own, with no tap behind it, is exactly
                // the pop-under pattern javaScriptCanOpenWindowsAutomatically=false exists to
                // stop; if it still reaches here on some WebView build, refuse it outright.
                if (!isUserGesture) return false
                if (resultMsg == null) return false
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false

                val popup = WebView(this@MainActivity)
                popup.settings.javaScriptEnabled = true
                popup.settings.domStorageEnabled = true
                // Match the opener tab's cookie policy. CookieManager.setAcceptCookie() is
                // process-wide, so forcing it to true here would re-enable cookies even when
                // the popup was opened from an incognito tab.
                val openerIsIncognito = tabs.firstOrNull { it.webView === view }?.isIncognito == true
                val popupCookieManager = CookieManager.getInstance()
                popupCookieManager.setAcceptCookie(!openerIsIncognito)
                popupCookieManager.setAcceptThirdPartyCookies(popup, !openerIsIncognito)
                popup.settings.userAgentString = computeUserAgent(
                    runCatching { Uri.parse(view?.url).host }.getOrNull()
                )
                applyUserAgentMetadata(popup)
                applyUserAgentDataOverride(popup)
                attachDownloadListener(popup)
                popup.webViewClient = object : WebViewClient() {
                    override fun onRenderProcessGone(
                        v: WebView?,
                        detail: android.webkit.RenderProcessGoneDetail?
                    ): Boolean {
                        AppFileLogger.log(
                            this@MainActivity,
                            "WEBVIEW_RENDER",
                            "popupRendererGone didCrash=" + detail?.didCrash() +
                                " priorityAtExit=" + detail?.rendererPriorityAtExit() +
                                " url=" + AppFileLogger.safeUrl(v?.url)
                        )
                        try {
                            v?.destroy()
                        } catch (_: Throwable) {
                        }
                        return true
                    }

                    override fun shouldOverrideUrlLoading(
                        v: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val destUrl = request?.url ?: run { popup.destroy(); return true }

                        if (isGithubArtifactUiDownloadUrl(destUrl)) {
                            AppFileLogger.logNow(
                                this@MainActivity,
                                "DOWNLOAD",
                                "popup github artifact UI link forwarded to active WebView url=" +
                                    AppFileLogger.safeUrl(destUrl.toString())
                            )
                            popup.destroy()
                            activeWebView.loadUrlHonest(destUrl.toString())
                            return true
                        }

                        if (isGithubArtifactDownloadUrl(destUrl)) {
                            startDownloadFromWebResourceRequest(v, request, destUrl)
                            popup.destroy()
                            return true
                        }

                        // isUserGesture (checked above, before this popup was even created)
                        // already proves a real tap opened this navigation -- that's exactly
                        // what separates it from a pop-under, so the only thing left to block
                        // here is a destination that's an actual known ad/tracker/gambling
                        // host. Also requiring the destination to share openerUrl's domain (or
                        // match a small identity-provider allowlist) used to reject any other
                        // legitimate cross-domain destination too, which is what made ordinary
                        // "Continue" / "click here to continue" redirect buttons silently do
                        // nothing.
                        val adBlockOn = AdBlockPrefs.isEnabled(this@MainActivity)
                        val isUnwantedPopup = adBlockOn && AdBlocker.shouldBlock(destUrl)
                        if (isUnwantedPopup) {
                            popup.destroy()
                            return true
                        }

                        // Identity-provider chains (Google/Apple/Microsoft/... sign-in) hop
                        // across several of THEIR OWN hosts -- e.g.
                        // accounts.google.com/ServiceLogin -> .../v3/signin/challenge/dp (the
                        // 2-Step Verification device-prompt page) -- before landing on the
                        // real final destination. Destroying the popup and forwarding only
                        // the FIRST hop (as this used to do) cuts that chain short and hands
                        // the main tab an intermediate URL instead of the finished sign-in,
                        // which is what produced the ServiceLogin <-> www.google.com/?pli=1
                        // bounce seen during earlier debugging. Let it keep following its
                        // own chain and only forward once it leaves that host.
                        // BB_WEBVIEW_SESSION_FIX_V1
                        // Keep popup User-Agent and cache policy stable across redirects.
                        popup.destroy()
                        activeWebView.loadUrlHonest(destUrl.toString())
                        return true
                    }
                }
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }

            // Lets a page's native <video> fullscreen/expand button actually work: HTML5
            // video fullscreen doesn't reuse the page's normal view hierarchy, it hands
            // WebView a separate "custom view" to display full-screen -- without this hook
            // the button has nothing to attach it to and silently does nothing.
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) return
                if (fullscreenCustomView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                fullscreenCustomView = view
                fullscreenCustomViewCallback = callback
                fullscreenContainer.addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                fullscreenContainer.visibility = View.VISIBLE
                enterImmersiveMode()
            }

            // The page (or the system back gesture, see onBackPressed) left video
            // fullscreen -- tear down exactly what onShowCustomView above added.
            override fun onHideCustomView() {
                exitFullscreenVideo()
            }

            // Lets a website's <input type="file"> (e.g. "upload photo/file") open the
            // system file/photo picker, plus a live camera-capture option when a camera
            // app is available.
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                launchFileChooser(fileChooserParams)
                return true
            }
        }

        attachDownloadListener(wv)

        // Press-and-hold on an <img> (e.g. a photo in a gallery/photos page) offers to
        // download it, the same way a real browser's long-press context menu does --
        // WebView only exposes this via HitTestResult, it doesn't wire up any UI for it
        // on its own. IMAGE_TYPE covers a plain <img>; SRC_IMAGE_ANCHOR_TYPE covers an
        // <img> that's also wrapped in a link (the common "tap photo to enlarge" markup),
        // where it's the image itself that should download, not the link destination.
        wv.setOnLongClickListener {
            val result = wv.hitTestResult
            val imageUrl = when (result.type) {
                WebView.HitTestResult.IMAGE_TYPE,
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> result.extra
                else -> null
            }
            if (imageUrl != null) {
                confirmDownloadImage(wv, imageUrl)
                true
            } else {
                false
            }
        }

        return wv
    }

    private fun addNewTab(url: String = homeUrl, isIncognito: Boolean = false) {
        val wv = createWebView(isIncognito)
        val title = if (isIncognito) {
            getString(R.string.incognito_tab_title)
        } else {
            getString(R.string.new_tab_title)
        }
        val tab = Tab(nextTabId++, wv, title, url, isIncognito)
        tabs.add(tab)
        // Apply the target tab's cookie policy before its first network navigation.
        // CookieManager is process-wide, so loading before switchToTab() could start an
        // incognito request under the previous tab's cookie policy and then flip it mid-load.
        switchToTab(tabs.size - 1)
        wv.loadUrlHonest(url)
        updateTabsBoxCount()
    }

    private fun switchToTab(index: Int) {
        if (index !in tabs.indices) return
        val previousTab = tabs.getOrNull(currentTabIndex)
        currentTabIndex = index
        val tab = tabs[index]
        // Keep the shared cookie jar's accept policy in sync with whichever tab is
        // actually on screen: incognito must not send/accept cookies (no reused login
        // session), a regular tab needs cookies back on to stay signed in normally.
        CookieManager.getInstance().setAcceptCookie(!tab.isIncognito)
        CookieManager.getInstance().setAcceptThirdPartyCookies(tab.webView, !tab.isIncognito)
        // A tab that isn't the one on screen has no business animating, tracking
        // location, or running plugins in the background. onPause() is per-WebView
        // (unlike pauseTimers(), which is global and would also freeze the tab we're
        // switching INTO), so this only quiets the tab being left behind.
        if (previousTab != null && previousTab !== tab) {
            previousTab.webView.onPause()
            // Only the WebView actually on screen should pre-rasterize offscreen content.
            previousTab.webView.settings.offscreenPreRaster = false
        }
        tab.webView.onResume()
        tab.webView.settings.offscreenPreRaster = true
        webViewContainer.removeAllViews()
        webViewContainer.addView(tab.webView)
        editUrl.setText(tab.url)
        updateDefaultBrowserButtonVisibility()
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val tab = tabs[index]
        val wasActive = index == currentTabIndex
        tabs.removeAt(index)
        webViewContainer.removeView(tab.webView)
        tab.webView.stopLoading()
        if (tab.isIncognito) {
            // Best-effort private-tab cleanup on close -- see the cacheMode note in
            // createWebView() about what this can and can't isolate.
            tab.webView.clearCache(true)
            tab.webView.clearFormData()
            tab.webView.clearHistory()
        }
        tab.webView.destroy()

        if (tabs.isEmpty()) {
            addNewTab()
            return
        }

        if (wasActive) {
            switchToTab(index.coerceAtMost(tabs.size - 1))
        } else if (index < currentTabIndex) {
            currentTabIndex -= 1
        }
        updateTabsBoxCount()
    }

    private fun updateTabsBoxCount() {
        btnTabsBox.text = tabs.size.toString()
    }

    private fun showTabsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_tabs, null)
        // tabsListContainer is now a RecyclerView (was a plain LinearLayout rebuilt from
        // scratch on every change) so opening/closing/switching tabs no longer re-inflates
        // every row; only the rows that actually changed get rebound, via DiffUtil.
        val listContainer = dialogView.findViewById<RecyclerView>(R.id.tabsListContainer)
        val btnNewTab = dialogView.findViewById<Button>(R.id.btnNewTab)
        val btnNewIncognitoTab = dialogView.findViewById<Button>(R.id.btnNewIncognitoTab)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        var tabsAdapter: TabRowAdapter? = null

        fun rebuild() {
            val items = tabs.mapIndexed { index, tab ->
                TabRowItem(
                    id = tab.id,
                    label = tab.title.ifBlank { tab.url },
                    isIncognito = tab.isIncognito,
                    isActive = index == currentTabIndex
                )
            }
            tabsAdapter?.submitList(items)
        }

        tabsAdapter = TabRowAdapter(
            onRowClick = { index ->
                switchToTab(index)
                dialog.dismiss()
            },
            onCloseClick = { index ->
                closeTab(index)
                if (tabs.isEmpty()) dialog.dismiss() else rebuild()
            }
        )
        listContainer.layoutManager = LinearLayoutManager(this)
        listContainer.adapter = tabsAdapter

        rebuild()

        btnNewTab.setOnClickListener {
            addNewTab()
            dialog.dismiss()
        }

        btnNewIncognitoTab.setOnClickListener {
            addNewTab(isIncognito = true)
            dialog.dismiss()
        }

        dialog.show()
    }

    // ---- History ----

    private fun showHistoryDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_history, null)
        val listContainer = dialogView.findViewById<LinearLayout>(R.id.historyListContainer)
        val emptyText = dialogView.findViewById<TextView>(R.id.historyEmptyText)
        val btnClear = dialogView.findViewById<Button>(R.id.btnClearHistory)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        fun rebuild() {
            listContainer.removeAllViews()
            val entries = HistoryStore.getAll(this)
            emptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
            entries.forEach { entry ->
                val row = layoutInflater.inflate(R.layout.item_history_row, listContainer, false) as TextView
                row.text = entry.title.ifBlank { entry.url }
                row.setOnClickListener {
                    activeWebView.loadUrlHonest(entry.url)
                    dialog.dismiss()
                }
                listContainer.addView(row)
            }
        }

        rebuild()

        btnClear.setOnClickListener {
            HistoryStore.clear(this)
            rebuild()
        }

        dialog.show()
    }

    // ---- Downloads ----

    // Shown on a long-press over an <img>; the actual download reuses startDownload()
    // below so it goes through the same storage-permission / DownloadManager path as
    // every other download in the app instead of a separate one-off code path.
    private fun confirmDownloadImage(wv: WebView, imageUrl: String) {
        AppFileLogger.log(this, "DOWNLOAD", "image dialog url=" + AppFileLogger.safeUrl(imageUrl))
        val guessedMime = imageUrl.substringAfterLast('.', "").substringBefore('?')
            .takeIf { it.isNotBlank() }
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.lowercase()) }
            ?: "image/*"
        AlertDialog.Builder(this)
            .setTitle(R.string.save_image_dialog_title)
            .setPositiveButton(R.string.save_image_action) { _, _ ->
                AppFileLogger.log(this, "DOWNLOAD", "image download confirmed mime=" + guessedMime)
                startDownload(imageUrl, wv.settings.userAgentString, "", guessedMime, wv.url)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun isGithubArtifactUiDownloadUrl(uri: Uri): Boolean {
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.host?.equals("github.com", ignoreCase = true) != true) return false

        val parts = uri.path.orEmpty().trim('/').split('/')
        if (parts.size != 7) return false
        if (parts[2] != "actions" || parts[3] != "runs" || parts[5] != "artifacts") return false
        if (!parts[4].all(Char::isDigit) || !parts[6].all(Char::isDigit)) return false
        return parts[0].isNotBlank() && parts[1].isNotBlank()
    }

    private fun isGithubArtifactDownloadUrl(uri: Uri): Boolean {
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        if (host != "api.github.com") return false
        val path = uri.path?.lowercase() ?: return false
        return Regex("^/repos/[^/]+/[^/]+/actions/artifacts/[0-9]+/zip$").matches(path)
    }

    private fun requestHeader(request: WebResourceRequest?, name: String): String? {
        return request?.requestHeaders?.entries
            ?.firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
    }

    private fun githubArtifactFileName(uri: Uri): String {
        val parts = uri.path.orEmpty().trimEnd('/').split('/')
        val artifactId = parts.getOrNull(parts.lastIndex - 1)
            ?.takeIf { it.all(Char::isDigit) }
        return if (artifactId != null) {
            "github-artifact-" + artifactId + ".zip"
        } else {
            "github-artifact.zip"
        }
    }

    private fun startDownloadFromWebResourceRequest(
        webView: WebView?,
        request: WebResourceRequest?,
        url: Uri
    ) {
        val userAgent = requestHeader(request, "User-Agent")
            ?: webView?.settings?.userAgentString.orEmpty()
        val referer = requestHeader(request, "Referer")
            ?: webView?.url
        val fileName = githubArtifactFileName(url)

        AppFileLogger.logNow(
            this,
            "DOWNLOAD",
            "github artifact download intercepted url=" +
                AppFileLogger.safeUrl(url.toString()) +
                " fileName=" + AppFileLogger.safeString(fileName)
        )
        AppFileLogger.traceNow(
            this,
            "DOWNLOAD_INTERCEPTED",
            "source=github-actions-artifact url=" +
                AppFileLogger.safeUrl(url.toString()) +
                " fileName=" + AppFileLogger.safeString(fileName)
        )

        runOnUiThread {
            startDownload(
                url.toString(),
                userAgent,
                "attachment; filename=" + fileName,
                "application/zip",
                referer
            )
        }
    }

    private fun attachDownloadListener(webView: WebView) {
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            try {
                val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
                AppFileLogger.logNow(
                    this,
                    "DOWNLOAD",
                    "listener scheme=" + AppFileLogger.safeString(scheme) +
                        " url=" + AppFileLogger.safeUrl(url) +
                        " mime=" + mimeType +
                        " length=" + contentLength +
                        " disposition=" + AppFileLogger.safeString(contentDisposition) +
                        " referer=" + AppFileLogger.safeUrl(webView.url)
                )
                AppFileLogger.traceNow(
                    this,
                    "DOWNLOAD_START",
                    "scheme=" + AppFileLogger.safeString(scheme) +
                        " url=" + AppFileLogger.safeUrl(url) +
                        " mime=" + AppFileLogger.safeString(mimeType) +
                        " length=" + contentLength +
                        " referer=" + AppFileLogger.safeUrl(webView.url)
                )
                if (scheme == "blob") {
                    handleBlobDownload(webView, url, contentDisposition, mimeType)
                } else {
                    startDownload(url, userAgent, contentDisposition, mimeType, webView.url)
                }
            } catch (t: Throwable) {
                AppFileLogger.logExceptionNow(this, "DOWNLOAD", "download listener failed", t)
                Toast.makeText(this, getString(R.string.download_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleBlobDownload(
        webView: WebView,
        blobUrl: String,
        contentDisposition: String,
        mimeType: String
    ) {
        AppFileLogger.logNow(
            this,
            "BLOB_DOWNLOAD",
            "requested url=" + AppFileLogger.safeUrl(blobUrl) +
                " mime=" + AppFileLogger.safeString(mimeType) +
                " disposition=" + AppFileLogger.safeString(contentDisposition)
        )

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingBlobDownload = PendingBlobDownload(
                webView,
                blobUrl,
                contentDisposition,
                mimeType
            )
            AppFileLogger.logNow(this, "BLOB_DOWNLOAD", "requesting WRITE_EXTERNAL_STORAGE")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_BLOB_STORAGE_PERMISSION
            )
            return
        }

        beginBlobDownload(
            webView,
            blobUrl,
            contentDisposition,
            mimeType
        )
    }

    private fun beginBlobDownload(
        webView: WebView,
        blobUrl: String,
        contentDisposition: String,
        mimeType: String
    ) {
        val token = UUID.randomUUID().toString()
        val bridgeName = "BlackBrowserBlobDownload_" + token.replace("-", "_")
        val bridge = BlobDownloadBridge(
            token,
            onComplete = { dataUrl ->
                runOnUiThread {
                    finishBlobDownload(
                        webView,
                        bridgeName,
                        dataUrl,
                        contentDisposition,
                        mimeType
                    )
                }
            },
            onError = { message ->
                runOnUiThread {
                    webView.removeJavascriptInterface(bridgeName)
                    AppFileLogger.logNow(
                        this,
                        "BLOB_DOWNLOAD",
                        "javascript extraction failed: " + AppFileLogger.safeString(message)
                    )
                    Toast.makeText(
                        this,
                        getString(R.string.download_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )

        webView.addJavascriptInterface(bridge, bridgeName)

        val quotedToken = org.json.JSONObject.quote(token)
        val quotedUrl = org.json.JSONObject.quote(blobUrl)
        val quotedBridge = org.json.JSONObject.quote(bridgeName)
        val maxBytes = MAX_BLOB_DOWNLOAD_BYTES
        val script = "(function(){" +
            "var token=" + quotedToken + ";" +
            "var url=" + quotedUrl + ";" +
            "var bridgeName=" + quotedBridge + ";" +
            "try{" +
            "fetch(url).then(function(response){" +
            "if(!response.ok)throw new Error('blob fetch HTTP '+response.status);" +
            "return response.blob();" +
            "}).then(function(blob){" +
            "if(blob.size>" + maxBytes + ")throw new Error('blob exceeds download size limit');" +
            "var reader=new FileReader();" +
            "reader.onloadend=function(){" +
            "try{window[bridgeName].complete(token,String(reader.result||''));}" +
            "catch(e){}" +
            "};" +
            "reader.onerror=function(){try{window[bridgeName].fail(token,'FileReader error');}catch(e){}};" +
            "reader.readAsDataURL(blob);" +
            "}).catch(function(e){" +
            "try{window[bridgeName].fail(token,String(e&&e.message||e));}catch(ignore){}" +
            "});" +
            "}catch(e){" +
            "try{window[bridgeName].fail(token,String(e&&e.message||e));}catch(ignore){}" +
            "}" +
            "})();"

        AppFileLogger.logNow(
            this,
            "BLOB_DOWNLOAD",
            "starting in-page blob extraction"
        )
        try {
            webView.evaluateJavascript(script, null)
        } catch (t: Throwable) {
            webView.removeJavascriptInterface(bridgeName)
            AppFileLogger.logExceptionNow(
                this,
                "BLOB_DOWNLOAD",
                "evaluateJavascript failed",
                t
            )
            Toast.makeText(
                this,
                getString(R.string.download_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun finishBlobDownload(
        webView: WebView,
        bridgeName: String,
        dataUrl: String,
        contentDisposition: String,
        requestedMimeType: String
    ) {
        try {
            webView.removeJavascriptInterface(bridgeName)

            val comma = dataUrl.indexOf(',')
            if (!dataUrl.startsWith("data:") || comma <= 5) {
                throw IllegalArgumentException("invalid blob data URL")
            }

            val metadata = dataUrl.substring(5, comma)
            if (!metadata.contains(";base64", ignoreCase = true)) {
                throw IllegalArgumentException("blob response is not base64")
            }

            val dataMimeType = metadata
                .substringBefore(';')
                .trim()
                .takeIf { it.isNotBlank() }
            val requestedMimeTypeValue = requestedMimeType
                .trim()
                .takeIf { it.isNotBlank() }
            val effectiveMimeType = when {
                dataMimeType != null &&
                    (requestedMimeTypeValue == null ||
                        extensionFromMimeType(requestedMimeTypeValue) == null) -> dataMimeType
                requestedMimeTypeValue != null -> requestedMimeTypeValue
                dataMimeType != null -> dataMimeType
                else -> "application/octet-stream"
            }

            val payload = dataUrl.substring(comma + 1)
            if (payload.length > MAX_BLOB_BASE64_CHARS) {
                throw IllegalArgumentException("encoded blob exceeds download size limit")
            }

            val bytes = Base64.decode(payload, Base64.DEFAULT)
            if (bytes.isEmpty()) {
                throw IllegalArgumentException("blob download returned no data")
            }
            if (bytes.size > MAX_BLOB_DOWNLOAD_BYTES) {
                throw IllegalArgumentException("decoded blob exceeds download size limit")
            }

            val fileName = contentDispositionFileName(contentDisposition)
                ?: "download" + (extensionFromMimeType(effectiveMimeType)?.let { ".$it" } ?: "")
            val safeFileName = sanitizeFileName(fileName)
            saveBlobToDownloads(safeFileName, effectiveMimeType, bytes)

            AppFileLogger.logNow(
                this,
                "BLOB_DOWNLOAD",
                "saved bytes=" + bytes.size +
                    " fileName=" + AppFileLogger.safeString(safeFileName) +
                    " mime=" + AppFileLogger.safeString(effectiveMimeType)
            )
            AppFileLogger.traceNow(
                this,
                "BLOB_DOWNLOAD_COMPLETE",
                "bytes=" + bytes.size +
                    " fileName=" + AppFileLogger.safeString(safeFileName)
            )
            Toast.makeText(
                this,
                getString(R.string.download_started, safeFileName),
                Toast.LENGTH_SHORT
            ).show()
        } catch (t: Throwable) {
            webView.removeJavascriptInterface(bridgeName)
            AppFileLogger.logExceptionNow(
                this,
                "BLOB_DOWNLOAD",
                "saving blob download failed",
                t
            )
            Toast.makeText(
                this,
                getString(R.string.download_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun saveBlobToDownloads(
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: throw IllegalStateException("could not create Downloads entry")

            try {
                contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(bytes)
                } ?: throw IllegalStateException("could not open Downloads output")

                val completeValues = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                contentResolver.update(uri, completeValues, null, null)
            } catch (t: Throwable) {
                contentResolver.delete(uri, null, null)
                throw t
            }
            return
        }

        val downloadsDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        if (!downloadsDir.exists() && !downloadsDir.mkdirs() && !downloadsDir.isDirectory) {
            throw IllegalStateException("could not create Downloads directory")
        }

        var target = File(downloadsDir, fileName)
        var index = 1
        while (target.exists()) {
            val dot = fileName.lastIndexOf('.')
            val base = if (dot > 0) fileName.substring(0, dot) else fileName
            val extension = if (dot > 0) fileName.substring(dot) else ""
            target = File(downloadsDir, base + " (" + index + ")" + extension)
            index++
        }

        FileOutputStream(target).use { output ->
            output.write(bytes)
        }
        android.media.MediaScannerConnection.scanFile(
            this,
            arrayOf(target.absolutePath),
            arrayOf(mimeType),
            null
        )
    }

    private fun startDownload(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String,
        referer: String?
    ) {
        AppFileLogger.log(
            this,
            "DOWNLOAD",
            "startDownload url=" + AppFileLogger.safeUrl(url) +
                " mime=" + mimeType +
                " disposition=" + AppFileLogger.safeString(contentDisposition) +
                " referer=" + AppFileLogger.safeUrl(referer)
        )
        try {
            val parsedUri = Uri.parse(url)
            if (parsedUri.scheme?.lowercase() !in setOf("http", "https")) {
                AppFileLogger.log(this, "DOWNLOAD", "rejected unsupported scheme=" + parsedUri.scheme)
                Toast.makeText(this, getString(R.string.download_failed), Toast.LENGTH_SHORT).show()
                return
            }

            val needsLegacyStoragePermission = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            AppFileLogger.log(this, "DOWNLOAD", "legacyStoragePermissionNeeded=" + needsLegacyStoragePermission)

            if (needsLegacyStoragePermission) {
                pendingDownload = PendingDownload(url, userAgent, contentDisposition, mimeType, referer)
                AppFileLogger.log(this, "DOWNLOAD", "requesting WRITE_EXTERNAL_STORAGE")
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQUEST_STORAGE_PERMISSION
                )
                return
            }

            enqueueDownload(url, userAgent, contentDisposition, mimeType, referer)
        } catch (t: Throwable) {
            AppFileLogger.logExceptionNow(this, "DOWNLOAD", "startDownload crashed", t)
            Toast.makeText(this, getString(R.string.download_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun enqueueDownload(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String,
        referer: String?
    ) {
        AppFileLogger.logNow(this, "DOWNLOAD", "enqueueDownload entered")
        try {
            val parsedUri = Uri.parse(url)
            if (parsedUri.scheme?.lowercase() !in setOf("http", "https")) {
                Toast.makeText(this, getString(R.string.download_failed), Toast.LENGTH_SHORT).show()
                return
            }

            val fileName = resolveDownloadFileName(url, contentDisposition, mimeType)
            AppFileLogger.log(this, "DOWNLOAD", "resolved fileName=" + AppFileLogger.safeString(fileName))
            val effectiveMimeType = mimeType.trim().ifBlank {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    fileName.substringAfterLast('.', "").lowercase()
                ) ?: "application/octet-stream"
            }
            val request = DownloadManager.Request(parsedUri).apply {
                CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let {
                    addRequestHeader("cookie", it)
                }
                userAgent.takeIf { it.isNotBlank() }?.let {
                    addRequestHeader("User-Agent", it)
                }
                referer?.takeIf { it.isNotBlank() }?.let {
                    addRequestHeader("Referer", it)
                }
                setMimeType(effectiveMimeType)
                setTitle(fileName)
                setDescription(getString(R.string.downloading))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: run {
                AppFileLogger.log(this, "DOWNLOAD", "DownloadManager service unavailable")
                Toast.makeText(this, getString(R.string.download_failed), Toast.LENGTH_SHORT).show()
                return
            }
            AppFileLogger.logNow(this, "DOWNLOAD", "calling DownloadManager.enqueue")
            val downloadId = dm.enqueue(request)
            synchronized(appDownloadIds) {
                appDownloadIds.add(downloadId)
            }
            AppFileLogger.logNow(this, "DOWNLOAD", "enqueue succeeded id=" + downloadId)
            AppFileLogger.traceNow(
                this,
                "DOWNLOAD_ENQUEUED",
                "id=" + downloadId + " fileName=" + AppFileLogger.safeString(fileName) +
                    " mime=" + AppFileLogger.safeString(effectiveMimeType)
            )
            Toast.makeText(this, getString(R.string.download_started, fileName), Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            AppFileLogger.logExceptionNow(this, "DOWNLOAD", "enqueueDownload crashed", t)
            Toast.makeText(this, getString(R.string.download_failed), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            val request = pendingDownload
            pendingDownload = null
            if (granted && request != null) {
                enqueueDownload(request.url, request.userAgent, request.contentDisposition, request.mimeType, request.referer)
            } else if (!granted) {
                Toast.makeText(this, getString(R.string.download_permission_denied), Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == REQUEST_BLOB_STORAGE_PERMISSION) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            val request = pendingBlobDownload
            pendingBlobDownload = null
            if (granted && request != null) {
                beginBlobDownload(
                    request.webView,
                    request.url,
                    request.contentDisposition,
                    request.mimeType
                )
            } else if (!granted) {
                Toast.makeText(
                    this,
                    getString(R.string.download_permission_denied),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ---- File chooser (uploads) ----

    private fun launchFileChooser(params: FileChooserParams?) {
        val contentIntent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        if (contentIntent.type == null) {
            contentIntent.type = "*/*"
        }

        val initialIntents = mutableListOf<Intent>()
        createCameraCaptureIntent()?.let { initialIntents.add(it) }

        val chooser = Intent.createChooser(contentIntent, getString(R.string.file_chooser_title))
        if (initialIntents.isNotEmpty()) {
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, initialIntents.toTypedArray())
        }

        try {
            fileChooserLauncher.launch(chooser)
        } catch (e: ActivityNotFoundException) {
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = null
        }
    }

    private fun createCameraCaptureIntent(): Intent? {
        val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (captureIntent.resolveActivity(packageManager) == null) return null
        return try {
            val fileName = "capture_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
            val photoFile = File(cacheDir, fileName)
            if (!photoFile.createNewFile()) throw IllegalStateException("could not create temporary camera file")
            val photoUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile)
            cameraCaptureFile = photoFile
            cameraImageUri = photoUri
            captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
            captureIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            captureIntent
        } catch (e: Exception) {
            cameraCaptureFile?.delete()
            cameraCaptureFile = null
            cameraImageUri = null
            null
        }
    }

    private fun handleFileChooserResult(resultCode: Int, data: Intent?) {
        val callback = fileChooserCallback
        fileChooserCallback = null
        if (callback == null) return

        if (resultCode != RESULT_OK) {
            callback.onReceiveValue(null)
            cameraImageUri = null
            cameraCaptureFile?.delete()
            cameraCaptureFile = null
            return
        }

        val results = mutableListOf<Uri>()
        val clipData = data?.clipData
        if (clipData != null) {
            for (i in 0 until clipData.itemCount) {
                clipData.getItemAt(i).uri?.let { results.add(it) }
            }
        } else {
            val dataUri = data?.data
            if (dataUri != null) {
                results.add(dataUri)
            } else if (cameraImageUri != null) {
                results.add(cameraImageUri!!)
            }
        }
        cameraImageUri = null
        cameraCaptureFile?.delete()
        cameraCaptureFile = null
        callback.onReceiveValue(if (results.isEmpty()) null else results.toTypedArray())
    }

    private fun resolveDownloadFileName(url: String, contentDisposition: String?, mimeType: String?): String {
        contentDispositionFileName(contentDisposition)?.let { return it }

        val urlName = runCatching { Uri.parse(url) }.getOrNull()
            ?.lastPathSegment
            ?.let { Uri.decode(it) }
            ?.substringBefore('?')
            ?.takeIf { it.isNotBlank() }

        if (urlName != null) {
            val ext = urlName.substringAfterLast('.', "")
            // The URL already carries a real extension (e.g. .apk) -> trust it over a
            // generic mimeType like application/octet-stream, which is what most servers
            // send for binary downloads and is what causes files to get renamed to a
            // meaningless ".bin" instead of keeping their real extension.
            if (ext.isNotEmpty() && ext.length <= 5 && ext.all { it.isLetterOrDigit() }) {
                return sanitizeFileName(urlName)
            }
            val guessedExt = extensionFromMimeType(mimeType)
            return sanitizeFileName(if (guessedExt != null) "$urlName.$guessedExt" else urlName)
        }

        return sanitizeFileName(URLUtil.guessFileName(url, contentDisposition, mimeType))
    }

    private fun contentDispositionFileName(contentDisposition: String?): String? {
        if (contentDisposition.isNullOrBlank()) return null

        Regex("filename\\*=(?:UTF-8'')?([^;]+)", RegexOption.IGNORE_CASE)
            .find(contentDisposition)?.groupValues?.get(1)?.trim()?.trim('"')
            ?.takeIf { it.isNotBlank() }
            ?.let { return sanitizeFileName(Uri.decode(it)) }

        Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
            .find(contentDisposition)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return sanitizeFileName(it) }

        return null
    }

    private fun extensionFromMimeType(mimeType: String?): String? {
        if (mimeType.isNullOrBlank()) return null
        val genericTypes = setOf(
            "application/octet-stream",
            "application/binary",
            "application/x-download",
            "binary/octet-stream"
        )
        if (mimeType.lowercase() in genericTypes) return null
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "download" }
    }

    // ---- Downloads list ----

    private data class DownloadEntry(
        val id: Long,
        val title: String,
        val status: Int,
        val localUri: String?,
        val mimeType: String?,
        val bytesDownloaded: Long,
        val bytesTotal: Long
    )

    private fun queryAllDownloads(): List<DownloadEntry> {
        return try {
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return emptyList()
            val entries = mutableListOf<DownloadEntry>()
            dm.query(DownloadManager.Query()).use { cursor ->
                val idIdx = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                val titleIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val uriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val mimeIdx = cursor.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
                val bytesDownloadedIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val bytesTotalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                while (cursor.moveToNext()) {
                    entries.add(
                        DownloadEntry(
                            id = if (idIdx >= 0) cursor.getLong(idIdx) else -1L,
                            title = if (titleIdx >= 0) cursor.getString(titleIdx) ?: "" else "",
                            status = if (statusIdx >= 0) cursor.getInt(statusIdx) else -1,
                            localUri = if (uriIdx >= 0) cursor.getString(uriIdx) else null,
                            mimeType = if (mimeIdx >= 0) cursor.getString(mimeIdx) else null,
                            bytesDownloaded = if (bytesDownloadedIdx >= 0) cursor.getLong(bytesDownloadedIdx) else 0L,
                            bytesTotal = if (bytesTotalIdx >= 0) cursor.getLong(bytesTotalIdx) else 0L
                        )
                    )
                }
            }
            entries.sortedByDescending { it.id }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun downloadStatusText(entry: DownloadEntry): String {
        val percent = if (entry.bytesTotal > 0) {
            ((entry.bytesDownloaded * 100L) / entry.bytesTotal).toInt().coerceIn(0, 100)
        } else {
            -1
        }
        return when (entry.status) {
            DownloadManager.STATUS_SUCCESSFUL -> getString(R.string.download_status_success)
            DownloadManager.STATUS_RUNNING ->
                if (percent >= 0) getString(R.string.download_status_running_percent, percent)
                else getString(R.string.download_status_running)
            DownloadManager.STATUS_PAUSED ->
                if (percent >= 0) getString(R.string.download_status_paused_percent, percent)
                else getString(R.string.download_status_paused)
            DownloadManager.STATUS_PENDING -> getString(R.string.download_status_pending)
            DownloadManager.STATUS_FAILED -> getString(R.string.download_status_failed)
            else -> ""
        }
    }

    private fun openDownloadedFile(entry: DownloadEntry) {
        if (entry.status != DownloadManager.STATUS_SUCCESSFUL) return
        try {
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val contentUri = dm.getUriForDownloadedFile(entry.id)
            val type = entry.mimeType ?: contentResolver.getType(contentUri) ?: "*/*"
            val openIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, type)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(openIntent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.download_open_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDownloadsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_downloads, null)
        val listContainer = dialogView.findViewById<LinearLayout>(R.id.downloadsListContainer)
        val emptyText = dialogView.findViewById<TextView>(R.id.downloadsEmptyText)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        val refreshHandler = Handler(Looper.getMainLooper())
        lateinit var refreshRunnable: Runnable
        refreshRunnable = Runnable {
            populateDownloadsList(dialog, listContainer, emptyText)
            if (dialog.isShowing) refreshHandler.postDelayed(refreshRunnable, 1000L)
        }
        dialog.setOnDismissListener { refreshHandler.removeCallbacks(refreshRunnable) }

        populateDownloadsList(dialog, listContainer, emptyText)
        dialog.show()
        refreshHandler.postDelayed(refreshRunnable, 1000L)
    }

    private fun populateDownloadsList(
        dialog: AlertDialog,
        listContainer: LinearLayout,
        emptyText: TextView
    ) {
        val entries = queryAllDownloads()
        emptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        listContainer.removeAllViews()
        entries.forEach { entry ->
            val row = layoutInflater.inflate(R.layout.item_download_row, listContainer, false)
            val rowFileName = row.findViewById<TextView>(R.id.rowFileName)
            val rowStatus = row.findViewById<TextView>(R.id.rowStatus)
            rowFileName.text = entry.title.ifBlank { entry.localUri ?: "" }
            rowStatus.text = downloadStatusText(entry)
            row.setOnClickListener {
                openDownloadedFile(entry)
                dialog.dismiss()
            }
            listContainer.addView(row)
        }
    }

    // ---- Desktop site / WebView channel ----

    // Hosts whose embedded "Continue with ..." / sign-in widgets refuse to render when the
    // User-Agent identifies the request as coming from an embedded WebView instead of a full
    // browser (detected via the "; wv" token and "Version/4.0 " prefix Android's default
    // WebView UA always includes). Stripping those tokens is ONLY safe to do for exactly
    // these navigation targets -- doing it globally (the previous behavior) made every
    // ordinary page load, including plain google.com/search, claim to be a full Chrome
    // browser while the underlying network/JS fingerprint still read as WebView. That
    // mismatch between the declared UA and the real fingerprint is a classic automated-
    // traffic signal, and is what was making Google show its "unusual traffic" verification
    // page on ordinary searches, not just on sign-in flows.
    private val uaSpoofHosts: Set<String> = setOf(
        "accounts.google.com",
        // Gmail's own web app (google.com/gmail -> mail.google.com) blocks/limits
        // embedded WebViews the same "; wv" / "Version/4.0 " way accounts.google.com's
        // sign-in page does -- it is a separate host from accounts.google.com and from
        // www.google.com/search, so it needed its own entry here rather than being
        // covered by either of those.
        "mail.google.com",
        "appleid.apple.com",
        "www.facebook.com",
        "m.facebook.com",
        "facebook.com",
        "login.microsoftonline.com",
        "login.live.com",
        "login.windows.net",
        "api.twitter.com",
        "twitter.com",
        "x.com",
        "login.yahoo.com",
        "github.com"
    )

    // Origin-match patterns (scheme + host, optional leading "*." wildcard for subdomains --
    // WebViewCompat.addDocumentStartJavaScript's own format) for exactly the hosts
    // uaSpoofHosts/hostNeedsUaSpoof already disguise at the network level. Used to scope
    // applyNavigatorUaPatch below to those origins instead of every page (see its comment).
    private val uaSpoofOriginRules: Set<String> =
        uaSpoofHosts.flatMap { listOf("https://$it", "https://*.$it") }.toSet()

    private fun hostNeedsUaSpoof(host: String?): Boolean =
        host != null && uaSpoofHosts.any { host == it || host.endsWith(".$it") }

    // The hop right after an identity-provider redirect needs the same no-cache treatment as
    // the identity-provider host itself: the URL it hands back to (e.g. google.com's own
    // "continue=" target) also carries a short-lived, one-time state token, and if that next
    // load gets served from cache instead of hitting the network, the stale response makes the
    // site bounce the flow straight back to sign-in again -- same loop, one hop later. `url`
    // still holds the page that's about to be left when this is checked, so it only looks one
    // hop back.
    private fun WebView.cameFromIdentityProvider(): Boolean =
        hostNeedsUaSpoof(runCatching { Uri.parse(url).host }.getOrNull())

    // Freshly re-derives the default UA (rather than reading back whatever a WebView's
    // settings currently hold) so toggling desktop mode on a tab that already had the
    // toggle applied once doesn't compound string replacements on top of each other.
    // `host` is the destination of the navigation about to happen (null when not yet
    // known, e.g. right after WebView creation, before the first load) -- the disguise
    // is only applied when that host actually needs it (see uaSpoofHosts above).
    private fun baseUserAgent(host: String?, forceSpoof: Boolean = false): String {
        val default = WebSettings.getDefaultUserAgent(this)
        if (!hostNeedsUaSpoof(host) && !forceSpoof) return default
        return default
            .replace("; wv", "")
            .replace("Version/4.0 ", "")
    }

    // Mirrors how Chrome's own "Request desktop site" works: swap only the platform token
    // and drop the "Mobile" marker, keeping the device's real WebKit/Chrome version intact
    // so the UA stays truthful about the rendering engine underneath it.
    private fun buildDesktopUserAgent(mobileUa: String): String =
        mobileUa.replace(" Mobile ", " ")

    private fun safeConsoleText(value: String?): String {
        if (value.isNullOrBlank()) return "<null>"
        return value.replace(Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)) { match ->
            AppFileLogger.safeUrl(match.value)
        }
    }

    private fun computeUserAgent(host: String? = null, forceSpoof: Boolean = false): String {
        val base = baseUserAgent(host, forceSpoof)
        return if (DesktopModePrefs.isEnabled(this)) buildDesktopUserAgent(base) else base
    }

    // WebView.loadUrl() -- unlike a link click or redirect the page itself triggers --
    // never reaches shouldOverrideUrlLoading below, so it would otherwise keep whatever
    // User-Agent the WebView last had. Routing every app-initiated navigation through
    // here keeps the honest-vs-disguised UA decision (see baseUserAgent) correct for the
    // actual destination instead of leaking over from whatever page was open before.
    // Prevents one user action from dispatching the exact same top-level URL twice
    // while the first navigation is still in flight. This does not block redirects,
    // subresources, or a deliberate new navigation after the current one finishes.
    private val inFlightAppNavigationUrls = java.util.WeakHashMap<WebView, String>()
    private val pageFinishGate = PageFinishGate<WebView>()
    private val mainFrameRetryGuards = java.util.WeakHashMap<WebView, MainFrameRetryGuard>()
    private val activeGoogleRateLimitFallbackUrls = java.util.WeakHashMap<WebView, String>()

    private fun mainFrameRetryGuardFor(webView: WebView): MainFrameRetryGuard =
        mainFrameRetryGuards.getOrPut(webView) { MainFrameRetryGuard() }

    private fun isGoogleSorryUrl(url: String): Boolean {
        return runCatching { GoogleRateLimitPolicy.isGoogleRateLimit(url, 429) }.getOrDefault(false)
    }

    private fun isLocalRateLimitFallbackUrl(url: String): Boolean {
        return url == "https://blackbrowser.invalid/rate-limit-fallback"
    }

    private fun isGoogleSettingsUrl(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        if (host != "www.google.com" && host != "google.com") return false

        val path = uri.path?.lowercase() ?: "/"
        return path == "/preferences" ||
            path == "/preferences/" ||
            path == "/safesearch" ||
            path == "/safesearch/"
    }

    private fun WebView.loadUrlHonest(url: String) {
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

    // The legacy UA string above and User-Agent Client Hints (the Sec-CH-UA-Mobile /
    // Sec-CH-UA-Platform request headers, and navigator.userAgentData in JS) are two
    // independent WebView settings. UserAgentMetadata.Builder defaults "mobile" to true
    // regardless of any custom userAgentString, so a site reading Client Hints instead of (or
    // together with) the classic UA string still sees a mobile client after the swap above --
    // this is why desktop mode only worked on flows still driven by classic UA sniffing.
    private fun applyUserAgentMetadata(wv: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
        val base = defaultUaMetadata.getOrPut(wv) {
            WebSettingsCompat.getUserAgentMetadata(wv.settings)
        }
        val metadata = if (DesktopModePrefs.isEnabled(this)) {
            UserAgentMetadata.Builder(base)
                .setMobile(false)
                .build()
        } else {
            base
        }
        WebSettingsCompat.setUserAgentMetadata(wv.settings, metadata)
    }

    // WebView only honors User-Agent Client Hints for apps that send the *default* UA
    // string; since computeUserAgent() always overrides it (see comment above), the
    // Sec-CH-UA-Mobile/-Platform headers and navigator.userAgentData in JS keep reporting
    // the real mobile device to any site that reads them, regardless of what
    // applyUserAgentMetadata() above just set -- this is why desktop mode only visibly
    // worked on sites still sniffing the classic UA string (e.g. Google Search), not on
    // sites gating their layout on Client Hints/userAgentData. Patching userAgentData
    // client-side via a document-start script is the only reliable way to cover those too.
    //
    // Even with the UA string, Client Hints, and userAgentData all lying about the
    // platform, most modern sites don't branch server-side on any of that at all -- they
    // ship one responsive page and pick mobile vs desktop CSS purely off the real
    // viewport width via their own <meta name="viewport"> tag, which WebView's
    // useWideViewPort setting always defers to when present. So the remaining piece is
    // rewriting that tag to a fixed desktop-sized width the moment it appears in the DOM,
    // the same way Chrome's own "Request desktop site" forces a wide virtual viewport
    // instead of just relying on the phone's real screen width.
    private fun applyUserAgentDataOverride(wv: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        uaScriptHandlers.remove(wv)?.remove()
        if (!DesktopModePrefs.isEnabled(this)) return

        val js = """
            (function() {
                if (window.navigator.userAgentData) {
                    var fake = {
                        brands: navigator.userAgentData.brands,
                        mobile: false,
                        platform: navigator.userAgentData.platform,
                        getHighEntropyValues: function() {
                            return Promise.resolve({
                                brands: navigator.userAgentData.brands,
                                mobile: false,
                                platform: navigator.userAgentData.platform,
                                platformVersion: navigator.userAgentData.platformVersion
                            });
                        }
                    };
                    Object.defineProperty(window.navigator, 'userAgentData', {
                        get: function() { return fake; },
                        configurable: true
                    });
                }

                var DESKTOP_VIEWPORT = "width=1024, initial-scale=1";
                function forceDesktopViewport(meta) {
                    if (meta.getAttribute('content') !== DESKTOP_VIEWPORT) {
                        meta.setAttribute('content', DESKTOP_VIEWPORT);
                    }
                }
                var existing = document.querySelector('meta[name="viewport"]');
                if (existing) forceDesktopViewport(existing);
                new MutationObserver(function(mutations) {
                    for (var i = 0; i < mutations.length; i++) {
                        var added = mutations[i].addedNodes;
                        for (var j = 0; j < added.length; j++) {
                            var node = added[j];
                            if (node.nodeType === 1) {
                                if (node.tagName === 'META' && node.getAttribute('name') === 'viewport') {
                                    forceDesktopViewport(node);
                                } else if (node.querySelector) {
                                    var nested = node.querySelector('meta[name="viewport"]');
                                    if (nested) forceDesktopViewport(nested);
                                }
                            }
                        }
                        if (mutations[i].type === 'attributes' &&
                            mutations[i].target.tagName === 'META' &&
                            mutations[i].target.getAttribute('name') === 'viewport') {
                            forceDesktopViewport(mutations[i].target);
                        }
                    }
                }).observe(document, {
                    childList: true,
                    subtree: true,
                    attributes: true,
                    attributeFilter: ['content']
                });
            })();
        """.trimIndent()

        uaScriptHandlers[wv] = WebViewCompat.addDocumentStartJavaScript(wv, js, setOf("*"))
    }


    private fun updateDesktopSiteIcon() {
        val enabled = DesktopModePrefs.isEnabled(this)
        val color = ContextCompat.getColor(
            this,
            if (enabled) R.color.desktop_on else R.color.desktop_off
        )
        btnDesktopSite.setColorFilter(color)
        btnDesktopSite.contentDescription = getString(
            if (enabled) R.string.desktop_site_desc_on else R.string.desktop_site_desc_off
        )
    }

    // Android ships WebView as a separately updatable system component: an app can read
    // which provider/version is currently active but cannot silently install a new one --
    // only Play Store, or the user via Settings > Developer options > WebView implementation,
    // can do that. This checks the active provider and, if it's a pre-release channel (Beta,
    // Dev, Canary, or a debug/AOSP build) rather than the Stable package, offers a one-tap
    // link to Stable on Play instead of leaving the app running on a beta WebView.
    private fun checkWebViewChannel() {
        val pkg = WebViewCompat.getCurrentWebViewPackage(this) ?: return
        if (pkg.packageName == STABLE_WEBVIEW_PACKAGE) return

        val prefs = getSharedPreferences("webview_channel_prefs", Context.MODE_PRIVATE)
        val dismissedKey = "${pkg.packageName}:${pkg.versionName}"
        if (prefs.getString("dismissed", null) == dismissedKey) return

        AlertDialog.Builder(this)
            .setTitle(R.string.webview_channel_warning_title)
            .setMessage(
                getString(
                    R.string.webview_channel_warning_message,
                    pkg.packageName,
                    pkg.versionName ?: "?"
                )
            )
            .setPositiveButton(R.string.webview_open_play_store) { _, _ ->
                openPlayStoreForStableWebView()
            }
            .setNegativeButton(R.string.webview_dismiss) { _, _ ->
                prefs.edit().putString("dismissed", dismissedKey).apply()
            }
            .show()
    }

    private fun openPlayStoreForStableWebView() {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$STABLE_WEBVIEW_PACKAGE"))
            )
        } catch (e: Exception) {
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=$STABLE_WEBVIEW_PACKAGE")
                    )
                )
            } catch (e2: Exception) {
                Toast.makeText(this, getString(R.string.webview_play_store_open_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---- External / intent scheme links ----

    // Chrome/Android's intent:// syntax lets a web page launch a native app (most often a
    // Play Store deep link) with a browser fallback baked into the URI itself. WebView has no
    // built-in handling for it, so without this it fails outright with
    // net::ERR_UNKNOWN_URL_SCHEME instead of opening the app, its fallback page, or Play Store.
    private fun handleIntentScheme(url: String): Boolean {
        val intent = try {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } catch (e: Exception) {
            return true // malformed intent:// URI -- nothing safe to load, just swallow it
        }
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null

        return try {
            startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            // Nothing registered for the intent's own target package (e.g. Play Store isn't
            // installed) -- fall back to the site's explicit browser_fallback_url extra, or
            // to the http(s) URL the intent:// already encodes via scheme=/host/path/query
            // (for a Play Store link this reconstructs to the normal play.google.com page).
            val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                ?: intent.data?.takeIf { it.scheme == "http" || it.scheme == "https" }?.toString()
            if (fallbackUrl != null) {
                activeWebView.loadUrlHonest(fallbackUrl)
            }
            true
        }
    }

    // Any other scheme WebView can't render itself (market:, tel:, mailto:, whatsapp:, geo:,
    // etc.) -- hand it to whichever app on the device claims it instead of failing to load it.
    private fun handleExternalScheme(url: String): Boolean {
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            true
        } catch (e: ActivityNotFoundException) {
            true // no app installed to handle it -- nothing else to do
        } catch (e: Exception) {
            true // malformed URI for this scheme -- nothing safe to load
        }
    }


    // ---- Ad block / default browser ----

    private fun updateAdBlockIcon() {
        val enabled = AdBlockPrefs.isEnabled(this)
        val color = ContextCompat.getColor(
            this,
            if (enabled) R.color.adblock_on else R.color.adblock_off
        )
        btnAdBlock.setColorFilter(color)
        btnAdBlock.contentDescription = getString(
            if (enabled) R.string.adblock_desc_on else R.string.adblock_desc_off
        )
    }

    private fun requestDefaultBrowser() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null &&
                roleManager.isRoleAvailable(RoleManager.ROLE_BROWSER) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_BROWSER)
            ) {
                defaultBrowserRoleLauncher.launch(
                    roleManager.createRequestRoleIntent(RoleManager.ROLE_BROWSER)
                )
                return
            }
        }
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.set_default_browser_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun isDefaultBrowser(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
                return roleManager.isRoleHeld(RoleManager.ROLE_BROWSER)
            }
        }
        val resolveInfo = packageManager.resolveActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("http://")),
            PackageManager.MATCH_DEFAULT_ONLY
        )
        return resolveInfo?.activityInfo?.packageName == packageName
    }

    private fun updateDefaultBrowserButtonVisibility() {
        btnSetDefaultBrowser.visibility = if (isDefaultBrowser()) View.GONE else View.VISIBLE
    }

    private fun loadFromInput() {
        val input = BrowserNavigation.toUrl(editUrl.text.toString())
        if (input.isBlank()) return
        activeWebView.loadUrlHonest(input)
    }

    // ---- Fullscreen video ----

    private fun exitFullscreenVideo() {
        val view = fullscreenCustomView ?: return
        fullscreenContainer.removeView(view)
        fullscreenContainer.visibility = View.GONE
        fullscreenCustomView = null
        fullscreenCustomViewCallback?.onCustomViewHidden()
        fullscreenCustomViewCallback = null
        exitImmersiveMode()
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun exitImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    private fun handleBackPressed() {
        if (fullscreenCustomView != null) {
            exitFullscreenVideo()
            return
        }
        if (activeWebView.canGoBack()) {
            activeWebView.goBack()
        } else {
            finish()
        }
    }

    private fun networkTransportSummary(): String {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "unavailable"
            val network = cm.activeNetwork ?: return "none"
            val caps = cm.getNetworkCapabilities(network) ?: return "unknown"
            val types = mutableListOf<String>()
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) types.add("WIFI")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) types.add("CELLULAR")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) types.add("VPN")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) types.add("ETHERNET")
            if (types.isEmpty()) types.add("OTHER")
            types.joinToString("+") + " validated=" +
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (t: Throwable) {
            "error:" + t.javaClass.simpleName
        }
    }

    companion object {
        private const val REQUEST_STORAGE_PERMISSION = 1001
        private const val REQUEST_BLOB_STORAGE_PERMISSION = 1002
        private const val MAX_BLOB_DOWNLOAD_BYTES = 50 * 1024 * 1024
        private const val MAX_BLOB_BASE64_CHARS =
            ((MAX_BLOB_DOWNLOAD_BYTES + 2) / 3) * 4 + 128
        private const val STABLE_WEBVIEW_PACKAGE = "com.google.android.webview"
    }
}

/** Immutable snapshot of one tab row, used to diff the tabs dialog's RecyclerView. */
private data class TabRowItem(
    val id: Int,
    val label: String,
    val isIncognito: Boolean,
    val isActive: Boolean
)

/**
 * Backs the tabs dialog's list. Replaces the previous approach of clearing and
 * re-inflating every row on any change (new tab, closed tab, switched tab) -- with
 * DiffUtil, only the rows that actually changed get rebound.
 */
private class TabRowAdapter(
    private val onRowClick: (Int) -> Unit,
    private val onCloseClick: (Int) -> Unit
) : ListAdapter<TabRowItem, TabRowAdapter.ViewHolder>(DIFF_CALLBACK) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.rowIncognitoIcon)
        val text: TextView = view.findViewById(R.id.rowText)
        val close: TextView = view.findViewById(R.id.rowClose)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tab_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.icon.visibility = if (item.isIncognito) View.VISIBLE else View.GONE
        holder.text.text = if (item.isActive) "\u25CF ${item.label}" else item.label
        holder.itemView.setOnClickListener { onRowClick(holder.bindingAdapterPosition) }
        holder.close.setOnClickListener { onCloseClick(holder.bindingAdapterPosition) }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<TabRowItem>() {
            override fun areItemsTheSame(oldItem: TabRowItem, newItem: TabRowItem) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: TabRowItem, newItem: TabRowItem) =
                oldItem == newItem
        }
    }
}
