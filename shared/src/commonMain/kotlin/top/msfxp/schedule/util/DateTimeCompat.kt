package top.msfxp.schedule.util

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

// 获取当前时间戳（毫秒）
@OptIn(ExperimentalTime::class)
fun currentEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()

// 获取当前系统时区日期
@OptIn(ExperimentalTime::class)
fun todayLocalDate(timeZone: TimeZone = TimeZone.currentSystemDefault()): LocalDate =
    Clock.System.now().toLocalDateTime(timeZone).date

// 获取当前系统时区本地时刻
@OptIn(ExperimentalTime::class)
fun nowLocalTime(timeZone: TimeZone = TimeZone.currentSystemDefault()): LocalTime =
    Clock.System.now().toLocalDateTime(timeZone).time

// 获取当前系统时区日期时间
@OptIn(ExperimentalTime::class)
fun nowLocalDateTime(timeZone: TimeZone = TimeZone.currentSystemDefault()): LocalDateTime =
    Clock.System.now().toLocalDateTime(timeZone)

// ISO 星期序号（周一=1 .. 周日=7）
val LocalDate.isoDayOfWeekNumber: Int
    get() = dayOfWeek.isoDayNumber

// 回溯到本周一（若当天是周一则返回自身）
fun LocalDate.previousOrSameMonday(): LocalDate {
    val offset = dayOfWeek.isoDayNumber - 1
    return if (offset == 0) this else this.minus(offset, DateTimeUnit.DAY)
}

// 日期增减天数与周数
fun LocalDate.plusDays(days: Int): LocalDate = this.plus(days, DateTimeUnit.DAY)

fun LocalDate.minusDays(days: Int): LocalDate = this.minus(days, DateTimeUnit.DAY)

fun LocalDate.plusWeeks(weeks: Int): LocalDate = this.plus(weeks, DateTimeUnit.WEEK)

// 计算指定年月包含的天数
fun daysInMonth(year: Int, month: Int): Int {
    val firstDay = LocalDate(year, month, 1)
    val nextMonth = firstDay.plus(1, DateTimeUnit.MONTH)
    return firstDay.daysUntil(nextMonth)
}

// 计算两个日期相差天数（this 到 other）
fun LocalDate.daysBetween(other: LocalDate): Int = this.daysUntil(other)

// 格式化为 yyyy-MM-dd
fun LocalDate.formatYmd(): String {
    val m = monthNumber.toString().padStart(2, '0')
    val d = dayOfMonth.toString().padStart(2, '0')
    return "$year-$m-$d"
}

// 格式化为 yyyy/MM/dd
fun LocalDate.formatSlashYmd(): String {
    val m = monthNumber.toString().padStart(2, '0')
    val d = dayOfMonth.toString().padStart(2, '0')
    return "$year/$m/$d"
}

// 格式化为 HH:mm
fun LocalTime.formatHm(): String {
    val h = hour.toString().padStart(2, '0')
    val m = minute.toString().padStart(2, '0')
    return "$h:$m"
}

// 计算从 this 到 other 的分钟差
fun LocalTime.minutesUntil(other: LocalTime): Int =
    (other.hour * 60 + other.minute) - (this.hour * 60 + this.minute)

// 将本地日期时间转为 Epoch 毫秒
@OptIn(ExperimentalTime::class)
fun LocalDate.atTimeEpochMillis(
    time: LocalTime,
    timeZone: TimeZone = TimeZone.currentSystemDefault()
): Long = this.atTime(time).toInstant(timeZone).toEpochMilliseconds()

// 安全解析 yyyy-MM-dd
fun parseLocalDateOrNull(text: String?): LocalDate? {
    val trimmed = text?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    return runCatching { LocalDate.parse(trimmed) }.getOrNull()
}

// 安全解析 HH:mm 或 HH:mm:ss
fun parseLocalTimeOrNull(text: String?): LocalTime? {
    val trimmed = text?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    return runCatching { LocalTime.parse(trimmed) }.getOrNull()
}
