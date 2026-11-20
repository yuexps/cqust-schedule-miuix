package top.msfxp.schedule.data.api

import top.msfxp.schedule.data.model.COURSE_TYPE_PRACTICAL
import top.msfxp.schedule.data.model.COURSE_TYPE_THEORY
import top.msfxp.schedule.data.model.Course
import top.msfxp.schedule.data.model.CourseEvent
import top.msfxp.schedule.data.model.DefaultCourseColors
import top.msfxp.schedule.data.model.buildCourseId
import top.msfxp.schedule.data.model.requireBaseCourseCode

// 时空矩阵构建结果
data class ScheduleBuildResult(
    val courses: List<Course>,
    val events: List<CourseEvent>,
    val theoryCourseCount: Int,
    val practicalCourseCount: Int
)

// 课表时空矩阵构建器（按基础课程代码归并课程主表，理论与实践事件统一下沉至 CourseEvent）
object ScheduleMatrixBuilder {

    // 构建课程元数据与时空事件列表（优先保留已有课程配色）
    fun build(
        tableId: String,
        semesterId: String,
        eamsCourses: List<RawEamsCourse>,
        practicalItems: List<RawPracticalItem> = emptyList(),
        existingColorMap: Map<String, Int> = emptyMap()
    ): ScheduleBuildResult {
        val theoryByBase = eamsCourses.groupBy {
            requireBaseCourseCode(it.courseCode, "理论课:${it.name}")
        }
        val practicalByBase = practicalItems.groupBy {
            requireBaseCourseCode(it.courseCode, "实践课:${it.name}")
        }

        val allBaseCodes = (theoryByBase.keys + practicalByBase.keys).distinct()
        val usedColors = existingColorMap.values.toMutableSet()
        var nextColorIdx = 0

        fun allocateColor(courseId: String): Int {
            existingColorMap[courseId]?.let { return it }
            // 优先分配尚未占用的调色槽位
            repeat(DefaultCourseColors.size) {
                val candidate = (nextColorIdx++) % DefaultCourseColors.size
                if (usedColors.add(candidate)) return candidate
            }
            return (nextColorIdx++) % DefaultCourseColors.size
        }

        val courses = mutableListOf<Course>()
        val events = mutableListOf<CourseEvent>()

        for (baseCode in allBaseCodes) {
            val theoryList = theoryByBase[baseCode].orEmpty()
            val practicalList = practicalByBase[baseCode].orEmpty()
            val courseId = buildCourseId(tableId, semesterId, baseCode)

            val firstTheory = theoryList.firstOrNull()
            val firstPractical = practicalList.firstOrNull()

            courses.add(
                Course(
                    id = courseId,
                    tableId = tableId,
                    semesterId = semesterId,
                    baseCode = baseCode,
                    courseCode = firstTheory?.courseCode?.ifBlank { baseCode } ?: baseCode,
                    name = firstTheory?.name ?: firstPractical?.name.orEmpty(),
                    credit = firstTheory?.credit.orEmpty(),
                    category = firstTheory?.category?.ifBlank { "实践教学" } ?: "实践教学",
                    colorIndex = allocateColor(courseId),
                    remark = firstTheory?.remark.orEmpty()
                )
            )

            for (raw in theoryList) {
                events.addAll(expandTheoryEvents(courseId, tableId, semesterId, raw))
            }
            for (item in practicalList) {
                events.addAll(expandPracticalEvents(courseId, tableId, semesterId, item))
            }
        }

        return ScheduleBuildResult(
            courses = courses,
            events = events,
            theoryCourseCount = theoryByBase.size,
            practicalCourseCount = practicalByBase.size
        )
    }

    // 展开单条常规理论课的各周次事件
    private fun expandTheoryEvents(
        courseId: String,
        tableId: String,
        semesterId: String,
        rawCourse: RawEamsCourse
    ): List<CourseEvent> {
        val targetWeeks = if (rawCourse.weeks.isEmpty()) listOf(0) else rawCourse.weeks
        val rawLocation = rawCourse.position.trim()
        return targetWeeks.map { week ->
            CourseEvent(
                courseId = courseId,
                tableId = tableId,
                semesterId = semesterId,
                eventType = COURSE_TYPE_THEORY,
                week = week,
                day = rawCourse.day,
                startSection = rawCourse.startSection,
                endSection = rawCourse.endSection,
                location = rawLocation,
                teacher = rawCourse.teacher,
                projectName = "",
                studentCount = rawCourse.studentCount
            )
        }
    }

    // 展开单条实践项目的各周次事件
    private fun expandPracticalEvents(
        courseId: String,
        tableId: String,
        semesterId: String,
        item: RawPracticalItem
    ): List<CourseEvent> {
        val rawLocation = item.location.trim()
        return item.weeks.map { week ->
            CourseEvent(
                courseId = courseId,
                tableId = tableId,
                semesterId = semesterId,
                eventType = COURSE_TYPE_PRACTICAL,
                week = week,
                day = item.day,
                startSection = item.startSection,
                endSection = item.endSection,
                location = rawLocation,
                teacher = item.teacher,
                projectName = item.projectName,
                studentCount = item.studentCount
            )
        }
    }
}
