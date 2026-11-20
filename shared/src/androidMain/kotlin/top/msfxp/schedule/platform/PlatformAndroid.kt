package top.msfxp.schedule.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// Android 平台配置
actual object PlatformConfig {
    actual val capabilities: PlatformCapabilities = PlatformCapabilities(
        supportsPreClassReminder = true,
        supportsCalendarSync = true,
        supportsClassAutomation = true,
        supportsPermissionManagement = true,
        supportsInAppApkUpdate = true,
        supportsDesktopWidget = true
    )
    actual var appVersionName: String = "1.1.4"
}

// Android 端 Ktor HTTP 客户端工厂
actual fun createPlatformHttpClient(
    followRedirects: Boolean,
    requestTimeoutMs: Long,
    connectTimeoutMs: Long,
    socketTimeoutMs: Long
): HttpClient {
    return HttpClient(Android) {
        this.followRedirects = followRedirects
        install(HttpTimeout) {
            requestTimeoutMillis = requestTimeoutMs
            connectTimeoutMillis = connectTimeoutMs
            socketTimeoutMillis = socketTimeoutMs
        }
    }
}

// Android 端加密能力提供入口
actual object PlatformCryptoProvider {
    actual val aesCipher: PlatformAesCipher = AndroidPlatformAesCipher
    actual val credentialCrypto: PlatformCredentialCrypto = AndroidKeyStoreCredentialCrypto
}

// Android 端 AES 与安全随机数实现
private object AndroidPlatformAesCipher : PlatformAesCipher {
    private val random = SecureRandom()

    override fun encryptAesCfbNoPadding(plain: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CFB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(plain)
    }

    override fun encryptAesCbcPkcs7(plain: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(plain)
    }

    override fun nextRandomInt(bound: Int): Int = random.nextInt(bound)
}

// Android 端本地凭证加解密实现（AndroidKeyStore 硬件保护密钥 + AES-GCM）
@OptIn(ExperimentalEncodingApi::class)
private object AndroidKeyStoreCredentialCrypto : PlatformCredentialCrypto {
    private const val KEY_ALIAS = "CqustMiuixScheduleLocalKey"
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        keyStore.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    override fun encrypt(rawText: String): String {
        if (rawText.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val iv = cipher.iv
            val cipherText = cipher.doFinal(rawText.encodeToByteArray())
            val combined = ByteArray(iv.size + cipherText.size)
            iv.copyInto(combined, destinationOffset = 0)
            cipherText.copyInto(combined, destinationOffset = iv.size)
            Base64.encode(combined)
        } catch (e: Exception) {
            Log.e("LocalCrypto", "KeyStore encryption failed", e)
            throw IllegalStateException("本地凭证加密失败", e)
        }
    }

    override fun decrypt(encryptedText: String): String {
        if (encryptedText.isEmpty()) return ""
        return try {
            val combined = Base64.decode(encryptedText)
            if (combined.size <= GCM_IV_LENGTH) return encryptedText
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val cipherText = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), spec)
            cipher.doFinal(cipherText).decodeToString()
        } catch (e: Exception) {
            Log.e("LocalCrypto", "KeyStore decryption failed, fallback to encrypted", e)
            encryptedText
        }
    }
}
