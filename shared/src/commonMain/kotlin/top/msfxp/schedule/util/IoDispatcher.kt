package top.msfxp.schedule.util

import kotlinx.coroutines.CoroutineDispatcher

// 跨平台 IO 调度器（common 源集无法直接引用 Dispatchers.IO）
expect val ioDispatcher: CoroutineDispatcher
