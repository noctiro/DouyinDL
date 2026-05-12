package com.noctiro.douyindl.data

data class VideoInfo(
    val url: String,
    val title: String,
    val videoId: String,
    val coverUrl: String? = null,
    val userAgent: String = "",
    val source: String = ""
)

interface VideoParser {
    val name: String
    fun canParse(url: String): Boolean
    suspend fun parse(url: String): VideoInfo
    suspend fun fetchFileSize(url: String, userAgent: String): Long
}
