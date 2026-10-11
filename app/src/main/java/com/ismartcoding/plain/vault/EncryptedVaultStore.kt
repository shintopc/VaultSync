package com.ismartcoding.plain.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Private, separate encrypted storage for user-selected VaultSync files.
 *
 * Existing PlainApp-managed files are not modified by this store. This class is only the
 * storage primitive; it does not by itself expose a browser endpoint or authorize a session.
 *
 * Each chunk is authenticated independently before its plaintext is returned to a caller.
 * The master key is non-exportable and held by Android Keystore.
 */
class EncryptedVaultStore(context: Context) {
    private val root = File(context.noBackupFilesDir, "vaultsync/vault-v1")
    private val objects = File(root, "objects")
    private val metadata = File(root, "metadata")

    init {
        check(objects.isDirectory || objects.mkdirs()) { "Unable to create vault object directory" }
        check(metadata.isDirectory || metadata.mkdirs()) { "Unable to create vault metadata directory" }
    }

    data class Item(
        val id: String,
        val displayName: String,
        val sizeBytes: Long,
        val createdAtMillis: Long,
    )

    /**
     * Imports a copy into the vault. The caller retains responsibility for the source file.
     * The input stream is consumed but not closed by this method.
     */
    @Synchronized
    fun importFile(displayName: String, input: InputStream): Item {
        require(displayName.isNotBlank()) { "A filename is required" }
        require(displayName.length <= MAX_DISPLAY_NAME_LENGTH) { "Filename is too long" }
        require(displayName.none { it == '\u0000' || it == '/' || it == '\\' }) { "Invalid filename" }

        val id = java.util.UUID.randomUUID().toString()
        val objectTemp = File(objects, ".$id.tmp")
        val metadataTemp = File(metadata, ".$id.tmp")
        val createdAt = System.currentTimeMillis()

        try {
            val size = FileOutputStream(objectTemp).use { output -> encrypt(input, output) }
            val metadataBytes = ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { data ->
                    data.writeUTF(displayName)
                    data.writeLong(size)
                    data.writeLong(createdAt)
                }
                bytes.toByteArray()
            }
            FileOutputStream(metadataTemp).use { output ->
                encrypt(ByteArrayInputStream(metadataBytes), output)
            }

            check(objectTemp.renameTo(objectFile(id))) { "Unable to finalize encrypted vault object" }
            check(metadataTemp.renameTo(metadataFile(id))) { "Unable to finalize encrypted vault metadata" }
            return Item(id, displayName, size, createdAt)
        } catch (error: Throwable) {
            objectTemp.delete()
            metadataTemp.delete()
            objectFile(id).delete()
            metadataFile(id).delete()
            throw error
        }
    }

    fun listItems(): List<Item> = metadata.listFiles { file ->
        file.isFile && file.name.endsWith(".meta") && isValidId(file.name.removeSuffix(".meta"))
    }?.map { file ->
        readMetadata(file.name.removeSuffix(".meta"))
    }?.sortedByDescending { it.createdAtMillis } ?: emptyList()

    fun getItem(id: String): Item? {
        requireValidId(id)
        val file = metadataFile(id)
        if (!file.isFile) return null
        return readMetadata(id)
    }

    /**
     * Returns a stream that authenticates each encrypted chunk before exposing its plaintext.
     * Callers must close the stream. Authorization must be enforced by the API layer before
     * this method is called for a browser request.
     */
    fun openStream(id: String): InputStream {
        requireValidId(id)
        val file = objectFile(id)
        if (!file.isFile) throw java.io.FileNotFoundException("Vault item not found")
        return ChunkDecryptingInputStream(FileInputStream(file), getOrCreateKey())
    }

    @Synchronized
    fun delete(id: String) {
        requireValidId(id)
        val objectFile = objectFile(id)
        val metadataFile = metadataFile(id)
        if (!objectFile.exists() && !metadataFile.exists()) return
        val objectDeleted = !objectFile.exists() || objectFile.delete()
        val metadataDeleted = !metadataFile.exists() || metadataFile.delete()
        check(objectDeleted && metadataDeleted) { "Unable to delete vault item" }
    }

    private fun readMetadata(id: String): Item {
        val bytes = ByteArrayOutputStream().use { output ->
            openEncryptedFile(metadataFile(id)).use { input -> input.copyTo(output) }
            output.toByteArray()
        }
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            val name = data.readUTF()
            val size = data.readLong()
            val createdAt = data.readLong()
            if (data.available() != 0 || size < 0 || name.isBlank()) {
                throw IOException("Invalid encrypted vault metadata")
            }
            return Item(id, name, size, createdAt)
        }
    }

    private fun openEncryptedFile(file: File): InputStream =
        ChunkDecryptingInputStream(FileInputStream(file), getOrCreateKey())

    private fun encrypt(input: InputStream, output: FileOutputStream): Long {
        val key = getOrCreateKey()
        val prefix = ByteArray(NONCE_PREFIX_BYTES).also(SecureRandom()::nextBytes)
        val data = DataOutputStream(BufferedOutputStream(output))
        data.writeInt(MAGIC)
        data.write(prefix)
        data.writeInt(CHUNK_SIZE)

        val buffer = ByteArray(CHUNK_SIZE)
        var counter = 0L
        var total = 0L
        while (true) {
            val length = fillBuffer(input, buffer)
            if (length == 0) break
            check(counter <= MAX_CHUNK_COUNTER) { "Vault file exceeds supported size" }
            val cipherText = cipherFor(Cipher.ENCRYPT_MODE, key, prefix, counter, length).doFinal(buffer, 0, length)
            data.writeInt(length)
            data.write(cipherText)
            total += length
            counter++
        }
        data.writeInt(END_OF_FILE)
        data.flush()
        return total
    }

    private inner class ChunkDecryptingInputStream(
        source: InputStream,
        private val key: SecretKey,
    ) : InputStream() {
        private val input = DataInputStream(BufferedInputStream(source))
        private val prefix: ByteArray
        private val chunkSize: Int
        private var counter = 0L
        private var current = ByteArray(0)
        private var offset = 0
        private var ended = false
        private var closed = false

        init {
            try {
                if (input.readInt() != MAGIC) throw IOException("Invalid vault file header")
                prefix = ByteArray(NONCE_PREFIX_BYTES).also(input::readFully)
                chunkSize = input.readInt()
                if (chunkSize != CHUNK_SIZE) throw IOException("Unsupported vault chunk size")
            } catch (error: Throwable) {
                input.close()
                throw error
            }
        }

        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            return if (count < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            check(!closed) { "Stream is closed" }
            if (off < 0 || len < 0 || len > buffer.size - off) throw IndexOutOfBoundsException()
            if (len == 0) return 0
            if (offset >= current.size && !loadNextChunk()) return -1
            val count = minOf(len, current.size - offset)
            current.copyInto(buffer, off, offset, offset + count)
            offset += count
            return count
        }

        private fun loadNextChunk(): Boolean {
            if (ended) return false
            val length = try {
                input.readInt()
            } catch (error: EOFException) {
                throw IOException("Truncated vault file", error)
            }
            if (length == END_OF_FILE) {
                if (input.read() != -1) throw IOException("Unexpected trailing data in vault file")
                ended = true
                current = ByteArray(0)
                offset = 0
                return false
            }
            if (length !in 1..chunkSize) throw IOException("Invalid vault chunk length")
            if (counter > MAX_CHUNK_COUNTER) throw IOException("Invalid vault chunk counter")
            val encrypted = ByteArray(length + GCM_TAG_BYTES)
            input.readFully(encrypted)
            // doFinal verifies the GCM tag before any bytes from this chunk are exposed.
            current = try {
                cipherFor(Cipher.DECRYPT_MODE, key, prefix, counter, length).doFinal(encrypted)
            } catch (error: Exception) {
                throw IOException("Vault authentication failed", error)
            }
            if (current.size != length) throw IOException("Invalid decrypted vault chunk")
            offset = 0
            counter++
            return true
        }

        override fun close() {
            if (!closed) {
                closed = true
                current.fill(0)
                input.close()
            }
        }
    }

    private fun cipherFor(
        mode: Int,
        key: SecretKey,
        prefix: ByteArray,
        counter: Long,
        plainLength: Int,
    ): Cipher {
        val nonce = ByteBuffer.allocate(NONCE_PREFIX_BYTES + COUNTER_BYTES)
            .put(prefix)
            .putInt(counter.toInt())
            .array()
        val aad = ByteBuffer.allocate(NONCE_PREFIX_BYTES + COUNTER_BYTES + Int.SIZE_BYTES)
            .put(prefix)
            .putInt(counter.toInt())
            .putInt(plainLength)
            .array()
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(mode, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            updateAAD(aad)
        }
    }

    private fun getOrCreateKey(): SecretKey = synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return@synchronized it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(false) // IVs are generated from per-file random prefixes and chunk counters.
                .build(),
        )
        generator.generateKey()
    }

    private fun objectFile(id: String) = File(objects, "$id.vlt")
    private fun metadataFile(id: String) = File(metadata, "$id.meta")

    private fun requireValidId(id: String) {
        require(isValidId(id)) { "Invalid vault item ID" }
    }

    private fun isValidId(id: String): Boolean =
        id.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))

    private fun fillBuffer(input: InputStream, buffer: ByteArray): Int {
        var used = 0
        while (used < buffer.size) {
            val count = input.read(buffer, used, buffer.size - used)
            if (count < 0) break
            if (count == 0) {
                val value = input.read()
                if (value < 0) break
                buffer[used++] = value.toByte()
            } else {
                used += count
            }
        }
        return used
    }

    companion object {
        private const val KEY_ALIAS = "com.shintopc.vaultsync.vault.master.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val MAGIC = 0x56535931 // VSY1
        private const val CHUNK_SIZE = 1024 * 1024
        private const val NONCE_PREFIX_BYTES = 8
        private const val COUNTER_BYTES = 4
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        private const val END_OF_FILE = -1
        private const val MAX_CHUNK_COUNTER = 0xffff_ffffL
        private const val MAX_DISPLAY_NAME_LENGTH = 1024
        private val KEY_LOCK = Any()
    }
}
