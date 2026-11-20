package top.msfxp.schedule.platform

import top.msfxp.schedule.data.api.AppRelease
import top.msfxp.schedule.data.model.AutoControlMode

// 平台系统日历同步接口
interface PlatformCalendarSync {
    suspend fun syncCurrentScheduleToCalendar(): Boolean
    suspend fun deleteScheduleCalendar(): Boolean
}

// 供 ViewModel 使用的跨平台文案提供器
interface PlatformStringProvider {
    fun syncSuccess(regularCount: Int, practicalCount: Int): String
    fun calendarSyncSuccess(): String
    fun syncFailedDefault(): String
    fun loginEmptyStudentId(): String
    fun loginEmptyPassword(): String
    fun loginConnectingHint(): String
    fun loginFailedGeneral(): String
    fun aboutDownloadStarted(): String
    fun testCourseDefaultName(): String
    fun testCourseDefaultLocation(): String
    fun testCourseDefaultTeacher(): String
}

// 平台自动化与通知测试接口
interface PlatformAutomationTester {
    fun sendTestNotification(courseName: String, position: String, teacher: String)
    fun scheduleDelayed30sTestNotification(courseName: String, position: String, teacher: String)
    suspend fun testToggleModeImmediately(mode: AutoControlMode)
    fun scheduleDelayed30sDndTest()
}

// 平台版本更新下载/跳转接口
interface PlatformReleaseInstaller {
    fun processReleaseDownload(release: AppRelease, onNotify: (String) -> Unit): Boolean
}
