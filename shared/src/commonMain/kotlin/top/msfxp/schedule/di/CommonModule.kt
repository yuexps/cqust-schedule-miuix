package top.msfxp.schedule.di

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import top.msfxp.schedule.data.api.CqustSyncManager
import top.msfxp.schedule.data.db.ScheduleDatabase
import top.msfxp.schedule.data.repository.ExamRepository
import top.msfxp.schedule.data.repository.ScheduleRepository
import top.msfxp.schedule.data.repository.SettingsRepository
import top.msfxp.schedule.ui.courses.CourseOverviewViewModel
import top.msfxp.schedule.ui.login.CqustLoginViewModel
import top.msfxp.schedule.ui.schedule.WeeklyScheduleViewModel
import top.msfxp.schedule.ui.settings.AboutViewModel
import top.msfxp.schedule.ui.settings.SettingsViewModel

// 双端共享的 Koin 业务与视图模型模块
val commonModule = module {
    single { get<ScheduleDatabase>().scheduleDao() }
    single { get<ScheduleDatabase>().examDao() }

    // 仓库与同步协调器
    single { SettingsRepository(get(), get()) }
    single { ScheduleRepository(get()) }
    single { ExamRepository(get(), get()) }
    single { CqustSyncManager(get(), get(), get(), get()) }

    // 跨平台共享 ViewModel
    viewModel { WeeklyScheduleViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { SettingsViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { CqustLoginViewModel(get(), get(), get()) }
    viewModel { CourseOverviewViewModel(get(), get(), get()) }
    viewModel { AboutViewModel(get(), get(), get()) }
}
