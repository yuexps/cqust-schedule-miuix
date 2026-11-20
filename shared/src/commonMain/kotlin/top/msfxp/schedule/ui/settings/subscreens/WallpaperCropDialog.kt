package top.msfxp.schedule.ui.settings.subscreens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.jetbrains.compose.resources.stringResource
import top.msfxp.schedule.shared.resources.Res
import top.msfxp.schedule.shared.resources.common_cancel
import top.msfxp.schedule.shared.resources.common_confirm
import top.msfxp.schedule.shared.resources.crop_wallpaper_title
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.basic.Close
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.max
import kotlin.math.roundToInt

// 壁纸裁剪区域（相对源图的归一化坐标，取值 0..1）
data class WallpaperCropRegion(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
)

// 课表壁纸区域框选器全屏弹窗（输出归一化裁剪区域，由各平台执行实际裁切）
@Composable
fun WallpaperCropDialog(
    sourceImage: ImageBitmap,
    onConfirm: (WallpaperCropRegion) -> Unit,
    onDismissRequest: () -> Unit,
    dialogProperties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false)
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = dialogProperties
    ) {
        var triggerConfirm by remember { mutableStateOf<(() -> Unit)?>(null) }

        // 全屏沉浸式容器
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // 1. 底层：全屏取景视窗（阴影遮罩完全延伸至屏幕四边）
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds(),
                contentAlignment = Alignment.Center
            ) {
                val availableWidth = constraints.maxWidth.toFloat()
                val availableHeight = constraints.maxHeight.toFloat()
                val density = LocalDensity.current
                val statusBarPx = WindowInsets.statusBars.getTop(density).toFloat()
                val bottomNavPx = WindowInsets.navigationBars.getBottom(density).toFloat()
                val topBarHeightPx = with(density) { 56.dp.toPx() }
                val topPaddingPx = with(density) { 16.dp.toPx() }
                val bottomPaddingPx = with(density) { 20.dp.toPx() }

                // 舒适可视工作区（上下留出优雅的呼吸边距）
                val safeTop = statusBarPx + topBarHeightPx + topPaddingPx
                val safeBottom = availableHeight - bottomNavPx - bottomPaddingPx
                val workAreaHeight = max(100f, safeBottom - safeTop)

                // 裁剪框比例按整窗尺寸计算（含系统栏），保证与壁纸 Cover 显示比例一致
                val windowSize = LocalWindowInfo.current.containerSize
                val screenRatio = remember(availableWidth, availableHeight, windowSize) {
                    val windowWidth = max(windowSize.width.toFloat(), availableWidth)
                    val windowHeight = max(windowSize.height.toFloat(), availableHeight)
                    if (windowHeight > 0f) windowWidth / windowHeight else 1f
                }
                val maxTargetWidth = availableWidth * 0.82f
                val maxTargetHeight = workAreaHeight * 0.96f

                val cropWidth: Float
                val cropHeight: Float
                if (maxTargetWidth / screenRatio <= maxTargetHeight) {
                    cropWidth = maxTargetWidth
                    cropHeight = maxTargetWidth / screenRatio
                } else {
                    cropHeight = maxTargetHeight
                    cropWidth = maxTargetHeight * screenRatio
                }

                // 垂直居中于舒适工作区
                val cropRect = remember(availableWidth, safeTop, safeBottom, cropWidth, cropHeight) {
                    val left = (availableWidth - cropWidth) / 2f
                    val centerY = (safeTop + safeBottom) / 2f
                    val top = centerY - cropHeight / 2f
                    Rect(left, top, left + cropWidth, top + cropHeight)
                }

                // 最小填充缩放比：严格保证底图不露白
                val minScale = remember(sourceImage, cropWidth, cropHeight) {
                    max(cropWidth / sourceImage.width.toFloat(), cropHeight / sourceImage.height.toFloat())
                }

                var scale by remember(minScale) { mutableFloatStateOf(minScale) }
                var offset by remember(minScale) { mutableStateOf(Offset.Zero) }

                LaunchedEffect(minScale) {
                    if (scale < minScale) {
                        scale = minScale
                        offset = Offset.Zero
                    }
                }

                // 手势边界约束计算：保证底图边缘绝不缩入裁剪框内部
                fun clampOffset(rawOffset: Offset, curScale: Float): Offset {
                    val imgW = sourceImage.width * curScale
                    val imgH = sourceImage.height * curScale
                    val maxPanX = max(0f, (imgW - cropWidth) / 2f)
                    val maxPanY = max(0f, (imgH - cropHeight) / 2f)
                    return Offset(
                        x = rawOffset.x.coerceIn(-maxPanX, maxPanX),
                        y = rawOffset.y.coerceIn(-maxPanY, maxPanY)
                    )
                }

                // 手势监听与绘制（边界严密截断）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .pointerInput(minScale) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val nextScale = (scale * zoom).coerceIn(minScale, minScale * 5f)
                                scale = nextScale
                                offset = clampOffset(offset + pan, nextScale)
                            }
                        }
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds()
                    ) {
                        // 1. 绘制底层图片（以 cropRect.center 为绝对基准对齐）
                        val currentImgW = sourceImage.width * scale
                        val currentImgH = sourceImage.height * scale
                        val imgLeft = cropRect.center.x - currentImgW / 2f + offset.x
                        val imgTop = cropRect.center.y - currentImgH / 2f + offset.y

                        drawImage(
                            image = sourceImage,
                            dstOffset = IntOffset(imgLeft.roundToInt(), imgTop.roundToInt()),
                            dstSize = IntSize(currentImgW.roundToInt(), currentImgH.roundToInt())
                        )

                        // 2. 绘制四周半透明黑色遮罩（紧贴屏幕物理四边，消除一切生硬断层）
                        val scrimColor = Color.Black.copy(alpha = 0.72f)
                        drawRect(
                            color = scrimColor,
                            topLeft = Offset.Zero,
                            size = Size(availableWidth, cropRect.top)
                        )
                        drawRect(
                            color = scrimColor,
                            topLeft = Offset(0f, cropRect.bottom),
                            size = Size(availableWidth, availableHeight - cropRect.bottom)
                        )
                        drawRect(
                            color = scrimColor,
                            topLeft = Offset(0f, cropRect.top),
                            size = Size(cropRect.left, cropHeight)
                        )
                        drawRect(
                            color = scrimColor,
                            topLeft = Offset(cropRect.right, cropRect.top),
                            size = Size(availableWidth - cropRect.right, cropHeight)
                        )

                        // 3. 绘制裁剪框细边线
                        drawRect(
                            color = Color.White.copy(alpha = 0.88f),
                            topLeft = cropRect.topLeft,
                            size = cropRect.size,
                            style = Stroke(width = 1.5.dp.toPx())
                        )

                        // 4. 绘制九宫格参考细线
                        val thirdW = cropWidth / 3f
                        val thirdH = cropHeight / 3f
                        val gridColor = Color.White.copy(alpha = 0.20f)
                        val gridStroke = Stroke(width = 1.dp.toPx())

                        drawLine(gridColor, Offset(cropRect.left + thirdW, cropRect.top), Offset(cropRect.left + thirdW, cropRect.bottom), strokeWidth = gridStroke.width)
                        drawLine(gridColor, Offset(cropRect.left + thirdW * 2, cropRect.top), Offset(cropRect.left + thirdW * 2, cropRect.bottom), strokeWidth = gridStroke.width)
                        drawLine(gridColor, Offset(cropRect.left, cropRect.top + thirdH), Offset(cropRect.right, cropRect.top + thirdH), strokeWidth = gridStroke.width)
                        drawLine(gridColor, Offset(cropRect.left, cropRect.top + thirdH * 2), Offset(cropRect.right, cropRect.top + thirdH * 2), strokeWidth = gridStroke.width)

                        // 5. 绘制四角 L 型标记
                        val cornerLen = 18.dp.toPx()
                        val cornerStroke = Stroke(width = 3.dp.toPx())
                        val cornerColor = Color.White

                        drawLine(cornerColor, Offset(cropRect.left, cropRect.top), Offset(cropRect.left + cornerLen, cropRect.top), strokeWidth = cornerStroke.width)
                        drawLine(cornerColor, Offset(cropRect.left, cropRect.top), Offset(cropRect.left, cropRect.top + cornerLen), strokeWidth = cornerStroke.width)
                        drawLine(cornerColor, Offset(cropRect.right, cropRect.top), Offset(cropRect.right - cornerLen, cropRect.top), strokeWidth = cornerStroke.width)
                        drawLine(cornerColor, Offset(cropRect.right, cropRect.top), Offset(cropRect.right, cropRect.top + cornerLen), strokeWidth = cornerStroke.width)
                        drawLine(cornerColor, Offset(cropRect.left, cropRect.bottom), Offset(cropRect.left + cornerLen, cropRect.bottom), strokeWidth = cornerStroke.width)
                        drawLine(cornerColor, Offset(cropRect.left, cropRect.bottom), Offset(cropRect.left, cropRect.bottom - cornerLen), strokeWidth = cornerStroke.width)
                        drawLine(cornerColor, Offset(cropRect.right, cropRect.bottom), Offset(cropRect.right - cornerLen, cropRect.bottom), strokeWidth = cornerStroke.width)
                        drawLine(cornerColor, Offset(cropRect.right, cropRect.bottom), Offset(cropRect.right, cropRect.bottom - cornerLen), strokeWidth = cornerStroke.width)
                    }
                }

                // 归一化裁剪区域生成器
                val doCrop: () -> Unit = {
                    val currentImgW = sourceImage.width * scale
                    val currentImgH = sourceImage.height * scale
                    val imgLeft = cropRect.center.x - currentImgW / 2f + offset.x
                    val imgTop = cropRect.center.y - currentImgH / 2f + offset.y

                    val left = ((cropRect.left - imgLeft) / scale / sourceImage.width).coerceIn(0f, 1f)
                    val top = ((cropRect.top - imgTop) / scale / sourceImage.height).coerceIn(0f, 1f)
                    val width = (cropWidth / scale / sourceImage.width).coerceIn(0f, 1f - left)
                    val height = (cropHeight / scale / sourceImage.height).coerceIn(0f, 1f - top)

                    onConfirm(WallpaperCropRegion(left = left, top = top, width = width, height = height))
                }

                DisposableEffect(doCrop) {
                    triggerConfirm = doCrop
                    onDispose { triggerConfirm = null }
                }
            }

            // 2. 顶层：悬浮顶部导航栏（位于最高层级，永远不会被底图遮盖）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.60f),
                                Color.Transparent
                            )
                        )
                    )
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismissRequest) {
                        Icon(
                            imageVector = MiuixIcons.Basic.Close,
                            contentDescription = stringResource(Res.string.common_cancel),
                            tint = Color.White
                        )
                    }

                    Text(
                        text = stringResource(Res.string.crop_wallpaper_title),
                        style = MiuixTheme.textStyles.title3.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )

                    IconButton(onClick = { triggerConfirm?.invoke() }) {
                        Icon(
                            imageVector = MiuixIcons.Basic.Check,
                            contentDescription = stringResource(Res.string.common_confirm),
                            tint = MiuixTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
