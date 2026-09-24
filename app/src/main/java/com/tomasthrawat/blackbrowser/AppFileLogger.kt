package com.tomasthrawat.blackbrowser

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
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
 * so no legacy storage permission is required. A new session log is created directly in:
 *   Download/BlackBrowser-Diagnostics-<timestamp>.log
 *
 * All existing AppFileLogger call sites are retained. "Now" variants write synchronously
 * for crash/error paths; the normal variants write on a small background executor.
 */
object AppFileLogger {
    private const val TAG = "BlackBrowser"
    private const val FILE_PREFIX = "BlackBrowser-Diagnostics-"
    private const val FILE_SUFFIX = ".log"
    private const val RELATIVE_PATH = "Download"
    private const val MIME_TYPE = "text/plain"

    private val lock = Any()
    private val writerExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var logUri: Uri? = null
    @Volatile private var logOutput: java.io.OutputStream? = null
    @Volatile private var initializationTraceWritten = false
    @Volatile private var crashHandlerInstalled = false

    fun initialize(context: Context) {
        val app = context.applicationContext
        var shouldTraceInitialization = false
        synchronized(lock) {
            ensureLogOutputLocked(app)
            if (!initializationTraceWritten && logOutput != null) {
                initializationTraceWritten = true
                shouldTraceInitialization = true
            }
        }
        if (shouldTraceInitialization) {
            traceNow(app, "LOGGER_INITIALIZED", "file=" + (logUri?.toString() ?: "<unavailable>"))
        }
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

    /**
     * Logs ordinary titles as-is, but routes URL-shaped titles through the
     * same query redaction used for URLs.
     */
    fun safeTitle(value: String?): String {
        if (value.isNullOrBlank()) return "<null>"
        val trimmed = value.trim()
        return if (
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            safeUrl(trimmed)
        } else {
            safeString(trimmed)
        }
    }

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
                val sensitive = lower == "q" ||
                lower == "query" ||
                lower == "search" ||
                lower == "search_query" ||
                lower == "text" ||
                lower == "prompt" ||
                lower == "continue" ||
                lower == "redirect" ||
                lower == "redirect_uri" ||
                lower == "return" ||
                lower == "return_url" ||
                lower == "next" ||
                lower == "target" ||
                lower == "dest" ||
                lower == "destination" ||
                lower == "s" ||
                lower == "state" ||
                lower == "nonce" ||
                lower == "sg_ss" ||
                lower == "sxsrf" ||
                lower == "ei" ||
                lower == "sei" ||
                lower == "oq" ||
                lower == "gs_lp" ||
                lower.contains("token") ||
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
                val output = ensureLogOutputLocked(context) ?: return
                output.write(line.toByteArray(Charsets.UTF_8))
                output.flush()
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

    private fun ensureLogOutputLocked(context: Context): java.io.OutputStream? {
        logOutput?.let { return it }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            if (!dir.exists() && !dir.mkdirs()) return null
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
            val file = java.io.File(
                dir,
                FILE_PREFIX + stamp + FILE_SUFFIX
            )
            val output = java.io.FileOutputStream(file, false)
            logUri = Uri.fromFile(file)
            logOutput = output
            return output
        }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val fileName = FILE_PREFIX + stamp + FILE_SUFFIX
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            values
        ) ?: return null

        try {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "w")
                ?: error("openFileDescriptor(w) returned null")
            val output = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
            val header = formatLine(
                "LOGGER",
                "TRACE",
                "LOGGER_FILE_CREATED name=" + fileName + " path=Download/" + fileName
            )
            output.write(header.toByteArray(Charsets.UTF_8))
            output.flush()

            val finalized = context.contentResolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                },
                null,
                null
            )
            if (finalized <= 0) {
                runCatching { output.close() }
                runCatching { context.contentResolver.delete(uri, null, null) }
                return null
            }

            logUri = uri
            logOutput = output
            return output
        } catch (t: Throwable) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            try {
                Log.e(TAG, "Unable to create diagnostic log file", t)
            } catch (_: Throwable) {
            }
            return null
        }
    }

    fun getLogUri(): Uri? = logUri

}
