package com.kaavalan.note.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * fix#12 regression guard (QA adversarial audit, data-loss): pins the
 * fix for a real, reproducible bug in [AppInitializer.runOnAppStart]'s
 * debug-only fixture auto-reseed.
 *
 * **The finding.** [AppInitializer.shouldAutoReseedFixture]'s
 * docstring documents the intended contract: read
 * `hasSeenOnboarding` once, "at true process start" -- i.e. before
 * onboarding's own [com.kaavalan.note.features.onboarding.OnboardingViewModel.finish]
 * has a chance to flip it to `true` in the same process -- so a
 * fresh install (or a post-`pm clear` debug artifact) skips the
 * 55-person/200-instruction fixture reseed until the *next* launch,
 * leaving onboarding's own first-run state (an empty Home after
 * Skip, or its own 6-person sample) intact.
 *
 * The pre-fix code read that flag from *inside* the fire-and-forget
 * `appScope.launch(Dispatchers.IO) { ... }` coroutine that also does
 * the reseed itself. `launch` schedules and returns immediately --
 * `runOnAppStart()` did not wait for that coroutine's body to run --
 * so whether the read happened before or after onboarding's
 * `finish()` write was entirely up to scheduling. Reproduced on 4
 * fresh installs: walking onboarding to completion (Skip, or the
 * full flow with the sample toggle) without relaunching, then
 * searching Home for 'K. Suresh' -- a name that exists only in the
 * 55-person fixture, never in onboarding's own data -- found it
 * immediately, on the same process, because the coroutine's read
 * lost the race against onboarding's `finish()` under real cold-start
 * Dispatchers.IO contention (the SQLCipher/Argon2id passphrase
 * derivation, `ensureDeviceOwner`, `databasePreflight`).
 *
 * The fix moves the gate read to run synchronously
 * (`kotlinx.coroutines.runBlocking`) on the calling thread, before
 * `runOnAppStart()` returns -- Android guarantees `Application
 * .onCreate()` (the sole caller, see [com.kaavalan.note.KaavalanApplication.onCreate])
 * fully completes before the first Activity/Compose/onboarding is
 * created, so a value captured there has no race window. Only the
 * actual reseed work (`fixtureLoader.reseedIfStale()`) remains on
 * the fire-and-forget `appScope.launch`, gated on the
 * already-captured result.
 *
 * **Why a static source scan instead of a runtime test.**
 * [AppInitializerTest]'s own comments establish why: every path
 * through `runOnAppStart()` past `System.loadLibrary("sqlcipher")`
 * -- which includes the reseed-gate logic this bug lives in -- is
 * unreachable under Robolectric, because that call throws
 * `UnsatisfiedLinkError` (no native lib on the JVM unit-test
 * classpath) and the v1.2.1 fix returns early on that throw, before
 * the gate code is ever reached. [AppInitializerTest]'s own "A6
 * debug build" test documents this as a known, accepted limitation.
 * A runtime assertion on `runOnAppStart()`'s coroutine-scheduling
 * behaviour is therefore not exercisable in this project's unit-test
 * classpath. Following the same established pattern as
 * [com.kaavalan.note.features.capture.NoteBarTest] and
 * [com.kaavalan.note.ui.home.HomeScreenTest], this test instead
 * asserts the *structural* invariant that determines the timing bug:
 * the reseed-gate read must be synchronous and must textually (and
 * therefore, given sequential execution, at runtime) precede --
 * rather than live inside -- the fire-and-forget reseed coroutine. A
 * regression back to "read the gate from inside `appScope.launch`"
 * fails this test the same way the original bug should have been
 * caught before it shipped.
 */
class AppInitializerReseedGateTimingTest {

    @Test
    fun `shouldAutoReseedFixture is read synchronously via runBlocking before the reseed coroutine is launched`() {
        val body = runOnAppStartBody()

        val runBlockingGateRead = REGEX_RUNBLOCKING_GATE_READ.find(body)
        assertNotNull(
            "runOnAppStart() must read the reseed gate synchronously with " +
                "`kotlinx.coroutines.runBlocking { shouldAutoReseedFixture() }` (or an " +
                "equivalent single-expression form) so the read completes on the " +
                "calling thread -- Application.onCreate() -- before this method " +
                "returns and before Compose/onboarding can run. A lazy read (e.g. " +
                "back inside the launch{} coroutine below) reintroduces the fix#12 " +
                "race. runOnAppStart() body was:\n$body",
            runBlockingGateRead,
        )

        val launchRange = findCallBraceBodyRange(body, "appScope.launch(")
        assertNotNull(
            "could not locate the appScope.launch(...) { ... } reseed coroutine in " +
                "runOnAppStart()",
            launchRange,
        )

        assertTrue(
            "the synchronous runBlocking gate read must occur BEFORE the " +
                "appScope.launch(...) block starts -- so the gate decision is made " +
                "at true process start rather than racing the launched coroutine. " +
                "gate read ends at index ${runBlockingGateRead!!.range.last}, launch " +
                "block starts at index ${launchRange!!.first}",
            runBlockingGateRead.range.last < launchRange.first,
        )
    }

