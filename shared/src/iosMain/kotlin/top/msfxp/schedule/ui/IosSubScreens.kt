package top.msfxp.schedule.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import top.msfxp.schedule.data.model.CourseColor
import top.msfxp.schedule.data.model.DefaultCourseColors
import top.msfxp.schedule.data.model.parseCoursePaletteJson
import top.msfxp.schedule.data.model.serializeCoursePalette
import top.msfxp.schedule.data.repository.SettingsRepository
import top.msfxp.schedule.platform.PlatformWallpaperStorage
import top.msfxp.schedule.shared.resources.*
import top.msfxp.schedule.ui.settings.SettingsViewModel
import top.msfxp.schedule.ui.settings.subscreens.ColorPickerDialog
import top.msfxp.schedule.ui.settings.subscreens.WallpaperCropDialog
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

// iOS 端个性化设置界面（课程卡片透明度与 16 色调色板自定义）
@Composable
fun IosPersonalizationScreen(
    onBack: () -> Unit,
    settingsRepository: SettingsRepository = koinInject(),
    wallpaperStorage: PlatformWallpaperStorage = koinInject()
) {
    val settings by settingsRepository.appSettingsFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val courseColors = remember(settings.customCourseColorsJson) {
        parseCoursePaletteJson(settings.customCourseColorsJson)
    }
    var localCardAlpha by remember(settings.courseCardAlpha) {
        mutableFloatStateOf(settings.courseCardAlpha)
    }
    var localWallpaperDim by remember(settings.wallpaperMaskDim) {
        mutableFloatStateOf(settings.wallpaperMaskDim)
    }
    var wallpaperTip by remember { mutableStateOf<StringResource?>(null) }
    var cropSourceBytes by remember { mutableStateOf<ByteArray?>(null) }
    var cropPreview by remember { mutableStateOf<ImageBitmap?>(null) }
    val hasWallpaper = remember(settings.hasCustomWallpaper) {
        settings.hasCustomWallpaper && wallpaperStorage.hasWallpaperFile()
    }
    var editingSlotIndex by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(Res.string.personalization_title),
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.padding(start = 12.dp)
                    ) {
                        Icon(
                            imageVector = MiuixIcons.ChevronBackward,
                            contentDescription = stringResource(Res.string.common_back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    SliderPreference(
                        title = stringResource(
                            Res.string.personalization_card_alpha_title,
                            (localCardAlpha * 100).roundToInt()
                        ),
                        value = localCardAlpha,
                        valueRange = 0.4f..1.0f,
                        onValueChange = { value ->
                            localCardAlpha = value
                            scope.launch { settingsRepository.updateCourseCardAlpha(value) }
                        }
                    )
                }
            }

            item {
                SmallTitle(text = stringResource(Res.string.personalization_section_wallpaper))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        ArrowPreference(
                            title = stringResource(
                                if (hasWallpaper) Res.string.personalization_wallpaper_change
                                else Res.string.personalization_wallpaper_select
                            ),
                            summary = stringResource(
                                if (hasWallpaper) Res.string.personalization_wallpaper_custom_status
                                else Res.string.personalization_wallpaper_default_status
                            ),
                            onClick = {
                                launchIosImagePicker { bytes ->
                                    if (bytes == null) return@launchIosImagePicker
                                    scope.launch {
                                        val preview = withContext(Dispatchers.Default) { decodeIosImageBitmap(bytes) }
                                        if (preview == null) {
                                            wallpaperTip = Res.string.personalization_wallpaper_set_fail
                                        } else {
                                            cropSourceBytes = bytes
                                            cropPreview = preview
                                        }
                                    }
                                }
                            }
                        )

                        if (hasWallpaper) {
                            ArrowPreference(
                                title = stringResource(Res.string.personalization_wallpaper_clear),
                                summary = "",
                                onClick = {
                                    scope.launch {
                                        if (settingsRepository.clearWallpaper()) {
                                            wallpaperTip = Res.string.personalization_wallpaper_clear_success
                                        }
                                    }
                                }
                            )
                            SliderPreference(
                                title = stringResource(
                                    Res.string.personalization_wallpaper_mask_dim_title,
                                    (localWallpaperDim * 100).roundToInt()
                                ),
                                value = localWallpaperDim,
                                valueRange = 0f..0.85f,
                                onValueChange = { value ->
                                    localWallpaperDim = value
                                    scope.launch { settingsRepository.updateWallpaperMaskDim(value) }
                                }
                            )
                        }

                        val tip = wallpaperTip
                        if (tip != null) {
                            LaunchedEffect(tip) {
                                delay(2000)
                                wallpaperTip = null
                            }
                            Text(
                                text = stringResource(tip),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
            }

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
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        for (row in 0 until 2) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                for (col in 0 until 8) {
                                    val idx = row * 8 + col
                                    val colorItem = courseColors.getOrElse(idx) { DefaultCourseColors[0] }
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(colorItem.light)
                                            .border(1.dp, Color.Black.copy(alpha = 0.1f), CircleShape)
                                            .clickable { editingSlotIndex = idx },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "${idx + 1}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        TextButton(
                            text = stringResource(Res.string.personalization_color_reset_slot),
                            onClick = { scope.launch { settingsRepository.resetCourseColors() } },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColors()
                        )
                    }
                }
            }
        }
    }

    val activeSlot = editingSlotIndex
    ColorPickerDialog(
        show = activeSlot != null,
        slotIndex = activeSlot ?: 0,
        initialColor = courseColors.getOrElse(activeSlot ?: 0) { DefaultCourseColors[0] }.light,
        defaultColor = DefaultCourseColors.getOrElse(activeSlot ?: 0) { DefaultCourseColors[0] },
        onDismissRequest = { editingSlotIndex = null },
        onConfirm = { pickedColor ->
            val slot = activeSlot ?: return@ColorPickerDialog
            val updated = courseColors.toMutableList()
            if (slot in updated.indices) {
                updated[slot] = CourseColor.fromColor(pickedColor)
                scope.launch {
                    settingsRepository.updateCustomCourseColors(serializeCoursePalette(updated))
                }
            }
            editingSlotIndex = null
        }
    )

    // 壁纸区域框选
    val previewImage = cropPreview
    val sourceBytes = cropSourceBytes
    if (previewImage != null && sourceBytes != null) {
        WallpaperCropDialog(
            sourceImage = previewImage,
            onConfirm = { region ->
                cropPreview = null
                cropSourceBytes = null
                scope.launch {
                    val cropped = withContext(Dispatchers.Default) {
                        cropIosImageToJpegBytes(sourceBytes, region)
                    }
                    val saved = cropped != null && settingsRepository.saveWallpaperBytes(cropped)
                    wallpaperTip = if (saved) {
                        Res.string.personalization_wallpaper_set_success
                    } else {
                        Res.string.personalization_wallpaper_set_fail
                    }
                }
            },
            onDismissRequest = {
                cropPreview = null
                cropSourceBytes = null
            }
        )
    }
}

