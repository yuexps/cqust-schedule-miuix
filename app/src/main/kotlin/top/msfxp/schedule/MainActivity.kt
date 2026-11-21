package top.msfxp.schedule

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        val platformSubScreens = PlatformSubScreens(
            preClassReminder = { onBack -> PreClassReminderScreen(onBack = onBack) },
            calendarSync = { onBack -> CalendarSyncScreen(onBack = onBack) },
            classAutomation = { onBack, onNavigateToPermissions ->
                ClassAutomationScreen(
                    onBack = onBack,
                    onNavigateToPermissions = onNavigateToPermissions
                )
            },
            permissionManagement = { onBack -> PermissionManagementScreen(onBack = onBack) }
        )
        enableEdgeToEdge()
        setContent {
            App(platformSubScreens)
        }
    }

    override fun onResume() {
        super.onResume()
        AppActionHelper.resumePendingInstallIfNeeded(this)
    }
}
