# 项目目录与模块架构导航 (PROJECT_STRUCTURE.md)

本项目为重庆科技大学（CQUST）课表应用，基于小米 HyperOS / Miuix 设计风格，采用 Jetpack Compose + Room + Koin + DataStore + WorkManager 构建。

---

## 1. 工程根目录与全局配置

| 文件 / 目录 | 核心功能直述 |
| :--- | :--- |
| [AGENTS.md](../AGENTS.md) | 智能体开发规范（换行符、注释、UI组件、字符串规范） |
| [.agents/](../.agents) | 智能体辅助资源与技能目录（包含 Miuix 技能） |
| [build.gradle.kts](../build.gradle.kts) | 根工程 Gradle 构建配置 |
| [settings.gradle.kts](../settings.gradle.kts) | 模块与依赖仓储配置 |
| [gradle/libs.versions.toml](../gradle/libs.versions.toml) | 统一版本目录与第三方库版本管理 |
| [docs/muix](muix) | Miuix Compose 组件库官方参考文档 |

---

## 2. 源码模块分层与文件清单 (`app/src/main/kotlin/top/msfxp/schedule`)

### 2.1 应用入口与依赖注入
- [ScheduleApplication.kt](../app/src/main/kotlin/top/msfxp/schedule/ScheduleApplication.kt)：Application 入口，负责 Koin 依赖注入与通知渠道初始化。
- [MainActivity.kt](../app/src/main/kotlin/top/msfxp/schedule/MainActivity.kt)：主 Activity，初始化边到边沉浸式窗口与 Miuix 主题容器。
- [App.kt](../app/src/main/kotlin/top/msfxp/schedule/App.kt)：全局 NavHost 导航容器，承载各 Screen 页面跳转。
- [di/AppModule.kt](../app/src/main/kotlin/top/msfxp/schedule/di/AppModule.kt)：Koin 模块定义，注册 Dao、Repository、Helper 及各类 ViewModel。
- [navigation/NavRoutes.kt](../app/src/main/kotlin/top/msfxp/schedule/navigation/NavRoutes.kt)：页面路由路径常量与页面参数定义。

---

### 2.2 教务网络传输与同步层 (`data/api`)
- [CqustCasAuthClient.kt](../app/src/main/kotlin/top/msfxp/schedule/data/api/CqustCasAuthClient.kt)：统一身份认证（CAS）登录流程、execution/lt 提取与验证码识别。
- [CqustWebVpnSession.kt](../app/src/main/kotlin/top/msfxp/schedule/data/api/CqustWebVpnSession.kt)：校园外网 WebVPN 隧道握手、会话保活与双重重定向维持。
- [CqustVpnCrypto.kt](../app/src/main/kotlin/top/msfxp/schedule/data/api/CqustVpnCrypto.kt)：WebVPN 专用 AES-CFB-128 URL/Path 编解码转换算法。
- [EamsTransport.kt](../app/src/main/kotlin/top/msfxp/schedule/data/api/EamsTransport.kt)：教务双轨网络传输接口及实现（校园内网直连 / 校外 WebVPN 代理）。
- [CqustEamsClient.kt](../app/src/main/kotlin/top/msfxp/schedule/data/api/CqustEamsClient.kt)：教务系统客户端，获取学期列表及拉取课表原始 HTML。
- [CqustEamsParser.kt](../app/src/main/kotlin/top/msfxp/schedule/data/api/CqustEamsParser.kt)：常规理论课表与未排课 HTML 解析器（提取课程、节次、教师与教室）。
- [CqustPracticalParser.kt](../app/src/main/kotlin/top/msfxp/schedule/data/api/CqustPracticalParser.kt)：实验/集中实践教学环节课表 HTML 解析器。
- [ScheduleMatrixBuilder.kt](../app/src/main/kotlin/top/msfxp/schedule/data/api/ScheduleMatrixBuilder.kt)：时空原子矩阵构建器，处理单双周展开、时间冲突聚合与地点纠偏。
- [CqustSyncManager.kt](../app/src/main/kotlin/top/msfxp/schedule/data/api/CqustSyncManager.kt)：全流程课表同步协调器（含 AndroidKeyStore AES-GCM 凭证安全加解密）。

---

