package com.vaultsync.core.security

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

data class VaultHeader(
    val formatVersion: Short,
    val chunkPlaintextSize: Int,
    val fileId: UUID,
    val fileNoncePrefix: ByteArray,
    val headerNonce: ByteArray,
    val wrappedDek: ByteArray,
    val originalFileSize: Long,
) {
    val totalChunks: Long
        get() = if (originalFileSize <= 0) {
            1L
        } else {
            (originalFileSize + chunkPlaintextSize - 1) / chunkPlaintextSize
        }

    val lastChunkIndex: Long
        get() = if (originalFileSize <= 0) {
            0L
        } else {
            (originalFileSize - 1) / chunkPlaintextSize
        }

    fun isFinalChunk(chunkIndex: Long): Boolean = chunkIndex >= lastChunkIndex

    fun chunkOffsetOnDisk(chunkIndex: Long): Long {
        require(chunkIndex >= 0) { "chunkIndex must be >= 0" }
        val fullChunkOnDisk = 4L + chunkPlaintextSize + CryptoConstants.GCM_TAG_SIZE_BYTES
        return VaultFileFormat.HEADER_SIZE_BYTES.toLong() + (chunkIndex * fullChunkOnDisk)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VaultHeader) return false
        return formatVersion == other.formatVersion &&
            chunkPlaintextSize == other.chunkPlaintextSize &&
            fileId == other.fileId &&
            fileNoncePrefix.contentEquals(other.fileNoncePrefix) &&
            headerNonce.contentEquals(other.headerNonce) &&
            wrappedDek.contentEquals(other.wrappedDek) &&
            originalFileSize == other.originalFileSize
    }

    override fun hashCode(): Int {
        var result = formatVersion.toInt()
        result = 31 * result + chunkPlaintextSize
        result = 31 * result + fileId.hashCode()
        result = 31 * result + fileNoncePrefix.contentHashCode()
        result = 31 * result + headerNonce.contentHashCode()
        result = 31 * result + wrappedDek.contentHashCode()
        result = 31 * result + originalFileSize.hashCode()
        return result
    }
}

object VaultFileFormat {
    const val HEADER_SIZE_BYTES = 98

    fun createHeaderAad(
        formatVersion: Short,
        chunkPlaintextSize: Int,
        fileId: UUID,
        fileNoncePrefix: ByteArray,
        originalFileSize: Long,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(38).order(ByteOrder.BIG_ENDIAN)
        buffer.put(CryptoConstants.MAGIC_BYTES)
        buffer.putShort(formatVersion)
        buffer.putInt(chunkPlaintextSize)
        buffer.putLong(fileId.mostSignificantBits)
        buffer.putLong(fileId.leastSignificantBits)
        buffer.put(fileNoncePrefix)
        buffer.putLong(originalFileSize)
        return buffer.array()
    }

    fun serializeHeader(header: VaultHeader): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
        buffer.put(CryptoConstants.MAGIC_BYTES)
        buffer.putShort(header.formatVersion)
        buffer.putInt(header.chunkPlaintextSize)
        buffer.putLong(header.fileId.mostSignificantBits)
        buffer.putLong(header.fileId.leastSignificantBits)
        buffer.put(header.fileNoncePrefix)
        buffer.put(header.headerNonce)
        buffer.put(header.wrappedDek)
        buffer.putLong(header.originalFileSize)
        return buffer.array()
    }

    fun deserializeHeader(bytes: ByteArray): VaultHeader {
        require(bytes.size >= HEADER_SIZE_BYTES) {
            "Header bytes too short: ${bytes.size} (expected at least $HEADER_SIZE_BYTES)"
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

        val magic = ByteArray(4)
        buffer.get(magic)
        if (!magic.contentEquals(CryptoConstants.MAGIC_BYTES)) {
            throw SecurityException("Invalid vault file magic: not a VaultSync encrypted file")
        }

        val version = buffer.short
        if (version != CryptoConstants.FORMAT_VERSION) {
            throw SecurityException("Unsupported vault file format version: $version")
        }

        val chunkPlaintextSize = buffer.int
        if (chunkPlaintextSize <= 0 || chunkPlaintextSize > 16 * 1024 * 1024) {
            throw SecurityException("Invalid chunk size in header: $chunkPlaintextSize")
        }

        val msb = buffer.long
        val lsb = buffer.long
        val fileId = UUID(msb, lsb)

        val fileNoncePrefix = ByteArray(CryptoConstants.FILE_NONCE_PREFIX_SIZE_BYTES)
        buffer.get(fileNoncePrefix)

        val headerNonce = ByteArray(CryptoConstants.GCM_NONCE_SIZE_BYTES)
        buffer.get(headerNonce)

        val wrappedDek = ByteArray(CryptoConstants.WRAPPED_KEY_SIZE_BYTES)
        buffer.get(wrappedDek)

        val originalFileSize = buffer.long

        return VaultHeader(
            formatVersion = version,
            chunkPlaintextSize = chunkPlaintextSize,
            fileId = fileId,
            fileNoncePrefix = fileNoncePrefix,
            headerNonce = headerNonce,
            wrappedDek = wrappedDek,
            originalFileSize = originalFileSize,
        )
    }

    fun createChunkNonce(
        fileNoncePrefix: ByteArray,
        chunkIndex: Long,
        isFinal: Boolean,
    ): ByteArray {
        val nonce = ByteArray(CryptoConstants.GCM_NONCE_SIZE_BYTES)
        System.arraycopy(fileNoncePrefix, 0, nonce, 0, 4)

        // 7 bytes for chunk index (big-endian)
        nonce[4] = ((chunkIndex ushr 48) and 0xFF).toByte()
        nonce[5] = ((chunkIndex ushr 40) and 0xFF).toByte()
        nonce[6] = ((chunkIndex ushr 32) and 0xFF).toByte()
        nonce[7] = ((chunkIndex ushr 24) and 0xFF).toByte()
        nonce[8] = ((chunkIndex ushr 16) and 0xFF).toByte()
        nonce[9] = ((chunkIndex ushr 8) and 0xFF).toByte()
        nonce[10] = (chunkIndex and 0xFF).toByte()

        // 1 byte isFinal flag
        nonce[11] = if (isFinal) 0x01.toByte() else 0x00.toByte()
        return nonce
    }

    fun createChunkAad(
        fileId: UUID,
        chunkIndex: Long,
        isFinal: Boolean,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(25).order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(fileId.mostSignificantBits)
        buffer.putLong(fileId.leastSignificantBits)
        buffer.putLong(chunkIndex)
        buffer.put(if (isFinal) 0x01.toByte() else 0x00.toByte())
        return buffer.array()
    }
}
