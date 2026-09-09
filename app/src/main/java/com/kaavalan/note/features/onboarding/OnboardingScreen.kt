package com.kaavalan.note.features.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** One short introduction, no roster setup or permissions required to start writing. */
@Composable
fun OnboardingScreen(onDone: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Shield, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Kaavalan note", style = MaterialTheme.typography.titleMedium)
            }
            Text("A clear head.\nA clear record.", style = MaterialTheme.typography.headlineLarge)
            Text("Your working notebook for instructions, decisions and follow-ups on duty.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IntroRow(Icons.Outlined.Today, "Today", "See your next action and follow up with your team.")
            IntroRow(Icons.Outlined.Checklist, "Instructions", "Record work for yourself, assigned by you, or received.")
            IntroRow(Icons.Outlined.People, "Contacts", "Link officers and staff when useful. You can start without adding anyone.")
            HorizontalDivider()
            Text("Private by default", style = MaterialTheme.typography.titleMedium)
            Text("Notes are encrypted on this phone. No account is needed. Google Drive backup is optional; system voice recognition may use an online service.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
        Button(onClick = { viewModel.finish(onDone) }, enabled = !state.working,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 52.dp)) {
            Text(if (state.working) "Opening…" else "Open my workspace")
        }
        TextButton(onClick = { viewModel.finish(onDone) }, enabled = !state.working,
            modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Skip") }
    }
}

@Composable
private fun IntroRow(icon: ImageVector, title: String, description: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
