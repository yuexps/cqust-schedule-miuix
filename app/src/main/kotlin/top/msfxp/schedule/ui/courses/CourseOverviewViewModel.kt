package top.msfxp.schedule.ui.courses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import top.msfxp.schedule.data.model.*
import top.msfxp.schedule.data.repository.ExamRepository
import top.msfxp.schedule.data.repository.ScheduleRepository
import top.msfxp.schedule.data.repository.SettingsRepository

// 课程概览分组项数据模型
data class CourseGroupItem(
    val courseId: String,
    val courseCode: String,
    val name: String,
    val credit: String,
    val category: String,
    val theoryTeacher: String = "",
    val practicalTeacher: String = "",
    val colorIndex: Int,
    val theorySlots: List<CompressedCourseSlot> = emptyList(),
    val practicalSlots: List<CompressedCourseSlot> = emptyList(),
    val isPracticalOnly: Boolean = false,
    val projectCount: Int = 0,
    val exams: List<ExamRecord> = emptyList()
) {
    val hasTheory: Boolean get() = theorySlots.isNotEmpty()
    val hasPractical: Boolean get() = practicalSlots.isNotEmpty()
    val hasExam: Boolean get() = exams.isNotEmpty()
    val primaryTeacher: String get() = theoryTeacher.ifBlank { practicalTeacher }
}

// 课程概览界面状态
data class CourseOverviewUiState(
    val courses: List<CourseGroupItem> = emptyList(),
    val unmatchedExams: List<ExamRecord> = emptyList(),
    val theoryCount: Int = 0,
    val practicalCount: Int = 0,
    val totalCredits: Double = 0.0,
    val courseColors: List<CourseColor> = DefaultCourseColors
)

// 课程概览视图模型
class CourseOverviewViewModel(
    scheduleRepository: ScheduleRepository,
    settingsRepository: SettingsRepository,
    examRepository: ExamRepository
) : ViewModel() {

    // 响应式聚合课程概览状态
    val uiState: StateFlow<CourseOverviewUiState> = combine(
        scheduleRepository.allCoursesFlow,
        settingsRepository.appSettingsFlow,
        examRepository.currentSemesterExamsFlow
    ) { allCourses, settings, exams ->
        val palette = parseCoursePaletteJson(settings.customCourseColorsJson)
        val examsByCourseId = exams.filter { !it.courseId.isNullOrBlank() }.groupBy { it.courseId }
        val groups = allCourses.map { relation ->
            val course = relation.course
            val theoryEvents = relation.events.filter { !it.isPractical }
            val practicalEvents = relation.events.filter { it.isPractical }

            val theorySlots = theoryEvents.toCompressedSlots()
                .sortedWith(compareBy({ it.day }, { sectionTimeKey(it.startSection) }, { it.weeks.minOrNull() ?: 0 }))
            val practicalSlots = practicalEvents.toCompressedSlots()
                .sortedWith(compareBy({ it.weeks.minOrNull() ?: 0 }, { it.day }, { sectionTimeKey(it.startSection) }))

            val theoryTeacher = theoryEvents
                .map { it.teacher }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString("/")

            val practicalTeacher = practicalEvents
                .map { it.teacher }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString("/")

            val projectCount = practicalEvents
                .map { it.projectName }
                .filter { it.isNotBlank() }
                .distinct()
                .size

            CourseGroupItem(
                courseId = course.id,
                courseCode = course.courseCode,
                name = course.name,
                credit = course.credit,
                category = course.category,
                theoryTeacher = theoryTeacher,
                practicalTeacher = practicalTeacher,
                colorIndex = course.colorIndex,
                theorySlots = theorySlots,
                practicalSlots = practicalSlots,
                isPracticalOnly = theoryEvents.isEmpty() && practicalEvents.isNotEmpty(),
                projectCount = projectCount,
                exams = examsByCourseId[course.id].orEmpty().distinctBy { it.id }
            )
        }.sortedBy { it.name }

        val courseIds = groups.map { it.courseId }.toSet()
        CourseOverviewUiState(
            courses = groups,
            unmatchedExams = exams.filter { it.courseId.isNullOrBlank() || it.courseId !in courseIds },
            theoryCount = groups.count { it.hasTheory },
            practicalCount = groups.count { it.hasPractical },
            totalCredits = groups.sumOf { it.credit.toDoubleOrNull() ?: 0.0 },
            courseColors = palette
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CourseOverviewUiState()
    )
}
