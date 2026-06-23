package com.alditalk.panther.util

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** In-memory CookieJar that stores cookies across requests. */
class MemoryCookieJar : CookieJar {
    private val store = mutableMapOf<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        val existing = store.getOrPut(host) { mutableListOf() }
        existing.removeAll { c -> cookies.any { it.name == c.name } }
        existing.addAll(cookies)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return store[url.host]?.filter { it.matches(url) } ?: emptyList()
    }
}
