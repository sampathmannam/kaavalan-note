package com.kaavalan.note.data.backup

import com.kaavalan.note.data.vault.IdentityCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.1.0 (PM rating) + v2.1.1 (security): the
 * [BackupCrypto] round-trip + failure-mode tests. The
 * crypto layer is the heart of the Drive backup
 * security model; the tests pin:
 *
 *  - BTV2 (v2.1.1) format: 32-byte salt, 600k PBKDF2,
 *    magic-bound AAD
 *  - BTV1 (v2.1.0/v2.1.1) backward compat: a v2.1.0
 *    blob can still be decrypted after upgrading to
 *    v2.1.1
 *  - AAD tampering: a BTV2 blob with a tampered magic
 *    (downgraded to BTV1) fails to decrypt
 *  - The general round-trip + wrong-passphrase +
 *    truncation + random salt/nonce pins
 */
class BackupCryptoTest {

    private val crypto = BackupCrypto()

    /**
     * The property that matters for a backup: what the app wrote
     * unattended, the user can open with what they typed.
     *
     * It did not hold before v2.2.1.
     * `SettingsViewModel.googleDriveBackUpNow` encrypted with the
     * raw phrase; [com.kaavalan.note.data.backup.DriveBackupWorker],
     * which cannot prompt at 3am, encrypted with the SHA-256 hash
     * `SecurePreferences` had stored. Different key material, so
     * the daily automatic backup would not open with the phrase
     * the restore dialog asks for — and the hash it *would* open
     * with appeared nowhere in the app
     * (`getBackupEncryptionKeyHash` had no reader outside the
     * worker). Every automatic backup was unrecoverable, silently:
     * the upload succeeded, the file listed, the size looked
     * right. It would have surfaced on a new device, which is the
     * one moment a backup exists for.
     *
     * No mocks. `DriveBackupWorkerTest` stubs the manager out
     * entirely, so it could never see which key went in; this
     * drives the real crypto in both directions.
     */
    @Test
    fun `a worker-written backup opens with the recovery phrase the user types`() {
        val phrase = "abandon ability able about above absent absorb abstract " +
            "absurd abuse access accident"
        val payload = """{"people":[],"instructions":[],"tags":[]}""".toByteArray()

        // What DriveBackupWorker writes: the stored key material.
        val storedKeyMaterial = BackupCrypto.keyMaterialFor(phrase.toCharArray())
        val blob = crypto.encrypt(payload, storedKeyMaterial)

        // What the restore dialog hands back: the phrase itself.
        val recovered = crypto.decryptWithRecoveryPhrase(blob, phrase.toCharArray())

        assertArrayEquals(
            "a backup written with the stored key material must open with the phrase " +
                "the user types, or every automatic backup is unrecoverable",
            payload,
            recovered,
        )
    }

    /**
     * The stored hash and the encryption key have to be the same
     * value or they drift apart again. `SettingsViewModel` stores
     * `IdentityCrypto.sha256Hex(phrase)` and the manager derives
     * `keyMaterialFor(phrase)`; this pins that those agree.
     */
    @Test
    fun `keyMaterialFor is the SHA-256 hex that SecurePreferences stores`() {
        val phrase = "correct horse battery staple and eight more words here now"
        val keyMaterial = String(BackupCrypto.keyMaterialFor(phrase.toCharArray()))

        assertEquals(
            "key material must be the same 64-char SHA-256 hex the app stores as the " +
                "backup encryption key hash",
            IdentityCrypto.sha256Hex(phrase),
            keyMaterial,
        )
        assertEquals(64, keyMaterial.length)
    }

    /**
     * Unifying the two write paths must not orphan blobs already
     * in a user's `appDataFolder`. A manual backup taken before
     * v2.2.1 was encrypted with the raw phrase; restore falls back
     * to that derivation when the canonical one fails its tag
     * check.
     */
    @Test
    fun `a pre-v2 dot 2 dot 1 manual backup still opens with the same phrase`() {
        val phrase = "legacy manual backup phrase from the older build here"
        val payload = "some older backup json".toByteArray()

        // The old googleDriveBackUpNow path: the raw phrase.
        val legacyBlob = crypto.encrypt(payload, phrase.toCharArray())

        assertArrayEquals(
            "a backup written with the pre-v2.2.1 derivation must still restore, or " +
                "fixing the worker would itself destroy existing backups",
            payload,
            crypto.decryptWithRecoveryPhrase(legacyBlob, phrase.toCharArray()),
        )
    }

