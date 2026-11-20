package top.msfxp.schedule.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// Android 端 IO 调度器实现
actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
