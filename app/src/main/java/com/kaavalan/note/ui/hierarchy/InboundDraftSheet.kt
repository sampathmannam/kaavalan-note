package com.kaavalan.note.ui.hierarchy

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kaavalan.note.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboundDraftSheet(inboundTitle: String, inboundRawText: String, onSaveAsOutgoing: (title: String, rawText: String) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by remember { mutableStateOf(inboundTitle) }
    var rawText by remember { mutableStateOf(inboundRawText) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.hierarchy_inbound_draft_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.hierarchy_inbound_draft_title_label)) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = rawText, onValueChange = { rawText = it }, label = { Text(stringResource(R.string.hierarchy_inbound_draft_body_label)) }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 10)
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onSaveAsOutgoing(title, rawText) },
                // Same gating pattern as DispatchComposerSheet's "Next"
                // button and PersonLinksRow's confirmButton: don't fire
                // the callback with fields the user never actually filled
                // in. Both Title and Body feed directly into the saved
                // outgoing note, so both must be non-blank.
                enabled = title.isNotBlank() && rawText.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.hierarchy_inbound_draft_save)) }
            Spacer(Modifier.height(24.dp))
        }
    }
}
