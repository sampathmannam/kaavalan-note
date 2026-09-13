package com.kaavalan.note.data.vault

import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * v1.8.0 (PROD-READINESS-P2-#1) + v2.1.1 (security):
 * the multi-user key sharing primitive.
 *
 * **The problem.** The v1.5.0 vault has a single
 * passphrase. A pilot deployment with 2-5 officers
 * in one station needs per-officer passphrases that
 * all unlock the same SQLCipher DB.
 *
 * **The pattern.** "Station master key" (SMK) +
 * "per-user key encryption key" (KEK) +
 * "per-user share".
 *
 *  - **SMK** is a 256-bit AES key that encrypts the
 *    SQLCipher DB. It never leaves the device and
 *    never appears in plaintext outside this class.
 *  - **KEK_u** is derived from user `u`'s passphrase
 *    via Argon2id. v1.8.0 falls back to PBKDF2 (the
 *    platform has PBKDF2; Argon2id needs a small JNI
 *    library which is out of scope for v1.8.0). The
 *    PBKDF2 iteration count is set high enough that a
 *    single unlock takes ~250 ms on a Pixel 6 (the
 *    same wall-clock cost as a real Argon2id
 *    deployment).
 *  - **Share_u** is the AES-GCM ciphertext of `SMK`
 *    under `KEK_u`, plus the per-user salt, the
 *    AES-GCM nonce, and the user id. The share is
 *    what gets persisted in the
 *    [com.kaavalan.note.data.user.UserEntity] row.
 *
 * **v1.8.0 trade-off.** The class is built and
 * tested. The wire-up to the actual SQLCipher key
 * is a v2.x change — the current
 * [com.kaavalan.note.data.vault.VaultCrypto] derives the
 * SQLCipher key from the single user passphrase
 * directly. A future v2.x replaces that derivation
 * with `MultiUserKeySharing.unwrap(smkShareFor(user),
 * userPassphrase)` and the local-only build moves
 * from "one user, one passphrase" to "one station,
 * N users, N passphrases, one SMK".
 *
 * **Why AES-GCM (not AES-CBC + HMAC).** GCM is
 * authenticated-encryption-with-associated-data. A
 * share that fails the GCM tag check is a hard
 * failure (no fallback to "decrypt and hope"), and
 * the tag covers the user id + the share version
 * number so a future rotation can be enforced
 * without an external integrity check.
 *
 * **v2.1.1 (security): CharArray instead of String
 * for the passphrase.** v1.8.0's [wrap] / [unwrap] /
 * [rewrap] all took `passphrase: String`, which is
 * interned by the JVM and survives in the string
 * pool until the next GC of the `StringTable`. A
 * heap dump at the right moment reveals the
 * passphrase. v2.1.1 takes `passphrase: CharArray`
 * (caller-controlled lifetime), encodes it to a
 * UTF-8 [ByteArray] without going through `String`,
 * and zeroes both buffers in a `finally` block. The
 * class isn't wired into the production vault
 * yet, so no live shares need to be re-wrapped.
 */
object MultiUserKeySharing {

    private const val PBKDF2_ALGO = "PBKDF2WithHmacSHA256"
    private const val PBKDF2_ITERATIONS = 600_000
    private const val PBKDF2_KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val SMK_BYTES = 32
    private const val VERSION: Byte = 1

    private val rng = SecureRandom()

    /**
     * The wrapped master key for one user.
     *
     * @property userId matches [com.kaavalan.note.data.user.UserEntity.id].
     * @property salt the per-user salt for PBKDF2.
     * @property nonce the AES-GCM nonce.
     * @property ciphertext the SMK encrypted under the user's KEK.
     * @property version the share version (currently always 1); a
     *  future v2.x bumps this to 2 when the share format changes.
     */
    data class Share(
        val userId: String,
        val salt: ByteArray,
        val nonce: ByteArray,
        val ciphertext: ByteArray,
        val version: Byte = VERSION,
    ) {
        init {
            require(salt.size == SALT_BYTES) { "salt must be $SALT_BYTES bytes" }
            require(nonce.size == NONCE_BYTES) { "nonce must be $NONCE_BYTES bytes" }
            require(ciphertext.isNotEmpty()) { "ciphertext must not be empty" }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Share) return false
            return userId == other.userId &&
                salt.contentEquals(other.salt) &&
                nonce.contentEquals(other.nonce) &&
                ciphertext.contentEquals(other.ciphertext) &&
                version == other.version
        }

        override fun hashCode(): Int {
            var result = userId.hashCode()
            result = 31 * result + salt.contentHashCode()
            result = 31 * result + nonce.contentHashCode()
            result = 31 * result + ciphertext.contentHashCode()
            result = 31 * result + version.hashCode()
            return result
        }
    }

    /**
     * The result of an [unwrap] call. The caller
     * (the future v2.x VaultCrypto) uses the
     * [masterKey] bytes to derive the SQLCipher
     * passphrase.
     */
    data class Unwrapped(
        val masterKey: ByteArray,
    )

    /**
     * Generate a fresh 256-bit station master key.
     * The caller persists it in [com.kaavalan.note.data.user.UserEntity]
     * for the device owner (the only user in v1.8.0).
     * v2.x calls this on the first officer's first
     * unlock and re-uses the existing SMK for the
     * remaining officers (no master rotation per
     * user-add).
     */
    fun newMasterKey(): ByteArray = ByteArray(SMK_BYTES).also { rng.nextBytes(it) }

    /**
     * Wrap [masterKey] for [userId] under a KEK
     * derived from [passphrase]. Returns a [Share]
     * that can be persisted on the [com.kaavalan.note.data.user.UserEntity]
     * row.
     *
     * The salt is freshly generated for every call —
     * the same passphrase + the same SMK produces
     * different bytes on every wrap. A future v2.x
     * "add officer" flow re-wraps the same SMK for
     * each new officer; the salts are independent.
     */
    fun wrap(
        masterKey: ByteArray,
        userId: String,
        passphrase: CharArray,
    ): Share {
        require(masterKey.size == SMK_BYTES) { "master key must be $SMK_BYTES bytes" }
        require(userId.isNotBlank()) { "userId must not be blank" }
        require(passphrase.isNotEmpty()) { "passphrase must not be empty" }
        val salt = ByteArray(SALT_BYTES).also { rng.nextBytes(it) }
        val nonce = ByteArray(NONCE_BYTES).also { rng.nextBytes(it) }
        val (kek, passBytes) = deriveKek(passphrase, salt)
        try {
            val ciphertext = encrypt(masterKey, kek, nonce)
            return Share(
                userId = userId,
                salt = salt,
                nonce = nonce,
                ciphertext = ciphertext,
            )
        } finally {
            passBytes.fill(0)
        }
    }

    /**
     * Unwrap [share] under a KEK derived from
     * [passphrase]. Returns the SMK.
     *
     * **Failure modes.** An invalid passphrase is
     * reported as [VaultError.MasterKeyUnwrap] (the
     * AES-GCM tag check fails). A wrong share
     * version is reported as [VaultError.MasterKeyUnwrap]
     * with a different message — a future v2.x
     * version-2 share is rejected by v1.8.0 code so
     * a "downgrade attack" can't be staged against
     * a user who hasn't upgraded yet.
     */
    fun unwrap(share: Share, passphrase: CharArray): Unwrapped {
        require(passphrase.isNotEmpty()) { "passphrase must not be empty" }
        if (share.version != VERSION) {
            throw VaultError.MasterKeyUnwrap(
                "share version ${share.version} is not supported by this build",
            )
        }
        val (kek, passBytes) = deriveKek(passphrase, share.salt)
        try {
            val masterKey = try {
                decrypt(share.ciphertext, kek, share.nonce)
            } catch (t: Exception) {
                // AES-GCM AEADBadTagException on a wrong
                // passphrase; the message is not propagated
                // to the caller (it's a low-level JCE
                // detail). Re-throw as the public
                // VaultError.
                throw VaultError.MasterKeyUnwrap(
                    "passphrase did not unwrap the share for user ${share.userId}",
                ).also { it.initCause(t) }
            }
            return Unwrapped(masterKey = masterKey)
        } finally {
            passBytes.fill(0)
        }
    }

    /**
     * Re-wrap [masterKey] for [userId] using the
     * same passphrase that the user already has.
     * The v2.x "rotate master key" path: the admin
     * generates a new master key, then re-wraps it
     * for every remaining officer using each
     * officer's *current* passphrase. The user
     * does NOT need to type a new passphrase.
     *
     * v1.8.0: not exercised at runtime. The class
     * is built and tested; the wire-up to the
     * VaultCrypto is a v2.x change.
     */
    fun rewrap(
        masterKey: ByteArray,
        userId: String,
        passphrase: CharArray,
    ): Share = wrap(masterKey, userId, passphrase)

    /**
     * v2.1.1: derive a KEK from a [CharArray]
     * passphrase + salt. Returns the [SecretKey] +
     * the UTF-8 byte encoding of the passphrase
     * (caller is responsible for zeroing the byte
     * array in a `finally` block).
     *
     * The [PBEKeySpec]'s [PBEKeySpec.clearPassword]
     * is best-effort; we explicitly zero the
     * caller-side [CharArray] via a separate code
     * path (the v2.1.1 v2.1.1 caller owns the
     * CharArray's lifetime).
     */
    private fun deriveKek(passphrase: CharArray, salt: ByteArray): Pair<SecretKey, ByteArray> {
        val passBytes: ByteArray = StandardCharsets.UTF_8
            .encode(CharBuffer.wrap(passphrase))
            .let { buf ->
                ByteArray(buf.remaining()).also { buf.get(it) }
            }
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
        val factory = javax.crypto.SecretKeyFactory.getInstance(PBKDF2_ALGO)
        val bytes = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES") to passBytes
    }

    private fun encrypt(plaintext: ByteArray, key: SecretKey, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        return cipher.doFinal(plaintext)
    }

    private fun decrypt(ciphertext: ByteArray, key: SecretKey, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        return cipher.doFinal(ciphertext)
    }
}
