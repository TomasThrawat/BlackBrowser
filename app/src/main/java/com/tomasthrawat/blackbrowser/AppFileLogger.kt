package com.tomasthrawat.blackbrowser

import android.content.Context
import android.net.Uri
import java.util.Locale

/**
 * Runtime logger intentionally disabled.
 *
 * BlackBrowser must not create diagnostic files in Downloads or any other public storage.
 * The public API remains available so call sites do not need logging-related control flow.
 */
object AppFileLogger {
    fun initialize(context: Context) = Unit

    fun installCrashHandler(context: Context) = Unit

    fun trace(context: Context, event: String, details: String = "") = Unit

    fun traceNow(context: Context, event: String, details: String = "") = Unit

    fun log(context: Context, tag: String, message: String) = Unit

    fun logNow(context: Context, tag: String, message: String) = Unit

    fun logExceptionNow(
        context: Context,
        tag: String,
        message: String,
        throwable: Throwable
    ) = Unit

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
}
