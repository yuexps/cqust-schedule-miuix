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
            if (BuildConfig.DEBUG) {
                androidLogger()
            }
            androidContext(this@ScheduleApplication)
            modules(appModule)
        }

        // 注册免打扰与闹钟每日周期调度
        DndSchedulerWorker.enqueuePeriodicWork(this)
    }
}
