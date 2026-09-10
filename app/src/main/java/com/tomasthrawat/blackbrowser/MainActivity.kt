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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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

        webViewContainer = findViewById(R.id.webViewContainer)
        editUrl = findViewById(R.id.editUrl)
        progressBar = findViewById(R.id.progressBar)
        btnAdBlock = findViewById(R.id.btnAdBlock)
        btnSetDefaultBrowser = findViewById(R.id.btnSetDefaultBrowser)
        btnHistory = findViewById(R.id.btnHistory)
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
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.loadWithOverviewMode = true
        wv.settings.useWideViewPort = true
        wv.settings.offscreenPreRaster = true
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val tab = tabs.find { it.webView === view } ?: return
                tab.url = url ?: tab.url
                tab.title = view?.title?.takeIf { it.isNotBlank() } ?: tab.url
                if (tabs.getOrNull(currentTabIndex)?.webView === view) {
                    editUrl.setText(tab.url)
                }
                HistoryStore.add(this@MainActivity, tab.title, tab.url)
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
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
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
