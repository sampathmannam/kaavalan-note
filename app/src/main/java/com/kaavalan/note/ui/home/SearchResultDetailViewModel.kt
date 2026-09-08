package com.kaavalan.note.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaavalan.note.data.instructions.Instruction
import com.kaavalan.note.data.instructions.RoomInstructionRepository
import com.kaavalan.note.data.reminder.ReminderManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * v1.7.0: dedicated ViewModel for the search-result instruction
 * detail sheet shown from [com.kaavalan.note.ui.home.HomeScreen].
 *
 * Why a separate VM? [com.kaavalan.note.ui.home.HomeViewModel] does
 * not currently expose status-mutation — the Home surface is a
 * read view of the Person + tag + instruction corpus. Wiring
 * the three transitions (markDone / markDropped / reopen) into
 * [com.kaavalan.note.ui.home.HomeViewModel] would expand its
 * responsibilities and make the Hilt-injected graph heavier
 * for a use case that only fires when the user opens a search
 * result. This VM is `hiltViewModel()`-ed from the search-
 * detail sheet closure and is short-lived.
 *
 * The VM delegates to the same 1-arg helpers on
 * [RoomInstructionRepository] that
 * [com.kaavalan.note.ui.today.TodayViewModel] uses — those
 * helpers also enqueue a PENDING_UPDATE row in the sync
 * outbox, so the search-result transition ends up on
 * Supabase on the next online tick, exactly as a transition
 * fired from the Today screen does. We do not call
 * [com.kaavalan.note.data.instructions.SupabaseInstructionRepository]
 * directly here: the sync engine owns the outbox-drain
 * lifecycle and the contract is "write locally, drain
 * remotely" — a second write path would race the outbox.
 */
@HiltViewModel
class SearchResultDetailViewModel @Inject constructor(
    private val roomInstructionRepository: RoomInstructionRepository,
    private val reminderManager: ReminderManager,
) : ViewModel() {

    fun markDone(instruction: Instruction) {
        viewModelScope.launch {
            runCatching { roomInstructionRepository.markDone(instruction.id) }
                .onSuccess { reminderManager.cancelDelivery(instruction.id) }
        }
    }

    fun markDropped(instruction: Instruction) {
        viewModelScope.launch {
            runCatching { roomInstructionRepository.markDropped(instruction.id, reason = null) }
                .onSuccess { reminderManager.cancelDelivery(instruction.id) }
        }
    }

    fun reopen(instruction: Instruction) {
        viewModelScope.launch {
            runCatching { roomInstructionRepository.reopen(instruction.id) }
        }
    }

    fun updateReminder(instruction: Instruction, reminderAtMs: Long?) {
        if (reminderAtMs != null && reminderAtMs <= System.currentTimeMillis()) return
        viewModelScope.launch {
            runCatching { reminderManager.update(instruction.id, reminderAtMs) }
        }
    }
}