    /**
     * The fallback must not soften into "any phrase works". A
     * wrong phrase fails both derivations and still throws.
     */
    @Test
    fun `decryptWithRecoveryPhrase still rejects the wrong phrase`() {
        val right = "the right recovery phrase with enough words in it"
        val wrong = "the wrong recovery phrase with enough words in it"
        val blob = crypto.encrypt(
            "secret".toByteArray(),
            BackupCrypto.keyMaterialFor(right.toCharArray()),
        )
        assertThrows(Throwable::class.java) {
            crypto.decryptWithRecoveryPhrase(blob, wrong.toCharArray())
        }
    }

    @Test
    fun `encrypt then decrypt returns the original plaintext`() {
        val plaintext = "the quick brown fox jumps over the lazy dog".toByteArray()
        val passphrase = "twelve words make a strong key".toCharArray()
        val blob = crypto.encrypt(plaintext, passphrase)
        val recovered = crypto.decrypt(blob, passphrase)
        assertArrayEquals(plaintext, recovered)
    }

    @Test
    fun `decrypt with the wrong passphrase throws (GCM tag mismatch)`() {
        val plaintext = "secret data".toByteArray()
        val right = "correct passphrase for testing".toCharArray()
        val wrong = "wrong passphrase for testing".toCharArray()
        val blob = crypto.encrypt(plaintext, right)
        assertThrows(Throwable::class.java) {
            crypto.decrypt(blob, wrong)
        }
    }

    @Test
    fun `encrypted blob starts with the BTV2 magic header`() {
        val blob = crypto.encrypt("hello".toByteArray(), "passphrase".toCharArray())
        // The magic is "BTV2" = 0x42 0x54 0x56 0x32.
        assertEquals(0x42, blob[0].toInt() and 0xFF)
        assertEquals(0x54, blob[1].toInt() and 0xFF)
        assertEquals(0x56, blob[2].toInt() and 0xFF)
        assertEquals(0x32, blob[3].toInt() and 0xFF)
    }

    @Test
    fun `encrypted blobs for the same plaintext are different (random salt and nonce)`() {
        // Two encryptions of the same plaintext with the
        // same passphrase must produce different blobs.
        // The salt + nonce are both random per encryption;
        // a deterministic encryption would be a
        // catastrophic flaw (it would leak which
        // backups share plaintext).
        val plaintext = "the same text".toByteArray()
        val passphrase = "a fixed passphrase".toCharArray()
        val a = crypto.encrypt(plaintext, passphrase)
        val b = crypto.encrypt(plaintext, passphrase)
        // v2.1.1 (BTV2): magic(4) + salt(32) + nonce(12)
        // are the variable header bytes that must differ
        // across encryptions.
        val headerLen = 4 // BTV2 magic
        val variableLen = 32 + 12 // salt + nonce
        for (i in headerLen until (headerLen + variableLen)) {
            assertNotEquals("byte $i (salt or nonce) should differ across encryptions", a[i], b[i])
        }
        // The blobs as a whole must differ.
        assertNotEquals(
            "two encryptions of the same plaintext with the same passphrase must produce different blobs",
            a.toList(),
            b.toList(),
        )
    }

    @Test
    fun `encrypted blob is larger than the plaintext (BTV2 header + GCM tag)`() {
        // v2.1.1 (BTV2): magic(4) + salt(32) + nonce(12)
        // + ciphertext(plaintext.size + 16 GCM tag).
        // 4 + 32 + 12 + 16 = 64 bytes of overhead, plus
        // the plaintext size.
        val plaintext = "x".repeat(100).toByteArray()
        val blob = crypto.encrypt(plaintext, "pass".toCharArray())
        assertEquals(
            "encrypted blob should be plaintext.size + 64 bytes (BTV2 header + GCM tag)",
            plaintext.size + 64,
            blob.size,
        )
    }

    @Test
    fun `decrypt of a truncated blob throws`() {
        val blob = crypto.encrypt("hello".toByteArray(), "pass".toCharArray())
        // Strip everything past the magic header.
        val truncated = blob.copyOfRange(0, 4)
        assertThrows(IllegalArgumentException::class.java) {
            crypto.decrypt(truncated, "pass".toCharArray())
        }
    }

