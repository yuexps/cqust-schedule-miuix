package top.msfxp.schedule.ui.settings.subscreens

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import top.msfxp.schedule.R
import top.msfxp.schedule.ui.settings.SettingsViewModel
import top.msfxp.schedule.util.PermissionHelper
import top.yukonga.miuix.kmp.basic.SnackbarHostState

// Android 端处理通知与精确闹钟权限，页面内容由 shared 提供。
@Composable
fun PreClassReminderScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val settings = uiState.appSettings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val feedback = AndroidReminderFeedback(
        snackbarHostState = snackbarHostState,
        scope = scope,
        messages = AndroidReminderMessages(
            notificationPermissionNotice = stringResource(R.string.reminder_permission_notice_toast),
            exactAlarmPermissionNotice = stringResource(R.string.reminder_test_alarm_permission_toast),
            testSent = stringResource(R.string.reminder_test_sent_toast),
            testDelayed = stringResource(R.string.reminder_test_delayed_scheduled_toast)
        )
    )
    val permissions = rememberAndroidReminderPermissions(context, viewModel, feedback)

    PreClassReminderContent(
        settings = settings,
        callbacks = createAndroidReminderCallbacks(onBack, viewModel, permissions, feedback),
        presentation = PreClassReminderPresentation(
            hintText = stringResource(R.string.reminder_hint_text)
        ),
        snackbarHostState = snackbarHostState
    )
}

private fun createAndroidReminderCallbacks(
    onBack: () -> Unit,
    viewModel: SettingsViewModel,
    permissions: AndroidReminderPermissions,
    feedback: AndroidReminderFeedback
): PreClassReminderCallbacks = PreClassReminderCallbacks(
    onBack = onBack,
    onReminderEnabledChange = { enable ->
        if (enable) {
            if (permissions.isNotificationEnabled) viewModel.updateReminderEnabled(true)
            else permissions.requestForToggle()
        } else {
            permissions.clearPendingEnable()
            viewModel.updateReminderEnabled(false)
        }
    },
    onRemindMinutesChange = viewModel::updateRemindBeforeMinutes,
    onImmediateTest = {
        if (permissions.isNotificationEnabled) {
            viewModel.sendTestNotification()
            feedback.show(feedback.messages.testSent)
        } else {
            permissions.requestForTest()
        }
    },
    onDelayedTest = {
        if (!permissions.isNotificationEnabled) {
            permissions.requestForTest()
        } else if (!permissions.isExactAlarmEnabled) {
            feedback.show(feedback.messages.exactAlarmPermissionNotice)
            permissions.openExactAlarmSettings()
        } else {
            viewModel.scheduleDelayed30sTestNotification()
            feedback.show(feedback.messages.testDelayed)
        }
    }
)

private data class AndroidReminderMessages(
    val notificationPermissionNotice: String,
    val exactAlarmPermissionNotice: String,
    val testSent: String,
    val testDelayed: String
)

private class AndroidReminderFeedback(
    private val snackbarHostState: SnackbarHostState,
    private val scope: CoroutineScope,
    val messages: AndroidReminderMessages
) {
    fun show(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }
}

private data class AndroidReminderPermissions(
    val isNotificationEnabled: Boolean,
    val isExactAlarmEnabled: Boolean,
    val requestForToggle: () -> Unit,
    val requestForTest: () -> Unit,
    val clearPendingEnable: () -> Unit,
    val openExactAlarmSettings: () -> Unit
)

@Composable
private fun rememberAndroidReminderPermissions(
    context: Context,
    viewModel: SettingsViewModel,
    feedback: AndroidReminderFeedback
): AndroidReminderPermissions {
    var refreshTrigger by remember { mutableIntStateOf(0) }
    val pendingEnableOnResume = rememberPendingReminderEnableOnResume(
        context = context,
        viewModel = viewModel,
        feedback = feedback,
        onRefresh = { refreshTrigger++ }
    )

    val isNotificationEnabled = remember(refreshTrigger) {
        PermissionHelper.isNotificationGranted(context)
    }
    val isExactAlarmEnabled = remember(refreshTrigger) {
        PermissionHelper.isExactAlarmGranted(context)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        refreshTrigger++
        if (granted) viewModel.updateReminderEnabled(true)
        else feedback.show(feedback.messages.notificationPermissionNotice)
    }

    return AndroidReminderPermissions(
        isNotificationEnabled = isNotificationEnabled,
        isExactAlarmEnabled = isExactAlarmEnabled,
        requestForToggle = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                pendingEnableOnResume.value = true
                PermissionHelper.openNotificationSettings(context)
            }
        },
        requestForTest = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                PermissionHelper.openNotificationSettings(context)
            }
        },
        clearPendingEnable = { pendingEnableOnResume.value = false },
        openExactAlarmSettings = { PermissionHelper.openExactAlarmSettings(context) }
    )
}

@Composable
private fun rememberPendingReminderEnableOnResume(
    context: Context,
    viewModel: SettingsViewModel,
    feedback: AndroidReminderFeedback,
    onRefresh: () -> Unit
): MutableState<Boolean> {
    val lifecycleOwner = LocalLifecycleOwner.current
    val pendingEnableOnResume = remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRefresh()
                if (pendingEnableOnResume.value) {
                    if (PermissionHelper.isNotificationGranted(context)) {
                        viewModel.updateReminderEnabled(true)
                    } else {
                        feedback.show(feedback.messages.notificationPermissionNotice)
                    }
                    pendingEnableOnResume.value = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return pendingEnableOnResume
}
