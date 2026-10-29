package com.vaultsync.core.media

fun filterAndSortMedia(
    items: List<DeviceMediaItem>,
    query: MediaQuery,
): List<DeviceMediaItem> {
    val normalizedSearch = query.searchText.trim().lowercase()

    return items
        .asSequence()
        .filter {
            if (query.includeVideos) {
                it.category == MediaCategory.Images || it.category == MediaCategory.Videos
            } else {
                it.category == query.category
            }
        }

        .filter { query.folderKey == null || it.folderKey == query.folderKey }
        .filter {
            normalizedSearch.isBlank() ||
                it.displayName.lowercase().contains(normalizedSearch) ||
                it.folderName.lowercase().contains(normalizedSearch)
        }
        .let { sequence ->
            when (query.sort) {
                MediaSort.Newest -> sequence.sortedByDescending { it.dateModifiedMillis }
                MediaSort.Oldest -> sequence.sortedBy { it.dateModifiedMillis }
                MediaSort.NameAsc -> sequence.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
                MediaSort.NameDesc -> sequence.sortedWith(compareByDescending<DeviceMediaItem> { it.displayName.lowercase() })
                MediaSort.SizeLargest -> sequence.sortedByDescending { it.sizeBytes }
                MediaSort.SizeSmallest -> sequence.sortedBy { it.sizeBytes }
            }
        }
        .toList()
}

fun foldersForCategory(
    items: List<DeviceMediaItem>,
    category: MediaCategory,
): List<MediaFolder> = items
    .asSequence()
    .filter { it.category == category }
    .groupBy { it.folderKey }
    .map { (folderKey, folderItems) ->
        val sortedItems = folderItems.sortedByDescending { it.dateModifiedMillis }
        MediaFolder(
            key = folderKey,
            name = sortedItems.first().folderName,
            itemCount = sortedItems.size,
            coverUri = sortedItems.firstOrNull { it.uri != null }?.uri,
            previewItems = sortedItems.take(3),
            allItems = sortedItems,
        )
    }
    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

fun foldersForGallery(
    items: List<DeviceMediaItem>,
): List<MediaFolder> = items
    .asSequence()
    .filter { it.category == MediaCategory.Images || it.category == MediaCategory.Videos }
    .groupBy { it.folderKey }
    .map { (folderKey, folderItems) ->
        val sortedItems = folderItems.sortedByDescending { it.dateModifiedMillis }
        MediaFolder(
            key = folderKey,
            name = sortedItems.first().folderName,
            itemCount = sortedItems.size,
            coverUri = sortedItems.firstOrNull { it.uri != null }?.uri,
            previewItems = sortedItems.take(3),
            allItems = sortedItems,
        )
    }
    .sortedWith { a, b ->
        fun priority(name: String): Int = when (name.lowercase()) {
            "camera" -> 0
            "dcim" -> 1
            "download", "downloads" -> 2
            "screenshots" -> 3
            "pictures" -> 4
            "movies", "video", "videos" -> 5
            else -> 10
        }
        val pA = priority(a.name)
        val pB = priority(b.name)
        if (pA != pB) pA.compareTo(pB) else a.name.compareTo(b.name, ignoreCase = true)
    }