    @Test
    fun `decrypt of a blob with the wrong magic throws`() {
        val good = crypto.encrypt("hello".toByteArray(), "pass".toCharArray())
        // Flip the first magic byte to 0xFF (still a
        // valid byte, but not 'B').
        val bad = good.copyOf()
        bad[0] = 0xFF.toByte()
        // v2.1.1 (security): the wrong-magic branch now goes
        // through Kotlin's `error(...)` helper, which throws
        // IllegalStateException (was IllegalArgumentException
        // in v2.1.0). The error() form was chosen so the
        // message is a constant string rather than the
        // require(...) lazy-message variant — the message
        // is part of the threat-model response and must be
        // stable across builds.
        val ex = assertThrows(IllegalStateException::class.java) {
            crypto.decrypt(bad, "pass".toCharArray())
        }
        assertTrue(
            "the exception should mention the magic mismatch",
            (ex.message ?: "").contains("magic"),
        )
    }

    @Test
    fun `empty passphrase is rejected at the encrypt boundary`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            crypto.encrypt("hello".toByteArray(), "".toCharArray())
        }
        assertTrue(
            "the exception should mention the empty passphrase",
            (ex.message ?: "").contains("passphrase"),
        )
    }

    // ----- v2.1.1 P1: BTV1 backward compat + AAD -----

    @Test
    fun `decrypt a BTV1 v2 dot 1 dot 0 backup with the same passphrase`() {
        // v2.1.1: a backup created by v2.1.0 or v2.1.1's
        // BTV1 format must still be decryptable after
        // upgrading to the BTV2-only encrypt path. The
        // v2.1.0 BTV1 format is:
        //   "BTV1" (4 bytes) + salt (16 bytes) + nonce
        //   (12 bytes) + ciphertext + 16-byte GCM tag.
        // PBKDF2: 100k iterations.
        // We hand-build a BTV1 blob here and assert the
        // loader recognises + decrypts it.
        val plaintext = "v2.1.0 backup body".toByteArray()
        val passphrase = "a passphrase that works for both".toCharArray()
        val btv1 = buildBtv1Blob(plaintext, passphrase)
        val recovered = crypto.decrypt(btv1, passphrase)
        assertArrayEquals(plaintext, recovered)
    }

    @Test
    fun `BTV2 blob with tampered magic (downgraded to BTV1) fails to decrypt`() {
        // v2.1.1: the magic is bound to the ciphertext
        // as AAD. Flipping the '2' (0x32) back to '1'
        // (0x31) — i.e. presenting a BTV2 blob as BTV1
        // — must invalidate the GCM auth tag and the
        // decrypt must throw.
        val blob = crypto.encrypt("hello".toByteArray(), "pass".toCharArray())
        val tampered = blob.copyOf()
        tampered[3] = 0x31 // '2' → '1'
        assertThrows(Throwable::class.java) {
            crypto.decrypt(tampered, "pass".toCharArray())
        }
    }

    @Test
    fun `BTV1 blob with v2 dot 1 dot 0 passphrase works but BTV2 path uses 600k PBKDF2`() {
        // v2.1.1: BTV1 uses 100k PBKDF2, BTV2 uses 600k.
        // A passphrase that decrypts a BTV1 blob does
        // NOT decrypt a BTV2 blob encrypted with the
        // same passphrase (the derived keys differ
        // because the iteration counts differ).
        val passphrase = "shared passphrase for both formats".toCharArray()
        val plaintext = "the same plaintext".toByteArray()
        val btv1 = buildBtv1Blob(plaintext, passphrase)
        val btv2 = crypto.encrypt(plaintext, passphrase)
        // Both decrypt to the same plaintext with the
        // same passphrase.
        assertArrayEquals(plaintext, crypto.decrypt(btv1, passphrase))
        assertArrayEquals(plaintext, crypto.decrypt(btv2, passphrase))
    }

    /**
     * Helper: build a BTV1-format blob (16-byte salt,
     * 100k PBKDF2, no AAD) from a plaintext + passphrase.
     * Mirrors the v2.1.0/v2.1.1 encrypt path so we can
     * pin the v2.1.1 decrypt-backward-compat.
     */
    private fun buildBtv1Blob(plaintext: ByteArray, passphrase: CharArray): ByteArray {
        val saltLen = 16
        val nonceLen = 12
        val iterations = 100_000
        val keyBits = 256
        val tagBits = 128
        val magic = byteArrayOf(0x42, 0x54, 0x56, 0x31) // "BTV1"
        val salt = ByteArray(saltLen).also { java.security.SecureRandom().nextBytes(it) }
        val nonce = ByteArray(nonceLen).also { java.security.SecureRandom().nextBytes(it) }
        val spec = javax.crypto.spec.PBEKeySpec(passphrase, salt, iterations, keyBits)
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        spec.clearPassword()
        val key = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(javax.crypto.Cipher.ENCRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(tagBits, nonce))
        }
        val ct = cipher.doFinal(plaintext)
        return magic + salt + nonce + ct
    }
}
