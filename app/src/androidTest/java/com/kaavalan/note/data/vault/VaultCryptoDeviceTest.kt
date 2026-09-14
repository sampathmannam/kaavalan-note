package com.kaavalan.note.data.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

/** Runs the Argon2 JNI contract on an Android ABI instead of skipping it on the host JVM. */
@RunWith(AndroidJUnit4::class)
class VaultCryptoDeviceTest {

    private val crypto = VaultCrypto()

    @Test
    fun deriveKeyIsDeterministicForTheSameInput() {
        val salt = ByteArray(VaultCrypto.SALT_BYTES) { it.toByte() }
        val first = crypto.deriveKey("correct horse battery staple".toCharArray(), salt)
        val second = crypto.deriveKey("correct horse battery staple".toCharArray(), salt)
        assertArrayEquals(first, second)
    }

    @Test
    fun deriveKeyIsSensitiveToThePassphrase() {
        val salt = ByteArray(VaultCrypto.SALT_BYTES) { it.toByte() }
        val first = crypto.deriveKey("correct horse battery staple".toCharArray(), salt)
        val second = crypto.deriveKey("wrong horse battery staple".toCharArray(), salt)
        assertEquals(VaultCrypto.KEY_BYTES, first.size)
        assertEquals(VaultCrypto.KEY_BYTES, second.size)
        assertNotEquals(first.toList(), second.toList())
    }

    @Test
    fun deriveKeyIsSensitiveToTheSalt() {
        val first = crypto.deriveKey(
            "same passphrase".toCharArray(),
            ByteArray(VaultCrypto.SALT_BYTES) { 0x00 },
        )
        val second = crypto.deriveKey(
            "same passphrase".toCharArray(),
            ByteArray(VaultCrypto.SALT_BYTES) { 0x01 },
        )
        assertNotEquals(first.toList(), second.toList())
    }

    @Test
    fun deriveKeyRejectsAWrongSizedSalt() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            crypto.deriveKey("any passphrase".toCharArray(), ByteArray(8))
        }
        assertTrue(error.message.orEmpty().contains("salt", ignoreCase = true))
    }

    @Test
    fun charArrayEncodingRemainsWireCompatible() {
        val passphrase = "hello world"
        val salt = ByteArray(VaultCrypto.SALT_BYTES) { it.toByte() }
        val fromCharBuffer = StandardCharsets.UTF_8
            .encode(CharBuffer.wrap(passphrase.toCharArray()))
            .let { buffer -> ByteArray(buffer.remaining()).also(buffer::get) }
        val legacyBytes = passphrase.toByteArray(StandardCharsets.UTF_8)

        assertArrayEquals(fromCharBuffer, legacyBytes)
        assertEquals(
            VaultCrypto.KEY_BYTES,
            crypto.deriveKey(passphrase.toCharArray(), salt).size,
        )
    }
}
