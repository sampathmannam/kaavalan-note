package com.kaavalan.note.features.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaavalan.note.data.local.AppDatabase
import com.kaavalan.note.data.local.entities.InstructionEntity
import com.kaavalan.note.data.local.entities.InstructionTagCrossRef
import com.kaavalan.note.data.local.entities.PersonEntity
import com.kaavalan.note.data.local.entities.SyncStatus
import com.kaavalan.note.data.local.entities.TagEntity
import com.kaavalan.note.data.preferences.KaavalanPreferences
import com.kaavalan.note.data.tags.TagKind
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Tier 1.2 (v2.0): the first-run onboarding ViewModel.
 *
 * 3 steps (per the spec):
 *  - Step 1: welcome screen.
 *  - Step 2: privacy / where-the-data-lives card.
 *  - Step 3: "add your first person" hero with a sample
 *    data toggle.
 *
 * `onDone(loadSample)` flips the `hasSeenOnboarding` flag
 * in DataStore (so the sheet only shows once), and if
 * `loadSample = true` seeds 6 sample people + 5 sample
 * instructions + 2 sample tags so the home tab is not
 * empty.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val db: AppDatabase,
    private val preferences: KaavalanPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    fun setSampleToggled(checked: Boolean) {
        _state.value = _state.value.copy(loadSample = checked)
    }

    fun setCurrentPage(page: Int) {
        _state.value = _state.value.copy(currentPage = page)
    }

    /**
     * Mark the onboarding as seen. The host (MainScaffold)
     * then dismisses the sheet. If `loadSample = true` we
     * also seed 6 sample people + 5 sample instructions
     * + 2 sample tags before the home tab is shown.
     */
    fun finish(onDone: () -> Unit) {
        val s = _state.value
        if (s.working) return
        _state.value = s.copy(working = true)
        viewModelScope.launch {
            try {
                if (s.loadSample) seedSampleData()
                preferences.setOnboardingSeen()
                _state.value = _state.value.copy(working = false, finished = true, error = null)
                onDone()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(working = false, error = "Could not open the workspace. Please try again.")
            }
        }
    }

    /**
     * The seeding routine is internal so the unit test can
     * call it directly without standing up the VM. The test
     * injects a Room in-memory DB and asserts the row counts
     * land exactly. The VM still calls this from `finish()`.
     */
    internal suspend fun seedSampleData() {
        val now = Instant.now().toString()
        val personDao = db.personDao()
        val insDao = db.instructionDao()
        val tagDao = db.tagDao()
        val xrefDao = db.instructionTagDao()
        val people = listOf(
            PersonEntity(
                id = "sample-p1", name = "Inspector Ramesh", designation = "SHO", station = "Thanjavur Town",
                phone = null, userId = "sample", createdAt = now, updatedAt = now,
                isSensitive = false, syncStatus = SyncStatus.SYNCED,
            ),
            PersonEntity(
                id = "sample-p2", name = "DSP Kavitha", designation = "DSP", station = "Thanjavur Rural",
                phone = null, userId = "sample", createdAt = now, updatedAt = now,
                isSensitive = false, syncStatus = SyncStatus.SYNCED,
            ),
            PersonEntity(
                id = "sample-p3", name = "SP Selvam", designation = "SP", station = "Thanjavur District",
                phone = null, userId = "sample", createdAt = now, updatedAt = now,
                isSensitive = false, syncStatus = SyncStatus.SYNCED,
            ),
            PersonEntity(
                id = "sample-p4", name = "Inspector Suresh", designation = "CI", station = "Kumbakonam",
                phone = null, userId = "sample", createdAt = now, updatedAt = now,
                isSensitive = false, syncStatus = SyncStatus.SYNCED,
            ),
            PersonEntity(
                id = "sample-p5", name = "Sub-Inspector Lakshmi", designation = "SI", station = "Pattukottai",
                phone = null, userId = "sample", createdAt = now, updatedAt = now,
                isSensitive = false, syncStatus = SyncStatus.SYNCED,
            ),
            PersonEntity(
                id = "sample-p6", name = "Inspector Mani", designation = "Inspector", station = "Mannargudi",
                phone = null, userId = "sample", createdAt = now, updatedAt = now,
                isSensitive = false, syncStatus = SyncStatus.SYNCED,
            ),
        )
        people.forEach { personDao.upsert(it) }
        val tagPriority = TagEntity(
            id = "sample-t-priority", name = "priority", kind = TagKind.PRIORITY.name,
            color = null, usageCount = 0, lastUsedAt = null, userId = "sample",
            createdAt = now, updatedAt = now, syncStatus = SyncStatus.SYNCED,
        )
        val tagFollow = TagEntity(
            id = "sample-t-follow", name = "follow-up", kind = TagKind.FREE.name,
            color = null, usageCount = 0, lastUsedAt = null, userId = "sample",
            createdAt = now, updatedAt = now, syncStatus = SyncStatus.SYNCED,
        )
        tagDao.upsert(tagPriority)
        tagDao.upsert(tagFollow)
        val instructions = listOf(
            InstructionEntity(
                id = UUID.randomUUID().toString(), personId = "sample-p1",
                direction = "INCOMING", status = "OPEN", source = "TEXT", priority = "HIGH",
                title = "Temple land inquiry — follow up by Friday",
                rawText = "Inspector Ramesh, please share the latest status on the temple land inquiry before Friday's review.",
                dueAt = null, capturedAt = now, createdAt = now, updatedAt = now,
                isSensitive = false, syncStatus = SyncStatus.SYNCED,
                completedAt = null, droppedReason = null, nextActionAt = null,
            ),
            InstructionEntity(
                id = UUID.randomUUID().toString(), personId = "sample-p2",
                direction = "OUTGOING", status = "OPEN", source = "TEXT", priority = "NORMAL",
                title = "Bandobast plan for the weekly market",
                rawText = "Draft a bandobast plan for the weekly market on Saturday and send it across.",
                dueAt = null, capturedAt = now, createdAt = now, updatedAt = now,
                isSensitive = false, syncStatus = SyncStatus.SYNCED,
                completedAt = null, droppedReason = null, nextActionAt = null,
            ),
            InstructionEntity(
                id = UUID.randomUUID().toString(), personId = "sample-p3",
                direction = "INCOMING", status = "OPEN", source = "TEXT", priority = "NORMAL",
                title = "Briefing for the next SP-level meeting",
                rawText = "SP wants a 3-line briefing on last week's chain-snatching cases.",
                dueAt = null, capturedAt = now, createdAt = now, updatedAt = now,
                isSensitive = false, syncStatus = SyncStatus.SYNCED,
                completedAt = null, droppedReason = null, nextActionAt = null,
            ),
            InstructionEntity(
                id = UUID.randomUUID().toString(), personId = "sample-p4",
                direction = "OUTGOING", status = "OPEN", source = "TEXT", priority = "NORMAL",
                title = "Coordinate patrol schedule with Kumbakonam CI",
                rawText = "Confirm the night patrol schedule with the CI in Kumbakonam by tomorrow.",
                dueAt = null, capturedAt = now, createdAt = now, updatedAt = now,
                isSensitive = false, syncStatus = SyncStatus.SYNCED,
                completedAt = null, droppedReason = null, nextActionAt = null,
            ),
            InstructionEntity(
                id = UUID.randomUUID().toString(), personId = "sample-p5",
                direction = "OUTGOING", status = "OPEN", source = "TEXT", priority = "LOW",
                title = "Pattukottai SI — follow up on the lost-document complaint",
                rawText = "Check on the status of the lost-document complaint filed last week.",
                dueAt = null, capturedAt = now, createdAt = now, updatedAt = now,
                isSensitive = false, syncStatus = SyncStatus.SYNCED,
                completedAt = null, droppedReason = null, nextActionAt = null,
            ),
        )
        instructions.forEach { insDao.upsert(it) }
        // attach tags
        xrefDao.attach(
            InstructionTagCrossRef(
                instructionId = instructions[0].id,
                tagId = tagPriority.id,
            ),
        )
        xrefDao.attach(
            InstructionTagCrossRef(
                instructionId = instructions[2].id,
                tagId = tagFollow.id,
            ),
        )
    }
}

data class OnboardingUiState(
    val currentPage: Int = 0,
    val loadSample: Boolean = false,
    val working: Boolean = false,
    val finished: Boolean = false,
    val error: String? = null,
)
