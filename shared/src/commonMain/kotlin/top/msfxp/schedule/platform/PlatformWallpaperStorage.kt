package top.msfxp.schedule.platform

import androidx.compose.ui.graphics.ImageBitmap

// 跨平台壁纸文件存储与解码接口
interface PlatformWallpaperStorage {
    fun hasWallpaperFile(): Boolean
    suspend fun loadWallpaperImage(): ImageBitmap?
    suspend fun saveWallpaperBytes(bytes: ByteArray): ImageBitmap?
    suspend fun clearWallpaperFile(): Boolean
}
