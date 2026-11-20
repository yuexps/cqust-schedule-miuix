package top.msfxp.schedule.data.api

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.msfxp.schedule.platform.createPlatformHttpClient
import kotlin.concurrent.Volatile

// WebVPN 网络会话管理器，维护 Cookie 与逐跳重定向
class CqustWebVpnSession(
    private val httpClient: HttpClient = defaultClient
) {
    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        const val VPN_GATEWAY = "https://web.cqust.edu.cn"
        const val VPN_TICKET_COOKIE = "wengine_vpn_ticketweb_cqust_edu_cn"

        val defaultClient by lazy {
            createPlatformHttpClient(
                followRedirects = false,
                requestTimeoutMs = 15000L,
                connectTimeoutMs = 6000L,
                socketTimeoutMs = 15000L
            )
        }
    }

    private val cookieMutex = Mutex()
    @Volatile
    private var cookieMap: Map<String, String> = emptyMap()

    // 教务与实践系统基地址
    var eamsBaseUrl: String? = null
    var sjjxBaseUrl: String? = null

    // 检查是否已持有有效的 WebVPN 会话 Ticket
    fun hasVpnTicket(): Boolean = cookieMap.containsKey(VPN_TICKET_COOKIE)

    // 获取特定 Cookie
    fun getCookie(key: String): String? = cookieMap[key]

    // 解析并合并 Cookie
    suspend fun updateCookies(response: HttpResponse) {
        val setCookies = response.headers.getAll(HttpHeaders.SetCookie)
            ?: response.headers.getAll("Set-Cookie")
            ?: response.headers.getAll("set-cookie")
            ?: return
        cookieMutex.withLock {
            val updated = cookieMap.toMutableMap()
            for (header in setCookies) {
                val items = header.split(Regex(",(?=[^;]+=[^;]+)"))
                for (item in items) {
                    val firstPart = item.substringBefore(';').trim()
                    val eqIndex = firstPart.indexOf('=')
                    if (eqIndex > 0) {
                        val key = firstPart.substring(0, eqIndex).trim()
                        val value = firstPart.substring(eqIndex + 1).trim()
                        if (key.isNotEmpty()) {
                            updated[key] = value
                        }
                    }
                }
            }
            cookieMap = updated
        }
    }

    // 格式化当前会话 Cookie 请求头
    fun getCookieHeader(): String {
        return cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    // 将相对路径解析为完整 URL
    fun resolveUrl(currentUrl: String, location: String): String {
        if (location.startsWith("http://") || location.startsWith("https://")) {
            return location
        }
        return try {
            val base = Url(currentUrl)
            val origin = "${base.protocol.name}://${base.hostWithPortIfSpecified}"
            when {
                location.startsWith("//") -> "${base.protocol.name}:$location"
                location.startsWith("/") -> "$origin$location"
                location.startsWith("?") -> "$origin${base.encodedPath}$location"
                else -> {
                    val baseDir = base.encodedPath.substringBeforeLast('/', "")
                    val normalized = normalizePath("$baseDir/$location")
                    "$origin$normalized"
                }
            }
        } catch (_: Exception) {
            if (location.startsWith("/")) {
                "$VPN_GATEWAY$location"
            } else {
                val prefix = currentUrl.substringBeforeLast('/')
                "$prefix/$location"
            }
        }
    }

    // 规范化相对路径中的 . 与 .. 片段
    private fun normalizePath(rawPath: String): String {
        val queryIdx = rawPath.indexOf('?')
        val pathPart = if (queryIdx >= 0) rawPath.substring(0, queryIdx) else rawPath
        val queryPart = if (queryIdx >= 0) rawPath.substring(queryIdx) else ""
        val segments = ArrayDeque<String>()
        for (segment in pathPart.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeLast()
                else -> segments.addLast(segment)
            }
        }
        return "/" + segments.joinToString("/") + queryPart
    }

    // 执行带有自动 Cookie 与请求头的 GET 请求
    suspend fun get(
        url: String,
        referer: String? = null,
        isAjax: Boolean = false,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse {
        val resp = httpClient.get(url) {
            header(HttpHeaders.UserAgent, USER_AGENT)
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            header(HttpHeaders.AcceptLanguage, "zh-CN,zh;q=0.9")
            if (referer != null) header(HttpHeaders.Referrer, referer)
            if (isAjax) header("X-Requested-With", "XMLHttpRequest")
            headers.forEach { (k, v) -> header(k, v) }
            val cookies = getCookieHeader()
            if (cookies.isNotEmpty()) header(HttpHeaders.Cookie, cookies)
        }
        updateCookies(resp)
        return resp
    }

    // 执行带有自动 Cookie 与请求头的 POST 请求
    suspend fun post(
        url: String,
        body: String,
        referer: String? = null,
        isAjax: Boolean = false,
        contentType: String = ContentType.Application.FormUrlEncoded.toString(),
        headers: Map<String, String> = emptyMap()
    ): HttpResponse {
        val resp = httpClient.post(url) {
            header(HttpHeaders.UserAgent, USER_AGENT)
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            header(HttpHeaders.AcceptLanguage, "zh-CN,zh;q=0.9")
            if (referer != null) header(HttpHeaders.Referrer, referer)
            if (isAjax) header("X-Requested-With", "XMLHttpRequest")
            headers.forEach { (k, v) -> header(k, v) }
            header(HttpHeaders.ContentType, contentType)
            val cookies = getCookieHeader()
            if (cookies.isNotEmpty()) header(HttpHeaders.Cookie, cookies)
            setBody(body)
        }
        updateCookies(resp)
        return resp
    }

    // 自动跟随重定向直到终点或满足停止条件
    suspend fun getFollowingRedirects(
        startUrl: String,
        referer: String? = null,
        maxHops: Int = 10,
        stopOnUrlContains: String? = null
    ): Pair<String, HttpResponse> {
        var currentUrl = startUrl
        var currentReferer = referer
        var hops = 0

        var response = get(currentUrl, currentReferer)
        while (hops < maxHops) {
            hops++
            if (response.status == HttpStatusCode.Found ||
                response.status == HttpStatusCode.MovedPermanently ||
                response.status == HttpStatusCode.SeeOther ||
                response.status == HttpStatusCode.TemporaryRedirect
            ) {
                val location = response.headers[HttpHeaders.Location] ?: break
                val nextUrl = resolveUrl(currentUrl, location)

                if (stopOnUrlContains != null && nextUrl.contains(stopOnUrlContains)) {
                    return nextUrl to response
                }

                currentReferer = currentUrl
                currentUrl = nextUrl
                response = get(currentUrl, currentReferer)
            } else {
                break
            }
        }
        return currentUrl to response
    }
}
