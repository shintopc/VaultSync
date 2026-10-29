package com.vaultsync.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.Random

class VaultEncryptionDecryptionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val random = Random(42)

    private fun generateRandomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        random.nextBytes(bytes)
        return bytes
    }

    @Test
    fun roundtripSmallPayloadSucceeds() {
        val originalData = "Hello, secure VaultSync world!".toByteArray(Charsets.UTF_8)
        val inputFile = tempFolder.newFile("plain_small.txt")
        inputFile.writeBytes(originalData)

        val encryptedFile = File(tempFolder.root, "encrypted_small.vsf")
        val decryptedFile = File(tempFolder.root, "decrypted_small.txt")
        val vmk = VaultKeyManager.generateVaultMasterKey()

        val encResult = VaultFileEncryptor.encryptFile(inputFile, encryptedFile, vmk)
        assertEquals(originalData.size.toLong(), encResult.originalSizeBytes)
        assertTrue(encryptedFile.exists())
        assertTrue(encryptedFile.length() > originalData.size)

        VaultFileDecryptor.decryptFile(encryptedFile, decryptedFile, vmk)
        assertTrue(decryptedFile.exists())
        assertArrayEquals(originalData, decryptedFile.readBytes())
    }

    @Test
    fun roundtripEmptyFileSucceeds() {
        val inputFile = tempFolder.newFile("empty.txt")
        val encryptedFile = File(tempFolder.root, "empty.vsf")
        val decryptedFile = File(tempFolder.root, "empty_decrypted.txt")
        val vmk = VaultKeyManager.generateVaultMasterKey()

        VaultFileEncryptor.encryptFile(inputFile, encryptedFile, vmk)
        assertTrue(encryptedFile.exists())

        VaultFileDecryptor.decryptFile(encryptedFile, decryptedFile, vmk)
        assertEquals(0L, decryptedFile.length())
    }

    @Test
    fun roundtripMultiChunkPayloadSucceeds() {
        // 200 KiB payload across 64 KiB chunks -> 4 chunks (64 + 64 + 64 + 8)
        val payloadSize = 200 * 1024
        val originalData = generateRandomBytes(payloadSize)
        val inputFile = tempFolder.newFile("multi_chunk_input.bin")
        inputFile.writeBytes(originalData)

        val encryptedFile = File(tempFolder.root, "multi_chunk.vsf")
        val decryptedFile = File(tempFolder.root, "multi_chunk_decrypted.bin")
        val vmk = VaultKeyManager.generateVaultMasterKey()

        val result = VaultFileEncryptor.encryptFile(
            inputFile = inputFile,
            destinationEncryptedFile = encryptedFile,
            vmk = vmk,
            chunkPlaintextSize = 64 * 1024,
        )

        assertEquals(4L, result.chunkCount)
        assertEquals(payloadSize.toLong(), result.originalSizeBytes)

        VaultFileDecryptor.decryptFile(encryptedFile, decryptedFile, vmk)
        assertArrayEquals(originalData, decryptedFile.readBytes())
    }

    @Test
    fun decryptWithWrongVmkThrowsException() {
        val originalData = "Super confidential media data".toByteArray(Charsets.UTF_8)
        val inputFile = tempFolder.newFile("secret.txt")
        inputFile.writeBytes(originalData)

        val encryptedFile = File(tempFolder.root, "secret.vsf")
        val decryptedFile = File(tempFolder.root, "secret_out.txt")

        val correctVmk = VaultKeyManager.generateVaultMasterKey()
        val wrongVmk = VaultKeyManager.generateVaultMasterKey()

        VaultFileEncryptor.encryptFile(inputFile, encryptedFile, correctVmk)

        assertThrows(Exception::class.java) {
            VaultFileDecryptor.decryptFile(encryptedFile, decryptedFile, wrongVmk)
        }
    }

    @Test
    fun tamperedHeaderThrowsException() {
        val originalData = "Tamper test payload".toByteArray(Charsets.UTF_8)
        val inputFile = tempFolder.newFile("tamper_hdr_in.txt")
        inputFile.writeBytes(originalData)

        val encryptedFile = File(tempFolder.root, "tamper_hdr.vsf")
        val decryptedFile = File(tempFolder.root, "tamper_hdr_out.txt")
        val vmk = VaultKeyManager.generateVaultMasterKey()

        VaultFileEncryptor.encryptFile(inputFile, encryptedFile, vmk)

        // Tamper with the wrapped DEK in the header (e.g. byte 50)
        val bytes = encryptedFile.readBytes()
        bytes[50] = (bytes[50].toInt() xor 0xFF).toByte()
        encryptedFile.writeBytes(bytes)

        assertThrows(Exception::class.java) {
            VaultFileDecryptor.decryptFile(encryptedFile, decryptedFile, vmk)
        }
    }

    @Test
    fun tamperedChunkCiphertextThrowsException() {
        val originalData = generateRandomBytes(100 * 1024)
        val inputFile = tempFolder.newFile("tamper_chunk_in.bin")
        inputFile.writeBytes(originalData)

        val encryptedFile = File(tempFolder.root, "tamper_chunk.vsf")
        val decryptedFile = File(tempFolder.root, "tamper_chunk_out.bin")
        val vmk = VaultKeyManager.generateVaultMasterKey()

        VaultFileEncryptor.encryptFile(inputFile, encryptedFile, vmk)

        // Modify byte in chunk 0 payload (offset > 98 bytes header + 4 bytes length)
        val bytes = encryptedFile.readBytes()
        bytes[120] = (bytes[120].toInt() xor 0xFF).toByte()
        encryptedFile.writeBytes(bytes)

        assertThrows(Exception::class.java) {
            VaultFileDecryptor.decryptFile(encryptedFile, decryptedFile, vmk)
        }
    }

    @Test
    fun truncatedFileThrowsException() {
        val originalData = generateRandomBytes(150 * 1024)
        val inputFile = tempFolder.newFile("trunc_in.bin")
        inputFile.writeBytes(originalData)

        val encryptedFile = File(tempFolder.root, "trunc.vsf")
        val decryptedFile = File(tempFolder.root, "trunc_out.bin")
        val vmk = VaultKeyManager.generateVaultMasterKey()

        VaultFileEncryptor.encryptFile(inputFile, encryptedFile, vmk)

        // Truncate the file so the last chunk is missing
        val bytes = encryptedFile.readBytes()
        val truncated = bytes.copyOf(bytes.size - 500)
        encryptedFile.writeBytes(truncated)

        assertThrows(Exception::class.java) {
            VaultFileDecryptor.decryptFile(encryptedFile, decryptedFile, vmk)
        }
    }

    @Test
    fun readRangeReturnsExactSubRange() {
        val payloadSize = 200 * 1024 // across ~4 chunks (64 KiB each)
        val originalData = generateRandomBytes(payloadSize)
        val inputFile = tempFolder.newFile("range_in.bin")
        inputFile.writeBytes(originalData)

        val encryptedFile = File(tempFolder.root, "range.vsf")
        val vmk = VaultKeyManager.generateVaultMasterKey()

        VaultFileEncryptor.encryptFile(
            inputFile = inputFile,
            destinationEncryptedFile = encryptedFile,
            vmk = vmk,
            chunkPlaintextSize = 64 * 1024,
        )

        // Case 1: Read within chunk 0
        val out1 = ByteArrayOutputStream()
        VaultFileDecryptor.readRange(encryptedFile, vmk, startOffset = 100, length = 500, outputStream = out1)
        val expected1 = originalData.copyOfRange(100, 600)
        assertArrayEquals(expected1, out1.toByteArray())

        // Case 2: Read across chunk 0 and chunk 1 boundary (e.g. 60,000 to 70,000)
        val out2 = ByteArrayOutputStream()
        VaultFileDecryptor.readRange(encryptedFile, vmk, startOffset = 60_000, length = 10_000, outputStream = out2)
        val expected2 = originalData.copyOfRange(60_000, 70_000)
        assertArrayEquals(expected2, out2.toByteArray())

        // Case 3: Read to end of file
        val out3 = ByteArrayOutputStream()
        VaultFileDecryptor.readRange(encryptedFile, vmk, startOffset = 190_000, length = 20_000, outputStream = out3)
        val expected3 = originalData.copyOfRange(190_000, payloadSize)
        assertArrayEquals(expected3, out3.toByteArray())
    }

    @Test
    fun failedEncryptionCleansUpTemporaryFile() {
        val inputFile = tempFolder.newFile("corrupt_read.txt")
        inputFile.writeBytes("Some data".toByteArray())

        val destFile = File(tempFolder.root, "will_fail.vsf")
        val invalidVmk = ByteArray(16) // Invalid key length (must be 32)

        assertThrows(IllegalArgumentException::class.java) {
            VaultFileEncryptor.encryptFile(inputFile, destFile, invalidVmk)
        }

        assertFalse(destFile.exists())
        val partFiles = tempFolder.root.listFiles { _, name -> name.endsWith(".part") }
        assertTrue(partFiles.isNullOrEmpty())
    }

    @Test
    fun truncatedHeaderThrowsSecurityException() {
        val truncatedFile = tempFolder.newFile("short_header.vsf")
        truncatedFile.writeBytes(ByteArray(50)) // Less than HEADER_SIZE_BYTES (98 bytes)
        val decryptedFile = File(tempFolder.root, "short_out.txt")
        val vmk = VaultKeyManager.generateVaultMasterKey()

        assertThrows(SecurityException::class.java) {
            VaultFileDecryptor.decryptFile(truncatedFile, decryptedFile, vmk)
        }
    }

    @Test
    fun tamperedNoncePrefixInHeaderThrowsException() {
        val originalData = "Sensitive media content".toByteArray(Charsets.UTF_8)
        val inputFile = tempFolder.newFile("nonce_test_in.txt")
        inputFile.writeBytes(originalData)

        val encryptedFile = File(tempFolder.root, "nonce_test.vsf")
        val decryptedFile = File(tempFolder.root, "nonce_test_out.txt")
        val vmk = VaultKeyManager.generateVaultMasterKey()

        VaultFileEncryptor.encryptFile(inputFile, encryptedFile, vmk)

        // Modify byte in the 12-byte headerNonce or fileNoncePrefix
        val bytes = encryptedFile.readBytes()
        bytes[24] = (bytes[24].toInt() xor 0x5A).toByte()
        encryptedFile.writeBytes(bytes)

        assertThrows(Exception::class.java) {
            VaultFileDecryptor.decryptFile(encryptedFile, decryptedFile, vmk)
        }
    }

    @Test
    fun zeroizeWipesKeyInMemory() {
        val key = ByteArray(32) { (it + 1).toByte() }
        assertTrue(key.any { it != 0.toByte() })

        VaultKeyManager.zeroize(key)
        assertTrue(key.all { it == 0.toByte() })
    }

    @Test
    fun encryptStreamRejectsZeroSizeWhenStreamHasData() {
        val stream = java.io.ByteArrayInputStream("Non-empty content".toByteArray(Charsets.UTF_8))
        val output = ByteArrayOutputStream()
        val vmk = VaultKeyManager.generateVaultMasterKey()

        assertThrows(IllegalArgumentException::class.java) {
            VaultFileEncryptor.encryptStream(
                inputStream = stream,
                outputStream = output,
                vmk = vmk,
                knownPlaintextSize = 0L,
            )
        }
    }

    @Test
    fun encryptStreamRejectsNegativeSize() {
        val stream = java.io.ByteArrayInputStream(ByteArray(0))
        val output = ByteArrayOutputStream()
        val vmk = VaultKeyManager.generateVaultMasterKey()

        assertThrows(IllegalArgumentException::class.java) {
            VaultFileEncryptor.encryptStream(
                inputStream = stream,
                outputStream = output,
                vmk = vmk,
                knownPlaintextSize = -1L,
            )
        }
    }
}
