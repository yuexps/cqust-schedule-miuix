# 项目目录与模块架构导航 (PROJECT_STRUCTURE.md)

本项目为重庆科技大学（CQUST）课表跨平台应用（Android + iOS），基于小米 HyperOS / Miuix 设计风格，采用 Kotlin Multiplatform (KMP) + Compose Multiplatform + Room KMP + Koin + DataStore + Ktor 构建。

---

## 1. 工程根目录与模块结构

| 文件 / 模块 | 核心功能直述 |
| :--- | :--- |
| [AGENTS.md](../AGENTS.md) | 智能体开发规范（换行符、注释、UI组件、字符串规范） |
| [build.gradle.kts](../build.gradle.kts) | 根工程 Gradle 构建配置 |
| [settings.gradle.kts](../settings.gradle.kts) | 多模块（`:shared`, `:app`）与依赖仓储配置 |
| [gradle/libs.versions.toml](../gradle/libs.versions.toml) | 统一版本目录与第三方库版本管理 |
| [shared/](../shared) | **KMP 共享核心模块**（`commonMain` 共享业务/数据/UI + `androidMain` + `iosMain`） |
| [app/](../app) | **Android 宿主与系统服务模块**（Application/Activity、前台静音服务、日历同步、闹钟、桌面微件） |
| [iosApp/](../iosApp) | **iOS SwiftUI 宿主工程**（承载 `ComposeApp` Framework 的 `MainViewController`；Xcode 工程与 `Info.plist` 均由 `project.yml` 经 XcodeGen 生成，禁止手改） |

---

## 2. KMP 共享核心模块 (`shared/src`)

### 2.1 跨平台根导航与依赖注入 (`commonMain/kotlin/top/msfxp/schedule`)
- [App.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/App.kt)：跨平台全局 `NavDisplay` 导航容器与 Miuix 主题入口。
- [di/CommonModule.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/di/CommonModule.kt)：双端共享 Koin 模块（注册 DAO、Repository、SyncManager 与 5 个跨平台 ViewModel）。
- [navigation/NavRoutes.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/navigation/NavRoutes.kt)：页面路由常量与类型安全导航键。
- [composeResources/values/strings.xml](../shared/src/commonMain/composeResources/values/strings.xml)：Compose Multiplatform 共享字符串资源（`Res.string.*`）。

### 2.2 教务网络传输与同步层 (`commonMain/.../data/api`)
- [CqustCasAuthClient.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/data/api/CqustCasAuthClient.kt)：统一身份认证（CAS）登录与教务系统 WebVPN 单点登录会话接入。
- [CqustWebVpnSession.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/data/api/CqustWebVpnSession.kt)：校园外网 WebVPN 隧道握手、会话保活与逐跳重定向维持。
- [CqustVpnCrypto.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/data/api/CqustVpnCrypto.kt)：WebVPN 专用 AES-CFB-128 URL 令牌转换与 CAS 密码 AES-CBC 加密。
- [CqustEamsParser.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/data/api/CqustEamsParser.kt)：常规理论课表与未排课 HTML 解析器。
- [CqustPracticalParser.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/data/api/CqustPracticalParser.kt)：实验/集中实践教学环节课表 HTML 解析器。
- [CqustExamParser.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/data/api/CqustExamParser.kt)：多批次考试 HTML 分段解析器。
- [ScheduleMatrixBuilder.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/data/api/ScheduleMatrixBuilder.kt)：时空原子矩阵构建器，按基础课程代码归并课程并保留自定义配色。
- [CqustSyncManager.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/data/api/CqustSyncManager.kt)：全流程课表同步协调器（Room KMP 跨平台写连接事务入库）。
- [UpdateCheckService.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/data/api/UpdateCheckService.kt)：Gitee / GitHub Release 检查更新服务与版本比对器。

