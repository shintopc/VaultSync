package com.vaultsync.core.model

enum class VaultCategory(val label: String) {
    All("All Files"),
    Images("Images"),
    Videos("Videos"),
    Audio("Audio"),
    Documents("Documents"),
    Other("Other");

    companion object {
        fun fromMimeOrExtension(mimeType: String?, fileName: String): VaultCategory {
            val cleanMime = mimeType?.lowercase()?.trim().orEmpty()
            val extension = fileName.substringAfterLast('.', "").lowercase().trim()

            return when {
                cleanMime.startsWith("image/") || extension in imageExtensions -> Images
                cleanMime.startsWith("video/") || extension in videoExtensions -> Videos
                cleanMime.startsWith("audio/") || extension in audioExtensions -> Audio
                cleanMime.startsWith("text/") ||
                    cleanMime == "application/pdf" ||
                    cleanMime.contains("document") ||
                    cleanMime.contains("sheet") ||
                    cleanMime.contains("presentation") ||
                    extension in documentExtensions -> Documents
                else -> Other
            }
        }

        private val imageExtensions = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "svg", "tiff", "ico"
        )
        private val videoExtensions = setOf(
            "mp4", "mkv", "webm", "avi", "mov", "wmv", "3gp", "flv", "m4v"
        )
        private val audioExtensions = setOf(
            "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma", "mid"
        )
        private val documentExtensions = setOf(
            "pdf", "doc", "docx", "txt", "rtf", "odt", "xls", "xlsx", "csv", "ppt", "pptx", "html", "md", "json"
        )
    }
}
