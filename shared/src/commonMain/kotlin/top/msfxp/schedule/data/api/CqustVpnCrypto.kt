package top.msfxp.schedule.data.api

import top.msfxp.schedule.platform.PlatformCryptoProvider
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// WebVPN 主机令牌算法与 CAS 密码加密工具
@OptIn(ExperimentalEncodingApi::class)
object CqustVpnCrypto {
    private const val VPN_PREFIX = "77726476706e69737468656265737421"
    private const val VPN_SECRET = "wrdvpnisthebest!"
    private const val CAS_CHARS = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"
    private const val HEX_CHARS = "0123456789abcdef"

    // 常用主机令牌缓存表
    private val KNOWN_TOKENS = mapOf(
        "cas.cqust.edu.cn" to "77726476706e69737468656265737421f3f652d224217d436a468ca88d1b203b",
        "casp.cqust.edu.cn" to "77726476706e69737468656265737421f3f6528c693379456d1cc7a99c406d36f8",
        "web.cqust.edu.cn" to "77726476706e69737468656265737421e7f243d224217d436a468ca88d1b203b",
        "jwnew.cqust.edu.cn" to "77726476706e69737468656265737421fae04f99307e6b416b1b9de29d51367b4912",
        "sjjx.cqust.edu.cn" to "77726476706e69737468656265737421e3fd4b84693379456d1cc7a99c406d369b",
        "icon.cqust.edu.cn" to "77726476706e69737468656265737421f9f44e92693379456d1cc7a99c406d3652"
    )

    // 计算 WebVPN 目标主机名加密令牌
    fun vpnToken(hostname: String): String {
        KNOWN_TOKENS[hostname]?.let { return it }

        val keyBytes = VPN_SECRET.encodeToByteArray()
        val encrypted = PlatformCryptoProvider.aesCipher.encryptAesCfbNoPadding(
            plain = hostname.encodeToByteArray(),
            key = keyBytes,
            iv = keyBytes
        )
        val sb = StringBuilder(VPN_PREFIX.length + encrypted.size * 2)
        sb.append(VPN_PREFIX)
        for (b in encrypted) {
            val unsigned = b.toInt() and 0xFF
            sb.append(HEX_CHARS[unsigned ushr 4])
            sb.append(HEX_CHARS[unsigned and 0x0F])
        }
        return sb.toString()
    }

    // 生成 CAS 登录使用的随机字符串
    private fun randomString(length: Int): String {
        val sb = StringBuilder(length)
        val cipher = PlatformCryptoProvider.aesCipher
        for (i in 0 until length) {
            sb.append(CAS_CHARS[cipher.nextRandomInt(CAS_CHARS.length)])
        }
        return sb.toString()
    }

    // CAS 密码加密
    fun encryptCasPassword(password: String, salt: String): String {
        val ivStr = randomString(16)
        val prefix = randomString(64)
        val plain = prefix + password

        val encrypted = PlatformCryptoProvider.aesCipher.encryptAesCbcPkcs7(
            plain = plain.encodeToByteArray(),
            key = salt.encodeToByteArray(),
            iv = ivStr.encodeToByteArray()
        )
        return Base64.encode(encrypted)
    }

    // 构造 WebVPN 代理地址
    fun buildVpnUrl(scheme: String, host: String, path: String): String {
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        return "https://web.cqust.edu.cn/$scheme/${vpnToken(host)}$cleanPath"
    }
}
