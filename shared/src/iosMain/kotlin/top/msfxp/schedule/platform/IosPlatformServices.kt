package top.msfxp.schedule.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalTime
import kotlinx.datetime.number
import okio.Path.Companion.toPath
import org.jetbrains.skia.Image
import org.koin.dsl.module
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptionSound
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.Foundation.NSDateComponents
import platform.darwin.NSObject
import platform.posix.memcpy
import top.msfxp.schedule.data.api.AppRelease
import top.msfxp.schedule.data.db.ScheduleDatabase
import top.msfxp.schedule.data.model.AutoControlMode
import top.msfxp.schedule.data.model.SemesterDateHelper
import top.msfxp.schedule.data.model.effectiveStartDate
import top.msfxp.schedule.data.model.findSectionTimeSlot
import top.msfxp.schedule.data.repository.ExamRepository
import top.msfxp.schedule.data.repository.ScheduleRepository
import top.msfxp.schedule.data.repository.SettingsRepository
import top.msfxp.schedule.di.commonModule
import top.msfxp.schedule.domain.EffectiveScheduleResolver
import top.msfxp.schedule.util.atTimeEpochMillis
import top.msfxp.schedule.util.currentEpochMillis
import top.msfxp.schedule.util.iosCoroutineExceptionHandler
import top.msfxp.schedule.util.isoDayOfWeekNumber
import top.msfxp.schedule.util.plusDays
import top.msfxp.schedule.util.todayLocalDate

// 获取 iOS 应用沙盒 Documents 目录路径
@OptIn(ExperimentalForeignApi::class)
fun iosDocumentsDirectoryPath(): String {
    val urls = NSFileManager.defaultManager.URLsForDirectory(
        directory = NSDocumentDirectory,
        inDomains = NSUserDomainMask
    )
    val docUrl = urls.firstOrNull() as? NSURL
    return requireNotNull(docUrl?.path) { "Unable to resolve iOS NSDocumentDirectory" }
}

// 创建 iOS 端 Room 数据库实例
fun createIosScheduleDatabase(): ScheduleDatabase {
    val dbFilePath = "${iosDocumentsDirectoryPath()}/cqust_schedule_database.db"
    return Room.databaseBuilder<ScheduleDatabase>(name = dbFilePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
}

// 创建 iOS 端 DataStore 实例
fun createIosPreferencesDataStore(): DataStore<Preferences> {
    val path = "${iosDocumentsDirectoryPath()}/cqust_schedule_prefs.preferences_pb"
    return PreferenceDataStoreFactory.createWithPath(produceFile = { path.toPath() })
}

// iOS 端壁纸存储与 Skia 图片解码实现
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
object IosPlatformWallpaperStorage : PlatformWallpaperStorage {
    private val wallpaperPath: String
        get() = "${iosDocumentsDirectoryPath()}/schedule_wallpaper.jpg"

    override fun hasWallpaperFile(): Boolean =
        NSFileManager.defaultManager.fileExistsAtPath(wallpaperPath)

    override suspend fun loadWallpaperImage(): ImageBitmap? = withContext(Dispatchers.IO) {
        if (!hasWallpaperFile()) return@withContext null
        val nsData = NSData.dataWithContentsOfFile(wallpaperPath) ?: return@withContext null
        val length = nsData.length.toInt()
        if (length <= 0) return@withContext null
        val bytes = ByteArray(length)
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), nsData.bytes, nsData.length)
        }
        runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
    }

    override suspend fun saveWallpaperBytes(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) return@withContext null
        val decoded = runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
            ?: return@withContext null
        val nsData = bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
        nsData.writeToFile(wallpaperPath, atomically = true)
        decoded
    }

    override suspend fun clearWallpaperFile(): Boolean = withContext(Dispatchers.IO) {
        if (hasWallpaperFile()) {
            NSFileManager.defaultManager.removeItemAtPath(wallpaperPath, error = null)
        }
        true
    }
}

// iOS 端文案提供器实现
object IosPlatformStringProvider : PlatformStringProvider {
    override fun syncSuccess(regularCount: Int, practicalCount: Int): String =
        "同步成功：$regularCount 门理论课，$practicalCount 项实践环节"

    override fun calendarSyncSuccess(): String = "课表已更新"
    override fun syncFailedDefault(): String = "同步失败，请稍后重试"
    override fun loginEmptyStudentId(): String = "请输入学号"
    override fun loginEmptyPassword(): String = "请输入统一身份认证密码"
    override fun loginConnectingHint(): String = "正在连接教务系统..."
    override fun loginFailedGeneral(): String = "登录失败，请检查学号密码或网络连接"
    override fun aboutDownloadStarted(): String = "已打开版本发布页面"
    override fun testCourseDefaultName(): String = "高等数学A(一)"
    override fun testCourseDefaultLocation(): String = "虎溪校区 H1201"
    override fun testCourseDefaultTeacher(): String = "张老师"
}

