package top.msfxp.schedule.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import top.msfxp.schedule.shared.resources.Res
import top.msfxp.schedule.shared.resources.calendar_sync_remind_format
import top.msfxp.schedule.shared.resources.common_back
import top.msfxp.schedule.shared.resources.perm_show_test_features_title
import top.msfxp.schedule.shared.resources.reminder_hint_text_ios
import top.msfxp.schedule.shared.resources.reminder_switch_desc
import top.msfxp.schedule.shared.resources.reminder_switch_title
import top.msfxp.schedule.shared.resources.reminder_test_delayed_desc
import top.msfxp.schedule.shared.resources.reminder_test_delayed_scheduled_toast
import top.msfxp.schedule.shared.resources.reminder_test_delayed_title
import top.msfxp.schedule.shared.resources.reminder_test_immediate_desc
import top.msfxp.schedule.shared.resources.reminder_test_immediate_title
import top.msfxp.schedule.shared.resources.reminder_test_sent_toast
import top.msfxp.schedule.shared.resources.reminder_time_title
import top.msfxp.schedule.shared.resources.reminder_title
import top.msfxp.schedule.ui.settings.SettingsViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val IosRemindMinutesOptions = listOf(5, 10, 15, 20, 30, 45, 60)

// iOS 端课前提醒设置界面（基于 UNUserNotificationCenter）
@Composable
fun IosPreClassReminderScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val settings = uiState.appSettings
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val remindMinutesLabels = IosRemindMinutesOptions.map {
        stringResource(Res.string.calendar_sync_remind_format, it)
    }
    val currentMinutesIndex = remember(settings.remindBeforeMinutes) {
        val idx = IosRemindMinutesOptions.indexOf(settings.remindBeforeMinutes)
        if (idx >= 0) idx else 2
    }
    val testSentToast = stringResource(Res.string.reminder_test_sent_toast)
    val testDelayedToast = stringResource(Res.string.reminder_test_delayed_scheduled_toast)

    Scaffold(
        snackbarHost = { SnackbarHost(state = snackbarHostState) },
        topBar = {
            SmallTopAppBar(
                title = stringResource(Res.string.reminder_title),
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
            // 提醒开关与提前时间下拉选择
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
                            OverlayDropdownPreference(
                                title = stringResource(Res.string.reminder_time_title),
                                items = remindMinutesLabels,
                                selectedIndex = currentMinutesIndex,
                                onSelectedIndexChange = { index ->
                                    val minutes = IosRemindMinutesOptions.getOrElse(index) { 15 }
                                    viewModel.updateRemindBeforeMinutes(minutes)
                                }
                            )
                        }
                    }
                }
            }

            // 模拟测试卡片
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
                                onClick = {
                                    viewModel.sendTestNotification()
                                    scope.launch { snackbarHostState.showSnackbar(testSentToast) }
                                }
                            )
                            ArrowPreference(
                                title = stringResource(Res.string.reminder_test_delayed_title),
                                summary = stringResource(Res.string.reminder_test_delayed_desc),
                                onClick = {
                                    viewModel.scheduleDelayed30sTestNotification()
                                    scope.launch { snackbarHostState.showSnackbar(testDelayedToast) }
                                }
                            )
                        }
                    }
                }
            }

            // 底部权限说明
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = MiuixIcons.Info,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier
                            .size(14.dp)
                            .offset(y = 1.5.dp)
                    )
                    Text(
                        text = stringResource(Res.string.reminder_hint_text_ios),
                        style = MiuixTheme.textStyles.footnote2.copy(lineHeight = 18.sp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
