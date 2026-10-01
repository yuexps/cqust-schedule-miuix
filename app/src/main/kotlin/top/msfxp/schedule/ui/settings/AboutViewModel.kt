package top.msfxp.schedule.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.msfxp.schedule.BuildConfig
import top.msfxp.schedule.data.api.GithubRelease
import top.msfxp.schedule.data.api.UpdateCheckService
import top.msfxp.schedule.data.repository.SettingsRepository

// 更新状态枚举
enum class UpdateCheckState {
    IDLE,
    CHECKING,
    LATEST,
    HAS_NEW_VERSION,
    FAILED
}

// 关于界面 UI 状态
data class AboutUiState(
    val localVersionName: String = "",
    val checkState: UpdateCheckState = UpdateCheckState.IDLE,
    val latestRelease: GithubRelease? = null,
    val showUpdateDialog: Boolean = false,
    val directApkDownloadUrl: String? = null,
    val showTestFeatures: Boolean = false,
    val errorMessage: String? = null
)

// 关于界面视图模型
class AboutViewModel(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AboutUiState(
            localVersionName = BuildConfig.VERSION_NAME
        )
    )
    val uiState: StateFlow<AboutUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.appSettingsFlow.collect { settings ->
                _uiState.update { it.copy(showTestFeatures = settings.showTestFeatures) }
            }
        }
    }

    // 切换测试功能开关
    fun updateShowTestFeatures(show: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateShowTestFeatures(show)
        }
    }

    // 检查 GitHub 仓库最新版本
    fun checkForUpdates(manual: Boolean = true) {
        if (_uiState.value.checkState == UpdateCheckState.CHECKING) return

        _uiState.update { it.copy(checkState = UpdateCheckState.CHECKING) }

        viewModelScope.launch {
            val result = UpdateCheckService.fetchLatestRelease()
            result.onSuccess { release ->
                if (UpdateCheckService.isNewerVersion(release.tagName, _uiState.value.localVersionName)) {
                    val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                    val directUrl = apkAsset?.browserDownloadUrl ?: release.htmlUrl

                    _uiState.update {
                        it.copy(
                            checkState = UpdateCheckState.HAS_NEW_VERSION,
                            latestRelease = release,
                            directApkDownloadUrl = directUrl,
                            showUpdateDialog = manual,
                            errorMessage = null
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            checkState = UpdateCheckState.LATEST,
                            latestRelease = release,
                            showUpdateDialog = false,
                            errorMessage = null
                        )
                    }
                }
            }.onFailure { throwable ->
                val reason = throwable.localizedMessage ?: throwable.message ?: "Unknown error"
                _uiState.update {
                    it.copy(
                        checkState = UpdateCheckState.FAILED,
                        showUpdateDialog = false,
                        errorMessage = reason
                    )
                }
            }
        }
    }

    // 关闭更新弹窗
    fun dismissUpdateDialog() {
        _uiState.update { it.copy(showUpdateDialog = false) }
    }
}
