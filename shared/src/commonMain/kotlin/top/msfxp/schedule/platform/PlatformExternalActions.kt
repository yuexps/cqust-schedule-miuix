package top.msfxp.schedule.platform

import androidx.compose.ui.graphics.ImageBitmap
import org.jetbrains.compose.resources.StringResource
import top.msfxp.schedule.shared.resources.Res
import top.msfxp.schedule.shared.resources.map_app_amap
import top.msfxp.schedule.shared.resources.map_app_apple
import top.msfxp.schedule.shared.resources.map_app_baidu
import top.msfxp.schedule.shared.resources.map_app_tencent
import top.msfxp.schedule.util.CourseLocationFormatter

// 跨平台地图应用枚举
enum class MapAppType(val appNameRes: StringResource) {
    APPLE(Res.string.map_app_apple),
    AMAP(Res.string.map_app_amap),
    BAIDU(Res.string.map_app_baidu),
    TENCENT(Res.string.map_app_tencent)
}

// 地图应用展示条目（含可选应用图标）
data class PlatformMapAppItem(
    val type: MapAppType,
    val icon: ImageBitmap? = null
)

// 外部链接、剪贴板、Toast 与地图导航平台处理器接口
interface PlatformExternalHandler {
    fun openBrowser(url: String): Boolean
    fun copyToClipboard(text: String, label: String = "Text")
    fun joinQQGroup(key: String): Boolean
    fun showToast(message: String)
    fun getAvailableMapApps(): List<PlatformMapAppItem>
    fun openMapWalkingNavigation(type: MapAppType, destination: String): Boolean
}

// 跨平台外部交互与地图导航门面
object PlatformExternalActions : PlatformExternalHandler {
    private const val SCHOOL_NAME = "重庆科技大学"
    private const val CAMPUS_HUXI = CourseLocationFormatter.CAMPUS_HUXI

    var delegate: PlatformExternalHandler = NoOpPlatformExternalHandler

    // 判断地点是否可导航
    private fun canNavigate(position: String): Boolean {
        val trimmed = position.trim()
        return trimmed.isNotBlank() && trimmed != "待定" && !trimmed.contains("未安排")
    }

    // 构造步行导航目的地：仅虎溪校区解析楼栋，其余保持原始文本
    fun buildWalkingDestination(position: String): String? {
        if (!canNavigate(position)) return null
        val trimmed = position.trim()
        if (trimmed.contains(SCHOOL_NAME)) return trimmed

        val parsed = CourseLocationFormatter.parse(trimmed)
        if (parsed.campus != CAMPUS_HUXI) {
            return "$SCHOOL_NAME $trimmed"
        }

        val target = parsed.building.ifBlank { parsed.room.ifBlank { parsed.detail } }
        return listOf(SCHOOL_NAME, parsed.campus, target)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    override fun openBrowser(url: String): Boolean = delegate.openBrowser(url)
    override fun copyToClipboard(text: String, label: String) = delegate.copyToClipboard(text, label)
    override fun joinQQGroup(key: String): Boolean = delegate.joinQQGroup(key)
    override fun showToast(message: String) = delegate.showToast(message)
    override fun getAvailableMapApps(): List<PlatformMapAppItem> = delegate.getAvailableMapApps()
    override fun openMapWalkingNavigation(type: MapAppType, destination: String): Boolean =
        delegate.openMapWalkingNavigation(type, destination)
}

private object NoOpPlatformExternalHandler : PlatformExternalHandler {
    override fun openBrowser(url: String): Boolean = false
    override fun copyToClipboard(text: String, label: String) = Unit
    override fun joinQQGroup(key: String): Boolean = false
    override fun showToast(message: String) = Unit
    override fun getAvailableMapApps(): List<PlatformMapAppItem> = emptyList()
    override fun openMapWalkingNavigation(type: MapAppType, destination: String): Boolean = false
}
