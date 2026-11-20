package top.msfxp.schedule.platform

import androidx.compose.runtime.Composable

// 平台差异化设置子页面渲染器
data class PlatformSubScreens(
    val Personalization: @Composable (onBack: () -> Unit) -> Unit = {},
    val PreClassReminder: @Composable (onBack: () -> Unit) -> Unit = {},
    val CalendarSync: @Composable (onBack: () -> Unit) -> Unit = {},
    val ClassAutomation: @Composable (onBack: () -> Unit, onNavigateToPermissions: () -> Unit) -> Unit = { _, _ -> },
    val PermissionManagement: @Composable (onBack: () -> Unit) -> Unit = {}
)

// 全局平台子页面注册入口
object PlatformSubScreenRegistry {
    var screens: PlatformSubScreens = PlatformSubScreens()
}
