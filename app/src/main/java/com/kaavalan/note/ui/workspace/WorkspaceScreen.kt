package com.kaavalan.note.ui.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaavalan.note.data.instructions.Instruction
import com.kaavalan.note.data.instructions.Direction
import com.kaavalan.note.data.instructions.Priority
import com.kaavalan.note.data.person.Person
import com.kaavalan.note.features.reminder.formatReminderTime
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Stateless destinations: actions are handled by the shell / view model, not nested screen VMs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    tab: WorkspaceTab,
    state: WorkspaceState,
    date: LocalDate,
    busy: Boolean,
    onSettings: () -> Unit,
    onCapture: () -> Unit,
    onInstruction: (String) -> Unit,
    onContact: (String) -> Unit,
    onAddContact: () -> Unit,
    onImportContact: () -> Unit,
    onOpenInstructions: () -> Unit,
    onComplete: (String) -> Unit,
    onRetry: () -> Unit,
) {
    var query by rememberSaveable(tab) { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(WorkFilter.OPEN) }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Text(when (tab) {
                        WorkspaceTab.TODAY -> "Today"
                        WorkspaceTab.INSTRUCTIONS -> "Instructions"
                        WorkspaceTab.CONTACTS -> "Contacts"
                    }, style = if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f)
                        MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.testTag("workspace_title"))
                },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
                windowInsets = WindowInsets(0),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when {
                state.loading -> Column(Modifier.padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    Text("Opening your workspace…", style = MaterialTheme.typography.bodyMedium)
                }
                state.error != null -> EmptyWorkspace(
                    title = "Your workspace could not load", body = state.error,
                    action = "Try again", onAction = onRetry,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
                tab == WorkspaceTab.TODAY -> TodayDesk(state, date, busy, onCapture, onInstruction, onOpenInstructions, onComplete)
                tab == WorkspaceTab.INSTRUCTIONS -> Column(Modifier.widthIn(max = 840.dp).fillMaxSize()) {
                    WorkspaceSearch(query, { query = it }, "Search notes, contacts or stations")
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(WorkFilter.entries) { item ->
                            FilterChip(modifier = Modifier.testTag("filter_${item.name}"), selected = filter == item,
                                onClick = { filter = item }, label = { Text(when (item) {
                                WorkFilter.OPEN -> "All open"
                                WorkFilter.FOR_ME -> "For me"
                                WorkFilter.ASSIGNED -> "Assigned"
                                WorkFilter.RECEIVED -> "Received"
                                WorkFilter.CLOSED -> "Closed"
                            }) })
                        }
                    }
                    val results = remember(state.instructions, state.contacts, filter, query) {
                        filterWork(state.instructions, state.contacts, filter, query)
                    }
                    if (results.isEmpty()) {
                        EmptyWorkspace(
                            title = if (query.isNotBlank()) "No matching instructions" else if (filter == WorkFilter.CLOSED) "Nothing closed yet" else "A clear place for your work",
                            body = if (query.isNotBlank()) "Try a name, station or a few words from the note."
                                else if (filter == WorkFilter.CLOSED) "Completed and closed instructions stay here. You can reopen them at any time."
                                else "Record a task for yourself, an instruction you gave, or one you received. A contact is optional.",
                            action = if (query.isNotBlank()) "Clear search" else "New note",
                            onAction = if (query.isNotBlank()) ({ query = "" }) else onCapture,
                            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        )
                    } else {
                        LazyColumn(Modifier.fillMaxSize().testTag("instructions_list"), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            item { Text("${results.size} ${if (results.size == 1) "instruction" else "instructions"}",
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            items(results, key = { it.id }) { item ->
                                WorkCard(item, state.contacts, date, onClick = { onInstruction(item.id) })
                            }
                        }
                    }
                }
                else -> ContactsDirectory(state, query, { query = it }, onContact, onAddContact, onImportContact)
            }
        }
    }
}

