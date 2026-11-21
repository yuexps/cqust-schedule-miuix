package top.msfxp.schedule.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import top.msfxp.schedule.shared.resources.Res
import top.msfxp.schedule.shared.resources.perm_show_test_features_title
import top.msfxp.schedule.shared.resources.reminder_hint_text_ios
import top.msfxp.schedule.shared.resources.reminder_test_delayed_scheduled_toast
import top.msfxp.schedule.shared.resources.reminder_test_sent_toast
import top.msfxp.schedule.ui.settings.SettingsViewModel
import top.msfxp.schedule.ui.settings.subscreens.CalendarSyncContent
import top.msfxp.schedule.ui.settings.subscreens.PreClassReminderCallbacks
import top.msfxp.schedule.ui.settings.subscreens.PreClassReminderContent
import top.msfxp.schedule.ui.settings.subscreens.PreClassReminderPresentation
import top.yukonga.miuix.kmp.basic.SnackbarHostState

@Composable
fun IosPreClassReminderScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val testSentToast = stringResource(Res.string.reminder_test_sent_toast)
    val testDelayedToast = stringResource(Res.string.reminder_test_delayed_scheduled_toast)

    PreClassReminderContent(
        settings = uiState.appSettings,
        callbacks = PreClassReminderCallbacks(
            onBack = onBack,
            onReminderEnabledChange = viewModel::updateReminderEnabled,
            onRemindMinutesChange = viewModel::updateRemindBeforeMinutes,
            onImmediateTest = {
                viewModel.sendTestNotification()
                scope.launch { snackbarHostState.showSnackbar(testSentToast) }
            },
            onDelayedTest = {
                viewModel.scheduleDelayed30sTestNotification()
                scope.launch { snackbarHostState.showSnackbar(testDelayedToast) }
            }
        ),
        presentation = PreClassReminderPresentation(
            hintText = stringResource(Res.string.reminder_hint_text_ios),
            testSectionTitle = stringResource(Res.string.perm_show_test_features_title)
        ),
        snackbarHostState = snackbarHostState
    )
}

@Composable
fun IosCalendarSyncScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var permissionDeniedCount by remember { mutableIntStateOf(0) }

    CalendarSyncContent(
        settings = uiState.appSettings,
        permissionDeniedCount = permissionDeniedCount,
        onBack = onBack,
        onAutoSyncChange = { enabled ->
            if (enabled) {
                viewModel.ensureCalendarAccess { hasAccess ->
                    if (hasAccess) {
                        viewModel.updateAutoSyncToCalendar(true)
                    } else {
                        permissionDeniedCount++
                    }
                }
            } else {
                viewModel.updateAutoSyncToCalendar(false)
            }
        },
        onRemindMinutesChange = viewModel::updateCalendarRemindBeforeMinutes,
        onSyncNow = viewModel::syncToCalendarManually,
        onDeleteCalendar = viewModel::deleteCalendarSchedule
    )
}