// iOS 端课前提醒设置界面（基于 UNUserNotificationCenter）
@Composable
fun IosPreClassReminderScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val settings = uiState.appSettings
    val minuteOptions = listOf(5, 10, 15, 20, 30)

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(Res.string.reminder_title),
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.padding(start = 12.dp)
                    ) {
                        Icon(
                            imageVector = MiuixIcons.ChevronBackward,
                            contentDescription = stringResource(Res.string.common_back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        SwitchPreference(
                            title = stringResource(Res.string.reminder_switch_title),
                            summary = stringResource(Res.string.reminder_switch_desc),
                            checked = settings.reminderEnabled,
                            onCheckedChange = { viewModel.updateReminderEnabled(it) }
                        )

                        if (settings.reminderEnabled) {
                            ArrowPreference(
                                title = stringResource(Res.string.reminder_time_title),
                                summary = stringResource(
                                    Res.string.calendar_sync_remind_format,
                                    settings.remindBeforeMinutes
                                ),
                                onClick = {
                                    val nextIdx = (minuteOptions.indexOf(settings.remindBeforeMinutes) + 1) % minuteOptions.size
                                    viewModel.updateRemindBeforeMinutes(minuteOptions[nextIdx])
                                }
                            )
                        }
                    }
                }
            }

            if (settings.showTestFeatures) {
                item {
                    SmallTitle(text = stringResource(Res.string.perm_show_test_features_title))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            ArrowPreference(
                                title = stringResource(Res.string.reminder_test_immediate_title),
                                summary = stringResource(Res.string.reminder_test_immediate_desc),
                                onClick = { viewModel.sendTestNotification() }
                            )
                            ArrowPreference(
                                title = stringResource(Res.string.reminder_test_delayed_title),
                                summary = stringResource(Res.string.reminder_test_delayed_desc),
                                onClick = { viewModel.scheduleDelayed30sTestNotification() }
                            )
                        }
                    }
                }
            }
        }
    }
}
