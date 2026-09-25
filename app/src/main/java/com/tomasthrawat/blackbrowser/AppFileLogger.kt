package com.tomasthrawat.blackbrowser

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Locale

/**
 * Diagnostic logger.
 *
 * Diagnostic output is kept in Android Logcat only.
 * This class deliberately does not create or write any files, including
 * files in the public Downloads collection.
 *
 * The public API used by the browser is retained so existing call sites
 * remain unchanged.
 */
object AppFileLogger {
    private const val TAG = "BlackBrowser"

    @Volatile
    private var crashHandlerInstalled = false

    fun initialize(context: Context) {
        Log.d(TAG, "LOGGER: persistent file logging disabled")
    }

    fun installCrashHandler(context: Context) {
        synchronized(this) {
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
        writeLog("TRACE", event, details)
    }

    fun traceNow(context: Context, event: String, details: String = "") {
        writeLog("TRACE_NOW", event, details)
    }

    fun log(context: Context, tag: String, message: String) {
        writeLog(tag, "LOG", message)
    }

    fun logNow(context: Context, tag: String, message: String) {
        writeLog(tag, "LOG_NOW", message)
    }

    fun logExceptionNow(
        context: Context,
        tag: String,
        message: String,
        throwable: Throwable
    ) {
        val stack = StringWriter()
        throwable.printStackTrace(PrintWriter(stack))
        writeLog(tag, "EXCEPTION", message + "\n" + stack)
    }

    private fun writeLog(tag: String, kind: String, message: String) {
        try {
            Log.d(TAG, "[" + kind + "][" + tag + "] " + message)
        } catch (_: Throwable) {
        }
    }

    fun safeString(value: String?): String =
        value?.replace("\n", "\\n")?.replace("\r", "\\r") ?: "<null>"

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

    /**
     * Kept for source compatibility. File logging is disabled, so this
     * always returns null and never points to a Downloads file.
     */
    fun getLogUri(): Uri? = null
}
