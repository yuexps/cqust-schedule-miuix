package top.msfxp.schedule.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalTime
import top.msfxp.schedule.data.db.ScheduleDao
import top.msfxp.schedule.data.model.*
import top.msfxp.schedule.domain.EffectiveScheduleResolver
import top.msfxp.schedule.util.currentEpochMillis
import top.msfxp.schedule.util.nowLocalTime

// 课表与学期数据仓库
class ScheduleRepository(
    private val dao: ScheduleDao
) {
    val defaultTableFlow: Flow<CourseTable?> = dao.getDefaultCourseTable()
    val allSemestersFlow: Flow<List<Semester>> = dao.getAllSemesters()
    val currentSemesterFlow: Flow<Semester?> = dao.getCurrentSemester()

    // 获取或创建默认课表 ID
    suspend fun getOrCreateDefaultTableId(): String {
        val existing = defaultTableFlow.first()
        if (existing != null) return existing.id
        val newTable = CourseTable(
            id = "default",
            name = "重科课表",
            createdAt = currentEpochMillis()
        )
        dao.insertCourseTable(newTable)
        return "default"
    }

    // 观察当前激活学期的全部课程及事件
    @OptIn(ExperimentalCoroutinesApi::class)
    val allCoursesFlow: Flow<List<CourseWithEvents>> = combine(
        defaultTableFlow,
        currentSemesterFlow
    ) { table, semester ->
        table?.id to semester?.id
    }.distinctUntilChanged().flatMapLatest { (tableId, semesterId) ->
        if (tableId != null && !semesterId.isNullOrBlank()) {
            dao.getAllCoursesWithEvents(tableId, semesterId)
        } else {
            flowOf(emptyList())
        }
    }

    // 观察当前激活学期的全部调课补丁
    @OptIn(ExperimentalCoroutinesApi::class)
    val currentAdjustmentsFlow: Flow<List<CourseAdjustment>> = combine(
        defaultTableFlow,
        currentSemesterFlow
    ) { table, semester ->
        table?.id to semester?.id
    }.distinctUntilChanged().flatMapLatest { (tableId, semesterId) ->
        if (tableId != null && !semesterId.isNullOrBlank()) {
            dao.getAdjustmentsFlow(tableId, semesterId)
        } else {
            flowOf(emptyList())
        }
    }

    fun getAllCoursesWithEvents(tableId: String, semesterId: String): Flow<List<CourseWithEvents>> {
        return dao.getAllCoursesWithEvents(tableId, semesterId)
    }

    suspend fun getAllCoursesWithEventsOnce(tableId: String, semesterId: String): List<CourseWithEvents> {
        return dao.getAllCoursesWithEventsOnce(tableId, semesterId)
    }

    // 检查指定学期是否已有本地缓存课表
    suspend fun hasCoursesForSemester(tableId: String, semesterId: String): Boolean {
        return dao.countCoursesForSemester(tableId, semesterId) > 0
    }

    // 读取指定学期已有课程配色映射
    suspend fun getCourseColorMap(tableId: String, semesterId: String): Map<String, Int> {
        return dao.getCourseColorsForSemester(tableId, semesterId).associate { it.id to it.colorIndex }
    }

    // 更新课程配色编号
    suspend fun updateCourseColor(tableId: String, courseId: String, colorIndex: Int) {
        dao.updateCourseColor(tableId, courseId, colorIndex)
    }

    // 按学期原子替换课程与事件
    suspend fun saveSemesterSchedule(
        tableId: String,
        semesterId: String,
        courses: List<Course>,
        events: List<CourseEvent>
    ) {
        dao.replaceSemesterCoursesAndEvents(tableId, semesterId, courses, events)
    }

    // 获取指定学期所有已排教学事件
    suspend fun getAllEventsForSemester(tableId: String, semesterId: String): List<CourseEventWithMeta> {
        return dao.getAllEventsForSemester(tableId, semesterId)
    }

    // 获取今日时间轴课程列表
    suspend fun getTodayCourses(
        tableId: String,
        semesterId: String,
        dayOfWeek: Int,
        currentWeek: Int
    ): List<TodayCourseItem> {
        if (semesterId.isBlank()) return emptyList()
        val allEvents = dao.getAllEventsForSemester(tableId, semesterId)
        val adjustments = dao.getAdjustmentsOnce(tableId, semesterId)
        val events = EffectiveScheduleResolver.resolveDayEvents(allEvents, adjustments, currentWeek, dayOfWeek)
        val currentTime = nowLocalTime()

        // 按作息时刻排序，使非标准作息节次落在正确位置
        return events.map { ev ->
            val startSlot = findSectionTimeSlot(ev.startSection)
            val endSlot = findSectionTimeSlot(ev.endSection) ?: startSlot

            val startTimeStr = startSlot?.startTime ?: "08:30"
            val endTimeStr = endSlot?.endTime ?: "09:15"

            val isFinished = try {
                LocalTime.parse(endTimeStr) < currentTime
            } catch (_: Exception) {
                false
            }

            TodayCourseItem(
                event = ev,
                startTime = startTimeStr,
                endTime = endTimeStr,
                isFinished = isFinished
            )
        }.sortedWith(compareBy({ it.startTime }, { it.event.courseName }))
    }

    // 清空课表下全部学期的课程与事件
    suspend fun clearAllCourses(tableId: String) {
        dao.clearAllCoursesForTable(tableId)
    }

    // 学期数据操作
    suspend fun getCurrentSemesterOnce(): Semester? = dao.getCurrentSemesterOnce()
    suspend fun getSemesterById(semesterId: String): Semester? = dao.getSemesterById(semesterId)

    suspend fun replaceSemesters(semesters: List<Semester>, activeSemesterId: String) {
        dao.replaceSemesters(semesters, activeSemesterId)
    }

    suspend fun setActiveSemester(semesterId: String) {
        dao.setActiveSemester(semesterId)
    }

    suspend fun updateSemesterStartDate(semesterId: String, startDate: String) {
        dao.updateSemesterStartDate(semesterId, startDate)
    }

    suspend fun updateSemesterTotalWeeks(semesterId: String, totalWeeks: Int) {
        dao.updateSemesterTotalWeeks(semesterId, totalWeeks)
    }

    suspend fun clearSemesters() {
        dao.clearSemesters()
    }

    // 调课规则 (CourseAdjustment) 相关操作
    fun getAdjustmentsFlow(tableId: String = "default", semesterId: String): Flow<List<CourseAdjustment>> =
        dao.getAdjustmentsFlow(tableId, semesterId)

    fun getAllAdjustmentsWithMetaFlow(tableId: String = "default", semesterId: String): Flow<List<CourseAdjustmentWithMeta>> =
        dao.getAllAdjustmentsWithMeta(tableId, semesterId)

    suspend fun getAdjustmentsOnce(tableId: String = "default", semesterId: String): List<CourseAdjustment> =
        dao.getAdjustmentsOnce(tableId, semesterId)

    suspend fun getAdjustmentForEvent(
        tableId: String = "default",
        semesterId: String,
        courseId: String,
        eventType: String,
        week: Int,
        day: Int,
        startSection: Int
    ): CourseAdjustment? = dao.getAdjustmentForEvent(
        tableId = tableId,
        semesterId = semesterId,
        courseId = courseId,
        eventType = eventType,
        week = week,
        day = day,
        startSection = startSection
    )

    // 移动课程（目标坐标不能有课）
    suspend fun moveCourse(adjustment: CourseAdjustment) {
        dao.upsertAdjustmentForEvent(adjustment.copy(isSuspended = false))
    }

    // 调换两门课程（单事务原子互换时段）
    suspend fun swapCourses(adjA: CourseAdjustment, adjB: CourseAdjustment) {
        dao.swapAdjustments(adjA, adjB)
    }

    // 标记删除（本节停课）
    suspend fun markCourseDeleted(adjustment: CourseAdjustment) {
        dao.upsertAdjustmentForEvent(adjustment.copy(isSuspended = true))
    }

    // 保存单项调课通用方法
    suspend fun saveAdjustment(adjustment: CourseAdjustment) {
        if (adjustment.isSuspended) {
            markCourseDeleted(adjustment)
        } else {
            moveCourse(adjustment)
        }
    }

    // 删除单项调课
    suspend fun deleteAdjustment(id: Long) {
        dao.deleteAdjustmentById(id)
    }

    // 清空当前学期调课记录
    suspend fun clearAdjustmentsBySemester(tableId: String = "default", semesterId: String) {
        dao.clearAdjustmentsBySemester(tableId, semesterId)
    }

    // 一键清空全部调课记录
    suspend fun clearAllAdjustments(tableId: String = "default") {
        dao.clearAllAdjustments(tableId)
    }
}
