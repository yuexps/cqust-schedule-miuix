package top.msfxp.schedule.service

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.msfxp.schedule.data.model.DefaultTimeSlots
import top.msfxp.schedule.data.model.effectiveStartDate
import top.msfxp.schedule.data.repository.ScheduleRepository
import top.msfxp.schedule.data.repository.SettingsRepository
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
        private const val LEGACY_CALENDAR_ACCOUNT_NAME = "cqust_schedule_account"
        private const val CALENDAR_ACCOUNT_TYPE = CalendarContract.ACCOUNT_TYPE_LOCAL
        private const val CALENDAR_DISPLAY_NAME = "重科课表"
        private const val BATCH_OPERATION_LIMIT = 200
    }

    // 日历账号使用当前应用包名
    private val accountName: String get() = context.packageName
    private val syncMutex = Mutex()

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

        syncMutex.withLock {
            try {
                val settings = settingsRepository.getAppSettingsOnce()
                val currentSemester = scheduleRepository.getCurrentSemesterOnce()
                val totalWeeks = currentSemester?.totalWeeks ?: 20
                val semesterStartDate = currentSemester.effectiveStartDate

                val tableId = scheduleRepository.getOrCreateDefaultTableId()
                val events = scheduleRepository.getAllEventsForTable(tableId)
                val slots = DefaultTimeSlots
                if (events.isEmpty()) return@withLock false

                val calendarId = getOrCreateCalendarId()
                if (calendarId == -1L) return@withLock false

                // 物理硬清除已有课程日程与提醒
                clearCalendarEvents(calendarId)

                val adjustments = scheduleRepository.getAdjustmentsOnce(tableId, semesterId = currentSemester?.id.orEmpty())
                val zoneId = ZoneId.systemDefault()
                val calendarRemindMinutes = settings.calendarRemindBeforeMinutes
                val payloads = mutableListOf<CalendarEventPayload>()

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

                        payloads.add(
                            CalendarEventPayload(
                                title = ev.calendarTitle,
                                location = ev.location,
                                description = desc,
                                startMillis = startMillis,
                                endMillis = endMillis,
                                remindMinutes = calendarRemindMinutes
                            )
                        )
                    }
                }

                // 批处理事务插入日历事件与提醒
                batchInsertCalendarEvents(calendarId, payloads)
                true
            } catch (e: Exception) {
                android.util.Log.e("CalendarSyncHelper", "Sync schedule to calendar failed", e)
                false
            }
        }
    }

    // 从系统日历中删除专属日历账户及所有关联日程
    suspend fun deleteScheduleCalendar(): Boolean = withContext(Dispatchers.IO) {
        if (!hasCalendarPermission()) return@withContext false

        syncMutex.withLock {
            try {
                val calendarIds = findAllCalendarIds()
                calendarIds.forEach { clearCalendarEvents(it) }

                val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                    .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CALENDAR_ACCOUNT_TYPE)
                    .build()

                val selection = "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?"
                val selectionArgs = arrayOf(accountName, CALENDAR_ACCOUNT_TYPE)
                context.contentResolver.delete(uri, selection, selectionArgs)
                true
            } catch (e: Exception) {
                android.util.Log.e("CalendarSyncHelper", "Delete schedule calendar failed", e)
                false
            }
        }
    }

    // 查询所有已存在的专属本地日历账户 ID（并清理旧版本遗留账号）
    private fun findAllCalendarIds(): List<Long> {
        val uri = CalendarContract.Calendars.CONTENT_URI
        val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_NAME)
        val selection = "(${CalendarContract.Calendars.ACCOUNT_NAME} = ? OR ${CalendarContract.Calendars.ACCOUNT_NAME} = ?) AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?"
        val selectionArgs = arrayOf(accountName, LEGACY_CALENDAR_ACCOUNT_NAME, CALENDAR_ACCOUNT_TYPE)

        val currentAccounts = mutableListOf<Long>()
        val legacyAccounts = mutableListOf<Long>()
        val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val acc = it.getString(1)
                if (acc == accountName) {
                    currentAccounts.add(id)
                } else {
                    legacyAccounts.add(id)
                }
            }
        }

        // 清除旧版本遗留账户及数据
        legacyAccounts.forEach { legacyId ->
            clearCalendarEvents(legacyId, LEGACY_CALENDAR_ACCOUNT_NAME)
            val delUri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, LEGACY_CALENDAR_ACCOUNT_NAME)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CALENDAR_ACCOUNT_TYPE)
                .build()
            context.contentResolver.delete(delUri, "${CalendarContract.Calendars._ID} = ?", arrayOf(legacyId.toString()))
        }

        return currentAccounts
    }

    // 获取或创建专属本地日历账户 ID
    private fun getOrCreateCalendarId(): Long {
        val existingIds = findAllCalendarIds()
        if (existingIds.isNotEmpty()) {
            // 清理多余的历史遗留账户，只保留首个
            if (existingIds.size > 1) {
                for (i in 1 until existingIds.size) {
                    val excessId = existingIds[i]
                    clearCalendarEvents(excessId)
                    val delUri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
                        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CALENDAR_ACCOUNT_TYPE)
                        .build()
                    context.contentResolver.delete(delUri, "${CalendarContract.Calendars._ID} = ?", arrayOf(excessId.toString()))
                }
            }
            return existingIds.first()
        }

        // 创建以应用包名命名的专属日历账户
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CALENDAR_ACCOUNT_TYPE)
            put(CalendarContract.Calendars.NAME, CALENDAR_DISPLAY_NAME)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CALENDAR_DISPLAY_NAME)
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF0066FF.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, accountName)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        }

        val insertUri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CALENDAR_ACCOUNT_TYPE)
            .build()

        val createdUri = context.contentResolver.insert(insertUri, values)
        return createdUri?.lastPathSegment?.toLongOrNull() ?: -1L
    }

    // 物理硬清除指定日历账户下的所有日程与关联提醒
    private fun clearCalendarEvents(calendarId: Long, customAccountName: String = accountName) {
        val uri = CalendarContract.Events.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, customAccountName)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CALENDAR_ACCOUNT_TYPE)
            .build()
        context.contentResolver.delete(uri, "${CalendarContract.Events.CALENDAR_ID} = ?", arrayOf(calendarId.toString()))
    }

    // 事务批处理批量插入日历事件与对应提醒
    private fun batchInsertCalendarEvents(calendarId: Long, payloads: List<CalendarEventPayload>) {
        if (payloads.isEmpty()) return

        val eventsUri = CalendarContract.Events.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CALENDAR_ACCOUNT_TYPE)
            .build()

        val remindersUri = CalendarContract.Reminders.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CALENDAR_ACCOUNT_TYPE)
            .build()

        val timeZoneId = TimeZone.getDefault().id
        val ops = ArrayList<ContentProviderOperation>()

        for (payload in payloads) {
            val eventOpIndex = ops.size
            ops.add(
                ContentProviderOperation.newInsert(eventsUri)
                    .withValue(CalendarContract.Events.CALENDAR_ID, calendarId)
                    .withValue(CalendarContract.Events.TITLE, payload.title)
                    .withValue(CalendarContract.Events.EVENT_LOCATION, payload.location)
                    .withValue(CalendarContract.Events.DESCRIPTION, payload.description)
                    .withValue(CalendarContract.Events.DTSTART, payload.startMillis)
                    .withValue(CalendarContract.Events.DTEND, payload.endMillis)
                    .withValue(CalendarContract.Events.EVENT_TIMEZONE, timeZoneId)
                    .build()
            )

            if (payload.remindMinutes > 0) {
                ops.add(
                    ContentProviderOperation.newInsert(remindersUri)
                        .withValueBackReference(CalendarContract.Reminders.EVENT_ID, eventOpIndex)
                        .withValue(CalendarContract.Reminders.MINUTES, payload.remindMinutes)
                        .withValue(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                        .build()
                )
            }

            if (ops.size >= BATCH_OPERATION_LIMIT) {
                context.contentResolver.applyBatch(CalendarContract.AUTHORITY, ops)
                ops.clear()
            }
        }

        if (ops.isNotEmpty()) {
            context.contentResolver.applyBatch(CalendarContract.AUTHORITY, ops)
            ops.clear()
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
