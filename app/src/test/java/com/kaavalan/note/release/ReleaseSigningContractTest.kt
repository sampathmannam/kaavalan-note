package com.kaavalan.note.release

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Public source-only guards: never resolve credentials or invoke release.sh. */
class ReleaseSigningContractTest {
    private val releaseScript = File("../release.sh").readLines()

    private fun commandFor(task: String): String = releaseScript.single {
        it.contains("./gradlew") && it.contains(":app:$task")
    }

    @Test
    fun `release preflight explicitly requests signing and preserves failure`() {
        val command = commandFor("signingReport")
        assertTrue(command.contains("-Pkaavalan.enableReleaseSigning=true"))
        assertFalse(command.contains("|| true"))
    }

    @Test
    fun `release assembly explicitly requests signing`() {
        assertTrue(commandFor("assembleRelease").contains("-Pkaavalan.enableReleaseSigning=true"))
    }

    @Test
    fun `unit tests never request release credentials`() {
        assertTrue(commandFor("testDebugUnitTest").contains("-Pkaavalan.enableReleaseSigning=false"))
    }
}