    @Test
    fun `shouldAutoReseedFixture is NOT called from inside the fire-and-forget appScope-launch block`() {
        val body = runOnAppStartBody()

        val launchRange = findCallBraceBodyRange(body, "appScope.launch(")
        assertNotNull(
            "could not locate the appScope.launch(...) { ... } reseed coroutine in " +
                "runOnAppStart()",
            launchRange,
        )
        val launchBody = body.substring(launchRange!!.first, launchRange.second)

        // This is the exact shape of the original fix#12 bug: the gate check
        // lived inside the fire-and-forget coroutine, so its result depended on
        // whatever Dispatchers.IO scheduling happened to do relative to
        // onboarding's own finish() in the same process.
        assertFalse(
            "shouldAutoReseedFixture() must NOT be called from inside the " +
                "appScope.launch { ... } block -- that is precisely the fix#12 bug " +
                "(the gate read racing onboarding's finish() instead of happening " +
                "at true process start). launch block body was:\n$launchBody",
            launchBody.contains("shouldAutoReseedFixture("),
        )
    }

    // ---- source-scan plumbing -------------------------------------------

    private fun runOnAppStartBody(): String {
        val text = readAppInitializerSource()
        assertNotNull(
            "AppInitializer.kt must be readable off disk at $APP_INITIALIZER_PATH",
            text,
        )
        val stripped = stripComments(text!!)
        val range = findFunctionBodyRange(stripped, "fun runOnAppStart(")
        assertNotNull("could not locate the runOnAppStart() function body", range)
        return stripped.substring(range!!.first, range.second)
    }

    private fun readAppInitializerSource(): String? {
        val candidates = listOf(
            File(APP_INITIALIZER_PATH),
            File("app/$APP_INITIALIZER_PATH"),
        )
        for (f in candidates) {
            if (f.exists()) return f.readText(Charsets.UTF_8)
        }
        return null
    }

    /**
     * Crude but sufficient comment stripper: [AppInitializer.kt] has no
     * string literal containing "//" or block-comment delimiters inside
     * [AppInitializer.runOnAppStart], so a line-oriented `//`-to-end-of-line
     * strip plus a `/* ... */` strip is exact for this file. Comments in
     * this source repeatedly describe the *buggy* code shape in prose
     * (e.g. "the reseed-gate check ... used to run *inside* the ...
     * launch{} block") as part of explaining the fix -- without stripping
     * them, a naive text search for the buggy pattern would false-positive
     * on the comments describing why it's no longer there.
     */
    private fun stripComments(text: String): String {
        val noBlockComments = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL).replace(text, "")
        return noBlockComments.lineSequence().joinToString("\n") { line ->
            val idx = line.indexOf("//")
            if (idx >= 0) line.substring(0, idx) else line
        }
    }

    /**
     * Finds a top-level function's body range: `signature` (e.g.
     * `"fun runOnAppStart("`) through the matching close of its trailing
     * `{ ... }` body. Same approach as
     * [com.kaavalan.note.features.capture.NoteBarTest.findComposableBodyRange]:
     * walk the parameter list's *parens* to their balanced close first
     * (safe for signatures with lambda-default parameters), then find the
     * body `{` after that.
     */
    private fun findFunctionBodyRange(text: String, signature: String): Pair<Int, Int>? {
        val sigIdx = text.indexOf(signature)
        if (sigIdx < 0) return null
        val paramsOpen = text.indexOf('(', sigIdx)
        if (paramsOpen < 0) return null
        var parenDepth = 0
        var i = paramsOpen
        var paramsClose = -1
        while (i < text.length) {
            when (text[i]) {
                '(' -> parenDepth += 1
                ')' -> {
                    parenDepth -= 1
                    if (parenDepth == 0) {
                        paramsClose = i
                        break
                    }
                }
            }
            i += 1
        }
        if (paramsClose < 0) return null
        val openBrace = text.indexOf('{', paramsClose)
        if (openBrace < 0) return null
        var depth = 0
        var j = openBrace
        while (j < text.length) {
            when (text[j]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return openBrace to (j + 1)
                }
            }
            j += 1
        }
        return null
    }

    /**
     * Finds the full extent of a `callPrefix(...) { ... }` call -- from the
     * start of [callPrefix] through the matching close of its trailing
     * content lambda. [callPrefix] must already include the opening `(`
     * (e.g. `"appScope.launch("`) so the argument-list parens are walked
     * to their balanced close before the content lambda's `{` is located --
     * the same problem [com.kaavalan.note.features.capture.NoteBarTest.findColumnBodyRange]
     * solves for `Column(...) { ... }`.
     */
    private fun findCallBraceBodyRange(text: String, callPrefix: String): Pair<Int, Int>? {
        val callIdx = text.indexOf(callPrefix)
        if (callIdx < 0) return null
        val argsOpen = callIdx + callPrefix.length - 1 // index of the '('
        var depth = 0
        var i = argsOpen
        var argsClose = -1
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) {
                        argsClose = i
                        break
                    }
                }
            }
            i += 1
        }
        if (argsClose < 0) return null
        val contentOpen = text.indexOf('{', argsClose)
        if (contentOpen < 0) return null
        var braceDepth = 0
        var j = contentOpen
        while (j < text.length) {
            when (text[j]) {
                '{' -> braceDepth += 1
                '}' -> {
                    braceDepth -= 1
                    if (braceDepth == 0) return callIdx to (j + 1)
                }
            }
            j += 1
        }
        return null
    }

    private companion object {
        const val APP_INITIALIZER_PATH =
            "src/main/java/com/kaavalan/note/data/local/AppInitializer.kt"

        // Matches e.g. `runBlocking { shouldAutoReseedFixture() }`, with or
        // without the `kotlinx.coroutines.` qualifier, and tolerant of the
        // exact variable it's assigned to -- the invariant under test is
        // "shouldAutoReseedFixture() is the direct (single-expression)
        // body of a runBlocking { } block", not the surrounding `val` name.
        val REGEX_RUNBLOCKING_GATE_READ = Regex(
            """runBlocking\s*\{\s*shouldAutoReseedFixture\(\)\s*\}""",
        )
    }
}
