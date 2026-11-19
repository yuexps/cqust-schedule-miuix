package top.msfxp.schedule.service

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import top.msfxp.schedule.data.model.AutoControlMode
import top.msfxp.schedule.data.model.CourseAdjustment
import top.msfxp.schedule.data.model.CourseWithEvents
import top.msfxp.schedule.data.model.ExamRecord
import top.msfxp.schedule.data.repository.ExamRepository
import top.msfxp.schedule.data.repository.ScheduleRepository
import top.msfxp.schedule.data.repository.SettingsRepository
import top.msfxp.schedule.widget.updateAllAppWidgets

// 课表数据与偏好监听器
class CourseAlarmObserver(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val scheduleRepository: ScheduleRepository,
    private val calendarSyncHelper: CalendarSyncHelper,
    private val examRepository: ExamRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    // 闹钟排程触发参数
    private data class AlarmTriggerParams(
        val reminderEnabled: Boolean,
        val remindBeforeMinutes: Int,
        val autoDndEnabled: Boolean,
        val autoControlMode: AutoControlMode,
        val semesterId: String?,
        val semesterStartDate: String?,
        val totalWeeks: Int?,
        val scheduleSignature: String,
        val adjustmentsSignature: String,
        val examsSignature: String
    )

    // 小组件刷新触发参数
    private data class WidgetTriggerParams(
        val semesterId: String?,
        val semesterStartDate: String?,
        val totalWeeks: Int?,
        val coursesColorSignature: String,
        val paletteJson: String?,
        val scheduleSignature: String,
        val adjustmentsSignature: String,
        val examsSignature: String
    )

    // 日历同步触发参数
    private data class CalendarSyncParams(
        val semesterId: String?,
        val semesterStartDate: String?,
        val totalWeeks: Int?,
        val scheduleSignature: String,
        val adjustmentsSignature: String,
        val examsSignature: String
    )

    // 启动响应式监听管道
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun startObserving() {
        val adjustmentsFlow = scheduleRepository.currentSemesterFlow.flatMapLatest { semester ->
            scheduleRepository.getAdjustmentsFlow(semesterId = semester?.id.orEmpty())
        }

        // 1. 课前提醒与免打扰自动化排程管道
        combine(
            settingsRepository.appSettingsFlow,
            scheduleRepository.currentSemesterFlow,
            scheduleRepository.allCoursesFlow,
            adjustmentsFlow,
            examRepository.currentSemesterExamsFlow
        ) { settings, semester, courses, adjustments, exams ->
            AlarmTriggerParams(
                reminderEnabled = settings.reminderEnabled,
                remindBeforeMinutes = settings.remindBeforeMinutes,
                autoDndEnabled = settings.autoDndEnabled,
                autoControlMode = settings.autoControlMode,
                semesterId = semester?.id,
                semesterStartDate = semester?.startDate,
                totalWeeks = semester?.totalWeeks,
                scheduleSignature = buildScheduleSignature(courses),
                adjustmentsSignature = buildAdjustmentsSignature(adjustments),
                examsSignature = buildExamsSignature(exams)
            )
        }
            .distinctUntilChanged()
            .debounce(500L)
            .onEach { DndSchedulerWorker.triggerImmediately(context) }
            .launchIn(scope)

        // 2. 桌面小组件刷新管道
        combine(
            settingsRepository.appSettingsFlow,
            scheduleRepository.currentSemesterFlow,
            scheduleRepository.allCoursesFlow,
            adjustmentsFlow,
            examRepository.currentSemesterExamsFlow
        ) { settings, semester, courses, adjustments, exams ->
            WidgetTriggerParams(
                semesterId = semester?.id,
                semesterStartDate = semester?.startDate,
                totalWeeks = semester?.totalWeeks,
                coursesColorSignature = courses.joinToString(",") { "${it.course.id}:${it.course.colorIndex}" },
                paletteJson = settings.customCourseColorsJson,
                scheduleSignature = buildScheduleSignature(courses),
                adjustmentsSignature = buildAdjustmentsSignature(adjustments),
                examsSignature = buildExamsSignature(exams)
            )
        }
            .distinctUntilChanged()
            .debounce(500L)
            .onEach { updateAllAppWidgets(context) }
            .launchIn(scope)

        // 3. 日历自动同步管道
        combine(
            scheduleRepository.currentSemesterFlow,
            scheduleRepository.allCoursesFlow,
            adjustmentsFlow,
            examRepository.currentSemesterExamsFlow
        ) { semester, courses, adjustments, exams ->
            CalendarSyncParams(
                semesterId = semester?.id,
                semesterStartDate = semester?.startDate,
                totalWeeks = semester?.totalWeeks,
                scheduleSignature = buildScheduleSignature(courses),
                adjustmentsSignature = buildAdjustmentsSignature(adjustments),
                examsSignature = buildExamsSignature(exams)
            )
        }
            .distinctUntilChanged()
            .drop(1)
            .debounce(500L)
            .onEach {
                val settings = settingsRepository.getAppSettingsOnce()
                if (settings.autoSyncToCalendar && calendarSyncHelper.hasCalendarPermission()) {
                    calendarSyncHelper.syncCurrentScheduleToCalendar()
                }
            }
            .launchIn(scope)
    }

    // 构建不含视觉配色的排课结构指纹
    private fun buildScheduleSignature(courses: List<CourseWithEvents>): String =
        courses.joinToString("|") { relation ->
            val eventsPart = relation.events.joinToString(";") { ev ->
                "${ev.eventType}:${ev.week}:${ev.day}:${ev.startSection}-${ev.endSection}:${ev.location}:${ev.teacher}:${ev.projectName}"
            }
            "${relation.course.id}:${relation.course.name}:$eventsPart"
        }

    // 构建调课补丁指纹
    private fun buildAdjustmentsSignature(adjustments: List<CourseAdjustment>): String =
        adjustments.joinToString("|") { adj ->
            "${adj.id}:${adj.courseId}:${adj.eventType}:${adj.originalWeek}:${adj.originalDay}:${adj.originalStartSection}" +
                "->${adj.targetWeek}:${adj.targetDay}:${adj.targetStartSection}-${adj.targetEndSection}:${adj.targetLocation}:${adj.isSuspended}"
        }

    // 构建考试记录指纹
    private fun buildExamsSignature(exams: List<ExamRecord>): String =
        exams.joinToString("|") { ex ->
            "${ex.id}:${ex.examDate}:${ex.startTime}-${ex.endTime}:${ex.position}:${ex.seatNo}:${ex.status}"
        }
}
