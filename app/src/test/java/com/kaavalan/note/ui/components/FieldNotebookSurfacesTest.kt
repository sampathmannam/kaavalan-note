package com.kaavalan.note.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2.7 field-notebook rules, scanned the same cheap way [DesignRulesTest] scans the
 * older ADHD rules: no Compose, no Robolectric, runs on every PR.
 *
 * Each test here corresponds to a rule that is written down in AGENTS.md, PRODUCT.md or
 * DESIGN.md, so breaking the rule in code breaks a test rather than only a review.
 */
class FieldNotebookSurfacesTest {

    private val uiRoot = File("src/main/java/com/kaavalan/note")

    private fun read(path: String): String = File(uiRoot, path).readText(Charsets.UTF_8)

    private fun uiSources(): List<File> = uiRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .filter {
            val p = it.absolutePath.replace('\\', '/')
            p.contains("/ui/") || p.contains("/features/")
        }
        .toList()

    /** Comments document the rules; only shipped code can violate them. */
    private fun code(text: String): String = text.lineSequence()
        .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
        .joinToString("\n")

    // ---------------------------------------------------------------- navigation

    @Test
    fun exactlyThreeWorkingDestinations_noFourthTab() {
        assertEquals(
            "AGENTS.md: Tabs = 3 (Today, Instructions, Contacts). Settings is a top-bar action.",
            "enum class WorkspaceTab { TODAY, INSTRUCTIONS, CONTACTS }",
            read("ui/workspace/WorkspaceModel.kt").lineSequence().first { it.startsWith("enum class WorkspaceTab") },
        )
        val nav = read("ui/workspace/OfficerAppRoot.kt").substringAfter("private fun WorkspaceNavigation")
            .substringBefore("\n}\n")
        assertEquals(
            "The navigation bar must list exactly the three working destinations.",
            3,
            Regex("""Triple\(Routes\.""").findAll(nav).count(),
        )
    }

    @Test
    fun eachWorkingTabOffersOneLabelledSubdivisionEntry_notAFourthTab() {
        val screen = read("ui/workspace/WorkspaceScreen.kt")
        listOf(
            "entry_subdivision_review",
            "entry_matters",
            "entry_stations_staff",
        ).forEach { assertTrue("DESIGN.md: missing subdivision entry $it", screen.contains(it)) }
    }

    // ---------------------------------------------------------------- restraint

    @Test
    fun noGradientsOrGlassAnywhereInTheUi() {
        val offenders = uiSources().filter { f ->
            val text = code(f.readText(Charsets.UTF_8))
            listOf("linearGradient", "verticalGradient", "radialGradient", "sweepGradient", ".blur(")
                .any { text.contains(it) }
        }
        assertTrue(
            "DESIGN.md: no ornamental gradients or glass. Offenders: " +
                offenders.joinToString { it.name },
            offenders.isEmpty(),
        )
    }

    @Test
    fun noStreakRankingOrScoringLanguageInTheUi() {
        val offenders = mutableListOf<String>()
        uiSources().forEach { f ->
            val text = code(f.readText(Charsets.UTF_8))
            listOf("streak", "leaderboard", "ranking").forEach { word ->
                if (Regex("""\b$word\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                    offenders.add("${f.name}: $word")
                }
            }
        }
        assertTrue(
            "PRODUCT.md: no streaks, ranking or productivity scoring. Offenders: $offenders",
            offenders.isEmpty(),
        )
    }

    // ---------------------------------------------------------------- legibility

    @Test
    fun badgesNeverHardClipTheirOwnLabel() {
        val badge = read("ui/components/KaavalanSurfaces.kt")
            .substringAfter("fun KaavalanBadge").substringBefore("fun KaavalanSectionHeading")
        assertTrue(
            "A badge label that runs past one line at 150% text must ellipsise, not clip.",
            badge.contains("overflow = TextOverflow.Ellipsis"),
        )
        assertFalse("maxLines = 1 with no overflow hard-clips the label.", badge.contains("maxLines = 1,"))
    }

    @Test
    fun theSharedEmptyStateKeepsThePageInsetAndOffersARealControl() {
        val emptyState = read("ui/components/KaavalanSurfaces.kt").substringAfter("fun KaavalanEmptyState")
        assertTrue(
            "DESIGN.md: page inset is 20dp; the empty state must not indent itself past the list it replaces.",
            emptyState.contains("horizontal = KaavalanSpacing.screen"),
        )
        assertTrue(
            "The single action in an empty state is a control at a 48dp target, not a bare link.",
            emptyState.contains("OutlinedButton(") && emptyState.contains("heightIn(min = 48.dp)"),
        )
        assertFalse("A bare TextButton carries its own padding and breaks the inset.", emptyState.contains("TextButton("))
    }

    @Test
    fun everyWorkingSurfaceUsesTheSharedEmptyState() {
        assertTrue(
            "WorkspaceScreen must route its empty / error blocks through KaavalanEmptyState.",
            read("ui/workspace/WorkspaceScreen.kt").contains("KaavalanEmptyState("),
        )
    }

    // ---------------------------------------------------------------- hierarchy

    @Test
    fun todayLeadsWithTheLocalDateInTheTitleBlock_notASeparateDateCard() {
        val screen = read("ui/workspace/WorkspaceScreen.kt")
        assertTrue(
            "DESIGN.md: Today starts with the local date.",
            screen.contains("""date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM"))"""),
        )
        assertFalse(
            "A card that only restates the date pushes the one labelled subdivision action " +
                "away from the top bar.",
            screen.contains("\"Duty book\""),
        )
    }

    @Test
    fun theOpenWorkspaceIsNamedOnEveryTabWhenItIsPrivate() {
        assertTrue(
            "PRODUCT.md: private mode must never be ambiguous about which workspace is open.",
            read("ui/workspace/WorkspaceScreen.kt").contains("private_workspace_badge"),
        )
    }

    // ---------------------------------------------------------------- consistency

    @Test
    fun everySettingsCategoryCarriesAnIcon() {
        val sheet = read("ui/settings/SettingsSheet.kt")
        val categories = Regex("""SettingsCategory\(""").findAll(sheet).count()
        val withIcon = Regex("""SettingsCategory\([^\n]*icon = Icons\.""").findAll(sheet).count()
        assertTrue("Settings has no categories to check — the scan is wrong.", categories > 0)
        assertEquals(
            "Settings categories must read like the rest of the workspace's entry rows.",
            categories,
            withIcon,
        )
    }

    @Test
    fun subdivisionListRowsLeadWithMeaningRatherThanDecoration() {
        val components = read("ui/subdivision/SubdivisionComponents.kt")
            .substringAfter("fun SubdivisionListRow")
        assertTrue(
            "A list row's leading element must be the icon of the kind of record it is.",
            components.contains("icon: ImageVector? = null") && components.contains("KaavalanIconTile("),
        )
        assertFalse(
            "A coloured dot inside a tile is decoration with no information.",
            components.contains("CircleShape"),
        )
    }

    @Test
    fun staffFirstUseHasOneAddControl() {
        val staff = read("ui/subdivision/StationsAndStaffScreen.kt")
            .substringAfter("private fun StaffList")
            .substringBefore("fun StationDetailScreen")
        assertTrue(
            "The empty Staff list must hide its count-row Add control.",
            staff.contains("if (!firstUse)"),
        )
        assertTrue(
            "The one visible first-use action must retain the device-test hook.",
            staff.contains("actionTestTag = if (firstUse) \"add_from_contacts\" else null"),
        )
    }
}
