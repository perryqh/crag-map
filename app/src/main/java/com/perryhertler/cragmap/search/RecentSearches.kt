package com.perryhertler.cragmap.search

import android.content.Context

private const val PREFS = "crag_atlas_search"
private const val KEY = "recent"
private const val MAX = 8

fun loadRecentSearches(context: Context): List<String> {
    val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
    if (raw.isBlank()) return emptyList()
    return raw.split('\u0001').filter { it.isNotBlank() }
}

fun rememberSearchQuery(context: Context, query: String) {
    val q = query.trim()
    if (q.isEmpty()) return
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val next = (listOf(q) + loadRecentSearches(context).filter { !it.equals(q, ignoreCase = true) })
        .take(MAX)
    prefs.edit().putString(KEY, next.joinToString("\u0001")).apply()
}
