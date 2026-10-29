package com.vaultsync.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class VaultCategoryTest {

    @Test
    fun categorizesImagesCorrectly() {
        assertEquals(VaultCategory.Images, VaultCategory.fromMimeOrExtension("image/jpeg", "photo.jpg"))
        assertEquals(VaultCategory.Images, VaultCategory.fromMimeOrExtension("image/png", "screenshot.png"))
        assertEquals(VaultCategory.Images, VaultCategory.fromMimeOrExtension(null, "picture.webp"))
        assertEquals(VaultCategory.Images, VaultCategory.fromMimeOrExtension(null, "RAW_PHOTO.HEIC"))
    }

    @Test
    fun categorizesVideosCorrectly() {
        assertEquals(VaultCategory.Videos, VaultCategory.fromMimeOrExtension("video/mp4", "clip.mp4"))
        assertEquals(VaultCategory.Videos, VaultCategory.fromMimeOrExtension(null, "vacation.mkv"))
        assertEquals(VaultCategory.Videos, VaultCategory.fromMimeOrExtension("video/quicktime", "movie.mov"))
    }

    @Test
    fun categorizesAudioCorrectly() {
        assertEquals(VaultCategory.Audio, VaultCategory.fromMimeOrExtension("audio/mpeg", "song.mp3"))
        assertEquals(VaultCategory.Audio, VaultCategory.fromMimeOrExtension(null, "recording.m4a"))
        assertEquals(VaultCategory.Audio, VaultCategory.fromMimeOrExtension("audio/flac", "audiofile.flac"))
    }

    @Test
    fun categorizesDocumentsCorrectly() {
        assertEquals(VaultCategory.Documents, VaultCategory.fromMimeOrExtension("application/pdf", "tax.pdf"))
        assertEquals(VaultCategory.Documents, VaultCategory.fromMimeOrExtension(null, "notes.txt"))
        assertEquals(VaultCategory.Documents, VaultCategory.fromMimeOrExtension(null, "report.docx"))
        assertEquals(VaultCategory.Documents, VaultCategory.fromMimeOrExtension(null, "budget.xlsx"))
    }

    @Test
    fun categorizesOtherCorrectly() {
        assertEquals(VaultCategory.Other, VaultCategory.fromMimeOrExtension("application/zip", "backup.zip"))
        assertEquals(VaultCategory.Other, VaultCategory.fromMimeOrExtension(null, "binary.dat"))
    }
}
