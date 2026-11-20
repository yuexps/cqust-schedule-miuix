package top.msfxp.schedule.di

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import top.msfxp.schedule.data.db.ScheduleDatabase
import top.msfxp.schedule.platform.AndroidPlatformAutomationTester
import top.msfxp.schedule.platform.AndroidPlatformReleaseInstaller
import top.msfxp.schedule.platform.AndroidPlatformStringProvider
import top.msfxp.schedule.platform.AndroidPlatformWallpaperStorage
import top.msfxp.schedule.platform.PlatformAutomationTester
import top.msfxp.schedule.platform.PlatformCalendarSync
import top.msfxp.schedule.platform.PlatformReleaseInstaller
import top.msfxp.schedule.platform.PlatformStringProvider
import top.msfxp.schedule.platform.PlatformWallpaperStorage
import top.msfxp.schedule.platform.createAndroidPreferencesDataStore
import top.msfxp.schedule.service.CalendarSyncHelper
import top.msfxp.schedule.service.CourseAlarmObserver
import top.msfxp.schedule.ui.settings.PersonalizationViewModel

// Android 应用 Koin 依赖注入模块
val appModule = module {
    includes(commonModule)

    // Room KMP 数据库（Android 端实例）
    single {
        val dbFile = androidContext().getDatabasePath("cqust_schedule_database")
        Room.databaseBuilder<ScheduleDatabase>(
            context = androidContext(),
            name = dbFile.absolutePath
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    // Android 平台存储与能力实现
    single { createAndroidPreferencesDataStore(androidContext()) }
    single { AndroidPlatformWallpaperStorage(androidContext()) }
    single<PlatformWallpaperStorage> { get<AndroidPlatformWallpaperStorage>() }
    single<PlatformStringProvider> { AndroidPlatformStringProvider(androidContext()) }
    single<PlatformAutomationTester> { AndroidPlatformAutomationTester(androidContext()) }
    single<PlatformReleaseInstaller> { AndroidPlatformReleaseInstaller(androidContext()) }
    single { CalendarSyncHelper(androidContext(), get(), get(), get()) }
    single<PlatformCalendarSync> { get<CalendarSyncHelper>() }

    // 课表与闹钟调度观察器
    single(createdAtStart = true) {
        CourseAlarmObserver(androidContext(), get(), get(), get(), get()).apply {
            startObserving()
        }
    }

    // Android 个性化视图模型（含相册裁剪支持）
    viewModel { PersonalizationViewModel(get(), get()) }
}
