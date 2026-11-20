package top.msfxp.schedule.data.repository

import androidx.compose.ui.graphics.ImageBitmap
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.msfxp.schedule.data.model.AppSettings
import top.msfxp.schedule.data.model.AutoControlMode
import top.msfxp.schedule.platform.PlatformWallpaperStorage
import top.msfxp.schedule.util.currentEpochMillis
import top.msfxp.schedule.util.ioDispatcher

// 跨平台用户偏好与应用设置仓库
class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val wallpaperStorage: PlatformWallpaperStorage
) {
    private val repositoryScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    // 应用偏好设置热流
    val appSettingsFlow: StateFlow<AppSettings> = dataStore.data
        .map { prefs -> AppSettings.fromPreferences(prefs) }
        .stateIn(
            scope = repositoryScope,
            started = SharingStarted.Eagerly,
            initialValue = AppSettings()
        )

    val currentSettings: AppSettings
        get() = appSettingsFlow.value

    private val _wallpaperBitmapFlow = MutableStateFlow<ImageBitmap?>(null)
    val wallpaperBitmapFlow: StateFlow<ImageBitmap?> = _wallpaperBitmapFlow.asStateFlow()

    init {
        repositoryScope.launch {
            loadWallpaperToCache()
        }
    }

    // 检查是否存在已保存壁纸文件
    fun hasWallpaperFile(): Boolean = wallpaperStorage.hasWallpaperFile()

    // 后台加载壁纸至内存缓存
    suspend fun loadWallpaperToCache() = withContext(ioDispatcher) {
        _wallpaperBitmapFlow.value = wallpaperStorage.loadWallpaperImage()
    }

    // 保存已编码壁纸字节并刷新缓存
    suspend fun saveWallpaperBytes(bytes: ByteArray): Boolean = withContext(ioDispatcher) {
        val decoded = wallpaperStorage.saveWallpaperBytes(bytes) ?: return@withContext false
        _wallpaperBitmapFlow.value = decoded
        updateSettings { it.copy(hasCustomWallpaper = true) }
        true
    }

    // 清除自定义课表壁纸
    suspend fun clearWallpaper(): Boolean = withContext(ioDispatcher) {
        val ok = wallpaperStorage.clearWallpaperFile()
        if (ok) {
            _wallpaperBitmapFlow.value = null
            updateSettings { it.copy(hasCustomWallpaper = false) }
        }
        ok
    }

    suspend fun getAppSettingsOnce(): AppSettings = appSettingsFlow.first()

    // 更新全局设置项
    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val current = AppSettings.fromPreferences(prefs)
            val updated = transform(current)
            prefs[AppSettings.KEY_CURRENT_TABLE_ID] = updated.currentTableId
            prefs[AppSettings.KEY_STUDENT_ID] = updated.studentId
            prefs[AppSettings.KEY_STUDENT_NAME] = updated.studentName
            prefs[AppSettings.KEY_COLLEGE] = updated.college
            prefs[AppSettings.KEY_MAJOR] = updated.major
            prefs[AppSettings.KEY_CLASS_NAME] = updated.className
            prefs[AppSettings.KEY_PASSWORD] = updated.passwordEncrypted
            prefs[AppSettings.KEY_IS_LOGGED_IN] = updated.isLoggedIn
            prefs[AppSettings.KEY_LAST_SYNC_TIME] = updated.lastSyncTime
            prefs[AppSettings.KEY_REMINDER_ENABLED] = updated.reminderEnabled
            prefs[AppSettings.KEY_REMIND_BEFORE_MINUTES] = updated.remindBeforeMinutes
            prefs[AppSettings.KEY_AUTO_DND_ENABLED] = updated.autoDndEnabled
            prefs[AppSettings.KEY_AUTO_CONTROL_MODE] = updated.autoControlMode.value
            prefs[AppSettings.KEY_AUTO_SYNC_TO_CALENDAR] = updated.autoSyncToCalendar
            prefs[AppSettings.KEY_CALENDAR_REMIND_BEFORE_MINUTES] = updated.calendarRemindBeforeMinutes
            prefs[AppSettings.KEY_HAS_CUSTOM_WALLPAPER] = updated.hasCustomWallpaper
            prefs[AppSettings.KEY_WALLPAPER_MASK_DIM] = updated.wallpaperMaskDim
            prefs[AppSettings.KEY_COURSE_CARD_ALPHA] = updated.courseCardAlpha
            prefs[AppSettings.KEY_CUSTOM_COURSE_COLORS_JSON] = updated.customCourseColorsJson
            prefs[AppSettings.KEY_SHOW_TEST_FEATURES] = updated.showTestFeatures
            prefs[AppSettings.KEY_LATEST_VERSION_TAG] = updated.latestVersionTag
            prefs[AppSettings.KEY_LATEST_VERSION_DOWNLOAD_URL] = updated.latestVersionDownloadUrl
            prefs[AppSettings.KEY_LATEST_VERSION_BODY] = updated.latestVersionBody
            prefs[AppSettings.KEY_LAST_UPDATE_CHECK_TIME] = updated.lastUpdateCheckTime
        }
    }

    // 保存登录学号、加密密码与个人信息
    suspend fun saveFullProfileAndCredentials(
        studentId: String,
        studentName: String = "",
        college: String = "",
        major: String = "",
        className: String = "",
        passwordEncrypted: String
    ) {
        updateSettings {
            it.copy(
                studentId = studentId,
                passwordEncrypted = passwordEncrypted,
                studentName = studentName.ifBlank { it.studentName },
                college = college.ifBlank { it.college },
                major = major.ifBlank { it.major },
                className = className.ifBlank { it.className },
                isLoggedIn = true,
                lastSyncTime = currentEpochMillis()
            )
        }
    }

    // 保存登录学号、加密密码与个人信息（别名）
    suspend fun saveCredentials(
        studentId: String,
        passwordEncrypted: String,
        studentName: String = "",
        college: String = "",
        major: String = "",
        className: String = ""
    ) = saveFullProfileAndCredentials(
        studentId = studentId,
        studentName = studentName,
        college = college,
        major = major,
        className = className,
        passwordEncrypted = passwordEncrypted
    )

    // 更新学生学籍档案
    suspend fun updateStudentProfile(name: String, college: String, major: String, className: String) {
        updateSettings {
            it.copy(
                studentName = name.ifBlank { it.studentName },
                college = college.ifBlank { it.college },
                major = major.ifBlank { it.major },
                className = className.ifBlank { it.className },
                lastSyncTime = currentEpochMillis()
            )
        }
    }

    suspend fun updateLastSyncTime(time: Long = currentEpochMillis()) {
        updateSettings { it.copy(lastSyncTime = time) }
    }

    // 清除登录状态与个人档案
    suspend fun clearLogin() {
        updateSettings {
            it.copy(
                studentName = "",
                college = "",
                major = "",
                className = "",
                passwordEncrypted = "",
                isLoggedIn = false
            )
        }
    }

    // 退出登录（别名）
    suspend fun logout() = clearLogin()

    suspend fun updateReminderEnabled(enabled: Boolean) {
        updateSettings { it.copy(reminderEnabled = enabled) }
    }

    suspend fun updateRemindBeforeMinutes(minutes: Int) {
        updateSettings { it.copy(remindBeforeMinutes = minutes) }
    }

    suspend fun updateCalendarRemindBeforeMinutes(minutes: Int) {
        updateSettings { it.copy(calendarRemindBeforeMinutes = minutes) }
    }

    suspend fun updateAutoDndEnabled(enabled: Boolean) {
        updateSettings { it.copy(autoDndEnabled = enabled) }
    }

    suspend fun updateAutoControlMode(mode: AutoControlMode) {
        updateSettings { it.copy(autoControlMode = mode) }
    }

    suspend fun updateAutoSyncToCalendar(enabled: Boolean) {
        updateSettings { it.copy(autoSyncToCalendar = enabled) }
    }

    suspend fun updateShowTestFeatures(show: Boolean) {
        updateSettings { it.copy(showTestFeatures = show) }
    }

    suspend fun updateWallpaperMaskDim(dim: Float) {
        updateSettings { it.copy(wallpaperMaskDim = dim.coerceIn(0f, 0.85f)) }
    }

    suspend fun updateCourseCardAlpha(alpha: Float) {
        updateSettings { it.copy(courseCardAlpha = alpha.coerceIn(0.3f, 1f)) }
    }

    suspend fun updateCustomCourseColors(json: String) {
        updateSettings { it.copy(customCourseColorsJson = json) }
    }

    suspend fun resetCourseColors() {
        updateSettings { it.copy(customCourseColorsJson = "") }
    }

    suspend fun saveLatestReleaseInfo(tag: String, downloadUrl: String, body: String) {
        updateSettings {
            it.copy(
                latestVersionTag = tag,
                latestVersionDownloadUrl = downloadUrl,
                latestVersionBody = body
            )
        }
    }

    suspend fun clearLatestReleaseInfo() {
        updateSettings {
            it.copy(
                latestVersionTag = "",
                latestVersionDownloadUrl = "",
                latestVersionBody = ""
            )
        }
    }

    suspend fun updateLastUpdateCheckTime(time: Long) {
        updateSettings { it.copy(lastUpdateCheckTime = time) }
    }
}
