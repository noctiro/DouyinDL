package com.noctiro.douyindl.data

import com.noctiro.douyindl.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.noctiro.douyindl.util.HttpClient
import okhttp3.Request

class DouyinParser : VideoParser {

    override val name = "抖音"

    private val json = Json { ignoreUnknownKeys = true }

    override fun canParse(url: String): Boolean {
        return url.contains("douyin.com") || url.contains("iesdouyin.com")
    }

    override suspend fun parse(url: String): VideoInfo = withContext(Dispatchers.IO) {
        val ua = randomUserAgent()

        val finalUrl = resolveRedirects(url, ua)

        val videoId = finalUrl.split("?")[0].trimEnd('/').split("/").last()
        val iesdUrl = "https://www.iesdouyin.com/share/video/$videoId"

        val pageRequest = Request.Builder()
            .url(iesdUrl)
            .header("User-Agent", ua)
            .build()

        val html = HttpClient.instance.newCall(pageRequest).execute().use { response ->
            response.body?.string() ?: throw ResException(R.string.error_fetch_page)
        }

        val pattern = Regex("""window\._ROUTER_DATA\s*=\s*(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        val matchResult = pattern.find(html) ?: throw ResException(R.string.error_parse_html)
        val jsonStr = matchResult.groupValues[1].trim()

        val jsonData = json.parseToJsonElement(jsonStr).jsonObject
        val loaderData = jsonData["loaderData"]?.jsonObject ?: throw ResException(R.string.error_parse_loader_data)

        val videoPageKey = "video_(id)/page"
        val notePageKey = "note_(id)/page"

        val videoInfoRes = when {
            loaderData.containsKey(videoPageKey) ->
                loaderData[videoPageKey]?.jsonObject?.get("videoInfoRes")?.jsonObject
            loaderData.containsKey(notePageKey) ->
                loaderData[notePageKey]?.jsonObject?.get("videoInfoRes")?.jsonObject
            else -> throw ResException(R.string.error_parse_video_info)
        } ?: throw ResException(R.string.error_video_info_empty)

        val itemList = videoInfoRes["item_list"]?.jsonArray
            ?: throw ResException(R.string.error_item_list_empty)
        if (itemList.isEmpty()) throw ResException(R.string.error_item_list_empty)
        val data = itemList[0].jsonObject

        val videoUrl = data["video"]?.jsonObject
            ?.get("play_addr")?.jsonObject
            ?.get("url_list")?.jsonArray
            ?.get(0)?.jsonPrimitive?.content
            ?.replace("playwm", "play")
            ?: throw ResException(R.string.error_get_video_url)

        val coverUrl = data["video"]?.jsonObject
            ?.get("cover")?.jsonObject
            ?.get("url_list")?.jsonArray
            ?.firstOrNull()?.jsonPrimitive?.content

        val desc = data["desc"]?.jsonPrimitive?.content?.trim()?.ifEmpty { null }
            ?: "douyin_$videoId"

        val safeTitle = desc
            .replace(Regex("""[\\/:*?"<>|#\n\r]"""), "_")
            .replace(Regex("""\.{2,}"""), ".")
            .trim(' ', '.')
            .take(80)

        VideoInfo(
            url = videoUrl,
            title = safeTitle,
            videoId = videoId,
            coverUrl = coverUrl,
            userAgent = ua,
            source = name
        )
    }

    private fun resolveRedirects(url: String, userAgent: String, maxHops: Int = 5): String {
        var current = url
        repeat(maxHops) {
            val request = Request.Builder()
                .url(current)
                .header("User-Agent", userAgent)
                .build()
            val response = HttpClient.noRedirectInstance.newCall(request).execute()
            val location = response.use { it.header("Location") }
            if (location == null) return current
            current = location
        }
        return current
    }

    override suspend fun fetchFileSize(url: String, userAgent: String): Long {
        return HttpClient.fetchContentLength(url, userAgent)
    }
}

fun randomUserAgent(): String {
    val osVersions = listOf("15_0", "15_4", "16_0", "16_3", "16_6", "17_0", "17_1", "17_2", "17_3", "17_4", "17_5", "18_0")
    val safariVersions = listOf("604.1", "605.1.15")
    val chromeVersions = listOf("120.0.6099.119", "121.0.6167.178", "122.0.6261.89", "122.0.6261.105", "123.0.6312.58", "124.0.6367.54")
    val edgeVersions = listOf("121.0.2277.107", "122.0.2365.56", "122.0.2365.92", "123.0.2420.65")
    val firefoxVersions = listOf("121.0", "122.0", "123.0", "124.0")

    val os = "iPhone; CPU iPhone OS ${osVersions.random()} like Mac OS X"
    val webkit = "AppleWebKit/605.1.15 (KHTML, like Gecko)"

    return when ((0..3).random()) {
        0 -> "Mozilla/5.0 ($os) $webkit Version/${osVersions.random().replace('_', '.')} Mobile/15E148 Safari/${safariVersions.random()}"
        1 -> "Mozilla/5.0 ($os) $webkit CriOS/${chromeVersions.random()} Mobile/15E148 Safari/${safariVersions.random()}"
        2 -> "Mozilla/5.0 ($os) $webkit EdgiOS/${edgeVersions.random()} Version/17.0 Mobile/15E148 Safari/${safariVersions.random()}"
        else -> "Mozilla/5.0 ($os) $webkit FxiOS/${firefoxVersions.random()} Mobile/15E148 Safari/605.1.15"
    }
}
