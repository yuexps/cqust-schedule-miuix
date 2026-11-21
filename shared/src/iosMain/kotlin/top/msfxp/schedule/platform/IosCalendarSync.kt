@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package top.msfxp.schedule.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import io.ktor.http.encodeURLParameter
import org.jetbrains.compose.resources.getString
import platform.EventKit.EKAlarm
import platform.EventKit.EKAuthorizationStatusAuthorized
import platform.EventKit.EKAuthorizationStatusDenied
import platform.EventKit.EKAuthorizationStatusFullAccess
import platform.EventKit.EKAuthorizationStatusRestricted
import platform.EventKit.EKCalendar
import platform.EventKit.EKEntityType
import platform.EventKit.EKEvent
import platform.EventKit.EKEventStore
import platform.EventKit.EKSpan
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIDevice
import top.msfxp.schedule.data.model.CourseEventWithMeta
import top.msfxp.schedule.data.model.CourseAdjustment
import top.msfxp.schedule.data.model.CourseWithEvents
import top.msfxp.schedule.data.model.ExamRecord
import top.msfxp.schedule.data.model.Semester
import top.msfxp.schedule.data.model.effectiveStartDate
import top.msfxp.schedule.data.model.findSectionTimeSlot
import top.msfxp.schedule.data.repository.ExamRepository
import top.msfxp.schedule.data.repository.ScheduleRepository
import top.msfxp.schedule.data.repository.SettingsRepository
import top.msfxp.schedule.domain.EffectiveScheduleResolver
import top.msfxp.schedule.shared.resources.Res
import top.msfxp.schedule.shared.resources.app_name
import top.msfxp.schedule.shared.resources.calendar_event_desc_adjusted
import top.msfxp.schedule.shared.resources.calendar_event_location_teacher_format
import top.msfxp.schedule.shared.resources.calendar_exam_desc_status_format
import top.msfxp.schedule.shared.resources.calendar_exam_desc_type_format
import top.msfxp.schedule.shared.resources.calendar_exam_location_format
import top.msfxp.schedule.shared.resources.calendar_exam_title_format
import top.msfxp.schedule.shared.resources.exam_type_label
import top.msfxp.schedule.util.atTimeEpochMillis
import top.msfxp.schedule.util.currentEpochMillis
import top.msfxp.schedule.util.iosCoroutineExceptionHandler
import top.msfxp.schedule.util.plusDays
import kotlin.coroutines.resume

// 待写入系统日历的日程条目
private data class DesiredCalendarEvent(
    val syncKey: String,
    val title: String,
    val location: String,
    val description: String,
    val startMillis: Long,
    val endMillis: Long,
    val remindMinutes: Int
)

// 课程日程构建上下文
private data class CourseEventContext(
    val tableId: String,
    val semesterStart: LocalDate,
    val remindMinutes: Int
)

// 自动同步监听所需的数据快照
private data class AutoSyncSnapshot(
    val semester: Semester?,
    val courses: List<CourseWithEvents>,
    val adjustments: List<CourseAdjustment>,
    val exams: List<ExamRecord>
)

// NSDate 参考日期（2001-01-01 00:00:00 UTC）对应的 Unix 秒数
private const val REFERENCE_DATE_EPOCH_SECONDS = 978_307_200.0
private const val CALENDAR_IDENTIFIER_KEY = "cqust_schedule_calendar_identifier"
private const val CALENDAR_EVENT_URL_PREFIX = "cqust-schedule://event/"

