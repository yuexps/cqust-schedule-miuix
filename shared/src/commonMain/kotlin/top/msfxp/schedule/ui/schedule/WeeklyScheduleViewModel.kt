package top.msfxp.schedule.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import top.msfxp.schedule.data.api.AppRelease
import top.msfxp.schedule.data.api.CqustSyncManager
import top.msfxp.schedule.data.api.LocalCrypto
import top.msfxp.schedule.data.api.UpdateCheckService
import top.msfxp.schedule.data.model.*
import top.msfxp.schedule.data.repository.ExamRepository
import top.msfxp.schedule.data.repository.ScheduleRepository
import top.msfxp.schedule.data.repository.SettingsRepository
import top.msfxp.schedule.domain.EffectiveScheduleResolver
import top.msfxp.schedule.domain.SemesterScheduleSnapshot
import top.msfxp.schedule.platform.PlatformConfig
import top.msfxp.schedule.platform.PlatformReleaseInstaller
import top.msfxp.schedule.platform.PlatformStringProvider
import top.msfxp.schedule.util.daysBetween
import top.msfxp.schedule.util.ioDispatcher
import top.msfxp.schedule.util.previousOrSameMonday
import top.msfxp.schedule.util.todayLocalDate

// 周课表主界面状态
data class WeeklyScheduleUiState(
    val isReady: Boolean = false,
    val isLoggedIn: Boolean = false,
    val semesterStartDate: LocalDate? = SemesterDateHelper.calculateDefaultStartDate(),
    val totalWeeks: Int = 20,
    val currentWeekNumber: Int = SemesterDateHelper.calculateWeekNumber(),
    val currentSemesterId: String = "",
    val isSemesterSet: Boolean = false,
    val isVacation: Boolean = false,
    val timeSlots: List<TimeSlot> = DefaultTimeSlots,
    val weekCellsMap: Map<Int, List<ScheduleGridCell>> = emptyMap(),
    val weekExamBlocksMap: Map<Int, List<ExamGridBlock>> = emptyMap(),
    val weekUnarrangedMap: Map<Int, List<CourseEventWithMeta>> = emptyMap(),
    val weekCourseCountMap: Map<Int, Int> = emptyMap(),
    val isSyncing: Boolean = false,
    val syncMessage: String? = null,
    val hasCustomWallpaper: Boolean = false,
    val wallpaperMaskDim: Float = 0.15f,
    val courseCardAlpha: Float = 0.95f,
    val courseColors: List<CourseColor> = DefaultCourseColors,
    val courseColorIndexMap: Map<String, Int> = emptyMap()
)

