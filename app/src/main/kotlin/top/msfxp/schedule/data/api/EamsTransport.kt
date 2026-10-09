package top.msfxp.schedule.data.api

import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.net.URI
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// 统一教务网络传输层接口
interface EamsTransport {
    val baseUrl: String

    suspend fun get(
        url: String,
        referer: String? = null,
        isAjax: Boolean = false,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse

    suspend fun post(
        url: String,
        body: String,
        referer: String? = null,
        isAjax: Boolean = false,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse

    fun resolveUrl(currentUrl: String, location: String): String
}

// 统一身份认证 WebVPN 传输通道适配器
class WebVpnEamsTransport(
    private val session: CqustWebVpnSession,
    override val baseUrl: String
) : EamsTransport {
    override suspend fun get(
        url: String,
        referer: String?,
        isAjax: Boolean,
        headers: Map<String, String>
    ): HttpResponse = session.get(url, referer, isAjax, headers)

    override suspend fun post(
        url: String,
        body: String,
        referer: String?,
        isAjax: Boolean,
        headers: Map<String, String>
    ): HttpResponse = session.post(
        url = url,
        body = body,
        referer = referer,
        isAjax = isAjax,
        headers = headers
    )

    override fun resolveUrl(currentUrl: String, location: String): String =
        session.resolveUrl(currentUrl, location)
}

// 校园网直连教务传输通道
class DirectEamsTransport(
    override val baseUrl: String,
    private val httpClient: HttpClient = defaultClient
) : EamsTransport {
    private val cookieMap = java.util.concurrent.ConcurrentHashMap<String, String>()

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        val defaultClient by lazy {
            HttpClient(Android) {
                followRedirects = false
                install(HttpTimeout) {
                    requestTimeoutMillis = 10000
                    connectTimeoutMillis = 4000
                    socketTimeoutMillis = 10000
                }
            }
        }

        // 3DES-CBC 密码加密
        fun cqustTripleDesEncrypt(message: String, key: String): String {
            val keyBytes = key.toByteArray(Charsets.UTF_8).copyOf(24)
            val ivBytes = key.substring(0, 8).toByteArray(Charsets.UTF_8)
            val secretKey = SecretKeySpec(keyBytes, "DESede")
            val ivSpec = IvParameterSpec(ivBytes)
            val cipher = Cipher.getInstance("DESede/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
            val encrypted = cipher.doFinal(message.toByteArray(Charsets.UTF_8))
            return android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
        }
    }

    private fun updateCookies(response: HttpResponse) {
        val setCookies = response.headers.getAll(HttpHeaders.SetCookie)
            ?: response.headers.getAll("Set-Cookie")
            ?: response.headers.getAll("set-cookie")
            ?: return
        for (header in setCookies) {
            val items = header.split(Regex(",(?=[^;]+=[^;]+)"))
            for (item in items) {
                val firstPart = item.substringBefore(';').trim()
                val eqIndex = firstPart.indexOf('=')
                if (eqIndex > 0) {
                    val key = firstPart.substring(0, eqIndex).trim()
                    val value = firstPart.substring(eqIndex + 1).trim()
                    if (key.isNotEmpty()) {
                        cookieMap[key] = value
                    }
                }
            }
        }
    }

    private fun HttpRequestBuilder.applyHeaders(referer: String?, isAjax: Boolean, extraHeaders: Map<String, String>) {
        header(HttpHeaders.UserAgent, USER_AGENT)
        header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        header(HttpHeaders.AcceptLanguage, "zh-CN,zh;q=0.9")
        if (referer != null) header(HttpHeaders.Referrer, referer)
        if (isAjax) header("X-Requested-With", "XMLHttpRequest")
        extraHeaders.forEach { (k, v) -> header(k, v) }
        if (cookieMap.isNotEmpty()) {
            header(HttpHeaders.Cookie, cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }
    }

    override suspend fun get(
        url: String,
        referer: String?,
        isAjax: Boolean,
        headers: Map<String, String>
    ): HttpResponse {
        val resp = httpClient.get(url) { applyHeaders(referer, isAjax, headers) }
        updateCookies(resp)
        return resp
    }

    override suspend fun post(
        url: String,
        body: String,
        referer: String?,
        isAjax: Boolean,
        headers: Map<String, String>
    ): HttpResponse {
        val resp = httpClient.post(url) {
            applyHeaders(referer, isAjax, headers)
            header(
                HttpHeaders.ContentType,
                if (isAjax) "application/x-www-form-urlencoded; charset=UTF-8"
                else ContentType.Application.FormUrlEncoded.toString()
            )
            setBody(body)
        }
        updateCookies(resp)
        return resp
    }

    override fun resolveUrl(currentUrl: String, location: String): String {
        return try {
            URI(currentUrl).resolve(location).toString()
        } catch (_: Exception) {
            if (location.startsWith("http")) location else "$baseUrl/$location"
        }
    }

    // 执行直连教务登录并维持会话
    suspend fun login(studentId: String, passwordRaw: String): Result<Unit> {
        return try {
            val loginPageResp = get("$baseUrl/login.action?cqustadminweb=1")
            if (loginPageResp.status != HttpStatusCode.OK) {
                return Result.failure(Exception("直连教务登录页无法访问(HTTP ${loginPageResp.status.value})"))
            }
            val html = loginPageResp.bodyAsText()
            val saltMatch = Regex("""tripleDesEncrypt\(.*?,\s*['"]([^'"]+)['"]\)""").find(html)
                ?: return Result.failure(Exception("未能从直连登录页提取加密密钥"))
            val salt = saltMatch.groupValues[1]

            val encryptedPassword = cqustTripleDesEncrypt(passwordRaw, salt)
            val postBody = "username=${URLEncoder.encode(studentId, "UTF-8")}&password=${URLEncoder.encode(encryptedPassword, "UTF-8")}&encodedPassword="
            val loginResp = post(
                url = "$baseUrl/login.action",
                body = postBody,
                referer = "$baseUrl/login.action?cqustadminweb=1"
            )

            val location = loginResp.headers[HttpHeaders.Location]
            if (location != null && (location.contains("home") || location.contains("index"))) {
                // 跟随重定向完成教务主页会话落地
                val homeTarget = resolveUrl("$baseUrl/login.action", location)
                get(homeTarget, referer = "$baseUrl/login.action")
                Result.success(Unit)
            } else {
                val failHtml = loginResp.bodyAsText()
                val errorMsg = Regex("""id=["']msg["'][^>]*>([\s\S]*?)</span>""", RegexOption.IGNORE_CASE)
                    .find(failHtml)?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), "")?.trim()
                Result.failure(Exception(errorMsg ?: "直连登录失败，请检查账号密码"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
