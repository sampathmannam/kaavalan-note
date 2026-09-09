package com.kaavalan.note.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Progressive disclosure with a single selected section; no nested settings sheets. */
@Composable
internal fun SettingsCategory(
    title: String, description: String, selected: String?, onSelect: (String?) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (selected != null && selected != title) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .clickable(role = Role.Button) { onSelect(if (selected == title) null else title) }
            .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (selected == title) Icon(Icons.AutoMirrored.Outlined.ArrowBack, "All settings")
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (selected == title) "All settings" else title, style = MaterialTheme.typography.titleMedium)
                Text(if (selected == title) title else description, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected == null) Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected == title) content()
        else HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
