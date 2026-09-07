package com.kaavalan.note.ui.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2.0 T3-3 (threat-model-led settings copy) static copy
 * tests.
 *
 * The spec is a copy-only change: the new copy must be
 * present in `strings.xml` and the Cryptee-framed text must
 * read end-to-end. We don't have a UI test for the full
 * screen render (Robolectric can't run Compose UI tests in
 * this repo — see `qa-patterns.md` §1.0) so this test
 * exercises the copy via `strings.xml` file reads.
 *
 * The threat model in Cryptee's framing covers four
 * situations: (1) data does not leave the device, (2) the
 * on-disk vault is encrypted, (3) the device-unlocked
 * adversary is the threat model boundary, (4) backup exports
 * are AES-256-GCM encrypted with a user-known passphrase.
 * We assert each of the four headings is present in
 * `strings.xml`.
 */
class ThreatModelCopyTest {

    private val stringsFile: File =
        File("src/main/res/values/strings.xml").absoluteFile

    private fun source(): String = stringsFile.readText(Charsets.UTF_8)

    @Test
    fun `strings xml file is at the expected path`() {
        assertTrue(
            "strings.xml must exist at ${stringsFile.absolutePath}",
            stringsFile.exists(),
        )
    }

    @Test
    fun `threat model section 'never leave this device' is present`() {
        val text = source()
        // v2.0 T3-3: the explicit "Your notes never leave this
        // device" copy. The user-facing string is the lead of
        // the threat-model section, per the Cryptee framing.
        assertTrue(
            "T3-3: threat model lead copy must be present",
            text.contains("never leave this device"),
        )
    }

    @Test
    fun `threat model section 'encrypted on disk' is present`() {
        val text = source()
        assertTrue(
            "T3-3: 'encrypted on disk' copy must be present",
            text.contains("encrypted on disk"),
        )
    }

    @Test
    fun `threat model section 'seized while unlocked' is present`() {
        val text = source()
        assertTrue(
            "T3-3: 'seized while unlocked' copy must be present",
            text.contains("seized while unlocked"),
        )
    }

    @Test
    fun `threat model section 'AES-256-GCM' backup copy is present`() {
        val text = source()
        assertTrue(
            "T3-3: 'AES-256-GCM' backup copy must be present",
            text.contains("AES-256-GCM"),
        )
    }

    @Test
    fun `threat model string keys exist for the full-screen view`() {
        val text = source()
        // The Settings -> Privacy -> Threat model row
        // navigates to the full-screen view; both
        // destinations must declare their string keys.
        listOf(
            "threat_model_title",
            "threat_model_back",
            "threat_model_lead",
            "threat_model_section_storage",
            "threat_model_section_storage_body",
            "threat_model_section_locked",
            "threat_model_section_locked_body",
            "threat_model_section_unlocked",
            "threat_model_section_unlocked_body",
            "threat_model_section_backup",
            "threat_model_section_backup_body",
            "threat_model_section_vault",
            "threat_model_section_vault_body",
            "threat_model_closing",
        ).forEach { key ->
            assertTrue("T3-3: $key must be declared in strings.xml", text.contains("name=\"$key\""))
        }
    }

    @Test
    fun `settings_privacy section is present and contains threat model row`() {
        val text = source()
        assertTrue("settings_section_privacy must be present", text.contains("settings_section_privacy"))
        assertTrue("settings_threat_model must be present", text.contains("settings_threat_model"))
        assertTrue("settings_threat_model_value must be present", text.contains("settings_threat_model_value"))
    }

    @Test
    fun `deniable vault copy is present and names it as a UI filter, not crypto deniability`() {
        // The settings copy for the hidden vault is
        // mandatory: it must tell the user the hidden mode
        // is a UI filter, not cryptographic deniability.
        val text = source()
        assertTrue(
            "T3-3: hidden vault body must mention 'UI filter' / 'behavioural'",
            text.contains("UI filter"),
        )
        assertTrue(
            "T3-3: hidden vault body must mention 'behavioural' (or 'behavioural concealment')",
            text.contains("behavioural"),
        )
    }

    /**
     * v2.2.1: the backup copy used to say "The passphrase is not stored
     * on the device."
     *
     * That was true while the only backup was the manual one. The daily
     * `DriveBackupWorker` runs unattended and cannot prompt for a
     * phrase, so it encrypts with the key material `SecurePreferences`
     * holds -- the SHA-256 of the phrase, which since v2.2.1 is exactly
     * what `BackupCrypto` derives the key from on both paths. A copy of
     * the backup key is therefore on the device, in
     * EncryptedSharedPreferences, and the one screen that exists to tell
     * the user what protects their data was telling them the opposite.
     *
     * A threat-model page that overstates the protection is worse than
     * no page: it is what a user reads before deciding what is safe to
     * keep in the app.
     */
    @Test
    fun `backup copy does not claim the key is absent from the device`() {
        val text = source()
        assertFalse(
            "the daily backup encrypts with key material held in " +
                "EncryptedSharedPreferences, so the copy must not claim the " +
                "passphrase is not stored on the device",
            text.contains("passphrase is not stored on the device"),
        )
        assertTrue(
            "the backup copy must say where the key is kept",
            text.contains("kept in encrypted app storage on this device"),
        )
    }
}
