package com.tomasthrawat.blackbrowser

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    private data class Tab(
        val id: Int,
        val webView: WebView,
        var title: String,
        var url: String
    )

    private lateinit var webViewContainer: FrameLayout
    private lateinit var editUrl: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var btnAdBlock: ImageButton
    private lateinit var btnSetDefaultBrowser: ImageButton
    private lateinit var btnHistory: ImageButton
    private lateinit var btnDownloads: ImageButton
    private lateinit var btnTabsBox: TextView

    private val homeUrl = "https://www.google.com"

    private val tabs = mutableListOf<Tab>()
    private var currentTabIndex = 0
    private var nextTabId = 1

    private val activeWebView: WebView
        get() = tabs[currentTabIndex].webView

    private data class PendingDownload(
        val url: String,
        val userAgent: String,
        val contentDisposition: String,
        val mimeType: String,
        val referer: String?
    )

    private var pendingDownload: PendingDownload? = null

    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id == -1L) return

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val cursor = dm.query(DownloadManager.Query().setFilterById(id))
            cursor.use {
                if (!it.moveToFirst()) return@use

                val statusIdx = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val mimeIdx = it.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
                val status = if (statusIdx >= 0) it.getInt(statusIdx) else -1
                val mime = if (mimeIdx >= 0) it.getString(mimeIdx) else null

                if (status == DownloadManager.STATUS_SUCCESSFUL &&
                    mime == "application/vnd.android.package-archive"
                ) {
                    try {
                        val contentUri = dm.getUriForDownloadedFile(id)
                        val installIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(contentUri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(installIntent)
                    } catch (e: Exception) {
                        Toast.makeText(context, getString(R.string.download_open_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Loads the bundled extended blocklist (HaGeZi/1Hosts/oisd/StevenBlack merge, ~321k
        // domains) from assets. Done on a background thread since it parses a few MB of text;
        // shouldBlock() keeps working off the smaller starting set until this finishes.
        Thread { AdBlocker.loadExtendedBlocklist(applicationContext) }.start()

        webViewContainer = findViewById(R.id.webViewContainer)
        editUrl = findViewById(R.id.editUrl)
        progressBar = findViewById(R.id.progressBar)
        btnAdBlock = findViewById(R.id.btnAdBlock)
        btnSetDefaultBrowser = findViewById(R.id.btnSetDefaultBrowser)
        btnHistory = findViewById(R.id.btnHistory)
        btnDownloads = findViewById(R.id.btnDownloads)
        btnTabsBox = findViewById(R.id.btnTabsBox)

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

        btnHistory.setOnClickListener {
            showHistoryDialog()
        }

        btnDownloads.setOnClickListener {
            showDownloadsDialog()
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.dataString?.let { addNewTab(it) }
    }

    override fun onResume() {
        super.onResume()
        updateDefaultBrowserButtonVisibility()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            downloadCompleteReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
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
        super.onDestroy()
        tabs.forEach { it.webView.destroy() }
    }

    // ---- Tabs ----

    private fun createWebView(): WebView {
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
        wv.settings.offscreenPreRaster = true
        // Some login/redirect chains still serve a stray http:// sub-resource from an
        // otherwise https:// page; without this WebView silently drops it and the page can
        // get stuck instead of completing its redirect.
        wv.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
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
        // and serve a blank/blocked state instead of the button. Stripping both makes those
        // pages see an ordinary mobile Chrome UA and the button renders normally.
        wv.settings.userAgentString = wv.settings.userAgentString
            .replace("; wv", "")
            .replace("Version/4.0 ", "")

        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // Pure-black rendering for every site, by default, when the installed WebView build
        // supports it (androidx.webkit feature-detected — never assumed).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.settings, true)
        }

        // Third-party cookies are off by default per-WebView; most cross-domain sign-in
        // redirects (Google/Facebook/GitHub OAuth callbacks, etc.) depend on them to complete.
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

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
                if (AdBlockPrefs.isEnabled(this@MainActivity) && AdBlocker.shouldBlock(url)) {
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val tab = tabs.find { it.webView === view } ?: return
                tab.url = url ?: tab.url
                tab.title = view?.title?.takeIf { it.isNotBlank() } ?: tab.url
                if (tabs.getOrNull(currentTabIndex)?.webView === view) {
                    editUrl.setText(tab.url)
                }
                HistoryStore.add(this@MainActivity, tab.title, tab.url)

                if (AdBlockPrefs.isEnabled(this@MainActivity)) {
                    val css = org.json.JSONObject.quote(AdBlocker.cosmeticHideCss())
                    view?.evaluateJavascript(
                        "(function(){var s=document.createElement('style');" +
                            "s.type='text/css';s.appendChild(document.createTextNode($css));" +
                            "document.head.appendChild(s);})();",
                        null
                    )
                }
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url
                if (url != null && AdBlockPrefs.isEnabled(this@MainActivity) && AdBlocker.shouldBlock(url)) {
                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (tabs.getOrNull(currentTabIndex)?.webView === view) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress in 1..99) ProgressBar.VISIBLE else ProgressBar.GONE
                }
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

                // Captured now: a tap-hijacking ad overlay can navigate the opener itself
                // before the popup's first URL resolves, which would otherwise let a blocked
                // destination borrow the *new* opener URL as its own trusted origin.
                val openerUrl = view?.url

                val popup = WebView(this@MainActivity)
                popup.settings.javaScriptEnabled = true
                popup.settings.domStorageEnabled = true
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val destUrl = request?.url
                        popup.destroy()
                        if (destUrl == null) return true

                        val adBlockOn = AdBlockPrefs.isEnabled(this@MainActivity)
                        val isUnwantedPopup = adBlockOn &&
                            (AdBlocker.shouldBlock(destUrl) ||
                                !AdBlocker.isTrustedPopupDestination(destUrl, openerUrl))
                        if (isUnwantedPopup) {
                            return true
                        }
                        activeWebView.loadUrl(destUrl.toString())
                        return true
                    }
                }
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }

        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType, wv.url)
        }

        return wv
    }

    private fun addNewTab(url: String = homeUrl) {
        val wv = createWebView()
        val tab = Tab(nextTabId++, wv, getString(R.string.new_tab_title), url)
        tabs.add(tab)
        wv.loadUrl(url)
        switchToTab(tabs.size - 1)
        updateTabsBoxCount()
    }

    private fun switchToTab(index: Int) {
        if (index !in tabs.indices) return
        currentTabIndex = index
        webViewContainer.removeAllViews()
        webViewContainer.addView(tabs[index].webView)
        editUrl.setText(tabs[index].url)
        updateDefaultBrowserButtonVisibility()
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val tab = tabs[index]
        val wasActive = index == currentTabIndex
        tabs.removeAt(index)
        webViewContainer.removeView(tab.webView)
        tab.webView.stopLoading()
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
        val listContainer = dialogView.findViewById<LinearLayout>(R.id.tabsListContainer)
        val btnNewTab = dialogView.findViewById<Button>(R.id.btnNewTab)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        fun rebuild() {
            listContainer.removeAllViews()
            tabs.forEachIndexed { index, tab ->
                val row = layoutInflater.inflate(R.layout.item_tab_row, listContainer, false)
                val rowText = row.findViewById<TextView>(R.id.rowText)
                val rowClose = row.findViewById<TextView>(R.id.rowClose)
                val label = tab.title.ifBlank { tab.url }
                rowText.text = if (index == currentTabIndex) "\u25CF $label" else label
                row.setOnClickListener {
                    switchToTab(index)
                    dialog.dismiss()
                }
                rowClose.setOnClickListener {
                    closeTab(index)
                    if (tabs.isEmpty()) dialog.dismiss() else rebuild()
                }
                listContainer.addView(row)
            }
        }

        rebuild()

        btnNewTab.setOnClickListener {
            addNewTab()
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
                    activeWebView.loadUrl(entry.url)
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

    private fun startDownload(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String,
        referer: String?
    ) {
        val needsLegacyStoragePermission = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED

        if (needsLegacyStoragePermission) {
            pendingDownload = PendingDownload(url, userAgent, contentDisposition, mimeType, referer)
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_STORAGE_PERMISSION
            )
            return
        }

        enqueueDownload(url, userAgent, contentDisposition, mimeType, referer)
    }

    private fun enqueueDownload(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String,
        referer: String?
    ) {
        try {
            val fileName = resolveDownloadFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("cookie", it) }
                addRequestHeader("User-Agent", userAgent)
                referer?.let { addRequestHeader("Referer", it) }
                setMimeType(mimeType)
                setTitle(fileName)
                setDescription(getString(R.string.downloading))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, getString(R.string.download_started, fileName), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
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
        }
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
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
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
        return entries.sortedByDescending { it.id }
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
                startActivityForResult(
                    roleManager.createRequestRoleIntent(RoleManager.ROLE_BROWSER),
                    REQUEST_SET_DEFAULT_BROWSER
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
        var input = editUrl.text.toString().trim()
        if (input.isEmpty()) return

        val looksLikeUrl = input.contains(".") && !input.contains(" ")
        input = if (looksLikeUrl) {
            if (!input.startsWith("http://") && !input.startsWith("https://")) {
                "https://$input"
            } else {
                input
            }
        } else {
            "https://www.google.com/search?q=${Uri.encode(input)}"
        }

        activeWebView.loadUrl(input)
    }

    @Suppress("DEPRECATION", "MissingSuperCall")
    override fun onBackPressed() {
        if (activeWebView.canGoBack()) {
            activeWebView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        private const val REQUEST_STORAGE_PERMISSION = 1001
        private const val REQUEST_SET_DEFAULT_BROWSER = 1002
    }
}
