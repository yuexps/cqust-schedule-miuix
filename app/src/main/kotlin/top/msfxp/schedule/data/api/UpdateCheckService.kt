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
    private const val DEFAULT_OWNER = "yuexps"
    private const val DEFAULT_REPO = "cqust-schedule-miuix"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client by lazy {
        HttpClient(Android) {
            install(HttpTimeout) {
                requestTimeoutMillis = 10000
                connectTimeoutMillis = 6000
                socketTimeoutMillis = 10000
            }
        }
    }

    // 检查是否有新版本 Release
    suspend fun fetchLatestRelease(
        owner: String = DEFAULT_OWNER,
        repo: String = DEFAULT_REPO
    ): Result<GithubRelease> {
        return runCatching {
            val url = "$GITHUB_API_BASE/$owner/$repo/releases/latest"
            val response = client.get(url) {
                header("Accept", "application/vnd.github.v3+json")
                header("User-Agent", "cqust-schedule-app")
            }

            when (response.status) {
                HttpStatusCode.OK -> {
                    val bodyText = response.bodyAsText()
                    json.decodeFromString<GithubRelease>(bodyText)
                }
                HttpStatusCode.NotFound -> throw IllegalStateException("HTTP 404 Not Found")
                else -> throw IllegalStateException("HTTP ${response.status.value}")
            }
        }
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
