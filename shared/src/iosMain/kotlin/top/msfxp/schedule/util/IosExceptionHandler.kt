package top.msfxp.schedule.util

import kotlinx.coroutines.CoroutineExceptionHandler

// iOS 常驻作用域异常兜底：Kotlin/Native 未捕获协程异常会直接终止进程，此处仅记录
val iosCoroutineExceptionHandler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
    println("[ios] uncaught coroutine exception: ${throwable::class.simpleName}: ${throwable.message}")
}
