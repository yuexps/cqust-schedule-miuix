package top.msfxp.schedule.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import top.msfxp.schedule.R
import top.msfxp.schedule.data.api.AppRelease
import top.msfxp.schedule.data.model.AutoControlMode
import top.msfxp.schedule.service.AudioModeControlService
import top.msfxp.schedule.service.CourseAlarmReceiver
import top.msfxp.schedule.util.AppActionHelper
import top.msfxp.schedule.util.ImageBitmapHelper
import java.io.ByteArrayOutputStream
import java.io.File

// 创建 Android 端 DataStore（复用原 cqust_schedule_prefs 路径，保持存量数据零迁移）
fun createAndroidPreferencesDataStore(context: Context): DataStore<Preferences> {
    return PreferenceDataStoreFactory.createWithPath(
        produceFile = {
            File(context.filesDir, "datastore/cqust_schedule_prefs.preferences_pb").absolutePath.toPath()
        }
    )
}

// Android 端壁纸存储与图片解码实现
class AndroidPlatformWallpaperStorage(private val context: Context) : PlatformWallpaperStorage {
    fun getWallpaperFile(): File {
        val dir = File(context.filesDir, "wallpaper")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "schedule_wallpaper.jpg")
    }

    override fun hasWallpaperFile(): Boolean {
        val file = getWallpaperFile()
        return file.exists() && file.length() > 0
    }

    override suspend fun loadWallpaperImage(): ImageBitmap? = withContext(Dispatchers.IO) {
        val file = getWallpaperFile()
        if (!file.exists() || file.length() <= 0) return@withContext null
        runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
    }

    override suspend fun saveWallpaperBytes(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val targetFile = getWallpaperFile()
            targetFile.writeBytes(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }

    override suspend fun clearWallpaperFile(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val file = getWallpaperFile()
            if (file.exists()) file.delete()
            true
        }.getOrDefault(false)
    }

    // Android 端：从相册 Uri 解码并纠正 EXIF 旋转（供裁剪弹窗使用）
    suspend fun decodeBitmapForCropping(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        ImageBitmapHelper.decodeAndCorrectOrientation(context, uri, maxDimension = 3840)
    }

    // Android 端：将 Bitmap 压缩为 JPEG 字节数组
    suspend fun encodeBitmapToJpegBytes(bitmap: Bitmap, quality: Int = 92): ByteArray = withContext(Dispatchers.IO) {
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
    }

    // Android 端：直接从 Uri 解码并编码为 JPEG 字节数组
    suspend fun decodeUriToJpegBytes(uri: Uri, quality: Int = 88): ByteArray? = withContext(Dispatchers.IO) {
        val bitmap = ImageBitmapHelper.decodeAndCorrectOrientation(context, uri, maxDimension = 2560)
            ?: return@withContext null
        encodeBitmapToJpegBytes(bitmap, quality)
    }
}

// Android 端文案提供器实现
class AndroidPlatformStringProvider(private val context: Context) : PlatformStringProvider {
    override fun syncSuccess(regularCount: Int, practicalCount: Int): String =
        context.getString(R.string.sync_success_format, regularCount, practicalCount)

    override fun calendarSyncSuccess(): String =
        context.getString(R.string.calendar_sync_success_toast)

    override fun syncFailedDefault(): String =
        context.getString(R.string.sync_failed_default)

    override fun loginEmptyStudentId(): String =
        context.getString(R.string.login_error_empty_student_id)

    override fun loginEmptyPassword(): String =
        context.getString(R.string.login_error_empty_password)

    override fun loginConnectingHint(): String =
        context.getString(R.string.login_connecting_hint)

    override fun loginFailedGeneral(): String =
        context.getString(R.string.login_failed_general)

    override fun aboutDownloadStarted(): String =
        context.getString(R.string.about_download_started_toast)

    override fun testCourseDefaultName(): String =
        context.getString(R.string.test_course_default_name)

    override fun testCourseDefaultLocation(): String =
        context.getString(R.string.test_course_default_location)

    override fun testCourseDefaultTeacher(): String =
        context.getString(R.string.test_course_default_teacher)
}

// Android 端自动化与通知测试实现
class AndroidPlatformAutomationTester(private val context: Context) : PlatformAutomationTester {
    override fun sendTestNotification(courseName: String, position: String, teacher: String) {
        val testTitle = context.getString(R.string.test_notification_title, courseName)
        CourseAlarmReceiver.showNotification(
            context = context,
            notificationId = TEST_NOTIFICATION_ID_IMMEDIATE,
            name = testTitle,
            position = position,
            teacher = teacher
        )
    }

