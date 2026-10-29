package com.vaultsync.core.security

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object VaultFileDecryptor {

    fun readHeader(encryptedFile: File): VaultHeader {
        require(encryptedFile.exists()) { "Encrypted file not found: ${encryptedFile.absolutePath}" }
        val headerBytes = ByteArray(VaultFileFormat.HEADER_SIZE_BYTES)
        FileInputStream(encryptedFile).use { input ->
            var bytesRead = 0
            while (bytesRead < headerBytes.size) {
                val count = input.read(headerBytes, bytesRead, headerBytes.size - bytesRead)
                if (count == -1) break
                bytesRead += count
            }
            if (bytesRead < headerBytes.size) {
                throw SecurityException("File too short to contain a valid VaultSync header")
            }
        }
        return VaultFileFormat.deserializeHeader(headerBytes)
    }

    fun unwrapDek(header: VaultHeader, vmk: ByteArray): ByteArray {
        val headerAad = VaultFileFormat.createHeaderAad(
            formatVersion = header.formatVersion,
            chunkPlaintextSize = header.chunkPlaintextSize,
            fileId = header.fileId,
            fileNoncePrefix = header.fileNoncePrefix,
            originalFileSize = header.originalFileSize,
        )
        val vmkSpec = SecretKeySpec(vmk, CryptoConstants.KEY_ALGORITHM)
        val cipher = Cipher.getInstance(CryptoConstants.CIPHER_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            vmkSpec,
            GCMParameterSpec(CryptoConstants.GCM_TAG_SIZE_BITS, header.headerNonce),
        )
        cipher.updateAAD(headerAad)
        return cipher.doFinal(header.wrappedDek)
    }

    fun decryptFile(
        encryptedFile: File,
        destinationPlaintextFile: File,
        vmk: ByteArray,
    ) {
        val tempFile = File(
            destinationPlaintextFile.parentFile,
            "${destinationPlaintextFile.name}.${UUID.randomUUID()}.part",
        )
        try {
            destinationPlaintextFile.parentFile?.mkdirs()
            BufferedInputStream(FileInputStream(encryptedFile)).use { input ->
                BufferedOutputStream(FileOutputStream(tempFile)).use { output ->
                    decryptStream(input, output, vmk)
                }
            }

            if (destinationPlaintextFile.exists()) {
                destinationPlaintextFile.delete()
            }
            if (!tempFile.renameTo(destinationPlaintextFile)) {
                tempFile.copyTo(destinationPlaintextFile, overwrite = true)
                tempFile.delete()
            }
        } catch (e: Exception) {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            throw e
        }
    }

    fun decryptStream(
        inputStream: InputStream,
        outputStream: OutputStream,
        vmk: ByteArray,
    ): Long {
        require(vmk.size == CryptoConstants.AES_KEY_SIZE_BYTES) { "VMK must be 256 bits" }

        val headerBytes = ByteArray(VaultFileFormat.HEADER_SIZE_BYTES)
        var headerBytesRead = 0
        while (headerBytesRead < headerBytes.size) {
            val count = inputStream.read(headerBytes, headerBytesRead, headerBytes.size - headerBytesRead)
            if (count == -1) break
            headerBytesRead += count
        }
        if (headerBytesRead < headerBytes.size) {
            throw SecurityException("Corrupted vault file: header is truncated")
        }

        val header = VaultFileFormat.deserializeHeader(headerBytes)
        val dek = unwrapDek(header, vmk)

        try {
            val dekSpec = SecretKeySpec(dek, CryptoConstants.KEY_ALGORITHM)
            val chunkCipher = Cipher.getInstance(CryptoConstants.CIPHER_TRANSFORMATION)
            val lengthBytes = ByteArray(4)
            val lengthBuffer = ByteBuffer.wrap(lengthBytes).order(ByteOrder.BIG_ENDIAN)

            var chunkIndex = 0L
            var totalPlaintextWritten = 0L
            var encounteredFinal = false

            while (true) {
                var lenRead = 0
                while (lenRead < 4) {
                    val count = inputStream.read(lengthBytes, lenRead, 4 - lenRead)
                    if (count == -1) break
                    lenRead += count
                }

                if (lenRead == 0) {
                    // End of stream reached
                    break
                }
                if (lenRead < 4) {
                    throw SecurityException("Corrupted chunk header at index $chunkIndex: truncated length")
                }

                lengthBuffer.clear()
                val chunkLength = lengthBuffer.int
                val maxExpectedChunkCiphertext = header.chunkPlaintextSize + CryptoConstants.GCM_TAG_SIZE_BYTES
                if (chunkLength < CryptoConstants.GCM_TAG_SIZE_BYTES || chunkLength > maxExpectedChunkCiphertext) {
                    throw SecurityException("Invalid chunk length $chunkLength at index $chunkIndex")
                }

                val ciphertext = ByteArray(chunkLength)
                var ctRead = 0
                while (ctRead < chunkLength) {
                    val count = inputStream.read(ciphertext, ctRead, chunkLength - ctRead)
                    if (count == -1) break
                    ctRead += count
                }
                if (ctRead < chunkLength) {
                    throw SecurityException("Corrupted chunk data at index $chunkIndex: expected $chunkLength bytes, got $ctRead")
                }

                val isFinal = header.isFinalChunk(chunkIndex)
                if (isFinal) {
                    encounteredFinal = true
                }

                val chunkNonce = VaultFileFormat.createChunkNonce(header.fileNoncePrefix, chunkIndex, isFinal)
                val chunkAad = VaultFileFormat.createChunkAad(header.fileId, chunkIndex, isFinal)

                chunkCipher.init(Cipher.DECRYPT_MODE, dekSpec, GCMParameterSpec(CryptoConstants.GCM_TAG_SIZE_BITS, chunkNonce))
                chunkCipher.updateAAD(chunkAad)
                val plaintext = chunkCipher.doFinal(ciphertext)

                outputStream.write(plaintext)
                totalPlaintextWritten += plaintext.size

                chunkIndex++
                if (isFinal) {
                    break
                }
            }

            if (!encounteredFinal && header.originalFileSize > 0) {
                throw SecurityException("Truncated vault file: stream ended before final chunk was verified")
            }

            if (totalPlaintextWritten != header.originalFileSize) {
                throw SecurityException("Decrypted size mismatch: expected ${header.originalFileSize}, got $totalPlaintextWritten")
            }

            outputStream.flush()
            return totalPlaintextWritten
        } finally {
            VaultKeyManager.zeroize(dek)
        }
    }

    /**
     * Decrypts a specific byte sub-range [startOffset, startOffset + length) from the encrypted file.
     * This directly enables HTTP Range streaming and fast video seeking.
     */
    fun readRange(
        encryptedFile: File,
        vmk: ByteArray,
        startOffset: Long,
        length: Long,
        outputStream: OutputStream,
    ): Long {
        require(encryptedFile.exists()) { "Encrypted file not found" }
        require(startOffset >= 0) { "startOffset must be >= 0" }
        require(length >= 0) { "length must be >= 0" }
        if (length == 0L) return 0L

        val header = readHeader(encryptedFile)
        val endOffset = minOf(header.originalFileSize, startOffset + length)
        if (startOffset >= header.originalFileSize) {
            return 0L
        }

        val dek = unwrapDek(header, vmk)
        try {
            val dekSpec = SecretKeySpec(dek, CryptoConstants.KEY_ALGORITHM)
            val chunkCipher = Cipher.getInstance(CryptoConstants.CIPHER_TRANSFORMATION)
            val lengthBuffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)

            val startChunk = startOffset / header.chunkPlaintextSize
            val endChunk = (endOffset - 1) / header.chunkPlaintextSize

            var totalBytesDelivered = 0L

            RandomAccessFile(encryptedFile, "r").use { raf ->
                for (chunkIndex in startChunk..endChunk) {
                    val chunkFileOffset = header.chunkOffsetOnDisk(chunkIndex)
                    raf.seek(chunkFileOffset)

                    lengthBuffer.clear()
                    raf.readFully(lengthBuffer.array())
                    val chunkLength = lengthBuffer.int

                    val ciphertext = ByteArray(chunkLength)
                    raf.readFully(ciphertext)

                    val isFinal = header.isFinalChunk(chunkIndex)
                    val chunkNonce = VaultFileFormat.createChunkNonce(header.fileNoncePrefix, chunkIndex, isFinal)
                    val chunkAad = VaultFileFormat.createChunkAad(header.fileId, chunkIndex, isFinal)

                    chunkCipher.init(Cipher.DECRYPT_MODE, dekSpec, GCMParameterSpec(CryptoConstants.GCM_TAG_SIZE_BITS, chunkNonce))
                    chunkCipher.updateAAD(chunkAad)
                    val plaintext = chunkCipher.doFinal(ciphertext)

                    val chunkStartPlaintextOffset = chunkIndex * header.chunkPlaintextSize
                    val sliceStart = maxOf(0L, startOffset - chunkStartPlaintextOffset).toInt()
                    val sliceEnd = minOf(plaintext.size.toLong(), endOffset - chunkStartPlaintextOffset).toInt()

                    val bytesToWrite = sliceEnd - sliceStart
                    if (bytesToWrite > 0) {
                        outputStream.write(plaintext, sliceStart, bytesToWrite)
                        totalBytesDelivered += bytesToWrite
                    }
                }
            }

            outputStream.flush()
            return totalBytesDelivered
        } finally {
            VaultKeyManager.zeroize(dek)
        }
    }
}
