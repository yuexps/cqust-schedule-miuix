package top.msfxp.schedule.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// GitHub Release 资源文件模型
@Serializable
data class GithubAsset(
    @SerialName("name")
    val name: String = "",
    @SerialName("browser_download_url")
    val browserDownloadUrl: String = "",
    @SerialName("size")
    val size: Long = 0L
)

// GitHub Release 数据模型
@Serializable
data class GithubRelease(
    @SerialName("tag_name")
    val tagName: String = "",
    @SerialName("name")
    val name: String? = null,
    @SerialName("body")
    val body: String? = null,
    @SerialName("html_url")
    val htmlUrl: String = "",
    @SerialName("assets")
    val assets: List<GithubAsset> = emptyList()
)

// 应用更新检查服务
object UpdateCheckService {
    private const val GITHUB_API_BASE = "https://api.github.com/repos"
    private const val GITHUB_API_MIRROR = "https://gh.dpik.top/https://api.github.com/repos"
    private val DOWNLOAD_MIRRORS = listOf(
        "https://ghproxy.net/",
        "https://gh-proxy.com/"
    )
    private const val DEFAULT_OWNER = "yuexps"
    private const val DEFAULT_REPO = "cqust-schedule-miuix"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client by lazy {
        HttpClient(Android) {
            install(HttpTimeout) {
                requestTimeoutMillis = 6000
                connectTimeoutMillis = 3000
                socketTimeoutMillis = 6000
            }
        }
    }

    // 检查是否有新版本 Release (直连失败自动使用镜像)
    suspend fun fetchLatestRelease(
        owner: String = DEFAULT_OWNER,
        repo: String = DEFAULT_REPO
    ): Result<GithubRelease> {
        val directUrl = "$GITHUB_API_BASE/$owner/$repo/releases/latest"
        val directResult = runCatching { fetchReleaseFromUrl(directUrl) }
        if (directResult.isSuccess) {
            return directResult
        }

        // 直连失败，降级使用镜像请求
        val mirrorUrl = "$GITHUB_API_MIRROR/$owner/$repo/releases/latest"
        return runCatching { fetchReleaseFromUrl(mirrorUrl) }
            .recoverCatching { throw directResult.exceptionOrNull() ?: it }
    }

    // 请求指定地址的 Release 信息
    private suspend fun fetchReleaseFromUrl(url: String): GithubRelease {
        val response = client.get(url) {
            header("Accept", "application/vnd.github.v3+json")
            header("User-Agent", "cqust-schedule-app")
        }

        return when (response.status) {
            HttpStatusCode.OK -> {
                val bodyText = response.bodyAsText()
                json.decodeFromString<GithubRelease>(bodyText)
            }
            HttpStatusCode.NotFound -> throw IllegalStateException("HTTP 404 Not Found")
            else -> throw IllegalStateException("HTTP ${response.status.value}")
        }
    }

    // 解析下载地址 (直连 GitHub 失败时依次尝试主备镜像)
    suspend fun resolveDownloadUrl(rawUrl: String): String {
        if (!rawUrl.contains("github.com", ignoreCase = true)) {
            return rawUrl
        }

        // 1. 尝试直连 GitHub
        val isDirectOk = runCatching {
            val response = client.get(rawUrl) {
                header("User-Agent", "cqust-schedule-app")
            }
            response.status.value in 200..399
        }.getOrDefault(false)

        if (isDirectOk) {
            return rawUrl
        }

        // 2. 直连失败，依次尝试主备镜像
        for (mirrorBase in DOWNLOAD_MIRRORS) {
            val candidateUrl = "$mirrorBase$rawUrl"
            val isMirrorOk = runCatching {
                val response = client.get(candidateUrl) {
                    header("User-Agent", "cqust-schedule-app")
                }
                response.status.value in 200..399
            }.getOrDefault(false)

            if (isMirrorOk) {
                return candidateUrl
            }
        }

        return "${DOWNLOAD_MIRRORS.first()}$rawUrl"
    }

    // 版本号比较逻辑
    fun isNewerVersion(remoteTag: String, localVersionName: String): Boolean {
        val remoteNums = extractVersionNumbers(remoteTag)
        val localNums = extractVersionNumbers(localVersionName)
        val maxLen = maxOf(remoteNums.size, localNums.size)

        for (i in 0 until maxLen) {
            val r = remoteNums.getOrElse(i) { 0 }
            val l = localNums.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    // 提取纯数字版本分段
    private fun extractVersionNumbers(raw: String): List<Int> {
        val clean = raw.trim().trimStart('v', 'V').split("-", "_", "+")[0]
        return clean.split(".").mapNotNull { it.trim().toIntOrNull() }
    }
}
