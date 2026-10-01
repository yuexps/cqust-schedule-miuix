package top.msfxp.schedule.service

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import top.msfxp.schedule.data.repository.ScheduleRepository
import top.msfxp.schedule.data.repository.SettingsRepository
import top.msfxp.schedule.widget.updateAllAppWidgets

// 课表数据与偏好监听器
class CourseAlarmObserver(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val scheduleRepository: ScheduleRepository,
    private val calendarSyncHelper: CalendarSyncHelper,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private data class ScheduleTriggerParams(
        val reminderEnabled: Boolean,
        val remindBeforeMinutes: Int,
        val autoDndEnabled: Boolean,
        val autoSyncToCalendar: Boolean,
        val semesterId: String?,
        val semesterStartDate: String?,
        val totalWeeks: Int?,
        val coursesHash: Int,
        val adjustmentsHash: Int
    )

    // 启动响应式监听管道
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun startObserving() {
        val adjustmentsWithSemesterFlow = scheduleRepository.currentSemesterFlow.flatMapLatest { semester ->
            scheduleRepository.getAllAdjustmentsWithMetaFlow(semesterId = semester?.id.orEmpty())
        }

        combine(
            settingsRepository.appSettingsFlow,
            scheduleRepository.currentSemesterFlow,
            scheduleRepository.allCoursesFlow,
            adjustmentsWithSemesterFlow
        ) { settings, semester, courses, adjustments ->
            ScheduleTriggerParams(
                reminderEnabled = settings.reminderEnabled,
                remindBeforeMinutes = settings.remindBeforeMinutes,
                autoDndEnabled = settings.autoDndEnabled,
                autoSyncToCalendar = settings.autoSyncToCalendar,
                semesterId = semester?.id,
                semesterStartDate = semester?.startDate,
                totalWeeks = semester?.totalWeeks,
                coursesHash = courses.hashCode(),
                adjustmentsHash = adjustments.hashCode()
            )
        }
            .distinctUntilChanged()
            .debounce(500L)
            .onEach { params ->
                if (params.reminderEnabled || params.autoDndEnabled) {
                    DndSchedulerWorker.triggerImmediately(context)
                }
                updateAllAppWidgets(context)
                if (params.autoSyncToCalendar && calendarSyncHelper.hasCalendarPermission()) {
                    calendarSyncHelper.syncCurrentScheduleToCalendar()
                }
            }
            .launchIn(scope)
    }
}
