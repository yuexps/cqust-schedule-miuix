package top.msfxp.schedule.data.api

// 成功连接的教务会话包装模型
data class ConnectedEamsSession(
    val transport: EamsTransport,
    val vpnSession: CqustWebVpnSession? = null
)

// 教务通信客户端
object CqustEamsClient {
    private const val JWNEW_HOST = "jwnew.cqust.edu.cn"
    private val DIRECT_URLS = listOf(
        "http://jwnew.cqust.edu.ex2.http.80.ipv6.cqust.edu.cn/eams",
        "http://jwnew.cqust.edu.cn/eams"
    )

    // 建立可用教务传输会话
    suspend fun connect(
        studentId: String,
        passwordRaw: String,
        onProgress: ((String) -> Unit)? = null
    ): Result<ConnectedEamsSession> {
        // 尝试统一身份认证通道
        onProgress?.invoke("正在通过统一身份认证登录...")
        val casResult = CqustCasAuthClient.login(studentId, passwordRaw)

        if (casResult.success && casResult.session != null) {
            onProgress?.invoke("统一身份认证成功，正在接入教务系统...")
            val vpnSession = casResult.session
            val vpnEamsBase = CqustVpnCrypto.buildVpnUrl("http", JWNEW_HOST, "/eams")
            val unifiedLoginUrl = "$vpnEamsBase/unifiedLogin.action"

            val (homeUrl, ssoResp) = vpnSession.getFollowingRedirects(unifiedLoginUrl)
            if (homeUrl.contains("eams") || ssoResp.status.value in 200..399) {
                val transport = WebVpnEamsTransport(vpnSession, vpnEamsBase)
                return Result.success(ConnectedEamsSession(transport = transport, vpnSession = vpnSession))
            }
        }

        // 2. 若统一身份认证因网络异常不可达，降级尝试直连/IPv6 通道
        if (casResult.isNetworkError || !casResult.success) {
            onProgress?.invoke("统一身份认证不可用，降级尝试校园网/IPv6直连通道...")
            for (directUrl in DIRECT_URLS) {
                val directTransport = DirectEamsTransport(baseUrl = directUrl)
                val loginResult = directTransport.login(studentId, passwordRaw)
                if (loginResult.isSuccess) {
                    return Result.success(ConnectedEamsSession(transport = directTransport, vpnSession = null))
                }
            }
        }

        val errMsg = casResult.errorMessage ?: "教务登录失败，请检查账号密码或网络连接"
        return Result.failure(Exception(errMsg))
    }
}
