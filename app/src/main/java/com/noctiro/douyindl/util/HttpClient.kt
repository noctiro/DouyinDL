package com.noctiro.douyindl.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object HttpClient {

    val instance: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val downloadInstance: OkHttpClient = instance.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val noRedirectInstance: OkHttpClient = instance.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun fetchContentLength(url: String, userAgent: String): Long = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .head()
            .header("User-Agent", userAgent)
            .build()
        instance.newCall(request).execute().use { response ->
            response.header("Content-Length")?.toLongOrNull() ?: -1L
        }
    }
}