// 周课表业务视图模型
class WeeklyScheduleViewModel(
    private val strings: PlatformStringProvider,
    private val releaseInstaller: PlatformReleaseInstaller,
    private val scheduleRepository: ScheduleRepository,
    private val settingsRepository: SettingsRepository,
    private val examRepository: ExamRepository,
    private val cqustSyncManager: CqustSyncManager
) : ViewModel() {

    private val _isSyncing = MutableStateFlow(false)
    private val _syncMessage = MutableStateFlow<String?>(null)

    private val _updateRelease = MutableStateFlow<AppRelease?>(null)
    val updateRelease: StateFlow<AppRelease?> = _updateRelease.asStateFlow()

    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    init {
        // 启动时确保默认课表存在、补全学期开学日期、后台静默同步课表与冷却静默检查更新
        viewModelScope.launch(ioDispatcher) {
            scheduleRepository.getOrCreateDefaultTableId()
            val currentSemester = scheduleRepository.getCurrentSemesterOnce()
            if (currentSemester != null && currentSemester.startDate.isBlank()) {
                scheduleRepository.updateSemesterStartDate(currentSemester.id, currentSemester.effectiveStartDate.toString())
            }
            cqustSyncManager.silentSyncIfNeeded(cooldownHours = 12L)

            // 间隔 24 小时自动静默检查新版本，发现新版本时在主课表首页主动呼起弹窗
            val newRelease = UpdateCheckService.silentCheckUpdateIfNeeded(
                settingsRepository = settingsRepository,
                localVersionName = PlatformConfig.appVersionName,
                cooldownHours = 24L
            )
            if (newRelease != null) {
                _updateRelease.value = newRelease
                _showUpdateDialog.value = true
            }
        }
    }

    private val syncStateFlow = combine(_isSyncing, _syncMessage) { syncing, msg ->
        Pair(syncing, msg)
    }

    // 随当前学期动态切换的调课 Flow
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val adjustmentsFlow = scheduleRepository.currentSemesterFlow.flatMapLatest { semester ->
        scheduleRepository.getAdjustmentsFlow(semesterId = semester?.id.orEmpty())
    }

    // 后台线程聚合全学期课表快照
    private data class ResolvedScheduleBundle(
        val semester: Semester?,
        val totalWeeks: Int,
        val parsedStart: LocalDate,
        val currentWeek: Int,
        val isVacation: Boolean,
        val snapshot: SemesterScheduleSnapshot
    )

    private val resolvedScheduleFlow: Flow<ResolvedScheduleBundle> = combine(
        scheduleRepository.currentSemesterFlow,
        scheduleRepository.allCoursesFlow,
        adjustmentsFlow,
        examRepository.currentSemesterExamsFlow
    ) { currentSemester, allCourses, adjustments, exams ->
        val today = todayLocalDate()
        val totalWeeks = currentSemester?.totalWeeks ?: 20
        val parsedStart = currentSemester.effectiveStartDate
        val currWeek = SemesterDateHelper.calculateWeekNumber(
            startDate = parsedStart,
            targetDate = today,
            totalWeeks = totalWeeks
        )
        val thisMonday = today.previousOrSameMonday()
        val daysDiff = parsedStart.daysBetween(thisMonday)
        val vacation = daysDiff < 0 || (daysDiff / 7) >= totalWeeks

        val snapshot = EffectiveScheduleResolver.buildSemesterScheduleSnapshot(
            allCourses = allCourses,
            adjustments = adjustments,
            exams = exams,
            semesterStart = parsedStart,
            totalWeeks = totalWeeks
        )
        ResolvedScheduleBundle(
            semester = currentSemester,
            totalWeeks = totalWeeks,
            parsedStart = parsedStart,
            currentWeek = currWeek,
            isVacation = vacation,
            snapshot = snapshot
        )
    }.flowOn(Dispatchers.Default)

    val uiState: StateFlow<WeeklyScheduleUiState> = combine(
        resolvedScheduleFlow,
        settingsRepository.appSettingsFlow,
        syncStateFlow
    ) { bundle, settings, (syncing, syncMsg) ->
        val palette = parseCoursePaletteJson(settings.customCourseColorsJson)

        WeeklyScheduleUiState(
            isReady = true,
            isLoggedIn = settings.isLoggedIn && settings.studentId.isNotBlank(),
            semesterStartDate = bundle.parsedStart,
            totalWeeks = bundle.totalWeeks,
            currentWeekNumber = bundle.currentWeek,
            isSemesterSet = true,
            isVacation = bundle.isVacation,
            timeSlots = DefaultTimeSlots,
            currentSemesterId = bundle.semester?.id.orEmpty(),
            weekCellsMap = bundle.snapshot.weekCellsMap,
            weekExamBlocksMap = bundle.snapshot.weekExamBlocksMap,
            weekUnarrangedMap = bundle.snapshot.weekUnarrangedMap,
            weekCourseCountMap = bundle.snapshot.weekCourseCountMap,
            isSyncing = syncing,
            syncMessage = syncMsg,
            hasCustomWallpaper = settings.hasCustomWallpaper,
            wallpaperMaskDim = settings.wallpaperMaskDim,
            courseCardAlpha = settings.courseCardAlpha,
            courseColors = palette,
            courseColorIndexMap = bundle.snapshot.courseColorIndexMap
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = run {
            val initialSettings = settingsRepository.currentSettings
            WeeklyScheduleUiState(
                hasCustomWallpaper = initialSettings.hasCustomWallpaper,
                wallpaperMaskDim = initialSettings.wallpaperMaskDim,
                courseCardAlpha = initialSettings.courseCardAlpha,
                courseColors = parseCoursePaletteJson(initialSettings.customCourseColorsJson)
            )
        }
    )

    val wallpaperBitmapFlow: StateFlow<androidx.compose.ui.graphics.ImageBitmap?> = settingsRepository.wallpaperBitmapFlow

    // 同步课表或触发登录回调
    fun onImportOrSyncClicked(onNeedLogin: () -> Unit) {
        viewModelScope.launch {
            val settings = settingsRepository.getAppSettingsOnce()
            if (!settings.isLoggedIn || settings.studentId.isBlank() || settings.passwordEncrypted.isBlank()) {
                onNeedLogin()
                return@launch
            }

            _isSyncing.value = true
            val rawPassword = LocalCrypto.decrypt(settings.passwordEncrypted)
            val currentSemester = scheduleRepository.getCurrentSemesterOnce()
            val result = cqustSyncManager.syncCourses(
                studentId = settings.studentId,
                passwordRaw = rawPassword,
                targetSemesterId = currentSemester?.id
            )
            _isSyncing.value = false
            if (result.isSuccess) {
                val summary = result.getOrNull()
                _syncMessage.value = if (summary != null) {
                    strings.syncSuccess(summary.regularCount, summary.practicalCount)
                } else {
                    strings.calendarSyncSuccess()
                }
            } else {
                _syncMessage.value = result.exceptionOrNull()?.message ?: strings.syncFailedDefault()
            }
        }
    }

    // 清除同步提示信息
    fun clearSyncMessage() {
        _syncMessage.value = null
    }

    // 更新当前学期开学日期
    fun setSemesterStartDate(date: LocalDate) {
        viewModelScope.launch {
            val current = scheduleRepository.getCurrentSemesterOnce()
            if (current != null) {
                scheduleRepository.updateSemesterStartDate(current.id, date.toString())
            }
        }
    }

    // 更新课程配色槽位
    fun updateCourseColor(courseId: String, newColorIndex: Int) {
        viewModelScope.launch(ioDispatcher) {
            val tableId = scheduleRepository.getOrCreateDefaultTableId()
            scheduleRepository.updateCourseColor(tableId, courseId, newColorIndex)
        }
    }

    // 全部调课记录流（随当前学期自动切换）
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val adjustmentsListFlow: StateFlow<List<CourseAdjustmentWithMeta>> =
        scheduleRepository.currentSemesterFlow.flatMapLatest { semester ->
            scheduleRepository.getAllAdjustmentsWithMetaFlow(semesterId = semester?.id.orEmpty())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 一键清除当前学期全部调课记录
    fun clearAllAdjustments() {
        viewModelScope.launch(ioDispatcher) {
            val tableId = scheduleRepository.getOrCreateDefaultTableId()
            val semId = scheduleRepository.getCurrentSemesterOnce()?.id.orEmpty()
            if (semId.isNotBlank()) {
                scheduleRepository.clearAdjustmentsBySemester(tableId, semId)
            }
        }
    }

    // 移动课程（目标坐标不能有课）
    fun moveCourse(adjustment: CourseAdjustment) {
        viewModelScope.launch(ioDispatcher) {
            scheduleRepository.moveCourse(adjustment)
        }
    }

    // 调换两门课程（目标坐标必须有课，两课互换时段）
    fun swapCourses(adjA: CourseAdjustment, adjB: CourseAdjustment) {
        viewModelScope.launch(ioDispatcher) {
            scheduleRepository.swapCourses(adjA, adjB)
        }
    }

    // 标记删除课程（本周停课）
    fun markCourseDeleted(adjustment: CourseAdjustment) {
        viewModelScope.launch(ioDispatcher) {
            scheduleRepository.markCourseDeleted(adjustment)
        }
    }

    // 保存调课通用入口
    fun saveAdjustment(adjustment: CourseAdjustment) {
        viewModelScope.launch(ioDispatcher) {
            scheduleRepository.saveAdjustment(adjustment)
        }
    }

    // 删除调课设置（恢复原课）
    fun deleteAdjustment(id: Long) {
        viewModelScope.launch(ioDispatcher) {
            scheduleRepository.deleteAdjustment(id)
        }
    }

    // 查找特定事件的调课记录
    suspend fun getAdjustmentForEvent(
        courseId: String,
        eventType: String,
        week: Int,
        day: Int,
        startSection: Int
    ): CourseAdjustment? {
        val tableId = scheduleRepository.getOrCreateDefaultTableId()
        val semId = scheduleRepository.getCurrentSemesterOnce()?.id.orEmpty()
        return scheduleRepository.getAdjustmentForEvent(tableId, semId, courseId, eventType, week, day, startSection)
    }

    // 检索指定周次、星期与节次上是否存在其他课程（用于校验移动与调换条件）
    fun getCourseAtSlot(
        week: Int,
        day: Int,
        startSection: Int,
        endSection: Int,
        excludeCourseId: String,
        excludeStartSection: Int
    ): CourseEventWithMeta? {
        val cells = uiState.value.weekCellsMap[week] ?: return null
        for (cell in cells) {
            if (cell.day != day) continue
            for (event in cell.courses) {
                val isOverlapped = maxOf(startSection, event.startSection) <= minOf(endSection, event.endSection)
                if (!isOverlapped) continue
                if (event.courseId == excludeCourseId && event.startSection == excludeStartSection) continue
                return event
            }
        }
        return null
    }

    // 检索指定周次与节次上的考试安排
    fun getExamAtSlot(
        week: Int,
        day: Int,
        startSection: Int,
        endSection: Int
    ): ExamRecord? {
        val blocks = uiState.value.weekExamBlocksMap[week] ?: return null
        return blocks.firstOrNull { block ->
            block.day == day &&
                maxOf(startSection, block.startSection) <= minOf(endSection, block.endSection)
        }?.exam
    }

    // 关闭更新弹窗
    fun dismissUpdateDialog() {
        _showUpdateDialog.value = false
    }

    // 执行安装包下载操作
    fun executeDownload(
        onNotify: (String) -> Unit
    ) {
        val release = _updateRelease.value ?: return
        dismissUpdateDialog()
        releaseInstaller.processReleaseDownload(release, onNotify)
    }
}
