package com.noctiro.douyindl.data

class VideoParserManager {

    private val parsers: List<VideoParser> = listOf(
        DouyinParser()
    )

    private val urlPattern = Regex("""https?://\S+""")

    fun extractUrl(input: String): String? {
        return urlPattern.find(input)?.value
    }

    suspend fun parse(input: String): VideoInfo {
        val url = extractUrl(input)
            ?: throw IllegalArgumentException("未找到有效的链接")

        val parser = parsers.firstOrNull { it.canParse(url) }
            ?: throw IllegalArgumentException("不支持的链接格式，目前支持: ${parsers.joinToString { it.name }}")

        return parser.parse(url)
    }

    suspend fun fetchFileSize(url: String, userAgent: String): Long {
        val parser = parsers.firstOrNull { it.canParse(url) } ?: parsers.first()
        return parser.fetchFileSize(url, userAgent)
    }
}
