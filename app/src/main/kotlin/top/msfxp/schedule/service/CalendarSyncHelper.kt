package top.msfxp.schedule.service

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.msfxp.schedule.data.model.DefaultTimeSlots
import top.msfxp.schedule.data.model.effectiveStartDate
import top.msfxp.schedule.data.repository.ScheduleRepository
import top.msfxp.schedule.data.repository.SettingsRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone

// 系统日历同步助手
class CalendarSyncHelper(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val scheduleRepository: ScheduleRepository
) {
    companion object {
        private const val CALENDAR_ACCOUNT_NAME = "cqust_schedule_account"
        private const val CALENDAR_ACCOUNT_TYPE = CalendarContract.ACCOUNT_TYPE_LOCAL
        private const val CALENDAR_DISPLAY_NAME = "重科课表"
    }

    // 检查日历读写权限
    fun hasCalendarPermission(): Boolean {
        val readGranted = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        val writeGranted = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        return readGranted && writeGranted
    }

    // 将当前课表同步至系统日历
    suspend fun syncCurrentScheduleToCalendar(): Boolean = withContext(Dispatchers.IO) {
        if (!hasCalendarPermission()) return@withContext false

        try {
            val settings = settingsRepository.getAppSettingsOnce()
            val currentSemester = scheduleRepository.getCurrentSemesterOnce()
            val totalWeeks = currentSemester?.totalWeeks ?: 20
            val semesterStartDate = currentSemester.effectiveStartDate

            val tableId = scheduleRepository.getOrCreateDefaultTableId()
            val events = scheduleRepository.getAllEventsForTable(tableId)
            val slots = DefaultTimeSlots
            if (events.isEmpty()) return@withContext false

            val calendarId = getOrCreateCalendarId()
            if (calendarId == -1L) return@withContext false

            // 清除已有课程日程
            clearCalendarEvents(calendarId)

            val adjustments = scheduleRepository.getAdjustmentsOnce(tableId, semesterId = currentSemester?.id.orEmpty())
            val zoneId = ZoneId.systemDefault()
            val calendarRemindMinutes = settings.calendarRemindBeforeMinutes

            for (w in 1..totalWeeks) {
                val weekRawEvents = events.filter { it.week == w && it.day in 1..7 && it.startSection > 0 }
                val weekAdjustedEvents = top.msfxp.schedule.data.repository.applyAdjustmentsToWeekEvents(weekRawEvents, adjustments, w, events)

                weekAdjustedEvents.forEach { ev ->
                    val startSlot = slots.find { it.sectionNumber == ev.startSection }
                    val endSlot = slots.find { it.sectionNumber == ev.endSection } ?: startSlot

                    val startTime = startSlot?.startTime ?: "08:30"
                    val endTime = endSlot?.endTime ?: "09:15"

                    val parsedStartTime = try { LocalTime.parse(startTime) } catch (_: Exception) { LocalTime.of(8, 30) }
                    val parsedEndTime = try { LocalTime.parse(endTime) } catch (_: Exception) { LocalTime.of(9, 15) }

                    val daysOffset = (w - 1) * 7L + (ev.day - 1)
                    val eventDate = semesterStartDate.plusDays(daysOffset)

                    val startDateTime = LocalDateTime.of(eventDate, parsedStartTime)
                    val endDateTime = LocalDateTime.of(eventDate, parsedEndTime)

                    val startMillis = startDateTime.atZone(zoneId).toInstant().toEpochMilli()
                    val endMillis = endDateTime.atZone(zoneId).toInstant().toEpochMilli()

                    val descParts = mutableListOf<String>()
                    if (ev.isAdjusted) descParts.add("【临时调课安排】")
                    if (ev.teacher.isNotBlank()) descParts.add("教师: ${ev.teacher}")
                    val desc = descParts.joinToString("\n")

                    val payload = CalendarEventPayload(
                        title = ev.calendarTitle,
                        location = ev.location,
                        description = desc,
                        startMillis = startMillis,
                        endMillis = endMillis,
                        remindMinutes = calendarRemindMinutes
                    )
                    insertCalendarEvent(calendarId, payload)
                }
            }

            true
        } catch (e: Exception) {
            android.util.Log.e("CalendarSyncHelper", "Sync schedule to calendar failed", e)
            false
        }
    }

    // 从系统日历中删除专属日历账户及所有关联日程
    suspend fun deleteScheduleCalendar(): Boolean = withContext(Dispatchers.IO) {
        if (!hasCalendarPermission()) return@withContext false

        try {
            val calendarId = findCalendarId()
            if (calendarId != -1L) {
                clearCalendarEvents(calendarId)
            }

            val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, CALENDAR_ACCOUNT_NAME)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CALENDAR_ACCOUNT_TYPE)
                .build()

            val selection = "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?"
            val selectionArgs = arrayOf(CALENDAR_ACCOUNT_NAME, CALENDAR_ACCOUNT_TYPE)
            context.contentResolver.delete(uri, selection, selectionArgs)
            true
        } catch (e: Exception) {
            android.util.Log.e("CalendarSyncHelper", "Delete schedule calendar failed", e)
            false
        }
    }

    // 查询已存在的专属本地日历账户 ID（若不存在返回 -1）
    private fun findCalendarId(): Long {
        val uri = CalendarContract.Calendars.CONTENT_URI
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val selection = "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?"
        val selectionArgs = arrayOf(CALENDAR_ACCOUNT_NAME, CALENDAR_ACCOUNT_TYPE)

        val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            if (it.moveToFirst()) {
                return it.getLong(0)
            }
        }
        return -1L
    }

    // 获取或创建专属本地日历账户 ID
    private fun getOrCreateCalendarId(): Long {
        val existingId = findCalendarId()
        if (existingId != -1L) return existingId

        // 创建本地专属日历账户
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, CALENDAR_ACCOUNT_NAME)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CALENDAR_ACCOUNT_TYPE)
            put(CalendarContract.Calendars.NAME, CALENDAR_DISPLAY_NAME)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CALENDAR_DISPLAY_NAME)
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF0066FF.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, CALENDAR_ACCOUNT_NAME)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        }

        val insertUri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, CALENDAR_ACCOUNT_NAME)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CALENDAR_ACCOUNT_TYPE)
            .build()

        val createdUri = context.contentResolver.insert(insertUri, values)
        return createdUri?.lastPathSegment?.toLongOrNull() ?: -1L
    }


    // 清除指定日历账户下的所有日程
    private fun clearCalendarEvents(calendarId: Long) {
        val uri = CalendarContract.Events.CONTENT_URI
        context.contentResolver.delete(uri, "${CalendarContract.Events.CALENDAR_ID} = ?", arrayOf(calendarId.toString()))
    }

    // 插入单条日历日程与提醒
    private fun insertCalendarEvent(calendarId: Long, payload: CalendarEventPayload) {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, payload.title)
            put(CalendarContract.Events.EVENT_LOCATION, payload.location)
            put(CalendarContract.Events.DESCRIPTION, payload.description)
            put(CalendarContract.Events.DTSTART, payload.startMillis)
            put(CalendarContract.Events.DTEND, payload.endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }

        val eventUri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        val eventId = eventUri?.lastPathSegment?.toLongOrNull()

        if (eventId != null && payload.remindMinutes > 0) {
            val reminderValues = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, payload.remindMinutes)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminderValues)
        }
    }
}

// 日历日程参数载荷
private data class CalendarEventPayload(
    val title: String,
    val location: String,
    val description: String,
    val startMillis: Long,
    val endMillis: Long,
    val remindMinutes: Int
)
