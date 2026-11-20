package top.msfxp.schedule.ui

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.UIKit.UIApplication
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy

// 保持选图代理存活，避免 UIKit 弱引用导致回调丢失
private var activePickerDelegate: NSObject? = null

// 调起 iOS 相册选图，返回 JPEG 字节（取消或失败返回 null）
fun launchIosImagePicker(onResult: (ByteArray?) -> Unit) {
    // UIKit 展示必须在主线程执行
    dispatch_async(dispatch_get_main_queue()) {
        presentImagePicker(onResult)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun presentImagePicker(onResult: (ByteArray?) -> Unit) {
    // 已有选择器在展示时不再重复调起，避免 UIKit 转场冲突崩溃
    if (activePickerDelegate != null) return
    val presenter = resolveTopViewController()
    if (presenter == null) {
        onResult(null)
        return
    }
    val pickerDelegate = ImagePickerDelegate { bytes ->
        activePickerDelegate = null
        onResult(bytes)
    }
    activePickerDelegate = pickerDelegate
    val picker = UIImagePickerController()
    picker.delegate = pickerDelegate
    presenter.presentViewController(picker, animated = true, completion = null)
}

// 定位当前窗口上的最上层视图控制器
private fun resolveTopViewController(): UIViewController? {
    val windows = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .flatMap { scene -> scene.windows.filterIsInstance<UIWindow>() }
    var current = (windows.firstOrNull { it.keyWindow } ?: windows.firstOrNull())
        ?.rootViewController ?: return null
    while (current.presentedViewController != null) {
        current = current.presentedViewController ?: return current
    }
    return current
}

// 相册选图回调代理
@OptIn(ExperimentalForeignApi::class)
private class ImagePickerDelegate(
    private val onResult: (ByteArray?) -> Unit
) : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {

    override fun imagePickerController(
        picker: UIImagePickerController,
        didFinishPickingMediaWithInfo: Map<Any?, *>
    ) {
        val image = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
        val bytes = image?.let { UIImageJPEGRepresentation(it, 0.9)?.toByteArray() }
        picker.dismissViewControllerAnimated(true, null)
        onResult(bytes)
    }

    override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
        picker.dismissViewControllerAnimated(true, null)
        onResult(null)
    }
}

// NSData 转 Kotlin 字节数组
@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size <= 0) return ByteArray(0)
    return ByteArray(size).also { out ->
        out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    }
}
