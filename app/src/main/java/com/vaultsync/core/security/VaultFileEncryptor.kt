package com.vaultsync.core.security

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
data class EncryptionResult(
    val fileId: UUID,
    val originalSizeBytes: Long,
    val encryptedSizeBytes: Long,
    val chunkCount: Long,
)

object VaultFileEncryptor {

    fun encryptFile(
        inputFile: File,
        destinationEncryptedFile: File,
        vmk: ByteArray,
        fileId: UUID = UUID.randomUUID(),
        chunkPlaintextSize: Int = CryptoConstants.DEFAULT_CHUNK_SIZE_BYTES,
    ): EncryptionResult {
        require(inputFile.exists()) { "Source file does not exist: ${inputFile.absolutePath}" }
        require(vmk.size == CryptoConstants.AES_KEY_SIZE_BYTES) { "VMK must be 256 bits" }

        val originalSize = inputFile.length()
        val tempFile = File(destinationEncryptedFile.parentFile, "${destinationEncryptedFile.name}.${UUID.randomUUID()}.part")

        try {
            destinationEncryptedFile.parentFile?.mkdirs()
            val result = BufferedInputStream(FileInputStream(inputFile)).use { input ->
                BufferedOutputStream(FileOutputStream(tempFile)).use { output ->
                    encryptStream(
                        inputStream = input,
                        outputStream = output,
                        vmk = vmk,
                        knownPlaintextSize = originalSize,
                        fileId = fileId,
                        chunkPlaintextSize = chunkPlaintextSize,
                    )
                }
            }

            if (destinationEncryptedFile.exists()) {
                destinationEncryptedFile.delete()
            }
            if (!tempFile.renameTo(destinationEncryptedFile)) {
                // Fallback copy if renameTo fails across file systems
                tempFile.copyTo(destinationEncryptedFile, overwrite = true)
                tempFile.delete()
            }

            return result
        } catch (e: Exception) {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            throw e
        }
    }

