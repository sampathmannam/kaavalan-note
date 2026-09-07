package com.kaavalan.note.build

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * v2.1.2 (release-integrity): a finding test for `proguard-rules.pro`.
 *
 * **The finding.** Every app-specific `-keep` rule in
 * `proguard-rules.pro` still named the pre-v2.1.1 `com.baton.app`
 * package after the brand rename moved the code to
 * `com.kaavalan.note`. Twelve rules — the entry-point keeps, the
 * `AppDatabase` keep and the kotlinx-serialization `$$serializer`
 * keep among them — matched nothing at all.
 *
 * The reason that survived eight releases is that **R8 does not
 * warn about a keep rule that matches zero classes.** A stale rule
 * is indistinguishable from a satisfied one in the build log. The
 * only symptom is a release-only crash when R8 strips or renames
 * something a rule was supposed to protect, and the release build
 * had itself been broken since v2.1.0 (no keystore on a clean
 * checkout), so nobody ran R8 to find out.
 *
 * **What this test does.** It parses every fully-qualified
 * `com.kaavalan.note.*` class name out of the rules file and
 * asserts each one resolves to a real source file. A rename or a
 * package move that leaves a rule behind now fails
 * `testDebugUnitTest` on the next run instead of surfacing as a
 * field report months later.
 *
 * Wildcard rules (`com.kaavalan.note.**`) and structural rules
 * (`extends CoroutineWorker`) are deliberately not checked — they
 * are the rot-resistant form and are what the fix moved toward.
 */
class ProguardRulesTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("could not locate the repository root from ${File("").absolutePath}")

    private val rulesFile = File(repoRoot, "app/proguard-rules.pro")
    private val sourceRoot = File(repoRoot, "app/src/main/java")

    /**
     * Guards the guard. If the working directory ever changes so
     * that the two paths above stop resolving, every other
     * assertion in this class would vacuously pass over an empty
     * list — the exact failure mode that let the stale rules
     * survive. Fail loudly instead.
     */
    @Test
    fun `rules file and source root are actually found`() {
        assertTrue(
            "proguard-rules.pro not found at ${rulesFile.absolutePath}",
            rulesFile.isFile,
        )
        assertTrue(
            "main source root not found at ${sourceRoot.absolutePath}",
            sourceRoot.isDirectory,
        )
    }

    @Test
    fun `no keep rule names the pre-rename com_baton_app package`() {
        val offenders = rulesFile.readLines()
            .withIndex()
            .filter { (_, line) -> !line.trimStart().startsWith("#") }
            .filter { (_, line) -> line.contains("com.baton.app") }
            .map { (i, line) -> "  line ${i + 1}: ${line.trim()}" }

        if (offenders.isNotEmpty()) {
            fail(
                "proguard-rules.pro still references the pre-v2.1.1 package " +
                    "`com.baton.app`, which no longer exists. These rules match " +
                    "nothing and R8 will not warn about them:\n" +
                    offenders.joinToString("\n"),
            )
        }
    }

    @Test
    fun `every fully-qualified keep rule resolves to a real class`() {
        val fqnPattern = Regex("""com\.kaavalan\.note\.[A-Za-z0-9_.$]+""")
        val checked = mutableListOf<String>()
        val missing = mutableListOf<String>()

        rulesFile.readLines().forEachIndexed { index, line ->
            if (line.trimStart().startsWith("#")) return@forEachIndexed
            fqnPattern.findAll(line).forEach { match ->
                val raw = match.value
                // Skip wildcard rules — `com.kaavalan.note.**` is the
                // rot-resistant form and has no single class to resolve.
                if (raw.contains("*")) return@forEach
                // `BriefNotifier$*` and friends: resolve the outer class.
                val outer = raw.substringBefore('$').trimEnd('.')
                val relative = outer.replace('.', '/') + ".kt"
                checked += outer
                if (!File(sourceRoot, relative).isFile) {
                    // A class may legitimately live in a file named after
                    // something else (multiple declarations per file), so
                    // fall back to a declaration search before failing.
                    val simpleName = outer.substringAfterLast('.')
                    val declaredSomewhere = sourceRoot.walkTopDown()
                        .filter { it.isFile && it.extension == "kt" }
                        .any { file ->
                            Regex("""\b(class|object|interface)\s+$simpleName\b""")
                                .containsMatchIn(file.readText())
                        }
                    if (!declaredSomewhere) {
                        missing += "  line ${index + 1}: $outer (expected $relative)"
                    }
                }
            }
        }

        assertTrue(
            "expected proguard-rules.pro to contain at least a few fully-qualified " +
                "com.kaavalan.note keep rules; found none, which means this test is " +
                "not actually checking anything",
            checked.size >= 5,
        )

        if (missing.isNotEmpty()) {
            fail(
                "proguard-rules.pro names ${missing.size} class(es) that no longer " +
                    "exist. R8 silently ignores keep rules that match nothing, so " +
                    "these protect nothing:\n" + missing.joinToString("\n"),
            )
        }
    }
}