// iOS 端系统日历同步（EventKit 专属“重科课表”日历）
class IosCalendarSync(
    private val settingsRepository: SettingsRepository,
    private val scheduleRepository: ScheduleRepository,
    private val examRepository: ExamRepository
) : PlatformCalendarSync {

    private val store = EKEventStore()
    private val syncMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + iosCoroutineExceptionHandler + Dispatchers.IO)

    // 课表、调课或考试变化时自动刷新已授权的系统日历
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun startObserving() {
        scope.launch {
            combine(
                scheduleRepository.currentSemesterFlow,
                scheduleRepository.allCoursesFlow,
                scheduleRepository.currentAdjustmentsFlow,
                examRepository.currentSemesterExamsFlow
            ) { semester, courses, adjustments, exams ->
                AutoSyncSnapshot(
                    semester = semester,
                    courses = courses,
                    adjustments = adjustments,
                    exams = exams
                )
            }.distinctUntilChanged()
                .drop(1)
                .debounce(500)
                .collectLatest {
                    val settings = settingsRepository.getAppSettingsOnce()
                    if (settings.autoSyncToCalendar && hasCalendarAccess()) {
                        syncCurrentScheduleToCalendar()
                    }
                }
        }
    }

    // 是否已获得日历读写授权
    fun hasCalendarAccess(): Boolean =
        isAuthorizedStatus(EKEventStore.authorizationStatusForEntityType(EKEntityType.EKEntityTypeEvent))

    override suspend fun ensureCalendarAccess(): Boolean = withContext(Dispatchers.Main) {
        requestCalendarAccess()
    }

    override suspend fun syncCurrentScheduleToCalendar(): Boolean = withContext(Dispatchers.Main) {
        if (!requestCalendarAccess()) return@withContext false
        syncMutex.withLock {
            try {
                performSync()
            } catch (error: Exception) {
                println("iOS calendar sync failed: ${error.message ?: "unknown error"}")
                false
            }
        }
    }

    override suspend fun deleteScheduleCalendar(): Boolean = withContext(Dispatchers.Main) {
        if (!requestCalendarAccess()) return@withContext false
        syncMutex.withLock {
            try {
                val calendars = findScheduleCalendars()
                val removed = calendars.all {
                    store.removeCalendar(it, commit = false, error = null)
                }
                val committed = removed && (calendars.isEmpty() || store.commit(error = null))
                if (committed) NSUserDefaults.standardUserDefaults.removeObjectForKey(CALENDAR_IDENTIFIER_KEY)
                committed
            } catch (error: Exception) {
                println("iOS calendar deletion failed: ${error.message ?: "unknown error"}")
                false
            }
        }
    }

    // 构建待同步日程并写入专属日历
    private suspend fun performSync(): Boolean {
        val desired = buildDesiredEvents() ?: return false
        val calendar = getOrCreateCalendar(getString(Res.string.app_name)) ?: return false
        return syncCalendarEvents(calendar, desired)
    }

    // 汇总本学期课程与考试日程
    private suspend fun buildDesiredEvents(): List<DesiredCalendarEvent>? {
        val settings = settingsRepository.getAppSettingsOnce()
        val semester = scheduleRepository.getCurrentSemesterOnce() ?: return null
        val tableId = scheduleRepository.getOrCreateDefaultTableId()
        val events = scheduleRepository.getAllEventsForSemester(tableId, semester.id)
        val adjustments = scheduleRepository.getAdjustmentsOnce(tableId, semester.id)
        val context = CourseEventContext(
            tableId = tableId,
            semesterStart = semester.effectiveStartDate,
            remindMinutes = settings.calendarRemindBeforeMinutes
        )
        val result = mutableListOf<DesiredCalendarEvent>()
        val usedKeys = mutableSetOf<String>()

        for (week in 1..semester.totalWeeks) {
            // 仅纳入有作息的课程事件，待安排项不写入日历
            val rawWeekEvents = events.filter { it.week == week && it.day in 1..7 && it.startSection != 0 }
            val weekEvents = EffectiveScheduleResolver.resolveWeekEvents(
                rawWeekEvents = rawWeekEvents,
                adjustments = adjustments,
                weekNumber = week,
                allEvents = events
            )
            weekEvents.forEach { event ->
                result.add(buildCourseEvent(context, week, event, usedKeys))
            }
        }

        examRepository.getExamsOnce(tableId, semester.id)
            .mapNotNull { buildExamEvent(it, context.remindMinutes) }
            .forEach { result.add(it) }
        return result
    }

    // 构建单条课程日程
    private suspend fun buildCourseEvent(
        context: CourseEventContext,
        week: Int,
        event: CourseEventWithMeta,
        usedKeys: MutableSet<String>
    ): DesiredCalendarEvent {
        val startSlot = findSectionTimeSlot(event.startSection)
        val endSlot = findSectionTimeSlot(event.endSection) ?: startSlot
        val startTime = parseTimeOrNull(startSlot?.startTime) ?: LocalTime(8, 30)
        val endTime = parseTimeOrNull(endSlot?.endTime) ?: LocalTime(9, 15)
        val eventDate = context.semesterStart.plusDays((week - 1) * 7 + (event.day - 1))
        val description = if (event.isAdjusted) getString(Res.string.calendar_event_desc_adjusted) else ""

        return DesiredCalendarEvent(
            syncKey = buildSyncKey(context.tableId, week, event, usedKeys),
            title = event.calendarTitle,
            location = buildLocation(event.displayLocation.trim(), event.teacher.trim()),
            description = description,
            startMillis = eventDate.atTimeEpochMillis(startTime),
            endMillis = eventDate.atTimeEpochMillis(endTime),
            remindMinutes = context.remindMinutes
        )
    }

    // 构建考试日程
    private suspend fun buildExamEvent(exam: ExamRecord, remindMinutes: Int): DesiredCalendarEvent? {
        val date = exam.examDateValue ?: return null
        val startTime = parseTimeOrNull(exam.startTime) ?: return null
        val endTime = parseTimeOrNull(exam.endTime) ?: return null
        val location = exam.seatNo?.takeIf { it.isNotBlank() }
            ?.let { getString(Res.string.calendar_exam_location_format, exam.displayPosition, it) }
            ?: exam.displayPosition
        val examType = exam.examType.ifBlank { getString(Res.string.exam_type_label) }
        val description = buildString {
            append(getString(Res.string.calendar_exam_desc_type_format, examType))
            if (!exam.status.isNullOrBlank()) {
                append("\n").append(getString(Res.string.calendar_exam_desc_status_format, exam.status))
            }
            if (exam.remark.isNotBlank()) append("\n").append(exam.remark)
        }

        return DesiredCalendarEvent(
            syncKey = "cqust_exam_${exam.id}",
            title = getString(Res.string.calendar_exam_title_format, exam.courseName),
            location = location,
            description = description,
            startMillis = date.atTimeEpochMillis(startTime),
            endMillis = date.atTimeEpochMillis(endTime),
            remindMinutes = remindMinutes
        )
    }

    // 拼接地点与教师
    private suspend fun buildLocation(location: String, teacher: String): String = when {
        teacher.isBlank() -> location
        location.isBlank() -> teacher
        else -> getString(Res.string.calendar_event_location_teacher_format, location, teacher)
    }

    // 生成去重的日历同步键
    private fun buildSyncKey(
        tableId: String,
        week: Int,
        event: CourseEventWithMeta,
        usedKeys: MutableSet<String>
    ): String {
        val cleanProject = event.projectName.trim().replace(Regex("[^a-zA-Z0-9_\\u4e00-\\u9fa5]"), "_")
        val base = "cqust_${tableId}_w${week}_d${event.day}_s${event.startSection}_c${event.courseId}_p${cleanProject}"
        var key = base
        var counter = 1
        while (!usedKeys.add(key)) {
            key = "${base}_${counter++}"
        }
        return key
    }

    // 增量同步：更新变动日程、插入新增、删除失效
    private fun syncCalendarEvents(calendar: EKCalendar, desired: List<DesiredCalendarEvent>): Boolean {
        val existing = queryExistingEvents(calendar)
        val desiredByKey = desired.associateBy { eventUrl(it.syncKey) }
        val matchedKeys = mutableSetOf<String>()
        var changeCount = 0
        var allSucceeded = true

        existing.forEach { event ->
            val key = event.URL?.absoluteString
            val target = key?.let { desiredByKey[it] }
            if (target == null || !matchedKeys.add(target.syncKey)) {
                allSucceeded = store.removeEvent(
                    event,
                    span = EKSpan.EKSpanThisEvent,
                    commit = false,
                    error = null
                ) && allSucceeded
                changeCount++
            } else if (isEventChanged(event, target)) {
                applyEvent(event, target)
                allSucceeded = store.saveEvent(
                    event,
                    span = EKSpan.EKSpanThisEvent,
                    commit = false,
                    error = null
                ) && allSucceeded
                changeCount++
            }
        }

        desired.filter { it.syncKey !in matchedKeys }.forEach { target ->
            val event = EKEvent.eventWithEventStore(store)
            event.calendar = calendar
            applyEvent(event, target)
            allSucceeded = store.saveEvent(
                event,
                span = EKSpan.EKSpanThisEvent,
                commit = false,
                error = null
            ) && allSucceeded
            changeCount++
        }

        if (changeCount > 0) allSucceeded = store.commit(error = null) && allSucceeded
        return allSucceeded
    }

    // 查询专属日历近一年的日程
    private fun queryExistingEvents(calendar: EKCalendar): List<EKEvent> {
        val nowSeconds = currentEpochMillis() / 1000.0
        val windowSeconds = 400L * 24 * 3600
        val start = toNsDate(((nowSeconds - windowSeconds) * 1000.0).toLong())
        val end = toNsDate(((nowSeconds + windowSeconds) * 1000.0).toLong())
        val predicate = store.predicateForEventsWithStartDate(start, end, listOf(calendar))
        return store.eventsMatchingPredicate(predicate).filterIsInstance<EKEvent>()
    }

    // 判断已有日程内容是否变动
    private fun isEventChanged(event: EKEvent, target: DesiredCalendarEvent): Boolean {
        if ((event.title ?: "") != target.title) return true
        if ((event.location ?: "") != target.location) return true
        if ((event.notes ?: "") != target.description) return true
        if (toEpochMillis(event.startDate) != target.startMillis) return true
        if (toEpochMillis(event.endDate) != target.endMillis) return true
        val alarms = event.alarms?.filterIsInstance<EKAlarm>().orEmpty()
        return if (target.remindMinutes > 0) {
            alarms.size != 1 || alarms.first().relativeOffset != -target.remindMinutes * 60.0
        } else {
            alarms.isNotEmpty()
        }
    }

    // 写入日程字段与提醒
    private fun applyEvent(event: EKEvent, target: DesiredCalendarEvent) {
        event.title = target.title
        event.location = target.location
        event.notes = target.description
        event.startDate = toNsDate(target.startMillis)
        event.endDate = toNsDate(target.endMillis)
        event.URL = NSURL.URLWithString(eventUrl(target.syncKey))
        event.alarms = if (target.remindMinutes > 0) {
            listOf(EKAlarm.alarmWithRelativeOffset(-target.remindMinutes * 60.0))
        } else {
            emptyList<EKAlarm>()
        }
    }

    // 查找“重科课表”专属日历
    private fun findScheduleCalendars(): List<EKCalendar> {
        val identifier = NSUserDefaults.standardUserDefaults.stringForKey(CALENDAR_IDENTIFIER_KEY)
            ?: return emptyList()
        return store.calendarsForEntityType(EKEntityType.EKEntityTypeEvent)
            .filterIsInstance<EKCalendar>()
            .filter { it.allowsContentModifications && it.calendarIdentifier == identifier }
    }

    // 获取或创建专属日历
    private fun getOrCreateCalendar(title: String): EKCalendar? {
        findScheduleCalendars().firstOrNull()?.let { calendar ->
            if (calendar.title != title) {
                calendar.title = title
                if (!store.saveCalendar(calendar, commit = true, error = null)) return null
            }
            return calendar
        }
        val calendar = EKCalendar.calendarForEntityType(EKEntityType.EKEntityTypeEvent, store)
        calendar.title = title
        calendar.source = store.defaultCalendarForNewEvents?.source ?: return null
        if (!store.saveCalendar(calendar, commit = true, error = null)) return null
        NSUserDefaults.standardUserDefaults.setObject(
            calendar.calendarIdentifier,
            forKey = CALENDAR_IDENTIFIER_KEY
        )
        return calendar
    }

    // 确认日历读写授权，必要时触发系统授权弹窗
    private suspend fun requestCalendarAccess(): Boolean {
        val status = EKEventStore.authorizationStatusForEntityType(EKEntityType.EKEntityTypeEvent)
        if (isAuthorizedStatus(status)) return true
        if (status == EKAuthorizationStatusDenied || status == EKAuthorizationStatusRestricted) return false
        return suspendCancellableCoroutine { cont ->
            val completion: (Boolean, NSError?) -> Unit = { granted, _ ->
                if (cont.isActive) cont.resume(granted)
            }
            if (isIos17OrLater()) {
                store.requestFullAccessToEventsWithCompletion(completion)
            } else {
                store.requestAccessToEntityType(EKEntityType.EKEntityTypeEvent, completion)
            }
        }
    }

    // 系统版本是否不低于 iOS 17
    private fun isIos17OrLater(): Boolean =
        (UIDevice.currentDevice.systemVersion.substringBefore('.').toIntOrNull() ?: 0) >= 17

    // 是否已获得完整日历权限
    private fun isAuthorizedStatus(status: Long): Boolean =
        status == EKAuthorizationStatusAuthorized || status == EKAuthorizationStatusFullAccess

    private fun parseTimeOrNull(text: String?): LocalTime? =
        text?.takeIf { it.isNotBlank() }?.let { runCatching { LocalTime.parse(it) }.getOrNull() }

    private fun toNsDate(epochMillis: Long): NSDate =
        NSDate(timeIntervalSinceReferenceDate = epochMillis / 1000.0 - REFERENCE_DATE_EPOCH_SECONDS)

    private fun toEpochMillis(date: NSDate?): Long =
        ((date?.timeIntervalSince1970 ?: 0.0) * 1000.0).toLong()

    private fun eventUrl(syncKey: String): String =
        CALENDAR_EVENT_URL_PREFIX + syncKey.encodeURLParameter()
}
