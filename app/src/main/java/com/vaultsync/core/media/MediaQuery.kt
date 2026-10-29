package com.vaultsync.core.media

data class MediaQuery(
    val category: MediaCategory = MediaCategory.Images,
    val searchText: String = "",
    val folderKey: String? = null,
    val sort: MediaSort = MediaSort.Newest,
    val includeVideos: Boolean = false,
)

