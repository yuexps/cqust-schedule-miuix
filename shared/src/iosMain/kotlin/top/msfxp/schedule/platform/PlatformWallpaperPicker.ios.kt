package top.msfxp.schedule.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.msfxp.schedule.ui.cropIosImageToJpegBytes
import top.msfxp.schedule.ui.decodeIosImageBitmap
import top.msfxp.schedule.ui.launchIosImagePicker
import top.msfxp.schedule.ui.settings.subscreens.WallpaperCropRegion

// iOS 端相册选图与区域裁切实现
@Composable
actual fun rememberPlatformWallpaperPicker(
    onDecodingStateChange: (Boolean) -> Unit,
    onImageReady: (PlatformCropSource) -> Unit,
    onError: () -> Unit
): PlatformWallpaperPicker {
    val scope = rememberCoroutineScope()
    val currentOnDecoding = rememberUpdatedState(onDecodingStateChange)
    val currentOnReady = rememberUpdatedState(onImageReady)
    val currentOnError = rememberUpdatedState(onError)

    return remember(scope) {
        object : PlatformWallpaperPicker {
            override fun launchPickImage() {
                launchIosImagePicker { bytes ->
                    if (bytes == null) return@launchIosImagePicker
                    scope.launch {
                        currentOnDecoding.value(true)
                        val preview = withContext(Dispatchers.Default) { decodeIosImageBitmap(bytes) }
                        currentOnDecoding.value(false)
                        if (preview != null) {
                            currentOnReady.value(
                                PlatformCropSource(
                                    preview = preview,
                                    nativePayload = bytes
                                )
                            )
                        } else {
                            currentOnError.value()
                        }
                    }
                }
            }

            override suspend fun cropToJpegBytes(
                source: PlatformCropSource,
                region: WallpaperCropRegion
            ): ByteArray? = withContext(Dispatchers.Default) {
                val bytes = source.nativePayload as? ByteArray ?: return@withContext null
                cropIosImageToJpegBytes(bytes, region)
            }
        }
    }
}

// iOS 裁剪弹窗默认铺满全屏宽度
actual fun wallpaperCropDialogProperties(): DialogProperties = DialogProperties(
    usePlatformDefaultWidth = false
)
