package com.ismartcoding.plain.vault

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.RandomAccessFile

@RunWith(AndroidJUnit4::class)
class EncryptedVaultStoreTest {
    private fun store(): EncryptedVaultStore {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return EncryptedVaultStore(context)
    }

    @Test
    fun roundTripsEmptySmallAndMultiChunkFiles() {
        val vault = store()
        val payloads = listOf(
            ByteArray(0),
            "private vault test".toByteArray(),
            ByteArray(2 * 1024 * 1024 + 37) { index -> (index * 31).toByte() },
        )

        payloads.forEachIndexed { index, payload ->
            val item = vault.importFile("sample-$index.bin", ByteArrayInputStream(payload))
            assertEquals(payload.size.toLong(), item.sizeBytes)
            assertArrayEquals(payload, vault.openStream(item.id).use { it.readBytes() })
            assertEquals("sample-$index.bin", vault.getItem(item.id)?.displayName)
            assertNotNull(vault.listItems().firstOrNull { it.id == item.id })
            vault.delete(item.id)
            assertNull(vault.getItem(item.id))
        }
    }

    @Test
    fun modifiedCiphertextFailsAuthentication() {
        val vault = store()
        val item = vault.importFile("tamper.bin", ByteArrayInputStream("do not leak".toByteArray()))
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val encrypted = java.io.File(
            context.noBackupFilesDir,
            "vaultsync/vault-v1/objects/${item.id}.vlt",
        )

        RandomAccessFile(encrypted, "rw").use { file ->
            file.seek(file.length() - 1)
            val original = file.readByte()
            file.seek(file.length() - 1)
            file.writeByte(original.toInt() xor 0x01)
        }

        try {
            vault.openStream(item.id).use { it.readBytes() }
            fail("Tampered vault content must not decrypt")
        } catch (_: IOException) {
            // Expected: the GCM authentication tag no longer matches.
        } finally {
            vault.delete(item.id)
        }
    }
}
