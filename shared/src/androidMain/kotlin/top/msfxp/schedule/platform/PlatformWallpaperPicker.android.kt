package top.msfxp.schedule.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.msfxp.schedule.ui.settings.subscreens.WallpaperCropRegion
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

// Android 端相册选图、EXIF 纠偏与区域裁切实现
@Composable
actual fun rememberPlatformWallpaperPicker(
    onDecodingStateChange: (Boolean) -> Unit,
    onImageReady: (PlatformCropSource) -> Unit,
    onError: () -> Unit
): PlatformWallpaperPicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnDecoding = rememberUpdatedState(onDecodingStateChange)
    val currentOnReady = rememberUpdatedState(onImageReady)
    val currentOnError = rememberUpdatedState(onError)

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            currentOnDecoding.value(true)
            val bitmap = withContext(Dispatchers.IO) {
                decodeAndCorrectAndroidBitmap(context, uri, maxDimension = 3840)
            }
            currentOnDecoding.value(false)
            if (bitmap != null) {
                currentOnReady.value(
                    PlatformCropSource(
                        preview = bitmap.asImageBitmap(),
                        nativePayload = bitmap
                    )
                )
            } else {
                currentOnError.value()
            }
        }
    }

    return remember(launcher) {
        object : PlatformWallpaperPicker {
            override fun launchPickImage() {
                launcher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }

            override suspend fun cropToJpegBytes(
                source: PlatformCropSource,
                region: WallpaperCropRegion
            ): ByteArray? = withContext(Dispatchers.IO) {
                val bitmap = source.nativePayload as? Bitmap ?: return@withContext null
                val cropped = cropAndroidBitmap(bitmap, region) ?: return@withContext null
                runCatching {
                    ByteArrayOutputStream().use { out ->
                        cropped.compress(Bitmap.CompressFormat.JPEG, 92, out)
                        out.toByteArray()
                    }
                }.getOrNull()
            }
        }
    }
}

// Android 裁剪弹窗需延展至系统栏四边，避免取景比例偏差
actual fun wallpaperCropDialogProperties(): DialogProperties = DialogProperties(
    usePlatformDefaultWidth = false,
    decorFitsSystemWindows = false
)

// 从相册 Uri 解码并按 EXIF 校正方向
private fun decodeAndCorrectAndroidBitmap(context: Context, uri: Uri, maxDimension: Int): Bitmap? {
    val tempFile = File(context.cacheDir, "img_decode_${System.currentTimeMillis()}.tmp")
    return try {
        val copySuccess = context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
                true
            }
        } ?: false
        if (!copySuccess || !tempFile.exists() || tempFile.length() == 0L) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(tempFile.absolutePath, bounds)
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension) {
            sampleSize *= 2
        }
        val bitmap = BitmapFactory.decodeFile(
            tempFile.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: return null

        val matrix = Matrix()
        runCatching {
            val exif = ExifInterface(tempFile.absolutePath)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            }
        }
        if (!matrix.isIdentity) {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            rotated
        } else {
            bitmap
        }
    } catch (_: Exception) {
        null
    } finally {
        tempFile.delete()
    }
}

// 按归一化区域裁切 Android 位图
private fun cropAndroidBitmap(source: Bitmap, region: WallpaperCropRegion): Bitmap? = runCatching {
    val x = (region.left * source.width).roundToInt().coerceIn(0, source.width - 1)
    val y = (region.top * source.height).roundToInt().coerceIn(0, source.height - 1)
    val w = (region.width * source.width).roundToInt().coerceIn(1, source.width - x)
    val h = (region.height * source.height).roundToInt().coerceIn(1, source.height - y)
    Bitmap.createBitmap(source, x, y, w, h)
}.getOrNull()
