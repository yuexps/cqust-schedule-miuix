package top.msfxp.schedule.platform

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.encodeURLParameter
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.CCCryptorCreateWithMode
import platform.CoreCrypto.CCCryptorFinal
import platform.CoreCrypto.CCCryptorRefVar
import platform.CoreCrypto.CCCryptorRelease
import platform.CoreCrypto.CCCryptorUpdate
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCBlockSizeAES128
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCModeCFB
import platform.CoreCrypto.kCCOptionPKCS7Padding
import platform.CoreCrypto.kCCSuccess
import platform.CoreCrypto.ccNoPadding
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecRandomDefault
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.UIColor
import platform.UIKit.UIFont
import platform.UIKit.UILabel
import platform.UIKit.NSTextAlignmentCenter
import platform.UIKit.UIApplication
import platform.UIKit.UIView
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
import platform.posix.memcpy
import platform.posix.size_tVar
import kotlin.random.Random

// iOS 平台配置（按需裁剪免打扰、APK 更新、Android 权限管理与桌面小组件）
actual object PlatformConfig {
    actual val capabilities: PlatformCapabilities = PlatformCapabilities(
        supportsPreClassReminder = true,
        supportsCalendarSync = false,
        supportsClassAutomation = false,
        supportsPermissionManagement = false,
        supportsInAppApkUpdate = false,
        supportsDesktopWidget = false
    )
    actual var appVersionName: String =
        (NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String) ?: "1.1.4"
}

// iOS 端 Ktor Darwin HTTP 客户端工厂
actual fun createPlatformHttpClient(
    followRedirects: Boolean,
    requestTimeoutMs: Long,
    connectTimeoutMs: Long,
    socketTimeoutMs: Long
): HttpClient {
    return HttpClient(Darwin) {
        this.followRedirects = followRedirects
        install(HttpTimeout) {
            requestTimeoutMillis = requestTimeoutMs
            connectTimeoutMillis = connectTimeoutMs
            socketTimeoutMillis = socketTimeoutMs
        }
    }
}

// iOS 端加密能力提供入口
actual object PlatformCryptoProvider {
    actual val aesCipher: PlatformAesCipher = IosPlatformAesCipher
    actual val credentialCrypto: PlatformCredentialCrypto = IosKeychainCredentialCrypto
}

// iOS 端 CommonCrypto AES 与安全随机数实现
@OptIn(ExperimentalForeignApi::class)
private object IosPlatformAesCipher : PlatformAesCipher {
    override fun encryptAesCfbNoPadding(plain: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val outBuf = ByteArray(plain.size + kCCBlockSizeAES128.toInt())
        memScoped {
            val cryptorRef = alloc<CCCryptorRefVar>()
            val statusCreate = key.usePinned { keyPinned ->
                iv.usePinned { ivPinned ->
                    CCCryptorCreateWithMode(
                        op = kCCEncrypt,
                        mode = kCCModeCFB,
                        alg = kCCAlgorithmAES,
                        padding = ccNoPadding,
                        iv = ivPinned.addressOf(0),
                        key = keyPinned.addressOf(0),
                        keyLength = key.size.convert(),
                        tweak = null,
                        tweakLength = 0u,
                        numRounds = 0,
                        options = 0u,
                        cryptorRef = cryptorRef.ptr
                    )
                }
            }
            check(statusCreate == kCCSuccess) { "AES-CFB create failed: $statusCreate" }
            try {
                val moved1 = alloc<size_tVar>()
                val statusUpdate = plain.usePinned { inPinned ->
                    outBuf.usePinned { outPinned ->
                        CCCryptorUpdate(
                            cryptorRef.value,
                            inPinned.addressOf(0),
                            plain.size.convert(),
                            outPinned.addressOf(0),
                            outBuf.size.convert(),
                            moved1.ptr
                        )
                    }
                }
                check(statusUpdate == kCCSuccess) { "AES-CFB update failed: $statusUpdate" }
                val moved2 = alloc<size_tVar>()
                val offset = moved1.value.toInt()
                val statusFinal = outBuf.usePinned { outPinned ->
                    CCCryptorFinal(
                        cryptorRef.value,
                        outPinned.addressOf(offset),
                        (outBuf.size - offset).convert(),
                        moved2.ptr
                    )
                }
                check(statusFinal == kCCSuccess) { "AES-CFB final failed: $statusFinal" }
                return outBuf.copyOf(offset + moved2.value.toInt())
            } finally {
                // 创建失败时 cryptorRef 为空，避免空指针释放
                cryptorRef.value?.let { CCCryptorRelease(it) }
            }
        }
    }

