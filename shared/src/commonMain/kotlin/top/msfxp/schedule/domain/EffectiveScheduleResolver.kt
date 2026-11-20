package top.msfxp.schedule.domain

import top.msfxp.schedule.data.model.CourseAdjustment
import top.msfxp.schedule.data.model.CourseEventWithMeta
import top.msfxp.schedule.data.model.CourseWithEvents
import top.msfxp.schedule.data.model.ExamGridBlock
import top.msfxp.schedule.data.model.ExamRecord
import top.msfxp.schedule.data.model.ScheduleGridCell
import top.msfxp.schedule.data.model.buildScheduleGridCells
import top.msfxp.schedule.data.model.findSectionTimeSlot
import top.msfxp.schedule.data.model.toMergedBlocks
import top.msfxp.schedule.data.model.toMeta
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import top.msfxp.schedule.util.ExamTimeMapper

// 全学期有效课表快照（课程、调课、考试统一合成结果）
data class SemesterScheduleSnapshot(
    val weekCellsMap: Map<Int, List<ScheduleGridCell>> = emptyMap(),
    val weekExamBlocksMap: Map<Int, List<ExamGridBlock>> = emptyMap(),
    val weekUnarrangedMap: Map<Int, List<CourseEventWithMeta>> = emptyMap(),
    val weekCourseCountMap: Map<Int, Int> = emptyMap(),
    val courseColorIndexMap: Map<String, Int> = emptyMap()
)

// 有效排课领域解析器（统一收口原始课程 + 调课补丁 + 考试映射合成逻辑）
object EffectiveScheduleResolver {

    // 应用调课规则到指定周次的课程事件列表
    fun resolveWeekEvents(
        rawWeekEvents: List<CourseEventWithMeta>,
        adjustments: List<CourseAdjustment>,
        weekNumber: Int,
        allEvents: List<CourseEventWithMeta> = emptyList()
    ): List<CourseEventWithMeta> {
        if (adjustments.isEmpty()) return rawWeekEvents

        val weekAdjustments = adjustments.filter {
            it.originalWeek == weekNumber || it.targetWeek == weekNumber
        }
        if (weekAdjustments.isEmpty()) return rawWeekEvents

        val result = mutableListOf<CourseEventWithMeta>()

        // 1. 处理原本周排课（本周内微调、停课剔除、调出到其他周）
        for (event in rawWeekEvents) {
            val adj = weekAdjustments.find {
                it.originalWeek == weekNumber &&
                    it.courseId == event.courseId &&
                    it.eventType == event.courseType &&
                    it.originalDay == event.day &&
                    it.originalStartSection == event.startSection
            }

            if (adj == null) {
                result.add(event)
            } else if (adj.isSuspended || adj.targetWeek != weekNumber) {
                continue
            } else {
                result.add(
                    event.copy(
                        day = adj.targetDay,
                        startSection = adj.targetStartSection,
                        endSection = adj.targetEndSection,
                        location = adj.targetLocation.ifBlank { event.location },
                        isAdjusted = true
                    )
                )
            }
        }

        // 2. 处理从其他周跨周调入本周的课程
        val incomingAdjustments = weekAdjustments.filter {
            it.originalWeek != weekNumber && it.targetWeek == weekNumber && !it.isSuspended
        }
        if (incomingAdjustments.isNotEmpty() && allEvents.isNotEmpty()) {
            for (incomingAdj in incomingAdjustments) {
                val originEvent = allEvents.find {
                    it.courseId == incomingAdj.courseId &&
                        it.courseType == incomingAdj.eventType &&
                        it.week == incomingAdj.originalWeek &&
                        it.day == incomingAdj.originalDay &&
                        it.startSection == incomingAdj.originalStartSection
                } ?: allEvents.find {
                    it.courseId == incomingAdj.courseId && it.courseType == incomingAdj.eventType
                }

                if (originEvent != null) {
                    result.add(
                        originEvent.copy(
                            week = weekNumber,
                            day = incomingAdj.targetDay,
                            startSection = incomingAdj.targetStartSection,
                            endSection = incomingAdj.targetEndSection,
                            location = incomingAdj.targetLocation.ifBlank { originEvent.location },
                            isAdjusted = true
                        )
                    )
                }
            }
        }

        return result
    }

    // 解析指定周次与星期的全部有效排课事件（含跨周调入）
    fun resolveDayEvents(
        allEvents: List<CourseEventWithMeta>,
        adjustments: List<CourseAdjustment>,
        weekNumber: Int,
        dayOfWeek: Int
    ): List<CourseEventWithMeta> {
        val rawWeekEvents = allEvents.filter { it.week == weekNumber && it.day in 1..7 }
        return resolveWeekEvents(rawWeekEvents, adjustments, weekNumber, allEvents)
            .filter { it.day == dayOfWeek }
    }

