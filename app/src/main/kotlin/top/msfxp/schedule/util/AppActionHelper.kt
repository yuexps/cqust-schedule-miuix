package top.msfxp.schedule.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri

// 外部应用跳转与交互辅助工具
object AppActionHelper {

    // 发起添加 QQ 群流程
    fun joinQQGroup(context: Context, key: String): Boolean {
        val uri = Uri.parse("mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3D$key")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    // 复制文本至系统剪贴板
    fun copyToClipboard(context: Context, text: String, label: String = "Text") {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard?.setPrimaryClip(clip)
    }

    // 打开系统外部浏览器访问链接
    fun openBrowser(context: Context, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    // 调用系统下载管理器直接下载文件
    fun downloadWithSystemManager(
        context: Context,
        url: String,
        fileName: String,
        title: String,
        description: String
    ): Boolean {
        return try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? android.app.DownloadManager
                ?: return false
            val request = android.app.DownloadManager.Request(Uri.parse(url)).apply {
                setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setMimeType("application/vnd.android.package-archive")
                setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fileName)
                setTitle(title)
                setDescription(description)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            downloadManager.enqueue(request)
            true
        } catch (_: Exception) {
            false
        }
    }
}