// iOS 端日历同步占位（后期按需扩展 EventKit）
object IosNoOpCalendarSync : PlatformCalendarSync {
    override suspend fun syncCurrentScheduleToCalendar(): Boolean = false
    override suspend fun deleteScheduleCalendar(): Boolean = false
}

// iOS 端版本发布跳转实现（直接打开浏览器访问 Release 页）
object IosPlatformReleaseInstaller : PlatformReleaseInstaller {
    override fun processReleaseDownload(release: AppRelease, onNotify: (String) -> Unit): Boolean {
        val url = release.htmlUrl.ifBlank { "https://github.com/yuexps/cqust-schedule-miuix/releases" }
        val opened = IosPlatformExternalHandler.openBrowser(url)
        if (opened) onNotify("已在浏览器打开新版本页面")
        return opened
    }
}

// 前台通知展示代理：App 在前台时仍弹出横幅与声音
private class IosForegroundNotificationDelegate : NSObject(), UNUserNotificationCenterDelegateProtocol {
    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        willPresentNotification: UNNotification,
        withCompletionHandler: (UNNotificationPresentationOptions) -> Unit
    ) {
        withCompletionHandler(
            UNNotificationPresentationOptionBanner or
                UNNotificationPresentationOptionList or
                UNNotificationPresentationOptionSound
        )
    }
}

// 系统以弱引用保存代理，需自身持有；实例在函数内创建，避免触发全局初始化器的 ObjC 分配问题
private var iosNotificationCenterDelegate: IosForegroundNotificationDelegate? = null

// 注册通知中心代理，使前台也能展示课前提醒与测试通知
fun installIosNotificationCenterDelegate() {
    val delegate = iosNotificationCenterDelegate
        ?: IosForegroundNotificationDelegate().also { iosNotificationCenterDelegate = it }
    UNUserNotificationCenter.currentNotificationCenter().delegate = delegate
}

