package top.msfxp.schedule.platform

// 平台 AES 加密与安全随机数能力接口
interface PlatformAesCipher {
    fun encryptAesCfbNoPadding(plain: ByteArray, key: ByteArray, iv: ByteArray): ByteArray
    fun encryptAesCbcPkcs7(plain: ByteArray, key: ByteArray, iv: ByteArray): ByteArray
    fun nextRandomInt(bound: Int): Int
}

// 平台本地凭证加解密接口（Android 使用 KeyStore，iOS 使用 Keychain）
interface PlatformCredentialCrypto {
    fun encrypt(rawText: String): String
    fun decrypt(encryptedText: String): String
}

// 平台加密能力提供入口
expect object PlatformCryptoProvider {
    val aesCipher: PlatformAesCipher
    val credentialCrypto: PlatformCredentialCrypto
}