    // 合并当日重叠课程的免打扰节次区间
    fun mergeDndSectionRanges(dayEvents: List<CourseEventWithMeta>): List<Pair<Int, Int>> {
        data class TimedRange(val startSection: Int, val endSection: Int, val start: LocalTime, val end: LocalTime)

        val timed = dayEvents.mapNotNull { ev ->
            val startSlot = findSectionTimeSlot(ev.startSection) ?: return@mapNotNull null
            val endSlot = findSectionTimeSlot(ev.endSection) ?: startSlot
            val startTime = runCatching { LocalTime.parse(startSlot.startTime) }.getOrNull() ?: return@mapNotNull null
            val endTime = runCatching { LocalTime.parse(endSlot.endTime) }.getOrNull() ?: return@mapNotNull null
            TimedRange(ev.startSection, ev.endSection, startTime, endTime)
        }.sortedWith(compareBy({ it.start }, { it.end }))

        if (timed.isEmpty()) return emptyList()

        val merged = mutableListOf<TimedRange>()
        var current = timed.first()
        for (idx in 1 until timed.size) {
            val next = timed[idx]
            if (next.start <= current.end) {
                if (next.end > current.end) {
                    current = current.copy(endSection = next.endSection, end = next.end)
                }
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged.map { it.startSection to it.endSection }
    }

    // 聚合全学期每周网格、待安排项与考试块
    fun buildSemesterScheduleSnapshot(
        allCourses: List<CourseWithEvents>,
        adjustments: List<CourseAdjustment>,
        exams: List<ExamRecord>,
        semesterStart: LocalDate,
        totalWeeks: Int
    ): SemesterScheduleSnapshot {
        val scheduledByWeek = mutableMapOf<Int, MutableList<CourseEventWithMeta>>()
        val unarrangedByWeek = mutableMapOf<Int, MutableList<CourseEventWithMeta>>()
        val allMetaEvents = mutableListOf<CourseEventWithMeta>()

        for (relation in allCourses) {
            val allCourseWeeks = relation.events.map { it.week }.filter { it > 0 }.distinct().sorted()
            for (ev in relation.events) {
                if (ev.week !in 1..totalWeeks) continue
                val meta = ev.toMeta(relation.course, allCourseWeeks)
                when {
                    ev.day in 1..7 -> {
                        allMetaEvents.add(meta)
                        scheduledByWeek.getOrPut(ev.week) { mutableListOf() }.add(meta)
                    }
                    ev.day == 0 -> {
                        unarrangedByWeek.getOrPut(ev.week) { mutableListOf() }.add(meta)
                    }
                }
            }
        }

        val blocksMap = mutableMapOf<Int, List<top.msfxp.schedule.data.model.MergedCourseBlock>>()
        val unarrangedMap = mutableMapOf<Int, List<CourseEventWithMeta>>()
        val countMap = mutableMapOf<Int, Int>()

        for (w in 1..totalWeeks) {
            val scheduledEvents = scheduledByWeek[w].orEmpty()
            val weekUnarranged = unarrangedByWeek[w].orEmpty()
            val adjustedScheduled = resolveWeekEvents(scheduledEvents, adjustments, w, allMetaEvents)

            val gridEvents = adjustedScheduled.filter { it.startSection > 0 }
            val pendingEvents = weekUnarranged.filter { unarr ->
                val adj = adjustments.find {
                    it.originalWeek == w && it.courseId == unarr.courseId && it.originalDay == 0
                }
                adj == null || (!adj.isSuspended && adj.targetDay == 0)
            } + adjustedScheduled.filter { it.startSection <= 0 }

            countMap[w] = gridEvents.size + pendingEvents.size
            blocksMap[w] = gridEvents.toMergedBlocks()
            unarrangedMap[w] = pendingEvents
        }

        val examBlocksMap = exams.filter { it.isScheduled }
            .mapNotNull { exam ->
                val week = exam.resolveWeek(semesterStart) ?: return@mapNotNull null
                val day = exam.resolvedWeekday ?: return@mapNotNull null
                val sections = ExamTimeMapper.resolveSections(exam.startTime, exam.endTime)
                    ?: return@mapNotNull null
                if (week !in 1..totalWeeks || day !in 1..7) return@mapNotNull null
                ExamGridBlock(week, day, sections.first, sections.second, exam)
            }
            .groupBy { it.week }

        examBlocksMap.forEach { (week, blocks) ->
            countMap[week] = (countMap[week] ?: 0) + blocks.size
        }

        val colorIndexByCourseId = allCourses.associate { it.course.id to it.course.colorIndex }
        val cellsMap = (1..totalWeeks).associateWith { week ->
            buildScheduleGridCells(
                courseBlocks = blocksMap[week].orEmpty(),
                examBlocks = examBlocksMap[week].orEmpty(),
                colorIndexByCourseId = colorIndexByCourseId
            )
        }

        return SemesterScheduleSnapshot(
            weekCellsMap = cellsMap,
            weekExamBlocksMap = examBlocksMap,
            weekUnarrangedMap = unarrangedMap,
            weekCourseCountMap = countMap,
            courseColorIndexMap = colorIndexByCourseId
        )
    }
}
