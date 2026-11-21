package top.msfxp.schedule.ui.settings.subscreens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import top.msfxp.schedule.data.model.DefaultCourseColors
import top.msfxp.schedule.platform.PlatformCropSource
import top.msfxp.schedule.platform.rememberPlatformWallpaperPicker
import top.msfxp.schedule.platform.wallpaperCropDialogProperties
import top.msfxp.schedule.shared.resources.Res
import top.msfxp.schedule.shared.resources.common_back
import top.msfxp.schedule.shared.resources.crop_wallpaper_loading
import top.msfxp.schedule.shared.resources.personalization_card_alpha_title
import top.msfxp.schedule.shared.resources.personalization_section_palette
import top.msfxp.schedule.shared.resources.personalization_section_wallpaper
import top.msfxp.schedule.shared.resources.personalization_title
import top.msfxp.schedule.shared.resources.personalization_wallpaper_change
import top.msfxp.schedule.shared.resources.personalization_wallpaper_clear
import top.msfxp.schedule.shared.resources.personalization_wallpaper_custom_status
import top.msfxp.schedule.shared.resources.personalization_wallpaper_default_status
import top.msfxp.schedule.shared.resources.personalization_wallpaper_mask_dim_title
import top.msfxp.schedule.shared.resources.personalization_wallpaper_select
import top.msfxp.schedule.ui.settings.PersonalizationViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

// 跨平台通用个性化设置界面（课表壁纸与 16 色课程调色板）
@Composable
fun PersonalizationScreen(
    onBack: () -> Unit,
    viewModel: PersonalizationViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val wallpaperBitmap by viewModel.wallpaperBitmapFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var editingSlotIndex by remember { mutableStateOf<Int?>(null) }
    var cropSource by remember { mutableStateOf<PlatformCropSource?>(null) }
    var isDecodingCropImage by remember { mutableStateOf(false) }

    var localMaskDim by remember { mutableFloatStateOf(uiState.wallpaperMaskDim) }
    var localCardAlpha by remember { mutableFloatStateOf(uiState.courseCardAlpha) }

    LaunchedEffect(uiState.hasCustomWallpaper) {
        localMaskDim = uiState.wallpaperMaskDim
        localCardAlpha = uiState.courseCardAlpha
    }

    val wallpaperPicker = rememberPlatformWallpaperPicker(
        onDecodingStateChange = { isDecodingCropImage = it },
        onImageReady = { cropSource = it },
        onError = { viewModel.notifyWallpaperSetFailed() }
    )

    // 监听操作反馈提示
    LaunchedEffect(uiState.message) {
        uiState.message?.let { msg ->
            val text = getString(msg.res)
            scope.launch {
                snackbarHostState.showSnackbar(text)
            }
            viewModel.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(state = snackbarHostState) },
        topBar = {
            SmallTopAppBar(
                title = stringResource(Res.string.personalization_title),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.ChevronBackward,
                            contentDescription = stringResource(Res.string.common_back),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 分组一：课表壁纸设置
            item {
                SmallTitle(text = stringResource(Res.string.personalization_section_wallpaper))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 壁纸预览与状态行
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            val currentWallpaper = wallpaperBitmap
                            if (currentWallpaper != null) {
                                Image(
                                    bitmap = currentWallpaper,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(RoundedCornerShape(10.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MiuixTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = MiuixIcons.Image,
                                        contentDescription = null,
                                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = if (uiState.hasCustomWallpaper) {
                                        stringResource(Res.string.personalization_wallpaper_custom_status)
                                    } else {
                                        stringResource(Res.string.personalization_wallpaper_default_status)
                                    },
                                    style = MiuixTheme.textStyles.title3.copy(fontWeight = FontWeight.Bold),
                                    color = MiuixTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // 操作按钮组
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                colors = ButtonDefaults.buttonColorsPrimary(),
                                modifier = Modifier.weight(1f),
                                onClick = { wallpaperPicker.launchPickImage() }
                            ) {
                                Text(
                                    text = if (uiState.hasCustomWallpaper) {
                                        stringResource(Res.string.personalization_wallpaper_change)
                                    } else {
                                        stringResource(Res.string.personalization_wallpaper_select)
                                    }
                                )
                            }

                            if (uiState.hasCustomWallpaper) {
                                TextButton(
                                    text = stringResource(Res.string.personalization_wallpaper_clear),
                                    modifier = Modifier.weight(1f),
                                    onClick = { viewModel.clearWallpaper() }
                                )
                            }
                        }

                        // 壁纸微调滑块组
                        if (uiState.hasCustomWallpaper) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                SliderPreference(
                                    title = stringResource(
                                        Res.string.personalization_wallpaper_mask_dim_title,
                                        (localMaskDim * 100).roundToInt()
                                    ),
                                    value = localMaskDim,
                                    valueRange = 0f..0.85f,
                                    onValueChange = {
                                        localMaskDim = it
                                        viewModel.updateWallpaperMaskDim(it)
                                    }
                                )

                                SliderPreference(
                                    title = stringResource(
                                        Res.string.personalization_card_alpha_title,
                                        (localCardAlpha * 100).roundToInt()
                                    ),
                                    value = localCardAlpha,
                                    valueRange = 0.4f..1f,
                                    onValueChange = {
                                        localCardAlpha = it
                                        viewModel.updateCourseCardAlpha(it)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 分组二：课程配色（4x4 色块网格）
            item {
                SmallTitle(text = stringResource(Res.string.personalization_section_palette))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val colors = uiState.courseColors
                        for (row in 0 until 4) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                for (col in 0 until 4) {
                                    val idx = row * 4 + col
                                    val itemColor = colors.getOrNull(idx)?.light ?: Color.Gray

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(46.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(itemColor)
                                            .border(
                                                width = 1.dp,
                                                color = Color.White.copy(alpha = 0.35f),
                                                shape = RoundedCornerShape(12.dp)
                                            )
                                            .clickable { editingSlotIndex = idx },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "${idx + 1}",
                                            style = MiuixTheme.textStyles.footnote2.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 颜色修改抽屉（依托当前 Scaffold 宿主）
        val activeSlot = editingSlotIndex ?: 0
        ColorPickerDialog(
            show = editingSlotIndex != null,
            slotIndex = activeSlot,
            initialColor = uiState.courseColors.getOrNull(activeSlot)?.light ?: DefaultCourseColors[0].light,
            defaultColor = DefaultCourseColors.getOrElse(activeSlot) { DefaultCourseColors[0] },
            onDismissRequest = { editingSlotIndex = null },
            onConfirm = { chosenColor ->
                editingSlotIndex?.let { slot ->
                    viewModel.updateSlotColor(slot, chosenColor)
                }
            }
        )

        // 图像载入中提示弹窗
        OverlayDialog(
            show = isDecodingCropImage,
            title = stringResource(Res.string.crop_wallpaper_loading),
            onDismissRequest = {}
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(Res.string.crop_wallpaper_loading),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }

    // 壁纸区域框选全屏弹窗
    val activeCropSource = cropSource
    if (activeCropSource != null) {
        WallpaperCropDialog(
            sourceImage = activeCropSource.preview,
            onConfirm = { region ->
                cropSource = null
                scope.launch {
                    val bytes = wallpaperPicker.cropToJpegBytes(activeCropSource, region)
                    viewModel.saveCroppedWallpaper(bytes)
                }
            },
            onDismissRequest = { cropSource = null },
            dialogProperties = wallpaperCropDialogProperties()
        )
    }
}
