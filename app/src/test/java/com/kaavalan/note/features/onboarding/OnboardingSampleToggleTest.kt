package com.kaavalan.note.features.onboarding
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Sample fixtures belong in Development tools, not in the officer's first-run workflow. */
class OnboardingSampleToggleTest {
    @Test fun firstRunDoesNotOptAnOfficerIntoSyntheticRecords() {
        assertFalse(OnboardingUiState().loadSample)
        val source = File("src/main/java/com/kaavalan/note/features/onboarding/OnboardingScreen.kt").readText()
        assertFalse(source.contains("setSampleToggled"))
        assertTrue(source.contains("viewModel.finish(onDone)"))
        assertTrue(source.contains("enabled = !state.working"))
    }
}
