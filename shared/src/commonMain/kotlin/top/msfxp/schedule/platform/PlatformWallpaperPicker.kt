package top.msfxp.schedule.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.window.DialogProperties
import top.msfxp.schedule.ui.settings.subscreens.WallpaperCropRegion

// 待裁剪的源图片句柄（封装 Compose 预览位图与平台原生载荷）
class PlatformCropSource(
    val preview: ImageBitmap,
    val nativePayload: Any
)

// 跨平台壁纸相册选图与区域裁切控制器
interface PlatformWallpaperPicker {
    fun launchPickImage()
    suspend fun cropToJpegBytes(source: PlatformCropSource, region: WallpaperCropRegion): ByteArray?
}

// 提供平台相册选图与裁切能力（Android 使用 PickVisualMedia，iOS 使用 UIImagePickerController）
@Composable
expect fun rememberPlatformWallpaperPicker(
    onDecodingStateChange: (Boolean) -> Unit,
    onImageReady: (PlatformCropSource) -> Unit,
    onError: () -> Unit
): PlatformWallpaperPicker

// 返回平台适配的壁纸裁剪弹窗属性
expect fun wallpaperCropDialogProperties(): DialogProperties
