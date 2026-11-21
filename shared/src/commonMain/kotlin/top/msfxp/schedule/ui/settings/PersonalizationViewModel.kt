package top.msfxp.schedule.ui.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import top.msfxp.schedule.data.model.CourseColor
import top.msfxp.schedule.data.model.DefaultCourseColors
import top.msfxp.schedule.data.model.parseCoursePaletteJson
import top.msfxp.schedule.data.model.serializeCoursePalette
import top.msfxp.schedule.data.repository.SettingsRepository
import top.msfxp.schedule.platform.PlatformWallpaperStorage
import top.msfxp.schedule.shared.resources.Res
import top.msfxp.schedule.shared.resources.personalization_wallpaper_clear_success
import top.msfxp.schedule.shared.resources.personalization_wallpaper_set_fail
import top.msfxp.schedule.shared.resources.personalization_wallpaper_set_success
import top.msfxp.schedule.util.ioDispatcher

// 个性化反馈提示枚举
enum class PersonalizationMessage(val res: StringResource) {
    WallpaperSetSuccess(Res.string.personalization_wallpaper_set_success),
    WallpaperSetFail(Res.string.personalization_wallpaper_set_fail),
    WallpaperClearSuccess(Res.string.personalization_wallpaper_clear_success)
}

// 个性化设置界面状态
data class PersonalizationUiState(
    val hasCustomWallpaper: Boolean = false,
    val wallpaperMaskDim: Float = 0.15f,
    val courseCardAlpha: Float = 0.95f,
    val courseColors: List<CourseColor> = DefaultCourseColors,
    val message: PersonalizationMessage? = null
)

// 双端共享个性化视图模型
class PersonalizationViewModel(
    private val settingsRepository: SettingsRepository,
    private val wallpaperStorage: PlatformWallpaperStorage
) : ViewModel() {

    private val _message = MutableStateFlow<PersonalizationMessage?>(null)

    val wallpaperBitmapFlow: StateFlow<ImageBitmap?> = settingsRepository.wallpaperBitmapFlow

    val uiState: StateFlow<PersonalizationUiState> = combine(
        settingsRepository.appSettingsFlow,
        _message
    ) { settings, msg ->
        val fileExists = settings.hasCustomWallpaper && wallpaperStorage.hasWallpaperFile()
        PersonalizationUiState(
            hasCustomWallpaper = fileExists,
            wallpaperMaskDim = settings.wallpaperMaskDim,
            courseCardAlpha = settings.courseCardAlpha,
            courseColors = parseCoursePaletteJson(settings.customCourseColorsJson),
            message = msg
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = run {
            val initialSettings = settingsRepository.currentSettings
            val fileExists = initialSettings.hasCustomWallpaper && wallpaperStorage.hasWallpaperFile()
            PersonalizationUiState(
                hasCustomWallpaper = fileExists,
                wallpaperMaskDim = initialSettings.wallpaperMaskDim,
                courseCardAlpha = initialSettings.courseCardAlpha,
                courseColors = parseCoursePaletteJson(initialSettings.customCourseColorsJson)
            )
        }
    )

    // 保存裁剪后的壁纸字节
    fun saveCroppedWallpaper(bytes: ByteArray?) {
        viewModelScope.launch(ioDispatcher) {
            val success = bytes != null && bytes.isNotEmpty() && settingsRepository.saveWallpaperBytes(bytes)
            _message.value = if (success) {
                PersonalizationMessage.WallpaperSetSuccess
            } else {
                PersonalizationMessage.WallpaperSetFail
            }
        }
    }

    // 报告选图或解码失败
    fun notifyWallpaperSetFailed() {
        _message.value = PersonalizationMessage.WallpaperSetFail
    }

    // 清除课表壁纸
    fun clearWallpaper() {
        viewModelScope.launch(ioDispatcher) {
            if (settingsRepository.clearWallpaper()) {
                _message.value = PersonalizationMessage.WallpaperClearSuccess
            }
        }
    }

    private var saveDimJob: Job? = null
    private var saveAlphaJob: Job? = null

    // 更新壁纸暗度遮罩（防抖写入）
    fun updateWallpaperMaskDim(dim: Float) {
        val clamped = dim.coerceIn(0f, 0.85f)
        saveDimJob?.cancel()
        saveDimJob = viewModelScope.launch(ioDispatcher) {
            delay(200)
            settingsRepository.updateWallpaperMaskDim(clamped)
        }
    }

    // 更新课程卡片不透明度（防抖写入）
    fun updateCourseCardAlpha(alpha: Float) {
        val clamped = alpha.coerceIn(0.4f, 1f)
        saveAlphaJob?.cancel()
        saveAlphaJob = viewModelScope.launch(ioDispatcher) {
            delay(200)
            settingsRepository.updateCourseCardAlpha(clamped)
        }
    }

    // 修改指定槽位配色
    fun updateSlotColor(index: Int, newColor: Color) {
        viewModelScope.launch {
            val currentList = uiState.value.courseColors.toMutableList()
            if (index in currentList.indices) {
                currentList[index] = CourseColor.fromColor(newColor)
                settingsRepository.updateCustomCourseColors(serializeCoursePalette(currentList))
            }
        }
    }

    // 清除反馈提示
    fun clearMessage() {
        _message.value = null
    }
}
