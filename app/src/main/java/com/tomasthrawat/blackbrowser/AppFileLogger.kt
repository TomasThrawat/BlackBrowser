package com.tomasthrawat.blackbrowser

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Persistent diagnostic logger.
 *
 * Android 10+ uses the public Download collection through MediaStore, so the diagnostic file
 * appears in Downloads without legacy storage permission. Older Android versions use the public
 * Downloads directory. All logger failures are swallowed so logging can never crash the browser.
 */
object AppFileLogger {
    private const val PREFS = "blackbrowser_debug_logging"
    private const val KEY_URI = "log_uri"
    private const val FILE_NAME = "BlackBrowser-debug.log"
    private const val TRACE_PREFS = "blackbrowser_trace_logging"
    private const val TRACE_KEY_URI = "trace_uri"
    private const val TRACE_FILE_NAME = "BlackBrowser-trace.log"
    private const val MIME_TYPE = "text/plain"

    private val executor = Executors.newSingleThreadExecutor()
    private val lock = Any()

    fun initialize(context: Context) {
        val app = context.applicationContext
        log(app, "LOGGER", "initialized sdk=" + Build.VERSION.SDK_INT + " model=" + Build.MODEL)
        trace(app, "SESSION", "start sdk=" + Build.VERSION.SDK_INT + " model=" + Build.MODEL)
    }

    fun trace(context: Context, event: String, details: String = "") {
        val app = context.applicationContext
        val line = formatLine("TRACE:" + event, details)
        try {
            executor.execute {
                appendLine(app, line, traceFile = true)
            }
        } catch (_: Throwable) {
        }
    }

    fun installCrashHandler(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                logExceptionNow(app, "FATAL", "uncaught exception thread=" + thread.name, throwable)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
        log(app, "LOGGER", "uncaught exception handler installed")
    }

    fun traceNow(context: Context, event: String, details: String = "") {
        val app = context.applicationContext
        val line = formatLine("TRACE:" + event, details)
        try {
            appendLine(app, line, traceFile = true)
        } catch (_: Throwable) {
        }
    }

    fun logNow(context: Context, tag: String, message: String) {
        val app = context.applicationContext
        val line = formatLine(tag, message)
        try {
            appendLine(app, line)
        } catch (_: Throwable) {
        }
    }

    fun log(context: Context, tag: String, message: String) {
        val app = context.applicationContext
        val line = formatLine(tag, message)
        try {
            executor.execute {
                appendLine(app, line)
            }
        } catch (_: Throwable) {
        }
    }

    fun logExceptionNow(
        context: Context,
        tag: String,
        message: String,
        throwable: Throwable
    ) {
        val app = context.applicationContext
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        val line = formatLine(tag, message + "\n" + writer.toString())
        try {
            synchronized(lock) {
                appendLine(app, line)
            }
        } catch (_: Throwable) {
        }
    }

    fun safeString(value: String?): String =
        value?.replace("\n", "\\n")?.replace("\r", "\\r") ?: "<null>"

    fun safeUrl(value: String?): String {
        if (value.isNullOrBlank()) return "<null>"
        return try {
            val uri = Uri.parse(value)
            val builder = Uri.Builder()
            uri.scheme?.let(builder::scheme)
            uri.authority?.let(builder::authority)
            uri.path?.let(builder::path)
            val redacted = mutableListOf<Pair<String, String>>()
            for (name in uri.queryParameterNames) {
                val lower = name.lowercase(Locale.US)
                val sensitive = lower.contains("token") ||
                    lower.contains("secret") ||
                    lower.contains("password") ||
                    lower == "code" ||
                    lower.contains("auth") ||
                    lower.contains("signature") ||
                    lower == "sig" ||
                    lower.startsWith("x-amz-")
                redacted.add(name to if (sensitive) "<redacted>" else (uri.getQueryParameter(name) ?: ""))
            }
            if (redacted.isNotEmpty()) {
                builder.encodedQuery(
                    redacted.joinToString("&") { (name, queryValue) ->
                        Uri.encode(name) + "=" + Uri.encode(queryValue)
                    }
                )
            }
            builder.build().toString()
        } catch (_: Throwable) {
            safeString(value)
        }
    }

    fun safeUri(uri: Uri?): String = uri?.let {
        try {
            val builder = Uri.Builder()
            it.scheme?.let(builder::scheme)
            it.authority?.let(builder::authority)
            it.path?.let(builder::path)
            builder.build().toString()
        } catch (_: Throwable) {
            "<invalid-uri>"
        }
    } ?: "<null>"

    private fun formatLine(tag: String, message: String): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        return "[" + timestamp + "] [" + Thread.currentThread().name + "] [" + tag + "] " +
            message + "\n"
    }

    private fun appendLine(context: Context, line: String, traceFile: Boolean = false) {
        synchronized(lock) {
            try {
                val uri = if (traceFile) { ensureTraceUri(context) } else { ensureLogUri(context) } ?: return
                context.contentResolver.openOutputStream(uri, "wa")?.use { output ->
                    output.write(line.toByteArray(Charsets.UTF_8))
                    output.flush()
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun ensureLogUri(context: Context): Uri? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_URI, null)?.let {
            try {
                Uri.parse(it)
            } catch (_: Throwable) {
                null
            }
        }
        if (existing != null) return existing

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: return null
            prefs.edit().putString(KEY_URI, uri.toString()).apply()
            uri
        } else {
            val downloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            if (!downloads.exists() && !downloads.mkdirs()) return null
            Uri.fromFile(File(downloads, FILE_NAME))
        }
    }
    private fun ensureTraceUri(context: Context): Uri? {
        val prefs = context.getSharedPreferences(TRACE_PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(TRACE_KEY_URI, null)?.let {
            try {
                Uri.parse(it)
            } catch (_: Throwable) {
                null
            }
        }
        if (existing != null) return existing

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, TRACE_FILE_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: return null
            prefs.edit().putString(TRACE_KEY_URI, uri.toString()).apply()
            uri
        } else {
            val downloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            if (!downloads.exists() && !downloads.mkdirs()) return null
            Uri.fromFile(File(downloads, TRACE_FILE_NAME))
        }
    }

}
