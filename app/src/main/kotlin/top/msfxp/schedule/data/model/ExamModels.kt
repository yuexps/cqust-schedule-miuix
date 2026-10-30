package top.msfxp.schedule.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

// 考试批次与形式常量
const val EXAM_TYPE_FINAL = "期末考试"
const val EXAM_MODE_WRITTEN = "标考"
const val EXAM_MODE_NON_STANDARD = "非标"

// 教务原始考试条目
data class RawExamItem(
    val sequence: Int,
    val courseCode: String,
    val courseName: String,
    val examType: String = EXAM_TYPE_FINAL,
    val examDate: String? = null,
    val week: Int? = null,
    val weekday: Int? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val position: String? = null,
    val seatNo: String? = null,
    val status: String? = null,
    val remark: String = "",
    val arrangeText: String = ""
) {
    // 日期与起止时间完整时视为标准化考试
    val isScheduled: Boolean
        get() = !examDate.isNullOrBlank() && !startTime.isNullOrBlank() && !endTime.isNullOrBlank()

    val examMode: String
        get() = if (isScheduled) EXAM_MODE_WRITTEN else EXAM_MODE_NON_STANDARD

    val canSyncCalendar: Boolean
        get() = isScheduled && !position.isNullOrBlank()
}

// 考试记录实体
@Entity(
    tableName = "exam_records",
    indices = [Index(value = ["tableId", "semesterId", "examDate"], name = "index_exam_records_semester")]
)
data class ExamRecord(
    @PrimaryKey val id: String,
    val tableId: String,
    val semesterId: String,
    val courseId: String?,
    val examType: String,
    val sequence: Int,
    val courseCode: String,
    val courseName: String,
    val examDate: String?,
    val week: Int?,
    val weekday: Int?,
    val startTime: String?,
    val endTime: String?,
    val position: String?,
    val seatNo: String?,
    val status: String?,
    val remark: String,
    val arrangeText: String,
    val updatedAt: Long
) {
    // 日期与起止时间完整时视为标准化考试
    val isScheduled: Boolean
        get() = !examDate.isNullOrBlank() && !startTime.isNullOrBlank() && !endTime.isNullOrBlank()

    val examMode: String
        get() = if (isScheduled) EXAM_MODE_WRITTEN else EXAM_MODE_NON_STANDARD

    val canSyncCalendar: Boolean
        get() = isScheduled && !position.isNullOrBlank()

    // 安全解析考试日期
    val examDateValue: LocalDate?
        get() = examDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    // 日期与安排星期不一致
    val hasWeekdayMismatch: Boolean
        get() {
            val date = examDateValue ?: return false
            return weekday != null && date.dayOfWeek.value != weekday
        }
}

// 教务考试抓取结果
data class ExamFetchResult(
    val success: Boolean,
    val errorMessage: String? = null,
    val items: List<RawExamItem> = emptyList(),
    val isSessionExpired: Boolean = false
)

// 构造考试记录稳定主键
fun buildExamRecordId(tableId: String, semesterId: String, item: RawExamItem): String {
    val key = if (item.isScheduled) {
        "${item.courseCode}|${item.examDate}|${item.startTime}"
    } else {
        "${item.courseCode}|${item.sequence}"
    }
    return "$tableId|$semesterId|$key"
}