### 2.3 数据持久化、领域与模型层 (`commonMain/.../data`, `domain`, `util`)
- [data/db/ScheduleDao.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/data/db/ScheduleDao.kt)：Room KMP v3 数据库定义（`@ConstructedBy(ScheduleDatabaseConstructor::class)`）与全量 DAO。
- [data/model/CourseModels.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/data/model/CourseModels.kt)：课程、排课事件、调课补丁、调色板模型及 `SemesterDateHelper`。
- [data/model/AppSettings.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/data/model/AppSettings.kt)：应用偏好设置模型与 DataStore 键定义。
- [data/model/ExamModels.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/data/model/ExamModels.kt)：考试原始条目、Room 实体与派生属性。
- [data/repository/ScheduleRepository.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/data/repository/ScheduleRepository.kt)：课表与调课统一数据仓库。
- [data/repository/ExamRepository.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/data/repository/ExamRepository.kt)：考试记录仓库。
- [data/repository/SettingsRepository.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/data/repository/SettingsRepository.kt)：跨平台偏好设置与壁纸缓存仓库。
- [domain/EffectiveScheduleResolver.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/domain/EffectiveScheduleResolver.kt)：有效排课领域解析器（原始课程 + 调课补丁 + 考试映射合成）。
- [util/DateTimeCompat.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/util/DateTimeCompat.kt)：基于 `kotlinx-datetime` 的跨平台日期与时刻工具集。
- [util/IoDispatcher.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/util/IoDispatcher.kt)：跨平台 IO 协程调度器（`android` / `ios` 各自绑定 `Dispatchers.IO`）。
- [util/CourseLocationFormatter.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/util/CourseLocationFormatter.kt)：校区、楼栋与教室解析规范化工具。
- [util/ExamTimeMapper.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/util/ExamTimeMapper.kt)：考试时刻映射课表节次区间。

### 2.4 跨平台共享 UI 与 ViewModel (`commonMain/.../ui`)
- **主课表 (`ui/schedule`)**：[WeeklyScheduleScreen.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/ui/schedule/WeeklyScheduleScreen.kt)、[WeeklyScheduleViewModel.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/ui/schedule/WeeklyScheduleViewModel.kt) 及 `components/` 下全部 11 个组件（`ScheduleGrid`、`CellDetailSheet`、`CourseDetailSubComponents`、`ExamDetailPage`、`CourseEditSheet`、`CoursePaletteSubSheet`、`CourseAdjustmentSubSheet`、`CourseAdjustmentRecordsSubSheet`、`WeekSelectorSheet`、`WormPagerIndicator`、`MapChooserDialog`）。
- **课程总览 (`ui/courses`)**：[CourseOverviewScreen.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/ui/courses/CourseOverviewScreen.kt)、[CourseOverviewViewModel.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/ui/courses/CourseOverviewViewModel.kt)。
- **教务登录 (`ui/login`)**：[CqustLoginScreen.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/ui/login/CqustLoginScreen.kt)、[CqustLoginViewModel.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/ui/login/CqustLoginViewModel.kt)。
- **设置与关于 (`ui/settings`)**：[SettingsScreen.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/ui/settings/SettingsScreen.kt)、[SettingsViewModel.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/ui/settings/SettingsViewModel.kt)、[AboutViewModel.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/ui/settings/AboutViewModel.kt)、[subscreens/AboutScreen.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/ui/settings/subscreens/AboutScreen.kt)、[subscreens/ColorPickerDialog.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/ui/settings/subscreens/ColorPickerDialog.kt)。

