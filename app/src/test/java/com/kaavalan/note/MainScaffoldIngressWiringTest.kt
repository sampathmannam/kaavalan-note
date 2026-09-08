package com.kaavalan.note
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Guard activity-scoped ingress after moving the working screens out of MainActivity. */
class MainScaffoldIngressWiringTest {
    private val activity = File("src/main/java/com/kaavalan/note/MainActivity.kt").readText()
    private val shell = File("src/main/java/com/kaavalan/note/ui/workspace/OfficerAppRoot.kt").readText()

    @Test fun activityPassesItsRootViewModelToTheAppShell() {
        assertTrue(activity.contains("rootViewModel = rootViewModel"))
        assertTrue(shell.contains("CaptureHost(capture, rootViewModel"))
        assertTrue(shell.indexOf("CaptureHost(capture") < shell.indexOf("NavHost("))
    }

    @Test fun navigationHasExactlyThreeWorkingDestinations_andSettingsDoesNotReplaceSelection() {
        assertTrue(shell.contains("Triple(Routes.TODAY"))
        assertTrue(shell.contains("Triple(Routes.HOME"))
        assertTrue(shell.contains("Triple(Routes.CONTACTS"))
        assertFalse(shell.contains("Triple(Routes.SETTINGS"))
        assertTrue(shell.contains("selected = currentRoute == route"))
        assertTrue(shell.contains("onSettings = { showSettings = true }"))
    }
}
