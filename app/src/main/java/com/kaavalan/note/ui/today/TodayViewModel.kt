package com.kaavalan.note.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaavalan.note.data.brief.BriefGenerator
import com.kaavalan.note.data.brief.BriefType
import com.kaavalan.note.data.brief.DailyBrief
import com.kaavalan.note.data.instructions.Instruction
import com.kaavalan.note.data.instructions.RoomInstructionRepository
import com.kaavalan.note.data.person.Person
import com.kaavalan.note.data.person.PersonRepository
import com.kaavalan.note.data.reminder.ReminderManager
import com.kaavalan.note.data.vault.VaultModeHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * M4-T1: Today screen view-model.
 *
 * M4-T5: also exposes the evening review. Computed from the same
 * brief; the stillOpen list is the still-pending section. The
 * review surface is a single dismiss tap, no punishment.
 *
 * v1.5.3 (VAULT-010 fix): also drives the mark-done / drop /
 * reopen actions on the instruction detail sheet. Each action
 * goes through the local Room repository (which writes
 * PENDING_UPDATE to the sync_queue, a no-op in vault mode).
 *
 * v1.6.2: also exposes the visible-people flow so the search
 * bar can filter people too. The flow is `flatMapLatest` over
 * the active vault mode — when the user switches to Hidden,
 * the people list shrinks, the person filter shrinks, and the
 * search bar sees it within the next emission.
 */
@HiltViewModel
class TodayViewModel @Inject constructor(
    private val briefGenerator: BriefGenerator,
    private val roomInstructionRepository: RoomInstructionRepository,
    private val personRepository: PersonRepository,
    private val vaultModeHolder: VaultModeHolder,
    private val reminderManager: ReminderManager,
) : ViewModel() {

    val brief: StateFlow<DailyBrief> = briefGenerator
        .observeDailyBrief(
            type = BriefType.MORNING,
            date = LocalDate.now(ZoneId.systemDefault()),
        )
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DailyBrief(
                date = LocalDate.now(ZoneId.systemDefault()).toString(),
                type = BriefType.MORNING,
                needsYouToday = emptyList(),
                waitingOnOthers = emptyList(),
                carriedOver = emptyList(),
            ),
        )

    val review: StateFlow<EveningReview> = briefGenerator
        .observeDailyBrief(
            type = BriefType.EVENING,
            date = LocalDate.now(ZoneId.systemDefault()),
        )
        .map { brief ->
            EveningReview(
                date = LocalDate.now(ZoneId.systemDefault()).toString(),
                stillOpen = brief.needsYouToday + brief.waitingOnOthers,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = EveningReview(),
        )

    /**
     * v1.6.2: the visible people list. Re-emits when the
     * vault mode changes, so the search bar can refilter.
     * Scope is the same `WhileSubscribed(5_000)` used
     * elsewhere — survives a config change and short
     * navigation hops.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val persons: StateFlow<List<Person>> = vaultModeHolder.mode
        .flatMapLatest { mode -> personRepository.observeAllInMode(mode.storageKey) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /**
     * v1.5.3 (VAULT-010): state-transition handlers. Each one
     * writes through the Room repository (which also enqueues a
     * PENDING_UPDATE for the sync outbox) and Room re-emits the
     * brief Flow, so the UI sees the change synchronously.
     */
    fun markDone(instructionId: String) {
        viewModelScope.launch {
            runCatching { roomInstructionRepository.markDone(instructionId) }
                .onSuccess { reminderManager.cancelDelivery(instructionId) }
        }
    }

    fun markDropped(instructionId: String, reason: String? = null) {
        viewModelScope.launch {
            runCatching { roomInstructionRepository.markDropped(instructionId, reason) }
                .onSuccess { reminderManager.cancelDelivery(instructionId) }
        }
    }

    fun reopen(instructionId: String) {
        viewModelScope.launch {
            runCatching { roomInstructionRepository.reopen(instructionId) }
        }
    }

    fun updateReminder(instructionId: String, reminderAtMs: Long?) {
        if (reminderAtMs != null && reminderAtMs <= System.currentTimeMillis()) return
        viewModelScope.launch {
            runCatching { reminderManager.update(instructionId, reminderAtMs) }
        }
    }

    suspend fun findInstruction(instructionId: String): Instruction? =
        roomInstructionRepository.fetchAll().firstOrNull { it.id == instructionId }
}

data class EveningReview(
    val date: String = "",
    val gotDoneToday: List<Instruction> = emptyList(),
    val stillOpen: List<Instruction> = emptyList(),
)
