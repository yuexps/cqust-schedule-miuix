package top.msfxp.schedule

import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.context.startKoin
import org.koin.mp.KoinPlatformTools
import platform.UIKit.UIViewController
import top.msfxp.schedule.data.repository.SettingsRepository
import top.msfxp.schedule.platform.IosCalendarSync
import top.msfxp.schedule.platform.IosNotificationScheduler
import top.msfxp.schedule.platform.IosPlatformExternalHandler
import top.msfxp.schedule.platform.PlatformExternalActions
import top.msfxp.schedule.platform.PlatformSubScreens
import top.msfxp.schedule.platform.installIosNotificationCenterDelegate
import top.msfxp.schedule.platform.iosModule
import top.msfxp.schedule.ui.IosCalendarSyncScreen
import top.msfxp.schedule.ui.IosPreClassReminderScreen
import top.msfxp.schedule.util.iosCoroutineExceptionHandler
import top.msfxp.schedule.util.ioDispatcher

private var isIosInitialized = false
private val iosLifecycleScope =
    CoroutineScope(SupervisorJob() + iosCoroutineExceptionHandler + ioDispatcher)

// 初始化 iOS 端 Koin 依赖注入与系统服务
fun initIosDependencies() {
    if (isIosInitialized) return

    PlatformExternalActions.delegate = IosPlatformExternalHandler
    installIosNotificationCenterDelegate()

    startKoin {
        modules(iosModule)
    }
    // 初始化成功后再置位，避免失败后无法重试
    isIosInitialized = true
}

// App 回到前台时续期未来 7 天本地课前与考试提醒，并按需刷新系统日历
fun onAppForeground() {
    if (!isIosInitialized) return
    val koin = KoinPlatformTools.defaultContext().get()
    runCatching { koin.get<IosNotificationScheduler>() }.getOrNull()?.let { scheduler ->
        iosLifecycleScope.launch {
            scheduler.rescheduleUpcomingNotifications()
        }
    }

    val calendarSync = runCatching { koin.get<IosCalendarSync>() }.getOrNull() ?: return
    val settingsRepository = runCatching { koin.get<SettingsRepository>() }.getOrNull() ?: return
    iosLifecycleScope.launch {
        // 仅在已授权且开启自动同步时刷新，避免前台反复弹授权框
        if (calendarSync.hasCalendarAccess() && settingsRepository.getAppSettingsOnce().autoSyncToCalendar) {
            calendarSync.syncCurrentScheduleToCalendar()
        }
    }
}

// 供 SwiftUI / UIKit 宿主承载的 iOS 主视图控制器
fun MainViewController(): UIViewController {
    initIosDependencies()
    val platformSubScreens = PlatformSubScreens(
        preClassReminder = { onBack -> IosPreClassReminderScreen(onBack = onBack) },
        calendarSync = { onBack -> IosCalendarSyncScreen(onBack = onBack) }
    )
    return ComposeUIViewController {
        App(platformSubScreens)
    }
}
