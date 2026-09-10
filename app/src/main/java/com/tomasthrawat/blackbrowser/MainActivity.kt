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
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var editUrl: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var btnAdBlock: ImageButton

    private val homeUrl = "https://www.google.com"

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

        webView = findViewById(R.id.webView)
        editUrl = findViewById(R.id.editUrl)
        progressBar = findViewById(R.id.progressBar)
        btnAdBlock = findViewById(R.id.btnAdBlock)

        val btnBack: ImageButton = findViewById(R.id.btnBack)
        val btnForward: ImageButton = findViewById(R.id.btnForward)
        val btnReload: ImageButton = findViewById(R.id.btnReload)
        val btnSetLauncher: ImageButton = findViewById(R.id.btnSetLauncher)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true
        webView.settings.offscreenPreRaster = true
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                editUrl.setText(url)
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

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress in 1..99) ProgressBar.VISIBLE else ProgressBar.GONE
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType, webView.url)
        }

        btnBack.setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }

        btnForward.setOnClickListener {
            if (webView.canGoForward()) webView.goForward()
        }

        btnReload.setOnClickListener {
            webView.reload()
        }

        btnSetLauncher.setOnClickListener {
            requestDefaultLauncher()
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
            webView.reload()
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

        webView.loadUrl(homeUrl)
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

    private fun requestDefaultLauncher() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null &&
                roleManager.isRoleAvailable(RoleManager.ROLE_HOME) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_HOME)
            ) {
                startActivityForResult(
                    roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME),
                    REQUEST_SET_DEFAULT_LAUNCHER
                )
                return
            }
        }
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.set_launcher_failed), Toast.LENGTH_SHORT).show()
        }
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

        webView.loadUrl(input)
    }

    @Suppress("DEPRECATION", "MissingSuperCall")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        private const val REQUEST_STORAGE_PERMISSION = 1001
        private const val REQUEST_SET_DEFAULT_LAUNCHER = 1002
    }
}
