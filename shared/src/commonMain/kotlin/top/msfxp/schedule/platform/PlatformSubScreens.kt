package top.msfxp.schedule.platform

import androidx.compose.runtime.Composable

// 平台差异化设置子页面渲染器
data class PlatformSubScreens(
    val preClassReminder: @Composable (onBack: () -> Unit) -> Unit,
    val calendarSync: @Composable (onBack: () -> Unit) -> Unit,
    val classAutomation: (@Composable (onBack: () -> Unit, onNavigateToPermissions: () -> Unit) -> Unit)? = null,
    val permissionManagement: (@Composable (onBack: () -> Unit) -> Unit)? = null
)