### 2.3 数据持久化与模型层 (`data/db`, `data/model`, `data/repository`)
- [data/db/ScheduleDao.kt](../app/src/main/kotlin/top/msfxp/schedule/data/db/ScheduleDao.kt)：Room 数据库实例与全量 DAO（课程、课表事件、调课记录的 CRUD 与事务批量写入）。
- [data/model/CourseModels.kt](../app/src/main/kotlin/top/msfxp/schedule/data/model/CourseModels.kt)：课程实体、排课原子事件、调课变动记录及主题调色板数据模型。
- [data/model/AppSettings.kt](../app/src/main/kotlin/top/msfxp/schedule/data/model/AppSettings.kt)：应用偏好设置模型、学期/周次日期推算工具及 DataStore 键。
- [data/repository/ScheduleRepository.kt](../app/src/main/kotlin/top/msfxp/schedule/data/repository/ScheduleRepository.kt)：课表统一数据仓库，提供响应式 Flow 查询、调课持久化与颜色更新。
- [data/repository/SettingsRepository.kt](../app/src/main/kotlin/top/msfxp/schedule/data/repository/SettingsRepository.kt)：用户偏好设置、个人档案与自定义壁纸文件的持久化仓库。

---

### 2.4 系统服务与小组件 (`service`, `widget`)
- [service/AudioModeControlService.kt](../app/src/main/kotlin/top/msfxp/schedule/service/AudioModeControlService.kt)：课程自动化音频模式切换短期前台服务（用于 Android 14+ / 17 后台音频强化提权切换静音）。
- [service/CalendarSyncHelper.kt](../app/src/main/kotlin/top/msfxp/schedule/service/CalendarSyncHelper.kt)：系统日历账户创建、精细化增量更新（Diff Sync）与提醒去重自愈。
- [service/CourseAlarmObserver.kt](../app/src/main/kotlin/top/msfxp/schedule/service/CourseAlarmObserver.kt)：课表自动化监听器，分流解耦处理免打扰排程、小组件刷新及课程数据变动时的日历同步。
- [service/CourseAlarmReceiver.kt](../app/src/main/kotlin/top/msfxp/schedule/service/CourseAlarmReceiver.kt)：课前提醒通知推送与上课自动免打扰/静音切换广播接收器。
- [service/DndSchedulerWorker.kt](../app/src/main/kotlin/top/msfxp/schedule/service/DndSchedulerWorker.kt)：WorkManager 定时后台任务，每日自动对齐注册课堂免打扰排程。
- [widget/WidgetProviders.kt](../app/src/main/kotlin/top/msfxp/schedule/widget/WidgetProviders.kt)：桌面小组件 AppWidgetProvider 与即时广播接收器。
- [widget/WidgetUpdateHelper.kt](../app/src/main/kotlin/top/msfxp/schedule/widget/WidgetUpdateHelper.kt)：桌面小组件 RemoteViews 数据组装、Miuix 圆角卡片渲染与桌面刷新。

---

### 2.5 界面展示与交互层 (`ui`)

#### A. 主课表 (`ui/schedule`)
- [WeeklyScheduleScreen.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/schedule/WeeklyScheduleScreen.kt)：周课表主界面（顶部操作栏、周次联动 Pager、主网格）。
- [WeeklyScheduleViewModel.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/schedule/WeeklyScheduleViewModel.kt)：主课表状态驱动（当前周计算、调课事务触发、壁纸缓存加载）。
- [components/ScheduleGrid.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/schedule/components/ScheduleGrid.kt)：核心课表格子画布（节次时间轴、课程卡片、重叠标记与点击事件）。
- [components/CourseDetailSheet.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/schedule/components/CourseDetailSheet.kt)：课程详情底部弹窗，提供基本信息、地点导航与调课入口。
- [components/CourseDetailSubComponents.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/schedule/components/CourseDetailSubComponents.kt)：课程详情辅助子组件（属性标签行、地点导航操作条）。
- [components/CourseEditSheet.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/schedule/components/CourseEditSheet.kt)：课程编辑总控弹窗，协调调色板、单次调课与调课历史子弹窗。
- [components/CoursePaletteSubSheet.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/schedule/components/CoursePaletteSubSheet.kt)：课程配色编号（Palette Index）选择弹窗。
- [components/CourseAdjustmentSubSheet.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/schedule/components/CourseAdjustmentSubSheet.kt)：调课操作表单（改期/改节次/停课/与目标课程交换）。
- [components/CourseAdjustmentRecordsSubSheet.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/schedule/components/CourseAdjustmentRecordsSubSheet.kt)：已生效调课记录管理列表，支持单条撤销与恢复原状。
- [components/WeekSelectorSheet.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/schedule/components/WeekSelectorSheet.kt)：学期 1~30 周次快速点选弹窗。
- [components/WormPagerIndicator.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/schedule/components/WormPagerIndicator.kt)：平滑蠕虫动画周次分页指示器。
- [components/MapChooserDialog.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/schedule/components/MapChooserDialog.kt)：外部地图应用选择面板（高德、百度、腾讯）。