    override fun encryptAesCbcPkcs7(plain: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val outBuf = ByteArray(plain.size + kCCBlockSizeAES128.toInt())
        memScoped {
            val moved = alloc<size_tVar>()
            val status = key.usePinned { keyPinned ->
                iv.usePinned { ivPinned ->
                    plain.usePinned { inPinned ->
                        outBuf.usePinned { outPinned ->
                            CCCrypt(
                                op = kCCEncrypt,
                                alg = kCCAlgorithmAES,
                                options = kCCOptionPKCS7Padding,
                                key = keyPinned.addressOf(0),
                                keyLength = key.size.convert(),
                                iv = ivPinned.addressOf(0),
                                dataIn = inPinned.addressOf(0),
                                dataInLength = plain.size.convert(),
                                dataOut = outPinned.addressOf(0),
                                dataOutAvailable = outBuf.size.convert(),
                                dataOutMoved = moved.ptr
                            )
                        }
                    }
                }
            }
            check(status == kCCSuccess) { "AES-CBC encrypt failed: $status" }
            return outBuf.copyOf(moved.value.toInt())
        }
    }

    override fun nextRandomInt(bound: Int): Int {
        if (bound <= 0) return 0
        val bytes = ByteArray(4)
        val status = bytes.usePinned { pinned ->
            SecRandomCopyBytes(kSecRandomDefault, 4u, pinned.addressOf(0))
        }
        if (status != errSecSuccess) return Random.nextInt(bound)
        val unsigned = ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
        return (unsigned and 0x7FFFFFFF) % bound
    }
}

// iOS 端 Keychain 凭证存储实现（密文字段保存固定占位标识，真实口令存于 Keychain）
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private object IosKeychainCredentialCrypto : PlatformCredentialCrypto {
    private const val SERVICE_NAME = "top.msfxp.schedule.credentials"
    private const val ACCOUNT_NAME = "cas_password"
    private const val KEYCHAIN_TOKEN = "KEYCHAIN_STORED_CREDENTIAL"

    override fun encrypt(rawText: String): String {
        if (rawText.isEmpty()) {
            deleteFromKeychain()
            return ""
        }
        saveToKeychain(rawText)
        return KEYCHAIN_TOKEN
    }

    override fun decrypt(encryptedText: String): String {
        if (encryptedText.isEmpty()) return ""
        if (encryptedText != KEYCHAIN_TOKEN) return encryptedText
        return readFromKeychain().orEmpty()
    }

    private fun saveToKeychain(value: String) {
        deleteFromKeychain()
        val bytes = value.encodeToByteArray()
        val data = bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
        val query = CFDictionaryCreateMutable(null, 4, null, null) ?: return
        val serviceRef = CFBridgingRetain(SERVICE_NAME)
        val accountRef = CFBridgingRetain(ACCOUNT_NAME)
        val dataRef = CFBridgingRetain(data)
        try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, serviceRef)
            CFDictionarySetValue(query, kSecAttrAccount, accountRef)
            CFDictionarySetValue(query, kSecValueData, dataRef)
            SecItemAdd(query, null)
        } finally {
            CFBridgingRelease(serviceRef)
            CFBridgingRelease(accountRef)
            CFBridgingRelease(dataRef)
            CFBridgingRelease(query)
        }
    }

    private fun readFromKeychain(): String? = memScoped {
        val query = CFDictionaryCreateMutable(null, 5, null, null) ?: return null
        val serviceRef = CFBridgingRetain(SERVICE_NAME)
        val accountRef = CFBridgingRetain(ACCOUNT_NAME)
        val result = alloc<CFTypeRefVar>()
        try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, serviceRef)
            CFDictionarySetValue(query, kSecAttrAccount, accountRef)
            CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
            val status = SecItemCopyMatching(query, result.ptr)
            if (status != errSecSuccess || result.value == null) return null
            val data = CFBridgingRelease(result.value) as? NSData ?: return null
            val len = data.length.toInt()
            if (len <= 0) return ""
            val bytes = ByteArray(len)
            bytes.usePinned { pinned ->
                memcpy(pinned.addressOf(0), data.bytes, data.length)
            }
            bytes.decodeToString()
        } finally {
            CFBridgingRelease(serviceRef)
            CFBridgingRelease(accountRef)
            CFBridgingRelease(query)
        }
    }

    private fun deleteFromKeychain() {
        val query = CFDictionaryCreateMutable(null, 3, null, null) ?: return
        val serviceRef = CFBridgingRetain(SERVICE_NAME)
        val accountRef = CFBridgingRetain(ACCOUNT_NAME)
        try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, serviceRef)
            CFDictionarySetValue(query, kSecAttrAccount, accountRef)
            SecItemDelete(query)
        } finally {
            CFBridgingRelease(serviceRef)
            CFBridgingRelease(accountRef)
            CFBridgingRelease(query)
        }
    }
}

