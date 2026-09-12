package com.kaavalan.note.features.capture

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kaavalan.note.R

/** Three disjoint 48dp+ targets; a tap on a caption never falls through to typing. */
@Composable
fun NoteBar(
    onTextClick: () -> Unit,
    onCameraClick: () -> Unit = {},
    onMicClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    hasDraft: Boolean = false,
) {
    val addNote = stringResource(R.string.a11y_add_note)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onTextClick, modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("capture_open")
                    .semantics { contentDescription = addNote }, shape = MaterialTheme.shapes.large,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                    Icon(Icons.Outlined.EditNote, null, Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(if (hasDraft) "Continue note" else "New note", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                }
                CaptureShortcut(Icons.Outlined.PhotoCamera, stringResource(R.string.note_bar_camera), onCameraClick)
                CaptureShortcut(Icons.Outlined.Mic, stringResource(R.string.note_bar_mic), onMicClick)
            }
        }
    }
}

@Composable
private fun CaptureShortcut(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.widthIn(min = 56.dp).heightIn(min = 56.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }.padding(horizontal = 6.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(3.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
