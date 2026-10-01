# 重科课表 (CQUST Schedule Miuix)

专为重庆科技大学学生打造的 Android 课表客户端，基于 Jetpack Compose 与小米 HyperOS / Miuix 设计风格构建。

## 核心特性

- **教务极速同步**：支持教务系统登录（含 WebVPN 内网穿透），一键导入课表、实验排课与调课变更；
- **HyperOS 设计**：遵循 Miuix 视觉规范，沉浸式界面、平滑手势与原生圆角控件；
- **上课自动化**：课前横幅提醒，课中自动开启静音 / 勿扰模式，下课自动恢复；
- **桌面微件与日历**：桌面快捷课表微件、系统日历一键导出、校内教学楼地图导航；
- **纯净轻量**：无广告、无推广、专注纯粹的课表与学习工具体验。

## 技术栈

- **语言环境**：Kotlin (JDK 21)
- **界面架构**：Jetpack Compose + [Miuix](https://github.com/miuix-kotlin/miuix)
- **数据持久化**：Room + DataStore Preferences
- **架构模式**：MVVM + Flow + Coroutines + KSP
- **构建目标**：`arm64-v8a`

## 免责声明

本项目为开源学习交流项目，非重庆科技大学官方发布应用。
仅用于个人日常课表查询与自动化辅助，请妥善保管个人统一身份认证凭据。
