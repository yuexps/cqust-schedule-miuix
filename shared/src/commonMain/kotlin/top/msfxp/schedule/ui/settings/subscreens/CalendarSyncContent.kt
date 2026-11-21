package top.msfxp.schedule.ui.settings.subscreens

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import top.msfxp.schedule.data.model.AppSettings
import top.msfxp.schedule.shared.resources.Res
import top.msfxp.schedule.shared.resources.calendar_delete_desc
import top.msfxp.schedule.shared.resources.calendar_delete_dialog_confirm
import top.msfxp.schedule.shared.resources.calendar_delete_dialog_message
import top.msfxp.schedule.shared.resources.calendar_delete_dialog_title
import top.msfxp.schedule.shared.resources.calendar_delete_fail_toast
import top.msfxp.schedule.shared.resources.calendar_delete_item_title
import top.msfxp.schedule.shared.resources.calendar_delete_success_toast
import top.msfxp.schedule.shared.resources.calendar_sync_account_hint
import top.msfxp.schedule.shared.resources.calendar_sync_desc_auto
import top.msfxp.schedule.shared.resources.calendar_sync_desc_now
import top.msfxp.schedule.shared.resources.calendar_sync_fail_toast
import top.msfxp.schedule.shared.resources.calendar_sync_item_auto
import top.msfxp.schedule.shared.resources.calendar_sync_item_now
import top.msfxp.schedule.shared.resources.calendar_sync_item_remind
import top.msfxp.schedule.shared.resources.calendar_sync_permission_needed_toast
import top.msfxp.schedule.shared.resources.calendar_sync_remind_format
import top.msfxp.schedule.shared.resources.calendar_sync_success_toast
import top.msfxp.schedule.shared.resources.calendar_sync_title
import top.msfxp.schedule.shared.resources.common_back
import top.msfxp.schedule.shared.resources.common_cancel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private val CalendarRemindOptions = listOf(5, 10, 15, 20, 30, 45, 60)

// 跨平台日历同步设置界面；平台页面仅负责处理日历权限。
@Composable
fun CalendarSyncContent(
    settings: AppSettings,
    permissionDeniedCount: Int = 0,
    onBack: () -> Unit,
    onAutoSyncChange: (Boolean) -> Unit,
    onRemindMinutesChange: (Int) -> Unit,
    onSyncNow: (onResult: (Boolean) -> Unit) -> Unit,
    onDeleteCalendar: (onResult: (Boolean) -> Unit) -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val remindMinutesLabels = CalendarRemindOptions.map {
        stringResource(Res.string.calendar_sync_remind_format, it)
    }
    val selectedMinutesIndex = remember(settings.calendarRemindBeforeMinutes) {
        val index = CalendarRemindOptions.indexOf(settings.calendarRemindBeforeMinutes)
        if (index >= 0) index else 2
    }
    val syncSuccessToast = stringResource(Res.string.calendar_sync_success_toast)
    val syncFailToast = stringResource(Res.string.calendar_sync_fail_toast)
    val deleteSuccessToast = stringResource(Res.string.calendar_delete_success_toast)
    val deleteFailToast = stringResource(Res.string.calendar_delete_fail_toast)
    val permissionNeededToast = stringResource(Res.string.calendar_sync_permission_needed_toast)

    LaunchedEffect(permissionDeniedCount) {
        if (permissionDeniedCount > 0) {
            snackbarHostState.showSnackbar(permissionNeededToast)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(state = snackbarHostState) },
        topBar = {
            SmallTopAppBar(
                title = stringResource(Res.string.calendar_sync_title),
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
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        SwitchPreference(
                            title = stringResource(Res.string.calendar_sync_item_auto),
                            summary = stringResource(Res.string.calendar_sync_desc_auto),
                            checked = settings.autoSyncToCalendar,
                            onCheckedChange = onAutoSyncChange
                        )

                        OverlayDropdownPreference(
                            title = stringResource(Res.string.calendar_sync_item_remind),
                            items = remindMinutesLabels,
                            selectedIndex = selectedMinutesIndex,
                            onSelectedIndexChange = { index ->
                                onRemindMinutesChange(CalendarRemindOptions.getOrElse(index) { 15 })
                            }
                        )

                        ArrowPreference(
                            title = stringResource(Res.string.calendar_sync_item_now),
                            summary = stringResource(Res.string.calendar_sync_desc_now),
                            onClick = {
                                onSyncNow { success ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            if (success) syncSuccessToast else syncFailToast
                                        )
                                    }
                                }
                            }
                        )

                        ArrowPreference(
                            title = stringResource(Res.string.calendar_delete_item_title),
                            summary = stringResource(Res.string.calendar_delete_desc),
                            onClick = { showDeleteConfirmDialog = true }
                        )
                    }
                }
            }

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
                        text = stringResource(Res.string.calendar_sync_account_hint),
                        style = MiuixTheme.textStyles.footnote2.copy(lineHeight = 18.sp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (showDeleteConfirmDialog) {
            WindowDialog(
                show = true,
                title = stringResource(Res.string.calendar_delete_dialog_title),
                summary = stringResource(Res.string.calendar_delete_dialog_message),
                onDismissRequest = { showDeleteConfirmDialog = false }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(
                        text = stringResource(Res.string.common_cancel),
                        onClick = { showDeleteConfirmDialog = false },
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            showDeleteConfirmDialog = false
                            onDeleteCalendar { success ->
                                if (success && settings.autoSyncToCalendar) {
                                    onAutoSyncChange(false)
                                }
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (success) deleteSuccessToast else deleteFailToast
                                    )
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = stringResource(Res.string.calendar_delete_dialog_confirm),
                            color = MiuixTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
