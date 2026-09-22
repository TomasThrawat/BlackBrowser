package com.tomasthrawat.blackbrowser

import java.util.WeakHashMap

internal class PageFinishGate<K : Any> {
    private data class State(
        var startedUrl: String? = null,
        var lastFinishedUrl: String? = null
    )

    private val states = WeakHashMap<K, State>()

    fun onPageStarted(key: K, url: String) {
        val state = states[key] ?: State().also { states[key] = it }
        state.startedUrl = url
    }

    fun shouldProcessPageFinished(key: K, url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val state = states[key] ?: State().also { states[key] = it }
        val matchesStartedLoad = state.startedUrl == url
        val untrackedNewLoad = state.startedUrl == null && state.lastFinishedUrl != url
        if (!matchesStartedLoad && !untrackedNewLoad) return false
        state.startedUrl = null
        state.lastFinishedUrl = url
        return true
    }

    fun clear() {
        states.clear()
    }
}