// iOS 本地通知调度与测试器（基于 UNUserNotificationCenter，7 天滑动窗口规避 64 条上限）
class IosNotificationScheduler(
    private val settingsRepository: SettingsRepository,
    private val scheduleRepository: ScheduleRepository,
    private val examRepository: ExamRepository
) : PlatformAutomationTester {

    private val scope = CoroutineScope(SupervisorJob() + iosCoroutineExceptionHandler + Dispatchers.IO)

    fun startObserving() {
        scope.launch {
            combine(
                settingsRepository.appSettingsFlow,
                scheduleRepository.currentSemesterFlow,
                scheduleRepository.allCoursesFlow,
                scheduleRepository.currentAdjustmentsFlow,
                examRepository.currentSemesterExamsFlow
            ) { settings, semester, _, _, _ ->
                settings to semester
            }.collectLatest { (settings, _) ->
                if (settings.reminderEnabled) {
                    requestNotificationPermissionIfNeeded()
                }
                rescheduleUpcomingNotifications()
            }
        }
    }

    fun requestNotificationPermissionIfNeeded(onResult: ((Boolean) -> Unit)? = null) {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        val options = UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge
        center.requestAuthorizationWithOptions(options) { granted, _ ->
            // 系统回调线程中抛异常会直接终止进程
            runCatching { onResult?.invoke(granted) }
        }
    }

    // 滚动注册未来 7 天课前与考试提醒（最多注册 48 条，预留安全余量低于 iOS 64 条硬上限）
    suspend fun rescheduleUpcomingNotifications() = withContext(Dispatchers.IO) {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        center.removeAllPendingNotificationRequests()

        val settings = settingsRepository.getAppSettingsOnce()
        if (!settings.reminderEnabled) return@withContext

        val semester = scheduleRepository.getCurrentSemesterOnce() ?: return@withContext
        val tableId = scheduleRepository.getOrCreateDefaultTableId()
        val allEvents = scheduleRepository.getAllEventsForSemester(tableId, semester.id)
        val adjustments = scheduleRepository.getAdjustmentsOnce(tableId, semester.id)
        val exams = examRepository.getExamsOnce(tableId, semester.id)
        val remindMinutes = settings.remindBeforeMinutes
        val nowMillis = currentEpochMillis()
        val today = todayLocalDate()

        var scheduledCount = 0
        val maxPending = 48

        for (offset in 0..6) {
            if (scheduledCount >= maxPending) break
            val targetDate = today.plusDays(offset)
            val weekNumber = SemesterDateHelper.calculateRawWeekNumber(
                startDate = semester.effectiveStartDate,
                targetDate = targetDate,
                totalWeeks = semester.totalWeeks
            ) ?: continue

            val dayEvents = EffectiveScheduleResolver.resolveDayEvents(
                allEvents = allEvents,
                adjustments = adjustments,
                weekNumber = weekNumber,
                dayOfWeek = targetDate.isoDayOfWeekNumber
            )
            val startEvents = dayEvents
                .groupBy { it.startSection }
                .mapValues { (_, group) -> group.firstOrNull { it.isPractical } ?: group.first() }

            for ((section, ev) in startEvents) {
                if (scheduledCount >= maxPending) break
                val slot = findSectionTimeSlot(section) ?: continue
                val startTime = runCatching { LocalTime.parse(slot.startTime) }.getOrNull() ?: continue
                val triggerMillis = targetDate.atTimeEpochMillis(startTime) - remindMinutes * 60_000L
                if (triggerMillis <= nowMillis) continue

                val triggerTotalMinutes = (startTime.hour * 60 + startTime.minute) - remindMinutes
                if (triggerTotalMinutes < 0) continue

                val comps = NSDateComponents().apply {
                    year = targetDate.year.toLong()
                    month = targetDate.month.number.toLong()
                    day = targetDate.day.toLong()
                    hour = (triggerTotalMinutes / 60).toLong()
                    minute = (triggerTotalMinutes % 60).toLong()
                    second = 0
                }
                val content = UNMutableNotificationContent().apply {
                    setTitle("即将上课：${ev.inlineTitle}")
                    setBody(
                        listOf(ev.displayLocation, ev.teacher)
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                    )
                    setSound(UNNotificationSound.defaultSound)
                }
                val trigger = UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(
                    dateComponents = comps,
                    repeats = false
                )
                val reqId = "cqust_course_${targetDate}_${section}"
                val request = UNNotificationRequest.requestWithIdentifier(reqId, content, trigger)
                center.addNotificationRequest(request, withCompletionHandler = null)
                scheduledCount++
            }

            // 当日考试提醒
            val dateStr = targetDate.toString()
            val dayExams = exams.filter { it.isScheduled && it.examDate == dateStr }
            dayExams.forEachIndexed { idx, exam ->
                if (scheduledCount >= maxPending) return@forEachIndexed
                val startTime = exam.startTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                    ?: return@forEachIndexed
                val triggerMillis = targetDate.atTimeEpochMillis(startTime) - remindMinutes * 60_000L
                if (triggerMillis <= nowMillis) return@forEachIndexed
                val triggerTotalMinutes = (startTime.hour * 60 + startTime.minute) - remindMinutes
                if (triggerTotalMinutes < 0) return@forEachIndexed

                val comps = NSDateComponents().apply {
                    year = targetDate.year.toLong()
                    month = targetDate.month.number.toLong()
                    day = targetDate.day.toLong()
                    hour = (triggerTotalMinutes / 60).toLong()
                    minute = (triggerTotalMinutes % 60).toLong()
                    second = 0
                }
                val content = UNMutableNotificationContent().apply {
                    setTitle("考试提醒：${exam.courseName}")
                    setBody(
                        listOfNotNull(exam.displayPosition, exam.seatNo?.takeIf { it.isNotBlank() }?.let { "座位 $it" })
                            .joinToString(" · ")
                    )
                    setSound(UNNotificationSound.defaultSound)
                }
                val trigger = UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(
                    dateComponents = comps,
                    repeats = false
                )
                val reqId = "cqust_exam_${targetDate}_$idx"
                val request = UNNotificationRequest.requestWithIdentifier(reqId, content, trigger)
                center.addNotificationRequest(request, withCompletionHandler = null)
                scheduledCount++
            }
        }
    }

    override fun sendTestNotification(courseName: String, position: String, teacher: String) {
        requestNotificationPermissionIfNeeded { granted ->
            if (!granted) return@requestNotificationPermissionIfNeeded
            val content = UNMutableNotificationContent().apply {
                setTitle("[测试] 即将上课：$courseName")
                setBody("$position · $teacher")
                setSound(UNNotificationSound.defaultSound)
            }
            val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(1.0, repeats = false)
            val request = UNNotificationRequest.requestWithIdentifier("cqust_test_now", content, trigger)
            UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request, null)
        }
    }

    override fun scheduleDelayed30sTestNotification(courseName: String, position: String, teacher: String) {
        requestNotificationPermissionIfNeeded { granted ->
            if (!granted) return@requestNotificationPermissionIfNeeded
            val content = UNMutableNotificationContent().apply {
                setTitle("[30秒后台测试] 即将上课：$courseName")
                setBody("$position · $teacher")
                setSound(UNNotificationSound.defaultSound)
            }
            val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(30.0, repeats = false)
            val request = UNNotificationRequest.requestWithIdentifier("cqust_test_30s", content, trigger)
            UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request, null)
        }
    }

    override suspend fun testToggleModeImmediately(mode: AutoControlMode) = Unit
    override fun scheduleDelayed30sDndTest() = Unit
}

// iOS 端 Koin 依赖注入模块
val iosModule = module {
    includes(commonModule)

    single { createIosScheduleDatabase() }
    single { createIosPreferencesDataStore() }
    single<PlatformWallpaperStorage> { IosPlatformWallpaperStorage }
    single<PlatformStringProvider> { IosPlatformStringProvider }
    single<PlatformCalendarSync> { IosNoOpCalendarSync }
    single<PlatformReleaseInstaller> { IosPlatformReleaseInstaller }

    single(createdAtStart = true) {
        IosNotificationScheduler(get(), get(), get()).apply {
            startObserving()
        }
    }
    single<PlatformAutomationTester> { get<IosNotificationScheduler>() }
}
