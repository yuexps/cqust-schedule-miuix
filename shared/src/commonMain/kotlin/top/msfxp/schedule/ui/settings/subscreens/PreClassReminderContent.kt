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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import top.msfxp.schedule.data.model.AppSettings
import top.msfxp.schedule.shared.resources.Res
import top.msfxp.schedule.shared.resources.calendar_sync_remind_format
import top.msfxp.schedule.shared.resources.common_back
import top.msfxp.schedule.shared.resources.reminder_switch_desc
import top.msfxp.schedule.shared.resources.reminder_switch_title
import top.msfxp.schedule.shared.resources.reminder_test_delayed_desc
import top.msfxp.schedule.shared.resources.reminder_test_delayed_title
import top.msfxp.schedule.shared.resources.reminder_test_immediate_desc
import top.msfxp.schedule.shared.resources.reminder_test_immediate_title
import top.msfxp.schedule.shared.resources.reminder_time_title
import top.msfxp.schedule.shared.resources.reminder_title
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

private val RemindMinutesOptions = listOf(5, 10, 15, 20, 30, 45, 60)

data class PreClassReminderCallbacks(
    val onBack: () -> Unit,
    val onReminderEnabledChange: (Boolean) -> Unit,
    val onRemindMinutesChange: (Int) -> Unit,
    val onImmediateTest: () -> Unit,
    val onDelayedTest: () -> Unit
)

data class PreClassReminderPresentation(
    val hintText: String,
    val testSectionTitle: String? = null
)

@Composable
fun PreClassReminderContent(
    settings: AppSettings,
    callbacks: PreClassReminderCallbacks,
    presentation: PreClassReminderPresentation,
    snackbarHostState: SnackbarHostState
) {
    Scaffold(
        snackbarHost = { SnackbarHost(state = snackbarHostState) },
        topBar = {
            SmallTopAppBar(
                title = stringResource(Res.string.reminder_title),
                navigationIcon = {
                    IconButton(onClick = callbacks.onBack) {
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
            item { ReminderOptionsCard(settings, callbacks) }
            if (settings.showTestFeatures) {
                item { ReminderTestCard(presentation.testSectionTitle, callbacks) }
            }
            item { ReminderHint(presentation.hintText) }
        }
    }
}

@Composable
private fun ReminderOptionsCard(
    settings: AppSettings,
    callbacks: PreClassReminderCallbacks
) {
    val remindMinutesLabels = RemindMinutesOptions.map {
        stringResource(Res.string.calendar_sync_remind_format, it)
    }
    val selectedMinutesIndex = remember(settings.remindBeforeMinutes) {
        val index = RemindMinutesOptions.indexOf(settings.remindBeforeMinutes)
        if (index >= 0) index else 2
    }

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
                onCheckedChange = callbacks.onReminderEnabledChange
            )

            if (settings.reminderEnabled) {
                OverlayDropdownPreference(
                    title = stringResource(Res.string.reminder_time_title),
                    items = remindMinutesLabels,
                    selectedIndex = selectedMinutesIndex,
                    onSelectedIndexChange = { index ->
                        callbacks.onRemindMinutesChange(RemindMinutesOptions.getOrElse(index) { 15 })
                    }
                )
            }
        }
    }
}

@Composable
private fun ReminderTestCard(
    title: String?,
    callbacks: PreClassReminderCallbacks
) {
    title?.let { SmallTitle(text = it) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            ArrowPreference(
                title = stringResource(Res.string.reminder_test_immediate_title),
                summary = stringResource(Res.string.reminder_test_immediate_desc),
                onClick = callbacks.onImmediateTest
            )
            ArrowPreference(
                title = stringResource(Res.string.reminder_test_delayed_title),
                summary = stringResource(Res.string.reminder_test_delayed_desc),
                onClick = callbacks.onDelayedTest
            )
        }
    }
}

@Composable
private fun ReminderHint(text: String) {
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
            text = text,
            style = MiuixTheme.textStyles.footnote2.copy(lineHeight = 18.sp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f)
        )
    }
}
