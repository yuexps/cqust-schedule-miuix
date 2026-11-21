package top.msfxp.schedule

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import top.msfxp.schedule.platform.PlatformSubScreenRegistry
import top.msfxp.schedule.platform.PlatformSubScreens
import top.msfxp.schedule.ui.settings.subscreens.CalendarSyncScreen
import top.msfxp.schedule.ui.settings.subscreens.ClassAutomationScreen
import top.msfxp.schedule.ui.settings.subscreens.PermissionManagementScreen
import top.msfxp.schedule.ui.settings.subscreens.PreClassReminderScreen
import top.msfxp.schedule.util.AppActionHelper

// 应用主入口 Activity
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlatformSubScreenRegistry.screens = PlatformSubScreens(
            PreClassReminder = { onBack -> PreClassReminderScreen(onBack = onBack) },
            CalendarSync = { onBack -> CalendarSyncScreen(onBack = onBack) },
            ClassAutomation = { onBack, onNavigateToPermissions ->
                ClassAutomationScreen(
                    onBack = onBack,
                    onNavigateToPermissions = onNavigateToPermissions
                )
            },
            PermissionManagement = { onBack -> PermissionManagementScreen(onBack = onBack) }
        )
        enableEdgeToEdge()
        setContent {
            App()
        }
    }

    override fun onResume() {
        super.onResume()
        AppActionHelper.resumePendingInstallIfNeeded(this)
    }
}