#### B. 课程总览 (`ui/courses`)
- [CourseOverviewScreen.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/courses/CourseOverviewScreen.kt)：学期全量课程列表页面，按学分、类型分栏展示。
- [CourseOverviewViewModel.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/courses/CourseOverviewViewModel.kt)：课程总览数据加载与关键词检索过滤。

#### C. 教务登录与同步 (`ui/login`)
- [CqustLoginScreen.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/login/CqustLoginScreen.kt)：统一身份认证登录界面（含验证码刷新、密码保存开关、实时同步步骤反馈）。
- [CqustLoginViewModel.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/login/CqustLoginViewModel.kt)：登录业务交互逻辑，驱动后台爬虫同步与异常状态提示。

#### D. 设置与个性化 (`ui/settings`)
- [SettingsScreen.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/settings/SettingsScreen.kt)：设置中心入口（课表设置、外观个性化、自动化、关于）。
- [SettingsViewModel.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/settings/SettingsViewModel.kt)：设置项通用读写状态持有与广播联动。
- [PersonalizationViewModel.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/settings/PersonalizationViewModel.kt)：个性化外观专用 ViewModel（卡片透明度、圆角、壁纸异步加载裁剪）。
- [subscreens/PersonalizationScreen.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/settings/subscreens/PersonalizationScreen.kt)：课表外观个性化设置界面。
- [subscreens/CalendarSyncScreen.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/settings/subscreens/CalendarSyncScreen.kt)：系统日历双向同步开关与同步状态设置。
- [subscreens/ClassAutomationScreen.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/settings/subscreens/ClassAutomationScreen.kt)：上课期间免打扰模式与静音自动化规则设置。
- [subscreens/PreClassReminderScreen.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/settings/subscreens/PreClassReminderScreen.kt)：提前提醒时间间隔与闹钟触发规则设置。
- [subscreens/PermissionManagementScreen.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/settings/subscreens/PermissionManagementScreen.kt)：系统通知、日历、精确闹钟权限集中授权与跳转中心。
- [subscreens/WallpaperCropDialog.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/settings/subscreens/WallpaperCropDialog.kt)：自定义课表背景壁纸位移与缩放裁剪对话框。
- [subscreens/ColorPickerDialog.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/settings/subscreens/ColorPickerDialog.kt)：Miuix 风格自定义色盘提取弹窗。
- [subscreens/AboutScreen.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/settings/subscreens/AboutScreen.kt)：关于应用二级页面（应用版本、开源仓库、检查更新、QQ群与免责声明）。
- [AboutViewModel.kt](../app/src/main/kotlin/top/msfxp/schedule/ui/settings/AboutViewModel.kt)：关于页面视图模型与更新状态持有。

---

### 2.6 通用工具与扩展层 (`util`, `data/api`)
- [data/api/UpdateCheckService.kt](../app/src/main/kotlin/top/msfxp/schedule/data/api/UpdateCheckService.kt)：GitHub Release 检查更新网络服务与语义化版本比对器。
- [AppActionHelper.kt](../app/src/main/kotlin/top/msfxp/schedule/util/AppActionHelper.kt)：手Q加群唤起、剪贴板复制及浏览器链接调起工具。
- [ImageBitmapHelper.kt](../app/src/main/kotlin/top/msfxp/schedule/util/ImageBitmapHelper.kt)：壁纸位图高保真采样缩放、旋转纠偏与文件安全落地。
- [MapNavigationHelper.kt](../app/src/main/kotlin/top/msfxp/schedule/util/MapNavigationHelper.kt)：检测已安装地图应用并生成各厂商步行导航调起 URI。
- [PermissionHelper.kt](../app/src/main/kotlin/top/msfxp/schedule/util/PermissionHelper.kt)：系统权限授权状态判断及设置页跳转意图构建。

---

## 3. 核心业务数据流向

1. **教务课表同步链路**：
   `CqustLoginScreen` -> `CqustSyncManager` -> `CqustCasAuthClient` / `CqustWebVpnSession` -> `CqustEamsClient` -> `CqustEamsParser` / `CqustPracticalParser` -> `ScheduleMatrixBuilder` -> `ScheduleDao` (写入数据库) -> `CalendarSyncHelper` (同步系统日历)。
2. **课表呈现与动态调课链路**：
   `ScheduleDao` -> `ScheduleRepository` -> `WeeklyScheduleViewModel` -> `WeeklyScheduleScreen` -> `ScheduleGrid` -> `CourseEditSheet` -> `CourseAdjustmentSubSheet` -> `ScheduleRepository` (更新并局部重组)。
3. **后台课前提醒与课堂免打扰链路**：
   `ScheduleRepository` -> `CourseAlarmObserver` -> `CourseAlarmScheduler` (精准闹钟) -> `CourseAlarmReceiver` / `DndSchedulerWorker` (系统通知推送 / 切换免打扰)。
