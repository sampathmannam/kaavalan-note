package com.kaavalan.note.ui.privacy

import com.kaavalan.note.data.auth.SecurePreferences
import com.kaavalan.note.data.vault.MnemonicGenerator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product-decision follow-up (adversarial-QA audit): pins the fix for
 * [RecoveryPhraseViewModel.start] unconditionally generating a brand-new
 * phrase, even when one already exists.
 *
 * **Why not a "view existing phrase" feature.** Only a SHA-256 hash of
 * the phrase is ever persisted (see [SecurePreferences]'s and
 * [com.kaavalan.note.data.vault.MnemonicGenerator]'s own doc comments:
 * "the phrase is never persisted"). A hash cannot be reversed back into
 * words, so there is no existing phrase to show again once the screen
 * that generated it closes. The fix instead warns before silently
 * replacing an existing phrase, via a new [RecoveryPhraseState.ConfirmRegenerate]
 * step the user must explicitly confirm.
 *
 * Plain unit tests against the ViewModel's public surface -- no
 * Compose/Robolectric involved, matching [DispatchViewModelTest]'s
 * pattern for the same kind of pure ViewModel-state-machine bug.
 * [SecurePreferences] and [MnemonicGenerator] are mocked (both are
 * concrete classes with real Android/security dependencies -- Keystore
 * and BIP39 wordlist file I/O respectively -- that this test has no
 * need to exercise for real).
 */
class RecoveryPhraseViewModelTest {

    private fun makeVm(securePreferences: SecurePreferences, mnemonicGenerator: MnemonicGenerator = mockk {
        every { generate12() } returns List(12) { "word$it" }
    }): RecoveryPhraseViewModel {
        return RecoveryPhraseViewModel(mnemonicGenerator, securePreferences)
    }

    @Test
    fun `start goes straight to Display when no recovery phrase exists yet`() {
        val securePreferences = mockk<SecurePreferences>(relaxed = true)
        every { securePreferences.recoveryPhraseHash() } returns null
        val vm = makeVm(securePreferences)

        vm.start()

        assertTrue(
            "with no existing phrase, start() must generate one immediately, no warning needed",
            vm.state.value is RecoveryPhraseState.Display,
        )
    }

    @Test
    fun `start goes to ConfirmRegenerate instead of generating when a phrase already exists`() {
        val securePreferences = mockk<SecurePreferences>(relaxed = true)
        every { securePreferences.recoveryPhraseHash() } returns "existing-hash-hex"
        val mnemonicGenerator = mockk<MnemonicGenerator>()
        val vm = makeVm(securePreferences, mnemonicGenerator)

        vm.start()

        assertEquals(
            "an existing phrase must route through a confirm step, not silently regenerate",
            RecoveryPhraseState.ConfirmRegenerate,
            vm.state.value,
        )
        verify(exactly = 0) { mnemonicGenerator.generate12() }
    }

    @Test
    fun `confirmRegenerate proceeds to Display after the warning is accepted`() {
        val securePreferences = mockk<SecurePreferences>(relaxed = true)
        every { securePreferences.recoveryPhraseHash() } returns "existing-hash-hex"
        val vm = makeVm(securePreferences)

        vm.start()
        assertEquals(RecoveryPhraseState.ConfirmRegenerate, vm.state.value)

        vm.confirmRegenerate()

        assertTrue(
            "confirming the warning must generate and display a new phrase",
            vm.state.value is RecoveryPhraseState.Display,
        )
    }

    @Test
    fun `cancelRegenerate backs out without ever generating a new phrase`() {
        val securePreferences = mockk<SecurePreferences>(relaxed = true)
        every { securePreferences.recoveryPhraseHash() } returns "existing-hash-hex"
        val mnemonicGenerator = mockk<MnemonicGenerator>()
        val vm = makeVm(securePreferences, mnemonicGenerator)

        vm.start()
        vm.cancelRegenerate()

        assertEquals(
            "cancelling must return to Idle, leaving the existing phrase's hash untouched",
            RecoveryPhraseState.Idle,
            vm.state.value,
        )
        verify(exactly = 0) { mnemonicGenerator.generate12() }
    }
}
