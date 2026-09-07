package com.kaavalan.note.data.backup

import android.util.Base64
import com.kaavalan.note.data.vault.IdentityCrypto
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v2.1.0 (PM rating) + v2.1.1 (security): the AES-256-GCM
 * encryption used for the Google Drive backup blob.
 *
 * **Why encrypt.** The Drive backup lands in the user's
 * `appDataFolder` (a hidden per-app storage). Google
 * never sees the bytes — but the bytes are also
 * accessible to any process that has read access to
 * the user's Google account (e.g. a future Drive API
 * client with the right scope, or Google's own
 * services that surface the file to the user on
 * demand). Encrypting client-side is the right
 * defence: the threat model says "no third party ever
 * sees the bytes in clear", and that includes the
 * cloud storage.
 *
 * **Why AES-256-GCM.** Industry-standard AEAD. 256-bit
 * key, 96-bit nonce, 128-bit auth tag. The nonce is
 * generated per encryption via [SecureRandom].
 *
 * **Why PBKDF2 from the recovery phrase.** The user
 * already has a 12-word recovery phrase (set up in
 * the v2.0+ recovery flow). Using the phrase as the
 * key source means:
 *
 *  1. The user has one secret to remember.
 *  2. The key is portable — on a new device, the user
 *     enters the phrase and can decrypt any past
 *     backup.
 *  3. The key never leaves the device. It is not
 *     uploaded to Drive.
 *
 * PBKDF2-HMAC-SHA256 with **600,000** iterations (v2.1.1
 * — was 100,000 in v2.1.0/v2.1.1's BTV1 format) + a
 * per-backup random 32-byte salt (v2.1.1 — was 16 bytes
 * in BTV1) gives ~600ms of derivation time on a Pixel 6,
 * which matches OWASP 2023+ guidance
 * (https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html#pbkdf2).
 *
 * **The format versions.** The on-disk blob is
 * version-prefixed:
 *
 * ```
 *   "BTV1" (4 bytes) — v2.1.0/v2.1.1's original format:
 *     salt (16 bytes)
 *     nonce (12 bytes)
 *     ciphertext (variable, includes the GCM tag)
 *     AAD: (none)
 *     PBKDF2 iterations: 100,000
 *
 *   "BTV2" (4 bytes) — v2.1.1+ format:
 *     salt (32 bytes)
 *     nonce (12 bytes)
 *     ciphertext (variable, includes the GCM tag)
 *     AAD: the magic bytes themselves ("BTV2")
 *     PBKDF2 iterations: 600,000
 * ```
 *
 * The magic is a version sentinel: a future v3.x can
 * add "BTV3" and the loader can branch. BTV1 backups
 * created by v2.1.0/v2.1.1 can still be **decrypted**
 * (so users with existing backups can restore after
 * upgrading) — the [encrypt] path only writes BTV2.
 *
 * **The AAD.** v2.1.1 binds the magic ("BTV2") to the
 * ciphertext as Additional Authenticated Data. GCM
 * mixes the AAD into the auth tag, so any tampering
 * with the magic (e.g. downgrading a BTV2 blob to a
 * BTV1 header) invalidates the tag and [decrypt]
 * throws. This blocks the "rewrite-the-header" attack
 * that v2.1.0's BTV1 was vulnerable to.
 */
@Singleton
class BackupCrypto @Inject constructor() {

    /**
     * Encrypt [plaintext] using a key derived from
     * [passphrase]. Returns the BTV2 format-encoded
     * blob (magic + salt + nonce + ciphertext).
     */
    fun encrypt(plaintext: ByteArray, passphrase: CharArray): ByteArray {
        require(passphrase.isNotEmpty()) { "passphrase must not be empty" }
        val salt = ByteArray(SALT_LEN_V2).also { SecureRandom().nextBytes(it) }
        val key: SecretKey = deriveKey(passphrase, salt, PBKDF2_ITERATIONS_V2)
        val nonce = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            // v2.1.1: bind the magic to the ciphertext
            // as AAD. GCM mixes AAD into the auth tag;
            // any change to the magic (e.g. downgrading
            // a BTV2 blob to a BTV1 header) invalidates
            // the tag and [decrypt] throws.
            init(
                Cipher.ENCRYPT_MODE,
                key,
                GCMParameterSpec(TAG_BITS, nonce),
            )
        }
        cipher.updateAAD(MAGIC_V2)
        val ciphertext = cipher.doFinal(plaintext)
        return MAGIC_V2 + salt + nonce + ciphertext
    }

    /**
     * Decrypt a Drive backup given the user's recovery
     * phrase, whichever of the two key derivations
     * wrote it.
     *
     * **Why two.** Until v2.2.1 the two write paths
     * disagreed. `SettingsViewModel.googleDriveBackUpNow`
     * passed the phrase straight through, while
     * [com.kaavalan.note.data.backup.DriveBackupWorker] —
     * which cannot prompt for a phrase at 3am — passed the
     * SHA-256 hash that `SecurePreferences` had stored. Two
     * different key sources, so the daily automatic backup
     * could not be opened with the phrase the restore
     * dialog asks for, and the hash it *could* be opened
     * with was never shown anywhere in the app. Every
     * automatic backup was unreadable, silently: the upload
     * succeeded, the file listed, the size looked right.
     *
     * v2.2.1 makes [keyMaterialFor] the one key source, so
     * new backups from either path open with the phrase.
     * The fallback below is for blobs already sitting in a
     * user's `appDataFolder` from a manual backup taken
     * before that change; without it, unifying the paths
     * would itself have made those unreadable.
     *
     * The legacy attempt only runs after the canonical one
     * has failed its GCM tag check, so the normal path
     * costs one PBKDF2, not two.
     */
    fun decryptWithRecoveryPhrase(blob: ByteArray, recoveryPhrase: CharArray): ByteArray {
        return try {
            decrypt(blob, keyMaterialFor(recoveryPhrase))
        } catch (canonicalFailure: Throwable) {
            try {
                decrypt(blob, recoveryPhrase)
            } catch (_: Throwable) {
                // Report the canonical failure: for anything
                // written by a current build, that is the real one.
                throw canonicalFailure
            }
        }
    }

    /**
     * Decrypt [blob] (in BTV1 or BTV2 format) using a
     * key derived from [passphrase]. Throws on:
     *  - unknown magic (not a Kaavalan note backup)
     *  - truncated blob
     *  - wrong passphrase (GCM tag mismatch)
     *  - AAD tampering (BTV2 only)
     */
    fun decrypt(blob: ByteArray, passphrase: CharArray): ByteArray {
        require(blob.size >= 4) { "blob is too short to be a Kaavalan note backup" }
        return when {
            startsWith(blob, MAGIC_V2) -> decryptV2(blob, passphrase)
            startsWith(blob, MAGIC_V1) -> decryptV1(blob, passphrase)
            else -> error("blob is not a Kaavalan note backup (magic mismatch)")
        }
    }

    private fun decryptV2(blob: ByteArray, passphrase: CharArray): ByteArray {
        require(blob.size >= HEADER_LEN_V2) { "BTV2 blob is truncated" }
        val salt = blob.copyOfRange(MAGIC_V2.size, MAGIC_V2.size + SALT_LEN_V2)
        val nonce = blob.copyOfRange(
            MAGIC_V2.size + SALT_LEN_V2,
            MAGIC_V2.size + SALT_LEN_V2 + NONCE_LEN,
        )
        val ciphertext = blob.copyOfRange(
            MAGIC_V2.size + SALT_LEN_V2 + NONCE_LEN,
            blob.size,
        )
        val key = deriveKey(passphrase, salt, PBKDF2_ITERATIONS_V2)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        }
        // AAD must match the AAD used at encrypt time.
        cipher.updateAAD(MAGIC_V2)
        return cipher.doFinal(ciphertext)
    }

    private fun decryptV1(blob: ByteArray, passphrase: CharArray): ByteArray {
        // v2.1.1: read a v2.1.0 / v2.1.1 (BTV1) backup.
        // The format is 16-byte salt, 100k PBKDF2, no
        // AAD. We keep this path so users with existing
        // backups can still restore after upgrading.
        require(blob.size >= HEADER_LEN_V1) { "BTV1 blob is truncated" }
        val salt = blob.copyOfRange(MAGIC_V1.size, MAGIC_V1.size + SALT_LEN_V1)
        val nonce = blob.copyOfRange(
            MAGIC_V1.size + SALT_LEN_V1,
            MAGIC_V1.size + SALT_LEN_V1 + NONCE_LEN,
        )
        val ciphertext = blob.copyOfRange(
            MAGIC_V1.size + SALT_LEN_V1 + NONCE_LEN,
            blob.size,
        )
        val key = deriveKey(passphrase, salt, PBKDF2_ITERATIONS_V1)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        }
        return cipher.doFinal(ciphertext)
    }

    /**
     * PBKDF2-HMAC-SHA256. The iteration count is
     * parameterised so [decryptV1] and [decryptV2] can
     * each use the count their format was encrypted
     * with.
     */
    private fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int,
    ): SecretKey {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        // v2.1.1: zero the PBE key spec's internal
        // passphrase copy when done. The PBEKeySpec
        // spec is silent on whether the JCE provider
        // zeroes the chars on `clearPassword`; we
        // explicitly zero here as a best-effort.
        spec.clearPassword()
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun startsWith(blob: ByteArray, prefix: ByteArray): Boolean {
        if (blob.size < prefix.size) return false
        for (i in prefix.indices) {
            if (blob[i] != prefix[i]) return false
        }
        return true
    }

    companion object {
        /**
         * The one key source for a Drive backup: the SHA-256
         * hex of the recovery phrase.
         *
         * Both write paths derive from this, so what
         * [DriveBackupWorker] writes unattended opens with the
         * phrase the restore dialog asks for. It is also
         * exactly the value
         * `SecurePreferences.setBackupEncryptionKeyHash`
         * stores, so the stored value and the encryption key
         * cannot drift apart — the drift is what broke every
         * automatic backup before v2.2.1.
         *
         * The hash, not the phrase, because the worker needs
         * usable key material at rest and the phrase is the
         * master secret other keys derive from. That does mean
         * someone holding both the unlocked device and the
         * Google account can open a backup; the threat-model
         * screen says so.
         */
        fun keyMaterialFor(recoveryPhrase: CharArray): CharArray =
            IdentityCrypto.sha256Hex(String(recoveryPhrase)).toCharArray()

        // "BTV1" — Kaavalan note encrypted-backup Version 1
        // (v2.1.0 / v2.1.1's original format). Kept
        // here so [decrypt] can recognise + restore
        // old backups. The 'B' = 0x42, 'T' = 0x54,
        // 'V' = 0x56, '1' = 0x31.
        private val MAGIC_V1 = byteArrayOf(0x42, 0x54, 0x56, 0x31)
        // "BTV2" — v2.1.1+ format. 32-byte salt, 600k
        // PBKDF2, magic-bound AAD. The '2' = 0x32.
        private val MAGIC_V2 = byteArrayOf(0x42, 0x54, 0x56, 0x32)
        // BTV1 layout.
        private const val SALT_LEN_V1 = 16
        private const val PBKDF2_ITERATIONS_V1 = 100_000
        private val HEADER_LEN_V1 = MAGIC_V1.size + SALT_LEN_V1 + NONCE_LEN
        // BTV2 layout.
        private const val SALT_LEN_V2 = 32
        private const val PBKDF2_ITERATIONS_V2 = 600_000
        private val HEADER_LEN_V2 = MAGIC_V2.size + SALT_LEN_V2 + NONCE_LEN
        // Shared.
        private const val NONCE_LEN = 12
        private const val TAG_BITS = 128
        private const val KEY_BITS = 256
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /**
         * Encode a passphrase-derived key as a
         * Base64 string for storage in SharedPreferences.
         * The salt is a separate random value embedded
         * with the ciphertext; this helper just returns
         * the key bytes for callers that want to use the
         * native Android Keystore for the salt.
         */
        fun toBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

        fun fromBase64(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)
    }
}
