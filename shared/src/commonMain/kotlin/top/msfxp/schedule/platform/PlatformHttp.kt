package top.msfxp.schedule.platform

import io.ktor.client.HttpClient

// 创建带超时配置的跨平台 HTTP 客户端
expect fun createPlatformHttpClient(
    followRedirects: Boolean = true,
    requestTimeoutMs: Long = 15000L,
    connectTimeoutMs: Long = 6000L,
    socketTimeoutMs: Long = 15000L
): HttpClient
