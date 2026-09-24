package com.tomasthrawat.blackbrowser

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent diagnostic logger.
 *
 * On Android 10+ the log is written to the public Downloads collection through MediaStore,
 * so no legacy storage permission is required. The file is:
 *   Download/BlackBrowser/BlackBrowser-Diagnostics.log
 *
 * All existing AppFileLogger call sites are retained. "Now" variants write synchronously
 * for crash/error paths; the normal variants write on a small background executor.
 */
object AppFileLogger {
    private const val TAG = "BlackBrowser"
    private const val PREFS = "blackbrowser_diagnostics"
    private const val PREF_LOG_URI = "log_uri"
    private const val FILE_NAME = "BlackBrowser-Diagnostics.log"
    private const val RELATIVE_PATH =
        Environment.DIRECTORY_DOWNLOADS + "/BlackBrowser"
    private const val MIME_TYPE = "text/plain"

    private val lock = Any()
    private val writerExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var applicationContext: Context? = null
    @Volatile private var logUri: Uri? = null
    @Volatile private var crashHandlerInstalled = false

    fun initialize(context: Context) {
        val app = context.applicationContext
        applicationContext = app
        synchronized(lock) {
            ensureLogUriLocked(app)
        }
        traceNow(app, "LOGGER_INITIALIZED", "file=Download/BlackBrowser/" + FILE_NAME)
    }

    fun installCrashHandler(context: Context) {
        initialize(context)
        synchronized(lock) {
            if (crashHandlerInstalled) return
            crashHandlerInstalled = true
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                logExceptionNow(
                    context,
                    "CRASH",
                    "uncaught exception thread=" + thread.name,
                    throwable
                )
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun trace(context: Context, event: String, details: String = "") {
        writeAsync(context, "TRACE", event, details)
    }

    fun traceNow(context: Context, event: String, details: String = "") {
        writeNow(context, "TRACE", event, details)
    }

    fun log(context: Context, tag: String, message: String) {
        writeAsync(context, tag, "LOG", message)
    }

    fun logNow(context: Context, tag: String, message: String) {
        writeNow(context, tag, "LOG", message)
    }

    fun logExceptionNow(
        context: Context,
        tag: String,
        message: String,
        throwable: Throwable
    ) {
        val stack = StringWriter()
        throwable.printStackTrace(PrintWriter(stack))
        writeNow(
            context,
            tag,
            "EXCEPTION",
            message + "\n" + stack
        )
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
                redacted.add(
                    name to if (sensitive) "<redacted>" else (uri.getQueryParameter(name) ?: "")
                )
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

    private fun writeAsync(
        context: Context,
        tag: String,
        kind: String,
        message: String
    ) {
        val app = context.applicationContext
        writerExecutor.execute {
            writeInternal(app, tag, kind, message)
        }
    }

    private fun writeNow(
        context: Context,
        tag: String,
        kind: String,
        message: String
    ) {
        val app = context.applicationContext
        writeInternal(app, tag, kind, message)
    }

    private fun writeInternal(
        context: Context,
        tag: String,
        kind: String,
        message: String
    ) {
        val line = formatLine(tag, kind, message)

        try {
            Log.d(TAG, tag + ": " + message)
        } catch (_: Throwable) {
        }

        try {
            synchronized(lock) {
                val uri = ensureLogUriLocked(context) ?: return
                if (uri.scheme == "file") {
                    java.io.FileOutputStream(
                        java.io.File(uri.path ?: return),
                        true
                    ).use { stream ->
                        stream.write(line.toByteArray(Charsets.UTF_8))
                        stream.flush()
                    }
                } else {
                    val output = context.contentResolver.openOutputStream(uri, "wa") ?: return
                    output.use { stream ->
                        stream.write(line.toByteArray(Charsets.UTF_8))
                        stream.flush()
                    }
                }
            }
        } catch (t: Throwable) {
            try {
                Log.e(TAG, "Unable to write diagnostic log", t)
            } catch (_: Throwable) {
            }
        }
    }

    private fun formatLine(tag: String, kind: String, message: String): String {
        val timestamp = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss.SSS Z",
            Locale.US
        ).format(Date())
        return timestamp + " [" + kind + "][" + tag + "] " + message + "\n"
    }

    private fun ensureLogUriLocked(context: Context): Uri? {
        logUri?.let { uri ->
            if (uriStillWritable(context, uri)) return uri
            logUri = null
        }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(PREF_LOG_URI, null)?.let { saved ->
            runCatching { Uri.parse(saved) }.getOrNull()?.let { uri ->
                if (uriStillWritable(context, uri)) {
                    logUri = uri
                    return uri
                }
            }
            prefs.edit().remove(PREF_LOG_URI).apply()
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            val folder = java.io.File(dir, "BlackBrowser")
            if (!folder.exists() && !folder.mkdirs()) return null
            val file = java.io.File(folder, FILE_NAME)
            return Uri.fromFile(file).also { logUri = it }
        }

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.Downloads.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            values
        ) ?: return null

        val finalized = runCatching {
            context.contentResolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                },
                null,
                null
            )
        }.getOrDefault(0)

        if (finalized <= 0) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            return null
        }

        prefs.edit().putString(PREF_LOG_URI, uri.toString()).apply()
        logUri = uri
        return uri
    }

    private fun uriStillWritable(context: Context, uri: Uri): Boolean {
        return try {
            if (uri.scheme == "file") {
                java.io.File(uri.path ?: return false).exists()
            } else {
                context.contentResolver.openOutputStream(uri, "wa")?.use { } != null
            }
        } catch (_: Throwable) {
            false
        }
    }
}
