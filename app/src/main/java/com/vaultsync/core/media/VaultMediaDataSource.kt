package com.vaultsync.core.media

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.vaultsync.core.security.CryptoConstants
import com.vaultsync.core.security.VaultFileDecryptor
import com.vaultsync.core.security.VaultFileFormat
import com.vaultsync.core.security.VaultHeader
import com.vaultsync.core.security.VaultKeyManager
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Custom Media3 [DataSource] that streams decrypted video bytes directly into ExoPlayer memory buffers
 * on-demand using AES-256-GCM chunk seeking.
 *
 * Benefits:
 * - Zero temporary files written to flash storage.
 * - Zero flash memory wear and zero data leakage.
 * - Instant startup playback and smooth seeking across arbitrary video offsets.
 */
@OptIn(UnstableApi::class)
class VaultMediaDataSource(
    private val resolveBlobFile: (Uri) -> File?,
    private val vmkProvider: () -> ByteArray?,
) : BaseDataSource(/* isNetwork = */ false) {

    private var currentUri: Uri? = null
    private var randomAccessFile: RandomAccessFile? = null
    private var header: VaultHeader? = null
    private var dek: ByteArray? = null
    private var dekSpec: SecretKeySpec? = null

    private var currentPlaintextPosition: Long = 0L
    private var bytesRemaining: Long = 0L

    // Cache the most recently decrypted chunk in RAM to minimize cipher re-inits
    private var cachedChunkIndex: Long = -1L
    private var cachedChunkPlaintext: ByteArray? = null

    override fun open(dataSpec: DataSpec): Long {
        currentUri = dataSpec.uri
        val blobFile = resolveBlobFile(dataSpec.uri)
            ?: throw java.io.FileNotFoundException("Vault blob file not resolved for URI: ${dataSpec.uri}")

        val vmk = vmkProvider()
            ?: throw SecurityException("Vault master key unavailable for streaming decryption")

        val raf = RandomAccessFile(blobFile, "r")
        randomAccessFile = raf

        val hdr = VaultFileDecryptor.readHeader(blobFile)
        header = hdr

        val unwrappedDek = VaultFileDecryptor.unwrapDek(hdr, vmk)
        dek = unwrappedDek
        dekSpec = SecretKeySpec(unwrappedDek, CryptoConstants.KEY_ALGORITHM)

        transferInitializing(dataSpec)

        currentPlaintextPosition = dataSpec.position
        val originalFileSize = hdr.originalFileSize

        if (currentPlaintextPosition > originalFileSize) {
            bytesRemaining = 0L
        } else {
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                minOf(dataSpec.length, originalFileSize - currentPlaintextPosition)
            } else {
                originalFileSize - currentPlaintextPosition
            }
        }

        cachedChunkIndex = -1L
        cachedChunkPlaintext = null

        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining <= 0) return C.RESULT_END_OF_INPUT

        val hdr = header ?: return C.RESULT_END_OF_INPUT
        val raf = randomAccessFile ?: return C.RESULT_END_OF_INPUT
        val keySpec = dekSpec ?: return C.RESULT_END_OF_INPUT

        val bytesToRead = minOf(length.toLong(), bytesRemaining).toInt()
        var bytesCopied = 0

        val chunkPlaintextSize = hdr.chunkPlaintextSize
        val lengthBuffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        val cipher = Cipher.getInstance(CryptoConstants.CIPHER_TRANSFORMATION)

        while (bytesCopied < bytesToRead) {
            val chunkIndex = currentPlaintextPosition / chunkPlaintextSize
            val offsetInChunk = (currentPlaintextPosition % chunkPlaintextSize).toInt()

            val chunkData = if (chunkIndex == cachedChunkIndex && cachedChunkPlaintext != null) {
                cachedChunkPlaintext!!
            } else {
                val chunkFileOffset = hdr.chunkOffsetOnDisk(chunkIndex)
                raf.seek(chunkFileOffset)

                lengthBuffer.clear()
                raf.readFully(lengthBuffer.array())
                val chunkLength = lengthBuffer.int

                val ciphertext = ByteArray(chunkLength)
                raf.readFully(ciphertext)

                val isFinal = hdr.isFinalChunk(chunkIndex)
                val chunkNonce = VaultFileFormat.createChunkNonce(hdr.fileNoncePrefix, chunkIndex, isFinal)
                val chunkAad = VaultFileFormat.createChunkAad(hdr.fileId, chunkIndex, isFinal)

                cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(CryptoConstants.GCM_TAG_SIZE_BITS, chunkNonce))
                cipher.updateAAD(chunkAad)
                val decrypted = cipher.doFinal(ciphertext)

                cachedChunkIndex = chunkIndex
                cachedChunkPlaintext = decrypted
                decrypted
            }

            val availableInChunk = chunkData.size - offsetInChunk
            val toCopy = minOf(availableInChunk, bytesToRead - bytesCopied)

            System.arraycopy(chunkData, offsetInChunk, buffer, offset + bytesCopied, toCopy)
            bytesCopied += toCopy
            currentPlaintextPosition += toCopy
            bytesRemaining -= toCopy
        }

        bytesTransferred(bytesCopied)
        return bytesCopied
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        try {
            randomAccessFile?.close()
        } catch (_: Exception) {}
        randomAccessFile = null

        dek?.let { VaultKeyManager.zeroize(it) }
        dek = null
        dekSpec = null
        header = null
        cachedChunkPlaintext = null
        cachedChunkIndex = -1L

        transferEnded()
    }
}

/**
 * Factory for creating [VaultMediaDataSource] instances.
 */
@OptIn(UnstableApi::class)
class VaultMediaDataSourceFactory(
    private val resolveBlobFile: (Uri) -> File?,
    private val vmkProvider: () -> ByteArray?,
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        return VaultMediaDataSource(resolveBlobFile, vmkProvider)
    }
}