// iOS 端外部链接、剪贴板与地图导航实现
object IosPlatformExternalHandler : PlatformExternalHandler {
    override fun openBrowser(url: String): Boolean {
        val nsUrl = platform.Foundation.NSURL.URLWithString(url) ?: return false
        val app = platform.UIKit.UIApplication.sharedApplication
        if (!app.canOpenURL(nsUrl)) return false
        app.openURL(nsUrl, options = emptyMap<Any?, Any>(), completionHandler = null)
        return true
    }

    override fun copyToClipboard(text: String, label: String) {
        platform.UIKit.UIPasteboard.generalPasteboard.string = text
    }

    override fun joinQQGroup(key: String): Boolean {
        val scheme = "mqqapi://card/show_pslcard?src_type=internal&version=1&card_type=group&uin=767082393"
        return openBrowser(scheme)
    }

    override fun showToast(message: String) = showIosToast(message)

    override fun getAvailableMapApps(): List<PlatformMapAppItem> {
        val app = platform.UIKit.UIApplication.sharedApplication
        val schemes = listOf(
            MapAppType.AMAP to "iosamap://",
            MapAppType.BAIDU to "baidumap://",
            MapAppType.TENCENT to "qqmap://"
        )
        return schemes.mapNotNull { (type, scheme) ->
            val url = platform.Foundation.NSURL.URLWithString(scheme) ?: return@mapNotNull null
            if (app.canOpenURL(url)) PlatformMapAppItem(type = type) else null
        }
    }

    override fun openMapWalkingNavigation(type: MapAppType, destination: String): Boolean {
        val encoded = destination.encodeURLParameter()
        val urlStr = when (type) {
            MapAppType.AMAP -> "iosamap://path?sourceApplication=cqust_schedule&dname=$encoded&dev=0&t=2"
            MapAppType.BAIDU -> "baidumap://map/direction?destination=$encoded&mode=walking&src=ios.cqust.schedule"
            MapAppType.TENCENT -> "qqmap://map/routeplan?type=walk&from=我的位置&to=$encoded&referer=cqust_schedule"
        }
        return openBrowser(urlStr)
    }
}

// 定位当前 key window（Toast 浮层挂载目标）
private fun resolveIosKeyWindow(): UIWindow? {
    val windows = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .flatMap { scene -> scene.windows.filterIsInstance<UIWindow>() }
    return windows.firstOrNull { it.keyWindow } ?: windows.firstOrNull()
}

// 在当前窗口层叠一个淡入淡出的轻量提示条
@OptIn(ExperimentalForeignApi::class)
private fun showIosToast(message: String) {
    if (message.isBlank()) return
    dispatch_async(dispatch_get_main_queue()) {
        val window = resolveIosKeyWindow() ?: return@dispatch_async
        val toast = UILabel().apply {
            text = message
            numberOfLines = 0L
            textAlignment = NSTextAlignmentCenter
            textColor = UIColor.whiteColor
            font = UIFont.systemFontOfSize(14.0)
            backgroundColor = UIColor.blackColor.colorWithAlphaComponent(0.82)
            alpha = 0.0
        }
        toast.layer.cornerRadius = 12.0
        toast.layer.masksToBounds = true

        val (windowWidth, windowHeight) = window.bounds.useContents { size.width to size.height }
        val maxContentWidth = windowWidth * 0.78
        val (contentWidth, contentHeight) = toast
            .sizeThatFits(CGSizeMake(maxContentWidth, Double.MAX_VALUE))
            .useContents { width to height }
        // UIView.frame 在 Kotlin/Native 中为只读属性，需通过 setFrame 赋值
        toast.setFrame(CGRectMake(0.0, 0.0, contentWidth + 32.0, contentHeight + 20.0))
        toast.center = CGPointMake(windowWidth / 2.0, windowHeight * 0.8)
        window.addSubview(toast)

        UIView.animateWithDuration(0.2, animations = { toast.alpha = 1.0 })
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 1_800_000_000L), dispatch_get_main_queue()) {
            UIView.animateWithDuration(
                duration = 0.25,
                animations = { toast.alpha = 0.0 },
                completion = { toast.removeFromSuperview() }
            )
        }
    }
}

