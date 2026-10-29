package com.vaultsync.core.media

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLibraryFiltersTest {

    private fun createItem(
        id: String,
        name: String,
        category: MediaCategory,
        folderName: String = "DCIM",
        folderKey: String = "DCIM",
        sizeBytes: Long = 1000L,
        dateModifiedMillis: Long = 1000L,
    ): DeviceMediaItem = DeviceMediaItem(
        id = id,
        uri = null,
        displayName = name,
        category = category,
        mimeType = when (category) {
            MediaCategory.Images -> "image/jpeg"
            MediaCategory.Videos -> "video/mp4"
            MediaCategory.Audio -> "audio/mpeg"
        },
        folderName = folderName,
        folderKey = folderKey,
        sizeBytes = sizeBytes,
        dateModifiedMillis = dateModifiedMillis,
        source = MediaSource.MediaStore,
    )

    @Test
    fun filterByCategoryFiltersCorrectly() {
        val items = listOf(
            createItem("1", "photo.jpg", MediaCategory.Images),
            createItem("2", "clip.mp4", MediaCategory.Videos),
            createItem("3", "song.mp3", MediaCategory.Audio),
        )

        val imageQuery = MediaQuery(category = MediaCategory.Images)
        val imageResults = filterAndSortMedia(items, imageQuery)
        assertEquals(1, imageResults.size)
        assertEquals("photo.jpg", imageResults.first().displayName)

        val videoQuery = MediaQuery(category = MediaCategory.Videos)
        val videoResults = filterAndSortMedia(items, videoQuery)
        assertEquals(1, videoResults.size)
        assertEquals("clip.mp4", videoResults.first().displayName)
    }

    @Test
    fun filterBySearchTextMatchesDisplayNameOrFolder() {
        val items = listOf(
            createItem("1", "holiday_beach.jpg", MediaCategory.Images, folderName = "Vacation"),
            createItem("2", "sunset.jpg", MediaCategory.Images, folderName = "Nature"),
            createItem("3", "city_trip.jpg", MediaCategory.Images, folderName = "Holiday2025"),
        )

        val query = MediaQuery(
            category = MediaCategory.Images,
            searchText = "holiday",
        )
        val results = filterAndSortMedia(items, query)
        assertEquals(2, results.size)
        assertTrue(results.any { it.id == "1" })
        assertTrue(results.any { it.id == "3" })
    }

    @Test
    fun filterByFolderKey() {
        val items = listOf(
            createItem("1", "img1.jpg", MediaCategory.Images, folderKey = "DCIM/Camera"),
            createItem("2", "img2.jpg", MediaCategory.Images, folderKey = "Pictures/Screenshots"),
        )

        val query = MediaQuery(
            category = MediaCategory.Images,
            folderKey = "Pictures/Screenshots",
        )
        val results = filterAndSortMedia(items, query)
        assertEquals(1, results.size)
        assertEquals("img2.jpg", results.first().displayName)
    }

    @Test
    fun sortMediaOrdersCorrectly() {
        val items = listOf(
            createItem("1", "b.jpg", MediaCategory.Images, sizeBytes = 100L, dateModifiedMillis = 2000L),
            createItem("2", "a.jpg", MediaCategory.Images, sizeBytes = 300L, dateModifiedMillis = 1000L),
            createItem("3", "c.jpg", MediaCategory.Images, sizeBytes = 200L, dateModifiedMillis = 3000L),
        )

        val byNewest = filterAndSortMedia(items, MediaQuery(MediaCategory.Images, sort = MediaSort.Newest))
        assertEquals(listOf("c.jpg", "b.jpg", "a.jpg"), byNewest.map { it.displayName })

        val byOldest = filterAndSortMedia(items, MediaQuery(MediaCategory.Images, sort = MediaSort.Oldest))
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), byOldest.map { it.displayName })

        val byNameAsc = filterAndSortMedia(items, MediaQuery(MediaCategory.Images, sort = MediaSort.NameAsc))
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), byNameAsc.map { it.displayName })

        val bySizeLargest = filterAndSortMedia(items, MediaQuery(MediaCategory.Images, sort = MediaSort.SizeLargest))
        assertEquals(listOf("a.jpg", "c.jpg", "b.jpg"), bySizeLargest.map { it.displayName })
    }

    @Test
    fun foldersForCategoryGroupsAndCountsCorrectly() {
        val items = listOf(
            createItem("1", "img1.jpg", MediaCategory.Images, folderName = "Camera", folderKey = "dcim_camera"),
            createItem("2", "img2.jpg", MediaCategory.Images, folderName = "Camera", folderKey = "dcim_camera"),
            createItem("3", "img3.jpg", MediaCategory.Images, folderName = "Screenshots", folderKey = "screenshots"),
            createItem("4", "vid1.mp4", MediaCategory.Videos, folderName = "Camera", folderKey = "dcim_camera"),
        )

        val folders = foldersForCategory(items, MediaCategory.Images)
        assertEquals(2, folders.size)

        val cameraFolder = folders.first { it.key == "dcim_camera" }
        assertEquals("Camera", cameraFolder.name)
        assertEquals(2, cameraFolder.itemCount)

        val screenshotFolder = folders.first { it.key == "screenshots" }
        assertEquals("Screenshots", screenshotFolder.name)
        assertEquals(1, screenshotFolder.itemCount)
    }
}
