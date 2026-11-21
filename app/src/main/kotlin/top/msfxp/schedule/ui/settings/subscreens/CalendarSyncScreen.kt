package top.msfxp.schedule.ui.settings.subscreens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.koin.androidx.compose.koinViewModel
import top.msfxp.schedule.ui.settings.SettingsViewModel
import top.msfxp.schedule.util.PermissionHelper

// Android 日历同步入口负责申请系统日历权限，共享界面负责呈现与交互。
@Composable
fun CalendarSyncScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var pendingPermissionAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var permissionDeniedCount by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTrigger++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isCalendarPermissionGranted = remember(refreshTrigger) {
        PermissionHelper.isCalendarGranted(context)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        refreshTrigger++
        if (permissions.values.all { it }) {
            pendingPermissionAction?.invoke()
        } else {
            permissionDeniedCount++
        }
        pendingPermissionAction = null
    }

    val runWithPermission: (() -> Unit) -> Unit = { action ->
        if (isCalendarPermissionGranted) {
            action()
        } else {
            pendingPermissionAction = action
            permissionLauncher.launch(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            )
        }
    }

    CalendarSyncContent(
        settings = uiState.appSettings,
        permissionDeniedCount = permissionDeniedCount,
        onBack = onBack,
        onAutoSyncChange = { enabled ->
            if (enabled) runWithPermission { viewModel.updateAutoSyncToCalendar(true) }
            else viewModel.updateAutoSyncToCalendar(false)
        },
        onRemindMinutesChange = viewModel::updateCalendarRemindBeforeMinutes,
        onSyncNow = { onResult ->
            runWithPermission { viewModel.syncToCalendarManually(onResult) }
        },
        onDeleteCalendar = { onResult ->
            runWithPermission { viewModel.deleteCalendarSchedule(onResult) }
        }
    )
}
