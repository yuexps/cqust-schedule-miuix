package top.msfxp.schedule

import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.context.startKoin
import org.koin.mp.KoinPlatformTools
import platform.UIKit.UIViewController
import top.msfxp.schedule.platform.IosNotificationScheduler
import top.msfxp.schedule.platform.IosPlatformExternalHandler
import top.msfxp.schedule.platform.PlatformExternalActions
import top.msfxp.schedule.platform.PlatformSubScreenRegistry
import top.msfxp.schedule.platform.PlatformSubScreens
import top.msfxp.schedule.platform.installIosNotificationCenterDelegate
import top.msfxp.schedule.platform.iosModule
import top.msfxp.schedule.ui.IosPreClassReminderScreen
import top.msfxp.schedule.util.iosCoroutineExceptionHandler
import top.msfxp.schedule.util.ioDispatcher

private var isIosInitialized = false
private val iosLifecycleScope =
    CoroutineScope(SupervisorJob() + iosCoroutineExceptionHandler + ioDispatcher)

// 初始化 iOS 端 Koin 依赖注入与平台服务注册
fun initIosDependencies() {
    if (isIosInitialized) return

    PlatformExternalActions.delegate = IosPlatformExternalHandler
    PlatformSubScreenRegistry.screens = PlatformSubScreens(
        PreClassReminder = { onBack -> IosPreClassReminderScreen(onBack = onBack) }
    )
    installIosNotificationCenterDelegate()

    startKoin {
        modules(iosModule)
    }
    // 初始化成功后再置位，避免失败后无法重试
    isIosInitialized = true
}

// App 回到前台时续期未来 7 天本地课前与考试提醒
fun onAppForeground() {
    if (!isIosInitialized) return
    val scheduler = runCatching {
        KoinPlatformTools.defaultContext().get().get<IosNotificationScheduler>()
    }.getOrNull() ?: return
    iosLifecycleScope.launch {
        scheduler.rescheduleUpcomingNotifications()
    }
}

// 供 SwiftUI / UIKit 宿主承载的 iOS 主视图控制器
fun MainViewController(): UIViewController {
    initIosDependencies()
    return ComposeUIViewController {
        App()
    }
}
