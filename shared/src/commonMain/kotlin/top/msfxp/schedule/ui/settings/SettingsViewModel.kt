package top.msfxp.schedule.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.msfxp.schedule.data.api.CqustSyncManager
import top.msfxp.schedule.data.api.LocalCrypto
import top.msfxp.schedule.data.model.AppSettings
import top.msfxp.schedule.data.model.AutoControlMode
import top.msfxp.schedule.data.model.Semester
import top.msfxp.schedule.data.repository.ScheduleRepository
import top.msfxp.schedule.data.repository.SettingsRepository
import top.msfxp.schedule.platform.PlatformAutomationTester
import top.msfxp.schedule.platform.PlatformCalendarSync
import top.msfxp.schedule.platform.PlatformStringProvider
import top.msfxp.schedule.util.CourseLocationFormatter

// 设置界面状态
data class SettingsUiState(
    val appSettings: AppSettings = AppSettings(),
    val currentSemester: Semester? = null,
    val availableSemesters: List<Semester> = emptyList(),
    val courseCount: Int = 0,
    val isSyncing: Boolean = false,
    val syncMessage: String? = null,
    val switchingSemesterId: String? = null
)

// 偏好设置视图模型
class SettingsViewModel(
    private val strings: PlatformStringProvider,
    private val settingsRepository: SettingsRepository,
    private val scheduleRepository: ScheduleRepository,
    private val cqustSyncManager: CqustSyncManager,
    private val calendarSync: PlatformCalendarSync,
    private val automationTester: PlatformAutomationTester
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            scheduleRepository.allCoursesFlow.collect { list ->
                _uiState.update { it.copy(courseCount = list.size) }
            }
        }

        viewModelScope.launch {
            combine(
                settingsRepository.appSettingsFlow,
                scheduleRepository.allSemestersFlow,
                scheduleRepository.currentSemesterFlow
            ) { settings, semesters, currentSemester ->
                Triple(settings, semesters, currentSemester)
            }.collect { (settings, semesters, currentSemester) ->
                _uiState.update {
                    it.copy(
                        appSettings = settings,
                        availableSemesters = semesters,
                        currentSemester = currentSemester
                    )
                }
            }
        }
    }

    // 重新同步课表数据
    fun resyncCourses(targetSemesterId: String? = null, onNeedLogin: () -> Unit) {
        val settings = _uiState.value.appSettings
        if (!settings.isLoggedIn || settings.studentId.isBlank() || settings.passwordEncrypted.isBlank()) {
            onNeedLogin()
            return
        }

        _uiState.update { it.copy(isSyncing = true, syncMessage = null) }
        viewModelScope.launch {
            val rawPassword = LocalCrypto.decrypt(settings.passwordEncrypted)
            val currentSem = _uiState.value.currentSemester
            val result = cqustSyncManager.syncCourses(
                studentId = settings.studentId,
                passwordRaw = rawPassword,
                targetSemesterId = targetSemesterId ?: currentSem?.id
            )
            _uiState.update {
                it.copy(
                    isSyncing = false,
                    syncMessage = if (result.isSuccess) {
                        val summary = result.getOrNull()
                        if (summary != null) {
                            strings.syncSuccess(summary.regularCount, summary.practicalCount)
                        } else {
                            strings.calendarSyncSuccess()
                        }
                    } else result.exceptionOrNull()?.message ?: strings.syncFailedDefault()
                )
            }
        }
    }

    // 切换当前激活学期（已有本地缓存则离线秒切，无缓存时联网同步）
    fun switchSemester(
        semesterId: String,
        onNeedLogin: () -> Unit,
        onSuccess: (() -> Unit)? = null
    ) {
        viewModelScope.launch {
            val tableId = scheduleRepository.getOrCreateDefaultTableId()
            val hasCache = scheduleRepository.hasCoursesForSemester(tableId, semesterId)
            if (hasCache) {
                scheduleRepository.setActiveSemester(semesterId)
                onSuccess?.invoke()
                return@launch
            }

            val settings = _uiState.value.appSettings
            if (!settings.isLoggedIn || settings.studentId.isBlank() || settings.passwordEncrypted.isBlank()) {
                onNeedLogin()
                return@launch
            }

            _uiState.update {
                it.copy(
                    isSyncing = true,
                    switchingSemesterId = semesterId,
                    syncMessage = null
                )
            }

            try {
                scheduleRepository.setActiveSemester(semesterId)
                val rawPassword = LocalCrypto.decrypt(settings.passwordEncrypted)
                val result = cqustSyncManager.syncCourses(
                    studentId = settings.studentId,
                    passwordRaw = rawPassword,
                    targetSemesterId = semesterId
                )
                val isSuccess = result.isSuccess
                val syncMsg = if (isSuccess) {
                    val summary = result.getOrNull()
                    if (summary != null) {
                        strings.syncSuccess(summary.regularCount, summary.practicalCount)
                    } else {
                        strings.calendarSyncSuccess()
                    }
                } else {
                    result.exceptionOrNull()?.message ?: strings.syncFailedDefault()
                }

                _uiState.update {
                    it.copy(
                        isSyncing = false,
                        switchingSemesterId = null,
                        syncMessage = syncMsg
                    )
                }

                if (isSuccess) {
                    onSuccess?.invoke()
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSyncing = false,
                        switchingSemesterId = null,
                        syncMessage = e.message ?: strings.syncFailedDefault()
                    )
                }
            }
        }
    }

    // 退出登录并清除本地缓存数据
    fun logout(onSuccess: () -> Unit) {
        viewModelScope.launch {
            val tableId = scheduleRepository.getOrCreateDefaultTableId()
            scheduleRepository.clearAllCourses(tableId)
            scheduleRepository.clearSemesters()
            settingsRepository.logout()
            onSuccess()
        }
    }

    // 更新当前学期开学日期
    fun updateSemesterStartDate(dateStr: String) {
        viewModelScope.launch {
            val currentSem = _uiState.value.currentSemester
            if (currentSem != null) {
                scheduleRepository.updateSemesterStartDate(currentSem.id, dateStr)
            }
        }
    }

    // 更新当前学期总周数
    fun updateTotalWeeks(weeks: Int) {
        viewModelScope.launch {
            val currentSem = _uiState.value.currentSemester
            if (currentSem != null) {
                scheduleRepository.updateSemesterTotalWeeks(currentSem.id, weeks)
            }
        }
    }

    // 更新课前提醒开关
    fun updateReminderEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateReminderEnabled(enabled)
        }
    }

    // 更新提前提醒分钟数
    fun updateRemindBeforeMinutes(minutes: Int) {
        viewModelScope.launch {
            settingsRepository.updateRemindBeforeMinutes(minutes)
        }
    }

    // 更新日历事件提前提醒分钟数
    fun updateCalendarRemindBeforeMinutes(minutes: Int) {
        viewModelScope.launch {
            settingsRepository.updateCalendarRemindBeforeMinutes(minutes)
            if (_uiState.value.appSettings.autoSyncToCalendar) {
                calendarSync.syncCurrentScheduleToCalendar()
            }
        }
    }

    // 更新课堂免打扰开关
    fun updateAutoDndEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateAutoDndEnabled(enabled)
        }
    }

    // 更新上课控制模式
    fun updateAutoControlMode(mode: AutoControlMode) {
        viewModelScope.launch {
            settingsRepository.updateAutoControlMode(mode)
        }
    }

    fun ensureCalendarAccess(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            onResult(calendarSync.ensureCalendarAccess())
        }
    }

    // 更新自动同步到系统日历开关
    fun updateAutoSyncToCalendar(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateAutoSyncToCalendar(enabled)
            if (enabled) {
                calendarSync.syncCurrentScheduleToCalendar()
            }
        }
    }

    // 更新测试功能显示与隐藏开关
    fun updateShowTestFeatures(show: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateShowTestFeatures(show)
        }
    }


    // 手动触发全量同步至系统日历
    fun syncToCalendarManually(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = calendarSync.syncCurrentScheduleToCalendar()
            onResult(success)
        }
    }

    // 从系统日历中删除所有课表日程及专属账户
    fun deleteCalendarSchedule(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = calendarSync.deleteScheduleCalendar()
            onResult(success)
        }
    }


    // 发送立即测试提醒通知
    fun sendTestNotification() {
        viewModelScope.launch {
            val (name, position, teacher) = getTestCoursePayload()
            automationTester.sendTestNotification(name, position, teacher)
        }
    }

    // 设置 30 秒后后台定时测试通知闹钟
    fun scheduleDelayed30sTestNotification() {
        viewModelScope.launch {
            val (name, position, teacher) = getTestCoursePayload()
            automationTester.scheduleDelayed30sTestNotification(name, position, teacher)
        }
    }

    // 立即测试切换控制模式并在10秒后自动恢复
    fun testToggleModeImmediately() {
        viewModelScope.launch {
            automationTester.testToggleModeImmediately(_uiState.value.appSettings.autoControlMode)
        }
    }

    // 设置 30 秒后后台定时测试勿扰/静音闹钟
    fun scheduleDelayed30sDndTest() {
        viewModelScope.launch {
            automationTester.scheduleDelayed30sDndTest()
        }
    }

    // 获取测试用课程字段
    private suspend fun getTestCoursePayload(): Triple<String, String, String> {
        val tableId = scheduleRepository.getOrCreateDefaultTableId()
        val semesterId = scheduleRepository.getCurrentSemesterOnce()?.id.orEmpty()
        val courses = if (semesterId.isNotBlank()) {
            scheduleRepository.getAllCoursesWithEventsOnce(tableId, semesterId)
        } else {
            emptyList()
        }
        val first = courses.firstOrNull()
        val firstEvent = first?.events?.firstOrNull()
        val name = first?.course?.name ?: strings.testCourseDefaultName()
        val position = firstEvent?.location?.let { CourseLocationFormatter.formatDisplay(it) }
            ?: strings.testCourseDefaultLocation()
        val teacher = firstEvent?.teacher ?: strings.testCourseDefaultTeacher()
        return Triple(name, position, teacher)
    }
}
