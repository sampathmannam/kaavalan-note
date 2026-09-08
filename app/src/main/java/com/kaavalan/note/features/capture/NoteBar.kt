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
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = onTextClick, modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("capture_open")
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

@Composable
private fun CaptureShortcut(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.widthIn(min = 52.dp).heightIn(min = 56.dp)
        .clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }.padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
