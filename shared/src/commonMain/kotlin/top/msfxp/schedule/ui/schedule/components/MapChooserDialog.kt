package top.msfxp.schedule.ui.schedule.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import top.msfxp.schedule.platform.PlatformExternalActions
import top.msfxp.schedule.shared.resources.*
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.MapAlbum
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

// 地图步行导航选择弹窗
@Composable
fun MapChooserDialog(
    show: Boolean,
    rawPosition: String,
    onDismissRequest: () -> Unit
) {
    if (!show) return
    val destination = remember(rawPosition) {
        PlatformExternalActions.buildWalkingDestination(rawPosition)
    } ?: return
    val availableApps = remember(rawPosition) {
        PlatformExternalActions.getAvailableMapApps()
    }

    WindowDialog(
        show = true,
        title = stringResource(Res.string.map_navigate_title),
        summary = stringResource(Res.string.map_navigate_destination_label) + ": " + destination,
        onDismissRequest = onDismissRequest
    ) {
        val dismiss = LocalDismissState.current

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            availableApps.forEachIndexed { index, appItem ->
                val isFirst = index == 0
                val isLast = index == availableApps.size - 1

                val itemShape = when {
                    isFirst && isLast -> RoundedCornerShape(16.dp)
                    isFirst -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    isLast -> RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
                    else -> RoundedCornerShape(0.dp)
                }

                val appIcon = appItem.icon
                val appName = stringResource(appItem.type.appNameRes)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(itemShape)
                        .clickable {
                            dismiss?.invoke()
                            onDismissRequest()
                            PlatformExternalActions.openMapWalkingNavigation(appItem.type, destination)
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (appIcon != null) {
                        Image(
                            bitmap = appIcon,
                            contentDescription = appName,
                            modifier = Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                    } else {
                        Icon(
                            imageVector = MiuixIcons.MapAlbum,
                            contentDescription = appName,
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Text(
                        text = appName,
                        style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Medium),
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