    override fun scheduleDelayed30sTestNotification(courseName: String, position: String, teacher: String) {
        val testTitle = context.getString(R.string.test_notification_wakeup_title, courseName)
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerMillis = System.currentTimeMillis() + 30_000L

        val reminderIntent = Intent(context, CourseAlarmReceiver::class.java).apply {
            putExtra(CourseAlarmReceiver.EXTRA_COURSE_NAME, testTitle)
            putExtra(CourseAlarmReceiver.EXTRA_COURSE_POSITION, position)
            putExtra(CourseAlarmReceiver.EXTRA_COURSE_TEACHER, teacher)
            putExtra(CourseAlarmReceiver.EXTRA_NOTIFICATION_ID, TEST_NOTIFICATION_ID_DELAYED)
            putExtra(CourseAlarmReceiver.EXTRA_IS_TEST, true)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            TEST_NOTIFICATION_ID_DELAYED,
            reminderIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        scheduleCompatAlarm(alarmManager, triggerMillis, pi)
    }

    override suspend fun testToggleModeImmediately(mode: AutoControlMode) {
        try {
            AudioModeControlService.start(context, true, mode)
            delay(10_000L)
            AudioModeControlService.start(context, false, mode)
        } catch (e: Exception) {
            Log.e("AutomationTester", "testToggleModeImmediately failed", e)
        }
    }

    override fun scheduleDelayed30sDndTest() {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val startMillis = System.currentTimeMillis() + 30_000L
        val endMillis = startMillis + 30_000L

        val startIntent = Intent(context, CourseAlarmReceiver::class.java).apply {
            putExtra(CourseAlarmReceiver.EXTRA_DND_ACTION, CourseAlarmReceiver.DND_ACTION_START)
            putExtra(CourseAlarmReceiver.EXTRA_IS_TEST, true)
        }
        val startPI = PendingIntent.getBroadcast(
            context,
            TEST_DND_PI_START,
            startIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val endIntent = Intent(context, CourseAlarmReceiver::class.java).apply {
            putExtra(CourseAlarmReceiver.EXTRA_DND_ACTION, CourseAlarmReceiver.DND_ACTION_END)
            putExtra(CourseAlarmReceiver.EXTRA_IS_TEST, true)
        }
        val endPI = PendingIntent.getBroadcast(
            context,
            TEST_DND_PI_END,
            endIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        scheduleCompatAlarm(alarmManager, startMillis, startPI)
        scheduleCompatAlarm(alarmManager, endMillis, endPI)
    }

    private fun scheduleCompatAlarm(alarmManager: AlarmManager, triggerMillis: Long, pi: PendingIntent) {
        if (alarmManager.canScheduleExactAlarms()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pi)
            } catch (_: SecurityException) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pi)
            }
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pi)
        }
    }

    companion object {
        private const val TEST_NOTIFICATION_ID_IMMEDIATE = 99999
        private const val TEST_NOTIFICATION_ID_DELAYED = 99998
        private const val TEST_DND_PI_START = 99996
        private const val TEST_DND_PI_END = 99997
    }
}

// Android 端版本更新下载与安装实现
class AndroidPlatformReleaseInstaller(private val context: Context) : PlatformReleaseInstaller {
    override fun processReleaseDownload(release: AppRelease, onNotify: (String) -> Unit): Boolean =
        AppActionHelper.processReleaseDownload(context, release, onNotify)
}

// Android 端外部链接、剪贴板、Toast 与地图导航实现
class AndroidPlatformExternalHandler(private val context: Context) : PlatformExternalHandler {
    override fun openBrowser(url: String): Boolean = AppActionHelper.openBrowser(context, url)

    override fun copyToClipboard(text: String, label: String) =
        AppActionHelper.copyToClipboard(context, text, label)

    override fun joinQQGroup(key: String): Boolean = AppActionHelper.joinQQGroup(context, key)

    override fun showToast(message: String) {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun getAvailableMapApps(): List<PlatformMapAppItem> {
        val mapping = listOf(
            MapAppType.AMAP to "com.autonavi.minimap",
            MapAppType.BAIDU to "com.baidu.BaiduMap",
            MapAppType.TENCENT to "com.tencent.map"
        )
        return mapping.mapNotNull { (type, pkg) ->
            val installed = try {
                context.packageManager.getPackageInfo(pkg, 0)
                true
            } catch (_: Exception) {
                false
            }
            if (!installed) return@mapNotNull null
            val icon = runCatching {
                val drawable = context.packageManager.getApplicationIcon(pkg)
                drawableToImageBitmap(drawable)
            }.getOrNull()
            PlatformMapAppItem(type = type, icon = icon)
        }
    }

    override fun openMapWalkingNavigation(type: MapAppType, destination: String): Boolean {
        val legacyType = when (type) {
            MapAppType.AMAP -> top.msfxp.schedule.util.MapAppType.AMAP
            MapAppType.BAIDU -> top.msfxp.schedule.util.MapAppType.BAIDU
            MapAppType.TENCENT -> top.msfxp.schedule.util.MapAppType.TENCENT
        }
        top.msfxp.schedule.util.MapNavigationHelper.openMapWalkingNavigation(context, legacyType, destination)
        return true
    }

    private fun drawableToImageBitmap(drawable: android.graphics.drawable.Drawable): ImageBitmap {
        if (drawable is android.graphics.drawable.BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap.asImageBitmap()
        }
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 72
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 72
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap.asImageBitmap()
    }
}