    fun encryptStream(
        inputStream: InputStream,
        outputStream: OutputStream,
        vmk: ByteArray,
        knownPlaintextSize: Long,
        fileId: UUID = UUID.randomUUID(),
        chunkPlaintextSize: Int = CryptoConstants.DEFAULT_CHUNK_SIZE_BYTES,
    ): EncryptionResult {
        require(vmk.size == CryptoConstants.AES_KEY_SIZE_BYTES) { "VMK must be 256 bits" }
        require(chunkPlaintextSize > 0) { "Chunk size must be positive" }

        require(knownPlaintextSize >= 0L) { "knownPlaintextSize must be non-negative (was $knownPlaintextSize)" }

        val dek = KeyDerivation.generateRandomBytes(CryptoConstants.AES_KEY_SIZE_BYTES)
        val fileNoncePrefix = KeyDerivation.generateRandomBytes(CryptoConstants.FILE_NONCE_PREFIX_SIZE_BYTES)
        val headerNonce = KeyDerivation.generateRandomBytes(CryptoConstants.GCM_NONCE_SIZE_BYTES)

        try {
            // 1. Wrap DEK with VMK and header AAD
            val headerAad = VaultFileFormat.createHeaderAad(
                formatVersion = CryptoConstants.FORMAT_VERSION,
                chunkPlaintextSize = chunkPlaintextSize,
                fileId = fileId,
                fileNoncePrefix = fileNoncePrefix,
                originalFileSize = knownPlaintextSize,
            )

            val vmkSpec = SecretKeySpec(vmk, CryptoConstants.KEY_ALGORITHM)
            val headerCipher = Cipher.getInstance(CryptoConstants.CIPHER_TRANSFORMATION)
            headerCipher.init(Cipher.ENCRYPT_MODE, vmkSpec, GCMParameterSpec(CryptoConstants.GCM_TAG_SIZE_BITS, headerNonce))
            headerCipher.updateAAD(headerAad)
            val wrappedDek = headerCipher.doFinal(dek)

            // 2. Write Header
            val header = VaultHeader(
                formatVersion = CryptoConstants.FORMAT_VERSION,
                chunkPlaintextSize = chunkPlaintextSize,
                fileId = fileId,
                fileNoncePrefix = fileNoncePrefix,
                headerNonce = headerNonce,
                wrappedDek = wrappedDek,
                originalFileSize = knownPlaintextSize,
            )
            val serializedHeader = VaultFileFormat.serializeHeader(header)
            outputStream.write(serializedHeader)

            var totalEncryptedBytesWritten = serializedHeader.size.toLong()
            var totalPlaintextBytesRead = 0L
            var chunkIndex = 0L

            val dekSpec = SecretKeySpec(dek, CryptoConstants.KEY_ALGORITHM)
            val chunkCipher = Cipher.getInstance(CryptoConstants.CIPHER_TRANSFORMATION)
            val lengthBuffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)

            val readBuffer = ByteArray(chunkPlaintextSize)

            // Handle empty file case
            if (knownPlaintextSize == 0L) {
                // Ensure the stream is truly empty and not an undeclared/streaming source
                val peek = inputStream.read()
                if (peek != -1) {
                    throw IllegalArgumentException(
                        "Stream contains data but knownPlaintextSize was specified as 0L. " +
                        "Spool the stream or provide exact size before encryption."
                    )
                }
                val chunkNonce = VaultFileFormat.createChunkNonce(fileNoncePrefix, 0L, isFinal = true)
                val chunkAad = VaultFileFormat.createChunkAad(fileId, 0L, isFinal = true)

                chunkCipher.init(Cipher.ENCRYPT_MODE, dekSpec, GCMParameterSpec(CryptoConstants.GCM_TAG_SIZE_BITS, chunkNonce))
                chunkCipher.updateAAD(chunkAad)
                val encryptedChunk = chunkCipher.doFinal(ByteArray(0))

                lengthBuffer.clear()
                lengthBuffer.putInt(encryptedChunk.size)
                outputStream.write(lengthBuffer.array())
                outputStream.write(encryptedChunk)
                totalEncryptedBytesWritten += 4 + encryptedChunk.size
                chunkIndex = 1L
            } else {
                while (totalPlaintextBytesRead < knownPlaintextSize) {
                    val bytesToRead = minOf(chunkPlaintextSize.toLong(), knownPlaintextSize - totalPlaintextBytesRead).toInt()
                    var bytesReadThisChunk = 0
                    while (bytesReadThisChunk < bytesToRead) {
                        val count = inputStream.read(readBuffer, bytesReadThisChunk, bytesToRead - bytesReadThisChunk)
                        if (count == -1) break
                        bytesReadThisChunk += count
                    }

                    if (bytesReadThisChunk == 0) break

                    totalPlaintextBytesRead += bytesReadThisChunk
                    val isFinal = totalPlaintextBytesRead >= knownPlaintextSize

                    val chunkNonce = VaultFileFormat.createChunkNonce(fileNoncePrefix, chunkIndex, isFinal)
                    val chunkAad = VaultFileFormat.createChunkAad(fileId, chunkIndex, isFinal)

                    chunkCipher.init(Cipher.ENCRYPT_MODE, dekSpec, GCMParameterSpec(CryptoConstants.GCM_TAG_SIZE_BITS, chunkNonce))
                    chunkCipher.updateAAD(chunkAad)
                    val encryptedChunk = chunkCipher.doFinal(readBuffer, 0, bytesReadThisChunk)

                    lengthBuffer.clear()
                    lengthBuffer.putInt(encryptedChunk.size)
                    outputStream.write(lengthBuffer.array())
                    outputStream.write(encryptedChunk)

                    totalEncryptedBytesWritten += 4 + encryptedChunk.size
                    chunkIndex++
                }
            }

            outputStream.flush()
            return EncryptionResult(
                fileId = fileId,
                originalSizeBytes = totalPlaintextBytesRead,
                encryptedSizeBytes = totalEncryptedBytesWritten,
                chunkCount = chunkIndex,
            )
        } finally {
            VaultKeyManager.zeroize(dek)
        }
    }
}
