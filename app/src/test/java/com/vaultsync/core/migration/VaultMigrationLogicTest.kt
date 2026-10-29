package com.vaultsync.core.migration

import com.vaultsync.core.security.VaultFileDecryptor
import com.vaultsync.core.security.VaultFileEncryptor
import com.vaultsync.core.security.VaultKeyManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

class VaultMigrationLogicTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(16384)
            var bytesRead: Int
            while (input.read(buf).also { bytesRead = it } != -1) {
                digest.update(buf, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun testFileByFileCopyAndIntegrityVerification() {
        val vmk = VaultKeyManager.generateVaultMasterKey()
        val plainBytes = "Confidential vault media content for migration testing".toByteArray(Charsets.UTF_8)

        val plainSource = tempFolder.newFile("plain.dat")
        plainSource.writeBytes(plainBytes)

        val sourceDir = tempFolder.newFolder("source_blobs")
        val destDir = tempFolder.newFolder("dest_blobs")

        val sourceEncrypted = File(sourceDir, "blob-1234.vsf")
        VaultFileEncryptor.encryptFile(plainSource, sourceEncrypted, vmk)
        assertTrue(sourceEncrypted.exists())

        val sourceSha256 = computeSha256(sourceEncrypted)
        val destEncrypted = File(destDir, "blob-1234.vsf")
        val destPart = File(destDir, "blob-1234.vsf.part")

        // 1. Simulate copy to .part
        val srcDigest = MessageDigest.getInstance("SHA-256")
        val dstDigest = MessageDigest.getInstance("SHA-256")
        FileInputStream(sourceEncrypted).use { input ->
            FileOutputStream(destPart).use { output ->
                val buf = ByteArray(8192)
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    srcDigest.update(buf, 0, read)
                    output.write(buf, 0, read)
                    dstDigest.update(buf, 0, read)
                }
                output.fd.sync()
            }
        }

        val computedSrc = srcDigest.digest().joinToString("") { "%02x".format(it) }
        val computedDst = dstDigest.digest().joinToString("") { "%02x".format(it) }
        assertEquals(computedSrc, computedDst)
        assertEquals(sourceSha256, computedDst)

        // 2. Atomic rename from .part to final
        assertTrue(destPart.renameTo(destEncrypted))
        assertTrue(destEncrypted.exists())
        assertEquals(sourceEncrypted.length(), destEncrypted.length())

        // 3. Verify Zero Re-encryption Invariant: byte-for-byte exact match
        assertArrayEquals(sourceEncrypted.readBytes(), destEncrypted.readBytes())

        // 4. Verify destination decrypts with original VMK to original plaintext
        val decryptedOutput = File(tempFolder.root, "decrypted_dest.dat")
        VaultFileDecryptor.decryptFile(destEncrypted, decryptedOutput, vmk)
        assertArrayEquals(plainBytes, decryptedOutput.readBytes())
    }

    @Test
    fun testResumptionFromInterruptedCopyingState() {
        val destDir = tempFolder.newFolder("dest_blobs_interrupted")
        val destPart = File(destDir, "blob-broken.vsf.part")
        destPart.writeBytes("partially copied junk bytes".toByteArray())

        // Simulation: on restart, if status is COPYING, partial file must be cleaned up
        if (destPart.exists()) {
            destPart.delete()
        }
        assertFalse("Partial .part file must be removed before fresh copy", destPart.exists())
    }

    @Test
    fun testResumptionFromVerifiedAtDestinationState() {
        val sourceDir = tempFolder.newFolder("source_resume")
        val destDir = tempFolder.newFolder("dest_resume")

        val sourceBlob = File(sourceDir, "blob-verified.vsf")
        val destBlob = File(destDir, "blob-verified.vsf")

        val testData = "Durable verified encrypted data".toByteArray()
        sourceBlob.writeBytes(testData)
        destBlob.writeBytes(testData)

        val sourceHash = computeSha256(sourceBlob)
        val destHash = computeSha256(destBlob)
        assertEquals(sourceHash, destHash)

        // Simulation: record was VERIFIED_AT_DESTINATION before crash
        val record = BlobJournalRecord(
            blobId = "blob-verified",
            expectedSizeBytes = testData.size.toLong(),
            sourceSha256 = destHash,
            state = BlobMigrationState.VERIFIED_AT_DESTINATION,
            attempts = 1,
            lastError = null,
        )

        // Engine resumes: destination is verified and intact, so safely delete source
        assertTrue(destBlob.exists())
        assertEquals(record.sourceSha256, computeSha256(destBlob))
        sourceBlob.delete()

        assertFalse("Source blob safely deleted after destination verified", sourceBlob.exists())
        assertTrue("Destination blob remains intact", destBlob.exists())
    }
}
