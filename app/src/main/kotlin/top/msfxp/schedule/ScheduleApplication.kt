package top.msfxp.schedule

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import top.msfxp.schedule.di.appModule
import top.msfxp.schedule.service.DndSchedulerWorker

// 应用程序入口，负责依赖注入与后台任务初始化
class ScheduleApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidLogger()
            androidContext(this@ScheduleApplication)
            modules(appModule)
        }

        // 初始化免打扰与闹钟每日调度及即时刷新
        DndSchedulerWorker.enqueuePeriodicWork(this)
        DndSchedulerWorker.triggerImmediately(this)
    }
}
