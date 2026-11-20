package top.msfxp.schedule.platform

// 平台能力开关，用于控制 Android / iOS 差异化功能入口
data class PlatformCapabilities(
    val supportsPreClassReminder: Boolean = true,
    val supportsCalendarSync: Boolean = true,
    val supportsClassAutomation: Boolean = true,
    val supportsPermissionManagement: Boolean = true,
    val supportsInAppApkUpdate: Boolean = true,
    val supportsDesktopWidget: Boolean = true
)

// 当前平台配置
expect object PlatformConfig {
    val capabilities: PlatformCapabilities
    var appVersionName: String
}
