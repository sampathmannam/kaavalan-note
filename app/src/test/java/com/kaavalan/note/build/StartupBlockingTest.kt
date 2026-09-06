package com.kaavalan.note.build

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * v2.1.2 (startup): a finding test for the cold-start path.
 *
 * AGENTS.md makes finding tests the convention for design rules —
 * "if a rule exists in this file, there must be a test that fails
 * if the rule is broken". This is that test for one rule:
 * **`Application.onCreate` must not block the main thread.**
 *
 * **The finding.** v2.1.1's `onCreate` wrapped the device-owner
 * bootstrap in `runBlocking { withTimeout(2_000) { ... } }`. It
 * bought a real ordering guarantee (the preflight needs the
 * device-owner row) at the cost of up to two seconds of blocked
 * main thread on every cold start — worst on first launch, which
 * is exactly when SQLCipher passphrase generation runs. Blocking
 * in `onCreate` delays the first frame, is what Play vitals
 * reports as a slow cold start, and at 5 s is an ANR.
 *
 * The ordering was preserved by sequencing both steps inside one
 * background coroutine instead, so the guarantee survives and the
 * block does not.
 *
 * This test reads the source rather than executing `onCreate`,
 * because the thing being asserted is a property of the call site:
 * a Robolectric run would happily execute `runBlocking` on its
 * single test thread and report nothing.
 */
class StartupBlockingTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("could not locate the repository root from ${File("").absolutePath}")

    private val applicationSource =
        File(repoRoot, "app/src/main/java/com/kaavalan/note/KaavalanApplication.kt")

    /**
     * Guards the guard: if the path stops resolving, every
     * assertion below would vacuously pass over empty text.
     */
    @Test
    fun `application source is actually found`() {
        assertTrue(
            "KaavalanApplication.kt not found at ${applicationSource.absolutePath}",
            applicationSource.isFile,
        )
        assertTrue(
            "KaavalanApplication.kt is unexpectedly empty",
            applicationSource.readText().length > 500,
        )
    }

    @Test
    fun `Application onCreate does not block the main thread`() {
        val offenders = applicationSource.readLines()
            .withIndex()
            .filterNot { (_, line) -> line.trimStart().startsWith("//") }
            .filterNot { (_, line) -> line.trimStart().startsWith("*") }
            .filter { (_, line) ->
                Regex("""\brunBlocking\s*[({]""").containsMatchIn(line) ||
                    Regex("""\bThread\.sleep\s*\(""").containsMatchIn(line) ||
                    // `.get()` / `.await()` on a future inside onCreate is the
                    // same mistake wearing a different hat.
                    Regex("""\bFutures?\.\w+\([^)]*\)\.get\(""").containsMatchIn(line)
            }
            .map { (i, line) -> "  line ${i + 1}: ${line.trim()}" }

        if (offenders.isNotEmpty()) {
            fail(
                "KaavalanApplication blocks the main thread during startup. " +
                    "Application.onCreate runs before the first frame, so anything " +
                    "blocking here delays every cold start and risks an ANR. Launch " +
                    "the work on the injected @ApplicationScope instead, sequencing " +
                    "steps inside one coroutine if they need ordering:\n" +
                    offenders.joinToString("\n"),
            )
        }
    }

    /**
     * `GlobalScope` has no lifecycle owner and no supervisor: a
     * failure in one child cancels its siblings, and nothing can
     * substitute it in a test. The application scope from
     * `CoroutineModule` is the injected, supervised equivalent.
     */
    @Test
    fun `Application uses the injected application scope, not GlobalScope`() {
        val offenders = applicationSource.readLines()
            .withIndex()
            .filterNot { (_, line) -> line.trimStart().startsWith("//") }
            .filterNot { (_, line) -> line.trimStart().startsWith("*") }
            .filter { (_, line) -> Regex("""\bGlobalScope\s*\.""").containsMatchIn(line) }
            .map { (i, line) -> "  line ${i + 1}: ${line.trim()}" }

        if (offenders.isNotEmpty()) {
            fail(
                "KaavalanApplication uses GlobalScope. Inject the " +
                    "@ApplicationScope CoroutineScope from CoroutineModule " +
                    "instead:\n" + offenders.joinToString("\n"),
            )
        }
    }
}
