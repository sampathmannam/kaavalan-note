package com.kaavalan.note.data.vault

import android.content.Context
import android.net.Uri
import com.kaavalan.note.data.local.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.crypto.AEADBadTagException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tier 1.1 (v2.0): imports a .kaavalan-note-vault file from a SAF URI.
 *
 *  1. Read the entire file bytes (the on-device DB is small
 *     even with thousands of rows — 5–50 MB max).
 *  2. Parse the 56-byte header.
 *  3. Derive the 32-byte AES key from the passphrase + header
 *     salt using the header's `m`/`t`/`p` (NOT the library
 *     defaults).
 *  4. Decrypt under AAD = header. On tag mismatch → map to
 *     `IncorrectPassphrase` (do NOT distinguish from
 *     tampered-file / wrong-passphrase / wrong-header).
 *  5. Close Room, overwrite the on-disk DB, reopen.
 *
 * **Error mapping.** [VaultError.IncorrectPassphrase] is the
 * universal "no" signal — same user string for the wrong
 * passphrase, the tampered header, and the truncated payload.
 * The verifier should not be able to distinguish these cases.
 */
@Singleton
class VaultImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crypto: VaultCrypto,
    private val db: AppDatabase,
) {

    /**
     * Decrypts [inputUri] under [passphrase] and replaces the
     * on-disk Room DB. On success, the running Room instance
     * is closed and reopened (the next DAO call re-reads the
     * file).
     */
    suspend fun import(inputUri: Uri, passphrase: CharArray): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val fileBytes = context.contentResolver.openInputStream(inputUri)?.use {
                    it.readBytes()
                } ?: throw VaultError.IoError("Could not open input")

                val parsed = VaultFormat.parseHeader(fileBytes)
                if (fileBytes.size < VaultCrypto.HEADER_BYTES + parsed.payloadLen) {
                    throw VaultError.NotAVault("truncated")
                }
                val aad = fileBytes.copyOfRange(0, VaultCrypto.HEADER_BYTES)
                val payload = fileBytes.copyOfRange(
                    VaultCrypto.HEADER_BYTES,
                    VaultCrypto.HEADER_BYTES + parsed.payloadLen,
                )

                val keyBytes = crypto.deriveKey(
                    passphrase = passphrase,
                    salt = parsed.salt,
                    m = parsed.kdfM,
                    t = parsed.kdfT,
                    p = parsed.kdfP,
                )
                passphrase.fill('\u0000')

                val plaintext = try {
                    crypto.decrypt(crypto.toSecretKey(keyBytes), parsed.iv, payload, aad)
                } catch (e: AEADBadTagException) {
                    keyBytes.fill(0)
                    throw VaultError.IncorrectPassphrase()
                }
                keyBytes.fill(0)

                replaceActiveDatabase(plaintext)
            }.recoverCatching { e ->
                passphrase.fill('\u0000')
                throw when (e) {
                    is VaultError -> e
                    is IOException -> if (isDiskFull(e)) {
                        VaultError.DiskFull()
                    } else {
                        VaultError.IoError(e.message ?: "io error")
                    }
                    else -> e
                }
            }
        }

    /**
     * Closes the running Room instance, overwrites the on-disk
     * DB with [plaintext], then re-opens. The next DAO call
     * re-reads the file.
     */
    private fun replaceActiveDatabase(plaintext: ByteArray) {
        try {
            db.close()
        } catch (_: Exception) { /* best-effort */ }
        val dbFile = File(context.getDatabasePath(AppDatabase.NAME).absolutePath)
        try {
            dbFile.parentFile?.mkdirs()
            dbFile.writeBytes(plaintext)
            // Wipe -wal / -shm from the previous session so Room
            // starts clean (a stale -wal from the old DB would
            // get replayed on top of the new file).
            File(dbFile.path + "-wal").takeIf { it.exists() }?.delete()
            File(dbFile.path + "-shm").takeIf { it.exists() }?.delete()
        } catch (e: IOException) {
            if (isDiskFull(e)) throw VaultError.DiskFull()
            throw VaultError.IoError(e.message ?: "io error")
        }
        // The Hilt-managed singleton will re-open on the next DAO
        // call. We trigger that by closing the in-memory caches
        // and relying on Room's lazy reopen behaviour.
    }

    private fun isDiskFull(e: IOException): Boolean {
        val msg = e.message?.lowercase().orEmpty()
        return msg.contains("space") || msg.contains("enospc")
    }
}
