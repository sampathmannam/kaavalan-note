package com.kaavalan.note.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaavalan.note.data.instructions.Instruction
import com.kaavalan.note.data.instructions.RoomInstructionRepository
import com.kaavalan.note.data.reminder.ReminderManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.receiveAsFlow
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
 * The VM delegates to the same local Room mutation helpers used by Today. Those helpers
 * atomically update the instruction and its forward-compatible outbox marker. The
 * current product is local-only; no network call is part of this interaction.
 */
@HiltViewModel
class SearchResultDetailViewModel @Inject constructor(
    private val roomInstructionRepository: RoomInstructionRepository,
    private val reminderManager: ReminderManager,
) : ViewModel() {

    private val messageChannel = kotlinx.coroutines.channels.Channel<String>(
        kotlinx.coroutines.channels.Channel.BUFFERED,
    )
    val messages = messageChannel.receiveAsFlow()

    fun markDone(instruction: Instruction) {
        mutate {
            roomInstructionRepository.markDone(instruction.id)
            reminderManager.cancelDelivery(instruction.id)
        }
    }

    fun markDropped(instruction: Instruction) {
        mutate {
            roomInstructionRepository.markDropped(instruction.id, reason = null)
            reminderManager.cancelDelivery(instruction.id)
        }
    }

    fun reopen(instruction: Instruction) {
        mutate { roomInstructionRepository.reopen(instruction.id) }
    }

    fun updateReminder(instruction: Instruction, reminderAtMs: Long?) {
        if (reminderAtMs != null && reminderAtMs <= System.currentTimeMillis()) return
        mutate { reminderManager.update(instruction.id, reminderAtMs) }
    }

    private fun mutate(work: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                work()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                messageChannel.trySend("Could not save that change. Please try again.")
            }
        }
    }
}