@Composable
private fun TodayDesk(
    state: WorkspaceState, date: LocalDate, busy: Boolean,
    onCapture: () -> Unit, onInstruction: (String) -> Unit, onOpenInstructions: () -> Unit, onComplete: (String) -> Unit,
) {
    val work = remember(state.instructions, date) { todayWork(state.instructions, date, ZoneId.systemDefault()) }
    val focus = work.attention.firstOrNull()
    LazyColumn(
        modifier = Modifier.widthIn(max = 840.dp).fillMaxSize().testTag("today_list"),
        contentPadding = PaddingValues(20.dp, 0.dp, 20.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.hidden) Text("Private contacts workspace", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
        }
        if (state.instructions.isEmpty()) {
            item {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Outlined.EditNote, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("A clear start to your duty.", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("Capture an instruction while it is fresh. Add a reminder if it needs your attention later.",
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Button(onClick = onCapture) { Text("Write your first note") }
                    }
                }
            }
            item { SectionHeading("Built around your day") }
            item { GuidanceRow("For me", "Tasks and decisions you will handle.") }
            item { GuidanceRow("Assigned by me", "Instructions to your team; a place to follow up.") }
            item { GuidanceRow("Received", "Instructions from a senior or another office.") }
        } else {
            item { SectionHeading("Your next action", if (focus == null) "Nothing requiring your action today" else "Start with one thing") }
            if (focus != null) {
                item { WorkCard(focus, state.contacts, date, featured = true, onClick = { onInstruction(focus.id) },
                    onDone = { onComplete(focus.id) }, busy = busy) }
            } else {
                item {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                            Text("You have room to focus. Follow-ups and future reminders are kept below.", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            if (work.attention.size > 1) {
                item { SectionHeading("Also for you", "${work.attention.size - 1} more to consider") }
                items(work.attention.drop(1), key = { "attention:${it.id}" }) { item ->
                    WorkCard(item, state.contacts, date, onClick = { onInstruction(item.id) })
                }
            }
            if (work.followUps.isNotEmpty()) {
                item { SectionHeading("Follow up", "Instructions you assigned or are waiting on") }
                items(work.followUps, key = { "follow:${it.id}" }) { item ->
                    WorkCard(item, state.contacts, date, onClick = { onInstruction(item.id) })
                }
            }
            if (work.upcoming.isNotEmpty()) {
                item { SectionHeading("Coming up", "Reminders after today") }
                items(work.upcoming.take(3), key = { "future:${it.id}" }) { item ->
                    WorkCard(item, state.contacts, date, onClick = { onInstruction(item.id) })
                }
            }
            if (work.completed.isNotEmpty()) {
                item { GuidanceRow("${work.completed.size} completed today", "Your completed work stays in Instructions → Closed.") }
            }
            item {
                OutlinedButton(onClick = onOpenInstructions, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("View all instructions")
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
fun WorkCard(
    instruction: Instruction,
    contacts: List<Person>,
    date: LocalDate,
    onClick: () -> Unit,
    featured: Boolean = false,
    onDone: (() -> Unit)? = null,
    busy: Boolean = false,
) {
    val contact = contacts.firstOrNull { it.id == instruction.personId }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("instruction_${instruction.id}"),
        colors = CardDefaults.cardColors(containerColor = if (featured) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(if (featured) 20.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(instruction.direction.officerLabel(), style = MaterialTheme.typography.labelLarge,
                    color = if (featured) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f))
                if (instruction.priority == Priority.HIGH || instruction.priority == Priority.URGENT) {
                    Text("Priority", style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(instruction.rawText.ifBlank { instruction.title },
                style = if (featured) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                maxLines = if (featured) 5 else 3, overflow = TextOverflow.Ellipsis)
            val context = contact?.let { listOfNotNull(it.name, it.station).joinToString(" · ") }
                ?: instruction.audience?.label
            if (context != null) Text(context, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val reminder = instruction.reminderMillis
            if (reminder != null) {
                val day = java.time.Instant.ofEpochMilli(reminder).atZone(ZoneId.systemDefault()).toLocalDate()
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Schedule, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text((if (day < date && !instruction.isClosed) "Carried over · " else "") + formatReminderTime(reminder),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (instruction.isClosed) Text(instruction.status.officerLabel(), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onDone != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDone, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Mark done")
                }
                TextButton(onClick = onClick, modifier = Modifier.weight(1f)) { Text("View / remind") }
            }
        }
    }
}

@Composable
private fun ContactsDirectory(state: WorkspaceState, query: String, onQuery: (String) -> Unit,
    onContact: (String) -> Unit, onAddContact: () -> Unit, onImportContact: () -> Unit) {
    val contacts = state.contacts.filter { person ->
        listOfNotNull(person.name, person.designation, person.station).joinToString(" ").contains(query.trim(), ignoreCase = true)
    }
    LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize().testTag("contacts_list"),
        contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
        Text("Your officers, staff and other work contacts. Link instructions to see each person’s follow-ups in one place.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        WorkspaceSearch(query, onQuery, "Search name, rank or station")
        TextButton(onClick = onImportContact, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text("Import from phone contacts")
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${state.contacts.size} ${if (state.contacts.size == 1) "contact" else "contacts"}",
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onAddContact, modifier = Modifier.testTag("add_contact")) { Icon(Icons.Outlined.PersonAdd, null, Modifier.size(18.dp));
                Spacer(Modifier.width(8.dp)); Text("Add contact") }
        }
        }
        if (contacts.isEmpty()) item {
            EmptyWorkspace(if (query.isBlank()) "Know who is handling what" else "No matching contacts",
                if (query.isBlank()) "Add a colleague with their rank and station. You can still save notes without adding anyone."
                else "Try their name, rank or station.",
                if (query.isBlank()) "Add contact" else "Clear search",
                if (query.isBlank()) onAddContact else ({ onQuery("") }))
        } else {
            items(contacts, key = { it.id }) { person ->
                val count = state.instructions.count { it.personId == person.id && !it.isClosed }
                Row(Modifier.fillMaxWidth().clickable { onContact(person.id) }.padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                            Text(person.name.trim().take(1).uppercase(), style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(person.name, style = MaterialTheme.typography.titleMedium)
                        Text(listOfNotNull(person.designation, person.station).filter(String::isNotBlank).joinToString(" · ").ifBlank { "Work contact" },
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (count == 0) "No open instructions" else "$count open ${if (count == 1) "instruction" else "instructions"}",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun WorkspaceSearch(query: String, onQuery: (String) -> Unit, hint: String) {
    OutlinedTextField(value = query, onValueChange = onQuery, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).testTag("workspace_search"),
        placeholder = { Text(hint, style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, contentDescription = "Clear search") } }) else null,
        shape = MaterialTheme.shapes.large)
}

@Composable
private fun SectionHeading(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun GuidanceRow(title: String, body: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyWorkspace(title: String, body: String, action: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onAction) { Text(action) }
    }
}
