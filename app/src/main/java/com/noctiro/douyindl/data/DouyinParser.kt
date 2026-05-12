package com.noctiro.douyindl.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class VideoInfo(
    val url: String,
    val title: String,
    val videoId: String,
    val coverUrl: String? = null,
    val userAgent: String = ""
)

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

class DouyinParser {

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun parseShareUrl(shareText: String): VideoInfo = withContext(Dispatchers.IO) {
        val ua = randomUserAgent()

        val urlPattern = Regex("""https?://[^\s]+""")
        val shareUrl = urlPattern.find(shareText)?.value
            ?: throw IllegalArgumentException("未找到有效的分享链接")

        val redirectRequest = Request.Builder()
            .url(shareUrl)
            .header("User-Agent", ua)
            .build()

        val redirectResponse = client.newCall(redirectRequest).execute()
        val finalUrl = redirectResponse.request.url.toString()
        redirectResponse.close()

        val videoId = finalUrl.split("?")[0].trimEnd('/').split("/").last()
        val iesdUrl = "https://www.iesdouyin.com/share/video/$videoId"

        val pageRequest = Request.Builder()
            .url(iesdUrl)
            .header("User-Agent", ua)
            .build()

        val pageResponse = client.newCall(pageRequest).execute()
        val html = pageResponse.body?.string() ?: throw Exception("获取页面内容失败")
        pageResponse.close()

        val pattern = Regex("""window\._ROUTER_DATA\s*=\s*(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        val matchResult = pattern.find(html) ?: throw Exception("从HTML中解析视频信息失败")
        val jsonStr = matchResult.groupValues[1].trim()

        val jsonData = json.parseToJsonElement(jsonStr).jsonObject
        val loaderData = jsonData["loaderData"]?.jsonObject ?: throw Exception("无法解析loaderData")

        val videoPageKey = "video_(id)/page"
        val notePageKey = "note_(id)/page"

        val videoInfoRes = when {
            loaderData.containsKey(videoPageKey) ->
                loaderData[videoPageKey]?.jsonObject?.get("videoInfoRes")?.jsonObject
            loaderData.containsKey(notePageKey) ->
                loaderData[notePageKey]?.jsonObject?.get("videoInfoRes")?.jsonObject
            else -> throw Exception("无法从JSON中解析视频或图集信息")
        } ?: throw Exception("videoInfoRes为空")

        val itemList = videoInfoRes["item_list"]?.jsonArray
            ?: throw Exception("item_list为空")
        val data = itemList[0].jsonObject

        val videoUrl = data["video"]?.jsonObject
            ?.get("play_addr")?.jsonObject
            ?.get("url_list")?.jsonArray
            ?.get(0)?.jsonPrimitive?.content
            ?.replace("playwm", "play")
            ?: throw Exception("无法获取视频URL")

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
            userAgent = ua
        )
    }
}