### 2.5 平台抽象与 Android / iOS 实现 (`platform/`, `androidMain/`, `iosMain/`)
- **公共抽象 (`commonMain/.../platform`)**：[PlatformCapabilities.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/platform/PlatformCapabilities.kt)、[PlatformCrypto.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/platform/PlatformCrypto.kt)、[PlatformHttp.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/platform/PlatformHttp.kt)、[PlatformServices.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/platform/PlatformServices.kt)、[PlatformWallpaperStorage.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/platform/PlatformWallpaperStorage.kt)、[PlatformExternalActions.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/platform/PlatformExternalActions.kt)、[PlatformSubScreens.kt](../shared/src/commonMain/kotlin/top/msfxp/schedule/platform/PlatformSubScreens.kt)。
- **Android 端实现 (`androidMain`)**：[PlatformAndroid.kt](../shared/src/androidMain/kotlin/top/msfxp/schedule/platform/PlatformAndroid.kt)（Ktor Android 引擎、javax AES、AndroidKeyStore）。
- **iOS 端实现 (`iosMain`)**：
  - [PlatformIos.kt](../shared/src/iosMain/kotlin/top/msfxp/schedule/platform/PlatformIos.kt)：Ktor Darwin 引擎、`CommonCrypto` AES-CFB/CBC、`Keychain` 凭证存储、`UIApplication` URL/地图导航及 iOS `PlatformCapabilities`。
  - [IosPlatformServices.kt](../shared/src/iosMain/kotlin/top/msfxp/schedule/platform/IosPlatformServices.kt)：iOS Room/DataStore 构建、Skia 壁纸解码、`UNUserNotificationCenter` 7 天滑动窗口课前提醒调度器与 `iosModule`。
  - [IosSubScreens.kt](../shared/src/iosMain/kotlin/top/msfxp/schedule/ui/IosSubScreens.kt)：iOS 个性化（含壁纸预览与遮罩）与课前提醒设置页。
  - [IosImagePicker.kt](../shared/src/iosMain/kotlin/top/msfxp/schedule/ui/IosImagePicker.kt)：iOS 原生相册选图器，支持选取图片作为课表壁纸。
  - [MainViewController.kt](../shared/src/iosMain/kotlin/top/msfxp/schedule/MainViewController.kt)：导出给 SwiftUI 的 `ComposeUIViewController` 主入口。

---

## 3. Android 宿主与系统服务模块 (`app/src/main/kotlin/top/msfxp/schedule`)

- **应用入口与 DI**：[ScheduleApplication.kt](../app/src/main/kotlin/top/msfxp/schedule/ScheduleApplication.kt)、[MainActivity.kt](../app/src/main/kotlin/top/msfxp/schedule/MainActivity.kt)、[di/AppModule.kt](../app/src/main/kotlin/top/msfxp/schedule/di/AppModule.kt)、[platform/AndroidPlatformServices.kt](../app/src/main/kotlin/top/msfxp/schedule/platform/AndroidPlatformServices.kt)。
- **Android 专属设置子页 (`ui/settings`)**：[PersonalizationViewModel.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/settings/PersonalizationViewModel.kt)、[PersonalizationScreen.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/settings/subscreens/PersonalizationScreen.kt)、[WallpaperCropDialog.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/settings/subscreens/WallpaperCropDialog.kt)、[PreClassReminderScreen.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/settings/subscreens/PreClassReminderScreen.kt)、[CalendarSyncScreen.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/settings/subscreens/CalendarSyncScreen.kt)、[ClassAutomationScreen.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/settings/subscreens/ClassAutomationScreen.kt)、[PermissionManagementScreen.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/settings/subscreens/PermissionManagementScreen.kt)。
- **系统服务与桌面微件 (`service/`, `widget/`, `util/`)**：[AudioModeControlService.kt](../app/src/main/kotlin/top/msfxp/schedule/service/AudioModeControlService.kt)、[CalendarSyncHelper.kt](../app/src/main/kotlin/top/msfxp/schedule/service/CalendarSyncHelper.kt)、[CourseAlarmObserver.kt](../app/src/main/kotlin/top/msfxp/schedule/service/CourseAlarmObserver.kt)、[CourseAlarmReceiver.kt](../app/src/main/kotlin/top/msfxp/schedule/service/CourseAlarmReceiver.kt)、[DndSchedulerWorker.kt](../app/src/main/kotlin/top/msfxp/schedule/service/DndSchedulerWorker.kt)、[DownloadCompleteReceiver.kt](../app/src/main/kotlin/top/msfxp/schedule/service/DownloadCompleteReceiver.kt)、[WidgetProviders.kt](../app/src/main/kotlin/top/msfxp/schedule/widget/WidgetProviders.kt)、[WidgetUpdateHelper.kt](../app/src/main/kotlin/top/msfxp/schedule/widget/WidgetUpdateHelper.kt)。
