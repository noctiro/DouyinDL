package com.noctiro.douyindl.data

import com.noctiro.douyindl.R

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
            ?: throw ResException(R.string.error_no_valid_link)

        val parser = parsers.firstOrNull { it.canParse(url) }
            ?: throw ResException(R.string.error_unsupported_format, arrayOf(parsers.joinToString { it.name }))

        return parser.parse(url)
    }

    suspend fun fetchFileSize(url: String, userAgent: String): Long {
        val parser = parsers.firstOrNull { it.canParse(url) } ?: parsers.first()
        return parser.fetchFileSize(url, userAgent)
    }
}
