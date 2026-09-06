package com.kaavalan.note.ui.hierarchy

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kaavalan.note.R
import com.kaavalan.note.data.instructions.AudienceRef
import com.kaavalan.note.data.instructions.DeliveryService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DispatchSheet(title: String, rawText: String, senderName: String, senderDesignation: String?, senderDivision: String?, onDismiss: () -> Unit, onSent: (String) -> Unit, viewModel: DispatchViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // v2.x (adversarial-QA fix): one-shot info Snackbar host, same
    // pattern as CaptureSheet's `infoMessages` collection -- surfaces
    // e.g. "At least one channel must stay selected." when
    // toggleChannel refuses to let the set go empty. The Channel is
    // buffered so a config change between the tap and the collect
    // doesn't drop the message.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.infoMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.hierarchy_dispatch_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            AudienceSummary(state.audience, state.recipientCount)
            Spacer(Modifier.height(12.dp))
            DueChip(dueAtMs = state.dueAtMs, onSet = { viewModel.setDue(it) })
            Spacer(Modifier.height(12.dp))
            ChannelToggles(state.channels) { viewModel.toggleChannel(it) }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { viewModel.submit(title = title, rawText = rawText, senderName = senderName, senderDesignation = senderDesignation, senderDivision = senderDivision, onDone = onSent) },
                enabled = state.audience != null && state.recipientCount > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.hierarchy_dispatch_title))
                }
            }
            state.lastResult?.let { r ->
                Spacer(Modifier.height(8.dp))
                // Three states, not two: fully-sent (sent > 0, failed == 0),
                // partial-failure (failed > 0, sent > 0), and fully-failed
                // (sent == 0, failed > 0). The original `if (r.failed == 0)`
                // collapsed the last two into "X of Y delivered" with the
                // success colour, which is misleading when nothing was
                // actually delivered (e.g. every recipient had no phone
                // number on file).
                val color = when {
                    r.failed == 0 -> MaterialTheme.colorScheme.primary
                    r.sent == 0 -> MaterialTheme.colorScheme.error
                    // Partial-failure: use the project's `Quiet` (amber)
                    // token, not the M3-default tertiary (which is
                    // purple-ish and clashes with the warm beige/blue
                    // palette). Amber matches the AGENTS.md "no red
                    // overdue" rule — warning without alarm.
                    else -> com.kaavalan.note.ui.theme.KaavalanColors.Quiet
                }
                val text = when {
                    r.failed == 0 -> stringResource(R.string.hierarchy_dispatch_receipts_other, r.sent, r.recipients)
                    r.sent == 0 -> stringResource(R.string.hierarchy_dispatch_receipts_none, r.recipients, r.failed)
                    else -> stringResource(R.string.hierarchy_dispatch_receipts_other, r.sent, r.recipients) + " (${r.failed} failed)"
                }
                Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // v2.x: the Snackbar host sits INSIDE the bottom sheet, as a
    // sibling of the Column content, mirroring CaptureSheet -- so
    // the message is co-located with the chip that produced it
    // rather than clipped to some other container's bounds.
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.imePadding().navigationBarsPadding(),
    ) { data ->
        Snackbar(
            snackbarData = data,
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        )
    }
}

@Composable private fun AudienceSummary(audience: AudienceRef?, count: Int) {
    val (label, color) = when (audience) {
        null -> "Pick an audience" to MaterialTheme.colorScheme.error
        is AudienceRef.ByPerson -> stringResource(R.string.hierarchy_audience_summary_to_person, audience.label) to MaterialTheme.colorScheme.onSurface
        is AudienceRef.ByDesignation -> stringResource(R.string.hierarchy_audience_summary_to_designation, audience.label) to MaterialTheme.colorScheme.onSurface
        is AudienceRef.ByStation -> stringResource(R.string.hierarchy_audience_summary_to_station, audience.label) to MaterialTheme.colorScheme.onSurface
        is AudienceRef.ByAll -> stringResource(R.string.hierarchy_audience_summary_to_all) to MaterialTheme.colorScheme.onSurface
    }
    Column {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
        if (count > 0) Text(if (count == 1) stringResource(R.string.hierarchy_audience_recipient_count_one) else stringResource(R.string.hierarchy_audience_recipient_count_other, count), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun ChannelToggles(selected: Set<DeliveryService.Channel>, onToggle: (DeliveryService.Channel) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(selected = DeliveryService.Channel.SMS in selected, onClick = { onToggle(DeliveryService.Channel.SMS) }, label = { Text(stringResource(R.string.hierarchy_dispatch_channel_sms)) })
        FilterChip(selected = DeliveryService.Channel.WHATSAPP in selected, onClick = { onToggle(DeliveryService.Channel.WHATSAPP) }, label = { Text(stringResource(R.string.hierarchy_dispatch_channel_whatsapp)) })
    }
}
