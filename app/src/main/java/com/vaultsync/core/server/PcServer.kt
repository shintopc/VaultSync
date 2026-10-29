package com.vaultsync.core.server

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Size
import com.vaultsync.core.media.DeviceMediaItem
import com.vaultsync.core.media.MediaCategory
import com.vaultsync.core.media.MediaRepository
import com.vaultsync.core.model.VaultCategory
import com.vaultsync.core.model.VaultEntry
import com.vaultsync.core.security.VaultFileDecryptor
import com.vaultsync.core.security.VaultSession
import com.vaultsync.core.storage.VaultStorageManager
import com.vaultsync.core.storage.VaultThumbnailManager
import com.vaultsync.core.vault.VaultRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ServerState {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR,
}

class PcServer(
    private val context: Context,
    private val authManager: PcAuthManager,
    private val mediaRepository: MediaRepository,
    private val vaultRepository: VaultRepository,
    private val vaultSession: VaultSession,
    private val storageManager: VaultStorageManager,
    private val defaultPort: Int = 8080,
    private val thumbnailManager: VaultThumbnailManager? = null,
) {
    private val effectiveThumbnailManager: VaultThumbnailManager by lazy {
        thumbnailManager ?: VaultThumbnailManager(context, storageManager)
    }
    @Volatile
    var state: ServerState = ServerState.STOPPED
        private set

    @Volatile
    var boundPort: Int = defaultPort
        private set

    @Volatile
    var errorMessage: String? = null
        private set

    private var serverEngine: EmbeddedServer<*, *>? = null

    val isRunning: Boolean
        get() = state == ServerState.RUNNING

    fun getLocalUrl(): String? {
        val ip = NetworkUtils.getLocalIpAddress(context) ?: return null
        return "http://$ip:$boundPort"
    }

    @Synchronized
    fun start(): Boolean {
        if (state == ServerState.RUNNING || state == ServerState.STARTING) {
            return true
        }

        state = ServerState.STARTING
        errorMessage = null

        // Attempt default port and fall back on conflicts
        for (port in defaultPort..(defaultPort + 10)) {
            try {
                val engine = createEngine(port)
                engine.start(wait = false)
                serverEngine = engine
                boundPort = port
                state = ServerState.RUNNING
                return true
            } catch (e: Exception) {
                if (isPortConflict(e)) {
                    continue // try next port
                } else {
                    errorMessage = "Failed to start server on port $port: ${e.message}"
                    state = ServerState.ERROR
                    return false
                }
            }
        }

        errorMessage = "All ports in range $defaultPort..${defaultPort + 10} are currently in use."
        state = ServerState.ERROR
        return false
    }

    @Synchronized
    fun stop() {
        try {
            serverEngine?.stop(500, 1000)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            serverEngine = null
            state = ServerState.STOPPED
            authManager.invalidateAllSessions()
        }
    }

    private fun createEngine(port: Int): EmbeddedServer<*, *> {
        return embeddedServer(CIO, port = port, host = "0.0.0.0") {
            routing {
                // Interceptor for preflight CORS on all routes
                options("{...}") {
                    handleCors(call)
                    call.respond(HttpStatusCode.OK)
                }

                // Web App Static Assets
                get("/") {
                    handleCors(call)
                    serveAsset(call, "index.html", ContentType.Text.Html)
                }

                get("/index.html") {
                    handleCors(call)
                    serveAsset(call, "index.html", ContentType.Text.Html)
                }

                get("/style.css") {
                    handleCors(call)
                    serveAsset(call, "style.css", ContentType.Text.CSS)
                }

                get("/app.js") {
                    handleCors(call)
                    serveAsset(call, "app.js", ContentType.parse("application/javascript"))
                }

                get("/favicon.ico") {
                    handleCors(call)
                    call.respond(HttpStatusCode.NoContent)
                }

                get("/api/status") {
                    handleCors(call)
                    val ip = NetworkUtils.getLocalIpAddress(context)
                    val status = ServerStatus(
                        isRunning = state == ServerState.RUNNING,
                        localIp = ip,
                        port = boundPort,
                        url = if (ip != null) "http://$ip:$boundPort" else null,
                        activeSessionsCount = authManager.getActiveSessionCount(),
                        isVaultUnlockedOnPhone = vaultSession.isUnlocked.value,
                    )
                    call.respondText(
                        text = ApiResponses.success(status.toJson()),
                        contentType = ContentType.Application.Json,
                    )
                }

                // Auth: Login
                post("/api/auth/login") {
                    handleCors(call)
                    val clientIp = getClientIp(call)

                    // 1. Private LAN check
                    if (!NetworkUtils.isPrivateSubnetAddress(clientIp)) {
                        call.respondText(
                            status = HttpStatusCode.Forbidden,
                            text = ApiResponses.error(ApiErrorCode.FORBIDDEN, "Access from non-private IP addresses is forbidden"),
                            contentType = ContentType.Application.Json,
                        )
                        return@post
                    }

                    // 2. CSRF / Origin check
                    val origin = call.request.header("Origin")
                    val referer = call.request.header("Referer")
                    val custom = call.request.header("X-VaultSync-Client")
                    val host = call.request.header("Host") ?: "localhost"
                    if (!authManager.validateOriginAndCsrf(origin, referer, host, custom)) {
                        call.respondText(
                            status = HttpStatusCode.Forbidden,
                            text = ApiResponses.error(ApiErrorCode.FORBIDDEN, "CSRF / Origin validation failed"),
                            contentType = ContentType.Application.Json,
                        )
                        return@post
                    }

                    // 3. Throttling check
                    val waitSec = authManager.isThrottled(clientIp)
                    if (waitSec > 0) {
                        call.respondText(
                            status = HttpStatusCode.TooManyRequests,
                            text = ApiResponses.error(
                                ApiErrorCode.THROTTLED,
                                "Too many failed attempts. Try again in $waitSec seconds.",
                            ),
                            contentType = ContentType.Application.Json,
                        )
                        return@post
                    }

                    // 3. Parse credentials
                    val body = call.receiveText()
                    val submittedPasscode = try {
                        JSONObject(body).optString("passcode", "")
                    } catch (e: Exception) {
                        ""
                    }

                    val session = authManager.authenticatePasscode(clientIp, submittedPasscode)
                    if (session != null) {
                        val resp = LoginResponse(
                            token = session.token,
                            expiresAt = session.createdAtMillis + (30 * 60 * 1000L),
                            hasVaultAccess = session.hasVaultAccess,
                        )
                        call.respondText(
                            text = ApiResponses.success(resp.toJson()),
                            contentType = ContentType.Application.Json,
                        )
                    } else {
                        val waitSecAfter = authManager.isThrottled(clientIp)
                        if (waitSecAfter > 0) {
                            call.respondText(
                                status = HttpStatusCode.TooManyRequests,
                                text = ApiResponses.error(
                                    ApiErrorCode.THROTTLED,
                                    "Too many failed attempts. Try again in $waitSecAfter seconds.",
                                ),
                                contentType = ContentType.Application.Json,
                            )
                        } else {
                            call.respondText(
                                status = HttpStatusCode.Unauthorized,
                                text = ApiResponses.error(ApiErrorCode.UNAUTHORIZED, "Invalid pairing passcode"),
                                contentType = ContentType.Application.Json,
                            )
                        }
                    }
                }

                // Auth: Logout
                post("/api/auth/logout") {
                    handleCors(call)
                    val token = extractToken(call)
                    authManager.logout(token)
                    call.respondText(
                        text = ApiResponses.success("Logged out successfully"),
                        contentType = ContentType.Application.Json,
                    )
                }

                // Auth: Check Session
                get("/api/auth/session") {
                    handleCors(call)
                    val session = requireValidSession(call) ?: return@get
                    val remainingSeconds = (30 * 60) - ((System.currentTimeMillis() - session.lastActivityMillis) / 1000)
                    val info = SessionInfo(
                        active = true,
                        clientIp = session.clientIp,
                        hasVaultAccess = session.hasVaultAccess,
                        remainingSeconds = remainingSeconds.coerceAtLeast(0),
                    )
                    call.respondText(
                        text = ApiResponses.success(info.toJson()),
                        contentType = ContentType.Application.Json,
                    )
                }

                // Vault: Unlock
                post("/api/vault/unlock") {
                    handleCors(call)
                    val session = requireValidSession(call) ?: return@post
                    val body = call.receiveText()
                    val pin = try {
                        JSONObject(body).optString("pin", "").trim()
                    } catch (e: Exception) {
                        ""
                    }

                    if (pin.isBlank()) {
                        call.respondText(
                            status = HttpStatusCode.BadRequest,
                            text = ApiResponses.error(ApiErrorCode.INVALID_REQUEST, "Master PIN or Password is required"),
                            contentType = ContentType.Application.Json,
                        )
                        return@post
                    }

                    val success = authManager.elevateSessionToVault(session.token, pin, vaultSession)
                    if (success) {
                        call.respondText(
                            text = ApiResponses.success("Vault authorized for this PC session"),
                            contentType = ContentType.Application.Json,
                        )
                    } else {
                        call.respondText(
                            status = HttpStatusCode.Unauthorized,
                            text = ApiResponses.error(ApiErrorCode.UNAUTHORIZED, "Incorrect Vault Master PIN or Password"),
                            contentType = ContentType.Application.Json,
                        )
                    }
                }

                // Vault: Lock
                post("/api/vault/lock") {
                    handleCors(call)
                    val session = requireValidSession(call) ?: return@post
                    authManager.revokeVaultAccess(session.token)
                    call.respondText(
                        text = ApiResponses.success("Vault access revoked for this session"),
                        contentType = ContentType.Application.Json,
                    )
                }

                // Media: List normal media items
                get("/api/media/list") {
                    handleCors(call)
                    requireValidSession(call) ?: return@get

                    val items = mediaRepository.loadMediaStoreItems()
                    val jsonArray = JSONArray()
                    items.forEach { item ->
                        val obj = JSONObject().apply {
                            put("id", item.id)
                            put("displayName", item.displayName)
                            put("mimeType", item.mimeType)
                            put("sizeBytes", item.sizeBytes)
                            put("dateModifiedMillis", item.dateModifiedMillis)
                            put("category", item.category.name)
                            put("uri", item.uri?.toString() ?: JSONObject.NULL)
                        }
                        jsonArray.put(obj)
                    }

                    call.respondText(
                        text = ApiResponses.success(jsonArray),
                        contentType = ContentType.Application.Json,
                    )
                }

                // Media: Streaming Normal Media File with Range Support
                get("/api/media/file/{id}") {
                    handleCors(call)
                    requireValidSession(call) ?: return@get

                    val id = call.parameters["id"]
                    if (id.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@get
                    }

                    val item = mediaRepository.loadMediaStoreItems().firstOrNull { it.id == id }
                    if (item == null || item.uri == null) {
                        call.respond(HttpStatusCode.NotFound)
                        return@get
                    }

                    val mime = item.mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
                    val totalSize = item.sizeBytes
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")
                    call.response.header(HttpHeaders.ContentDisposition, "inline; filename=\"${item.displayName}\"")

                    val rangeHeader = call.request.header(HttpHeaders.Range)
                    if (rangeHeader != null && rangeHeader.startsWith("bytes=") && totalSize > 0) {
                        val rangeSpec = rangeHeader.removePrefix("bytes=").trim()
                        val parts = rangeSpec.split('-', limit = 2)
                        var start = parts[0].toLongOrNull() ?: 0L
                        var end = if (parts.size > 1 && parts[1].isNotBlank()) parts[1].toLongOrNull() ?: (totalSize - 1) else (totalSize - 1)
                        end = minOf(end, totalSize - 1)

                        val contentLength = (end - start) + 1
                        call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/$totalSize")

                        call.respondOutputStream(
                            status = HttpStatusCode.PartialContent,
                            contentType = ContentType.parse(mime),
                            contentLength = contentLength,
                        ) {
                            openMediaStream(item.uri)?.use { stream ->
                                stream.skip(start)
                                val buf = ByteArray(65536)
                                var remaining = contentLength
                                while (remaining > 0) {
                                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                                    val read = stream.read(buf, 0, toRead)
                                    if (read <= 0) break
                                    write(buf, 0, read)
                                    remaining -= read
                                }
                                flush()
                            }
                        }
                    } else {
                        call.respondOutputStream(
                            status = HttpStatusCode.OK,
                            contentType = ContentType.parse(mime),
                            contentLength = if (totalSize > 0) totalSize else null,
                        ) {
                            openMediaStream(item.uri)?.use { stream ->
                                stream.copyTo(this)
                            }
                        }
                    }
                }

                // Media: Thumbnail (Images & Videos)
                get("/api/media/thumbnail/{id}") {
                    handleCors(call)
                    requireValidSession(call) ?: return@get

                    val id = call.parameters["id"]
                    if (id.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@get
                    }

                    val item = mediaRepository.loadMediaStoreItems().firstOrNull { it.id == id }
                    if (item == null || item.uri == null) {
                        call.respond(HttpStatusCode.NotFound)
                        return@get
                    }

                    val thumbBytes = getOrGenerateMediaThumbnail(item)
                    if (thumbBytes != null && thumbBytes.isNotEmpty()) {
                        call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
                        call.respondBytes(thumbBytes, ContentType.Image.JPEG)
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }

                // Vault: Folders
                get("/api/vault/folders") {
                    handleCors(call)
                    requireVaultSession(call) ?: return@get

                    val folders = runBlocking { vaultRepository.getAllFolders().first() }
                    val jsonArray = JSONArray()
                    folders.forEach { folder ->
                        val obj = JSONObject().apply {
                            put("id", folder.id)
                            put("name", folder.name)
                            put("parentId", folder.parentId ?: JSONObject.NULL)
                            put("createdAtMillis", folder.createdAtMillis)
                        }
                        jsonArray.put(obj)
                    }

                    call.respondText(
                        text = ApiResponses.success(jsonArray),
                        contentType = ContentType.Application.Json,
                    )
                }

                // Vault: Entries
                get("/api/vault/entries") {
                    handleCors(call)
                    requireVaultSession(call) ?: return@get

                    val entries = runBlocking { vaultRepository.getAllEntries().first() }
                    val jsonArray = JSONArray()
                    entries.forEach { entry ->
                        val obj = JSONObject().apply {
                            put("id", entry.id)
                            put("fileName", entry.originalFileName)
                            put("category", entry.category.name)
                            put("mimeType", entry.mimeType ?: JSONObject.NULL)
                            put("sizeBytes", entry.originalSizeBytes)
                            put("dateModifiedMillis", entry.dateModifiedMillis)
                            put("folderId", entry.folderId ?: JSONObject.NULL)
                        }
                        jsonArray.put(obj)
                    }

                    call.respondText(
                        text = ApiResponses.success(jsonArray),
                        contentType = ContentType.Application.Json,
                    )
                }

                // Vault: Dynamic Stream Decryption with HTTP Range Seeking (no static URLs)
                get("/api/vault/file/{id}") {
                    handleCors(call)
                    requireVaultSession(call) ?: return@get

                    val entryId = call.parameters["id"]
                    if (entryId.isNullOrBlank()) {
                        call.respondText(
                            status = HttpStatusCode.BadRequest,
                            text = ApiResponses.error(ApiErrorCode.INVALID_REQUEST, "File ID missing"),
                            contentType = ContentType.Application.Json,
                        )
                        return@get
                    }

                    val entry = runBlocking { vaultRepository.getEntry(entryId) }
                    if (entry == null) {
                        call.respondText(
                            status = HttpStatusCode.NotFound,
                            text = ApiResponses.error(ApiErrorCode.NOT_FOUND, "Vault file not found"),
                            contentType = ContentType.Application.Json,
                        )
                        return@get
                    }

                    val blobFile = storageManager.getEncryptedBlobFile(entry.id)
                    if (!blobFile.exists()) {
                        call.respondText(
                            status = HttpStatusCode.NotFound,
                            text = ApiResponses.error(ApiErrorCode.NOT_FOUND, "Encrypted blob not found"),
                            contentType = ContentType.Application.Json,
                        )
                        return@get
                    }

                    val mime = entry.mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
                    val totalSize = entry.originalSizeBytes
                    val rangeHeader = call.request.header(HttpHeaders.Range)

                    // Security headers: strictly prevent browser / proxy disk caching of private vault media
                    call.response.header(HttpHeaders.CacheControl, "no-store, no-cache, must-revalidate, private, max-age=0")
                    call.response.header("Pragma", "no-cache")
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")
                    call.response.header(HttpHeaders.ContentDisposition, "inline; filename=\"${entry.originalFileName}\"")

                    try {
                        if (rangeHeader != null && rangeHeader.startsWith("bytes=") && totalSize > 0) {
                            val rangeSpec = rangeHeader.removePrefix("bytes=").trim()
                            val parts = rangeSpec.split('-', limit = 2)
                            var start = parts[0].toLongOrNull() ?: 0L
                            var end = if (parts.size > 1 && parts[1].isNotBlank()) parts[1].toLongOrNull() ?: (totalSize - 1) else (totalSize - 1)

                            if (parts[0].isBlank() && parts.size > 1 && parts[1].isNotBlank()) {
                                val suffix = parts[1].toLongOrNull() ?: 0L
                                start = (totalSize - suffix).coerceAtLeast(0L)
                                end = totalSize - 1
                            }

                            end = minOf(end, totalSize - 1)
                            if (start > end || start >= totalSize || start < 0) {
                                call.response.header(HttpHeaders.ContentRange, "bytes */$totalSize")
                                call.respond(HttpStatusCode(416, "Requested Range Not Satisfiable"))
                                return@get
                            }

                            val contentLength = (end - start) + 1
                            call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/$totalSize")

                            vaultSession.withVmkSuspend { vmk ->
                                call.respondOutputStream(
                                    status = HttpStatusCode.PartialContent,
                                    contentType = ContentType.parse(mime),
                                    contentLength = contentLength,
                                ) {
                                    VaultFileDecryptor.readRange(blobFile, vmk, start, contentLength, this)
                                }
                            }
                        } else {
                            // Full stream
                            vaultSession.withVmkSuspend { vmk ->
                                call.respondOutputStream(
                                    status = HttpStatusCode.OK,
                                    contentType = ContentType.parse(mime),
                                    contentLength = totalSize,
                                ) {
                                    VaultFileDecryptor.readRange(blobFile, vmk, 0, totalSize, this)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        call.respondText(
                            status = HttpStatusCode.InternalServerError,
                            text = ApiResponses.error(ApiErrorCode.INTERNAL_ERROR, "Decryption streaming error: ${e.message}"),
                            contentType = ContentType.Application.Json,
                        )
                    }
                }

                // Vault: Thumbnail (Images & Videos)
                get("/api/vault/thumbnail/{id}") {
                    handleCors(call)
                    requireVaultSession(call) ?: return@get

                    val entryId = call.parameters["id"]
                    if (entryId.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@get
                    }

                    val entry = runBlocking { vaultRepository.getEntry(entryId) }
                    if (entry == null) {
                        call.respond(HttpStatusCode.NotFound)
                        return@get
                    }

                    val thumbFile = try {
                        vaultSession.withVmkSuspend { vmk ->
                            effectiveThumbnailManager.getOrCreateThumbnail(entry, vmk)
                        }
                    } catch (_: Exception) {
                        null
                    }

                    if (thumbFile == null || !thumbFile.exists() || thumbFile.length() == 0L) {
                        call.respond(HttpStatusCode.NotFound)
                        return@get
                    }

                    call.response.header(HttpHeaders.CacheControl, "no-store, no-cache, must-revalidate, private, max-age=0")
                    call.response.header("Pragma", "no-cache")
                    call.respondBytes(thumbFile.readBytes(), ContentType.Image.JPEG)
                }

                // Upload: Normal Media File from PC
                post("/api/upload/media") {
                    handleCors(call)
                    requireValidSession(call) ?: return@post

                    val rawName = call.request.queryParameters["filename"]
                        ?: call.request.header("X-Filename")
                    val cleanName = sanitizeFileName(rawName, "upload")

                    val uploadDir = File(context.filesDir, "uploads").apply { mkdirs() }
                    val targetFile = File(uploadDir, cleanName)

                    // Path traversal protection: target must resolve strictly inside uploadDir
                    if (!targetFile.canonicalPath.startsWith(uploadDir.canonicalPath + File.separator)) {
                        call.respondText(
                            status = HttpStatusCode.BadRequest,
                            text = ApiResponses.error(ApiErrorCode.INVALID_REQUEST, "Invalid upload filename"),
                            contentType = ContentType.Application.Json,
                        )
                        return@post
                    }

                    val tempPartFile = File(uploadDir, "${cleanName}.${UUID.randomUUID()}.part")

                    try {
                        call.receiveChannel().toInputStream().use { input ->
                            FileOutputStream(tempPartFile).use { output ->
                                input.copyTo(output)
                            }
                        }

                        if (targetFile.exists()) {
                            targetFile.delete()
                        }
                        if (!tempPartFile.renameTo(targetFile)) {
                            tempPartFile.copyTo(targetFile, overwrite = true)
                            tempPartFile.delete()
                        }

                        val resp = JSONObject().apply {
                            put("fileName", cleanName)
                            put("sizeBytes", targetFile.length())
                        }
                        call.respondText(
                            text = ApiResponses.success(resp),
                            contentType = ContentType.Application.Json,
                        )
                    } catch (e: Exception) {
                        if (tempPartFile.exists()) {
                            tempPartFile.delete()
                        }
                        call.respondText(
                            status = HttpStatusCode.InternalServerError,
                            text = ApiResponses.error(ApiErrorCode.INTERNAL_ERROR, "Media upload failed: ${e.message}"),
                            contentType = ContentType.Application.Json,
                        )
                    }
                }

                // Upload: Directly Encrypt and Import File into Vault from PC
                post("/api/upload/vault") {
                    handleCors(call)
                    requireVaultSession(call) ?: return@post

                    val rawName = call.request.queryParameters["filename"]
                        ?: call.request.header("X-Filename")
                    val cleanName = sanitizeFileName(rawName, "vault_upload")
                    val folderId = call.request.queryParameters["folderId"]?.takeIf { it.isNotBlank() }

                    val contentLengthHeader = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull() ?: 0L
                    val mimeType = call.request.header(HttpHeaders.ContentType)

                    try {
                        var importedEntry: VaultEntry? = null
                        vaultSession.withVmkSuspend { vmk ->
                            call.receiveChannel().toInputStream().use { input ->
                                importedEntry = vaultRepository.importStream(
                                    inputStream = input,
                                    displayName = cleanName,
                                    originalSize = contentLengthHeader,
                                    mimeType = mimeType,
                                    destinationFolderId = folderId,
                                    vmk = vmk,
                                )
                            }
                        }

                        val obj = JSONObject().apply {
                            put("id", importedEntry?.id)
                            put("fileName", importedEntry?.originalFileName)
                            put("category", importedEntry?.category?.name)
                            put("sizeBytes", importedEntry?.originalSizeBytes)
                            put("folderId", importedEntry?.folderId ?: JSONObject.NULL)
                        }
                        call.respondText(
                            text = ApiResponses.success(obj),
                            contentType = ContentType.Application.Json,
                        )
                    } catch (e: Exception) {
                        call.respondText(
                            status = HttpStatusCode.InternalServerError,
                            text = ApiResponses.error(ApiErrorCode.INTERNAL_ERROR, "Vault upload failed: ${e.message}"),
                            contentType = ContentType.Application.Json,
                        )
                    }
                }

                // Batch Download: Media Files as ZIP
                get("/api/media/download/zip") {
                    handleCors(call)
                    requireValidSession(call) ?: return@get

                    val idList = call.request.queryParameters["ids"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                    if (idList.isEmpty()) {
                        call.respondText(
                            status = HttpStatusCode.BadRequest,
                            text = ApiResponses.error(ApiErrorCode.INVALID_REQUEST, "No media IDs specified"),
                            contentType = ContentType.Application.Json,
                        )
                        return@get
                    }

                    val allItems = mediaRepository.loadMediaStoreItems().associateBy { it.id }
                    val selectedItems = idList.mapNotNull { allItems[it] }

                    call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"vaultsync_media.zip\"")
                    call.respondOutputStream(
                        contentType = ContentType.parse("application/zip"),
                    ) {
                        ZipOutputStream(this).use { zip ->
                            val usedNames = mutableSetOf<String>()
                            for (item in selectedItems) {
                                if (item.uri == null) continue
                                var entryName = item.displayName
                                var counter = 1
                                while (entryName in usedNames) {
                                    val dot = item.displayName.lastIndexOf('.')
                                    entryName = if (dot > 0) {
                                        "${item.displayName.substring(0, dot)}_$counter.${item.displayName.substring(dot + 1)}"
                                    } else {
                                        "${item.displayName}_$counter"
                                    }
                                    counter++
                                }
                                usedNames.add(entryName)

                                zip.putNextEntry(ZipEntry(entryName))
                                openMediaStream(item.uri)?.use { stream ->
                                    stream.copyTo(zip)
                                }
                                zip.closeEntry()
                            }
                            zip.flush()
                        }
                    }
                }

                // Batch Download: Decrypted Vault Files as ZIP
                get("/api/vault/download/zip") {
                    handleCors(call)
                    requireVaultSession(call) ?: return@get

                    val idList = call.request.queryParameters["ids"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                    if (idList.isEmpty()) {
                        call.respondText(
                            status = HttpStatusCode.BadRequest,
                            text = ApiResponses.error(ApiErrorCode.INVALID_REQUEST, "No vault IDs specified"),
                            contentType = ContentType.Application.Json,
                        )
                        return@get
                    }

                    val entries = idList.mapNotNull { runBlocking { vaultRepository.getEntry(it) } }

                    call.response.header(HttpHeaders.CacheControl, "no-store, no-cache, must-revalidate, private, max-age=0")
                    call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"vaultsync_vault_export.zip\"")

                    vaultSession.withVmkSuspend { vmk ->
                        call.respondOutputStream(
                            contentType = ContentType.parse("application/zip"),
                        ) {
                            ZipOutputStream(this).use { zip ->
                                val usedNames = mutableSetOf<String>()
                                for (entry in entries) {
                                    val blobFile = storageManager.getEncryptedBlobFile(entry.id)
                                    if (!blobFile.exists()) continue

                                    var entryName = entry.originalFileName
                                    var counter = 1
                                    while (entryName in usedNames) {
                                        val dot = entry.originalFileName.lastIndexOf('.')
                                        entryName = if (dot > 0) {
                                            "${entry.originalFileName.substring(0, dot)}_$counter.${entry.originalFileName.substring(dot + 1)}"
                                        } else {
                                            "${entry.originalFileName}_$counter"
                                        }
                                        counter++
                                    }
                                    usedNames.add(entryName)

                                    zip.putNextEntry(ZipEntry(entryName))
                                    VaultFileDecryptor.readRange(blobFile, vmk, 0, entry.originalSizeBytes, zip)
                                    zip.closeEntry()
                                }
                                zip.flush()
                            }
                        }
                    }
                }

                // Vault: Create Folder from PC
                post("/api/vault/folders/create") {
                    handleCors(call)
                    requireVaultSession(call) ?: return@post

                    val body = call.receiveText()
                    val json = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
                    val name = json.optString("name", "").trim()
                    val parentId = json.optString("parentId", "").takeIf { it.isNotBlank() }

                    if (name.isBlank()) {
                        call.respondText(
                            status = HttpStatusCode.BadRequest,
                            text = ApiResponses.error(ApiErrorCode.INVALID_REQUEST, "Folder name is required"),
                            contentType = ContentType.Application.Json,
                        )
                        return@post
                    }

                    try {
                        val folder = runBlocking { vaultRepository.createFolder(name, parentId) }
                        val obj = JSONObject().apply {
                            put("id", folder.id)
                            put("name", folder.name)
                            put("parentId", folder.parentId ?: JSONObject.NULL)
                            put("createdAtMillis", folder.createdAtMillis)
                        }
                        call.respondText(
                            text = ApiResponses.success(obj),
                            contentType = ContentType.Application.Json,
                        )
                    } catch (e: Exception) {
                        call.respondText(
                            status = HttpStatusCode.InternalServerError,
                            text = ApiResponses.error(ApiErrorCode.INTERNAL_ERROR, "Folder creation failed: ${e.message}"),
                            contentType = ContentType.Application.Json,
                        )
                    }
                }

                // Vault: Delete Entry from PC
                post("/api/vault/entries/delete") {
                    handleCors(call)
                    requireVaultSession(call) ?: return@post

                    val body = call.receiveText()
                    val json = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
                    val id = json.optString("id", "").ifBlank { json.optString("entryId", "") }

                    if (id.isBlank()) {
                        call.respondText(
                            status = HttpStatusCode.BadRequest,
                            text = ApiResponses.error(ApiErrorCode.INVALID_REQUEST, "Entry ID is required"),
                            contentType = ContentType.Application.Json,
                        )
                        return@post
                    }

                    try {
                        runBlocking { vaultRepository.deleteEntry(id) }
                        call.respondText(
                            text = ApiResponses.success("Entry deleted"),
                            contentType = ContentType.Application.Json,
                        )
                    } catch (e: Exception) {
                        call.respondText(
                            status = HttpStatusCode.InternalServerError,
                            text = ApiResponses.error(ApiErrorCode.INTERNAL_ERROR, "Delete failed: ${e.message}"),
                            contentType = ContentType.Application.Json,
                        )
                    }
                }

                // Vault: Delete Folder from PC
                post("/api/vault/folders/delete") {
                    handleCors(call)
                    requireVaultSession(call) ?: return@post

                    val body = call.receiveText()
                    val json = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
                    val id = json.optString("id", "").ifBlank { json.optString("folderId", "") }

                    if (id.isBlank()) {
                        call.respondText(
                            status = HttpStatusCode.BadRequest,
                            text = ApiResponses.error(ApiErrorCode.INVALID_REQUEST, "Folder ID is required"),
                            contentType = ContentType.Application.Json,
                        )
                        return@post
                    }

                    try {
                        runBlocking { vaultRepository.deleteFolder(id) }
                        call.respondText(
                            text = ApiResponses.success("Folder deleted"),
                            contentType = ContentType.Application.Json,
                        )
                    } catch (e: Exception) {
                        call.respondText(
                            status = HttpStatusCode.InternalServerError,
                            text = ApiResponses.error(ApiErrorCode.INTERNAL_ERROR, "Delete folder failed: ${e.message}"),
                            contentType = ContentType.Application.Json,
                        )
                    }
                }

                // Vault: Rename Entry from PC
                post("/api/vault/entries/rename") {
                    handleCors(call)
                    requireVaultSession(call) ?: return@post

                    val body = call.receiveText()
                    val json = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
                    val id = json.optString("id", "")
                    val newName = json.optString("newName", "").trim()

                    if (id.isBlank() || newName.isBlank()) {
                        call.respondText(
                            status = HttpStatusCode.BadRequest,
                            text = ApiResponses.error(ApiErrorCode.INVALID_REQUEST, "ID and newName are required"),
                            contentType = ContentType.Application.Json,
                        )
                        return@post
                    }

                    try {
                        runBlocking { vaultRepository.renameEntry(id, newName) }
                        call.respondText(
                            text = ApiResponses.success("Entry renamed"),
                            contentType = ContentType.Application.Json,
                        )
                    } catch (e: Exception) {
                        call.respondText(
                            status = HttpStatusCode.InternalServerError,
                            text = ApiResponses.error(ApiErrorCode.INTERNAL_ERROR, "Rename failed: ${e.message}"),
                            contentType = ContentType.Application.Json,
                        )
                    }
                }
            }
        }
    }

    private suspend fun serveAsset(call: ApplicationCall, assetName: String, contentType: ContentType) {
        // Path traversal protection
        if (assetName.contains("..") || assetName.startsWith("/") || assetName.startsWith("\\")) {
            call.respond(HttpStatusCode.BadRequest)
            return
        }

        call.response.header(HttpHeaders.CacheControl, "no-cache, no-store, must-revalidate")
        call.response.header("Pragma", "no-cache")

        try {
            context.assets.open("web/$assetName").use { input ->
                val bytes = input.readBytes()
                call.respondBytes(bytes, contentType)
            }
        } catch (e: Throwable) {
            // JVM host / test environment fallback: search candidates
            val candidateFiles = listOf(
                File("src/main/assets/web/$assetName"),
                File("app/src/main/assets/web/$assetName"),
                File("../app/src/main/assets/web/$assetName"),
            )
            val assetFile = candidateFiles.firstOrNull { it.exists() && it.isFile }
            if (assetFile != null) {
                call.respondBytes(assetFile.readBytes(), contentType)
                return
            }

            // SPA fallback: return index.html if asset not found
            try {
                context.assets.open("web/index.html").use { input ->
                    val bytes = input.readBytes()
                    call.respondBytes(bytes, ContentType.Text.Html)
                }
            } catch (e2: Throwable) {
                val candidateIndexFiles = listOf(
                    File("src/main/assets/web/index.html"),
                    File("app/src/main/assets/web/index.html"),
                    File("../app/src/main/assets/web/index.html"),
                )
                val indexFile = candidateIndexFiles.firstOrNull { it.exists() && it.isFile }
                if (indexFile != null) {
                    call.respondBytes(indexFile.readBytes(), ContentType.Text.Html)
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }
        }
    }

    private suspend fun requireValidSession(call: ApplicationCall): PcSession? {
        val clientIp = getClientIp(call)
        if (!NetworkUtils.isPrivateSubnetAddress(clientIp)) {
            call.respondText(
                status = HttpStatusCode.Forbidden,
                text = ApiResponses.error(ApiErrorCode.FORBIDDEN, "Access from non-private IP addresses is forbidden"),
                contentType = ContentType.Application.Json,
            )
            return null
        }

        // Validate CSRF if mutating method
        if (call.request.httpMethod.value in listOf("POST", "PUT", "DELETE")) {
            val origin = call.request.header("Origin")
            val referer = call.request.header("Referer")
            val custom = call.request.header("X-VaultSync-Client")
            val host = call.request.header("Host") ?: "localhost"
            if (!authManager.validateOriginAndCsrf(origin, referer, host, custom)) {
                call.respondText(
                    status = HttpStatusCode.Forbidden,
                    text = ApiResponses.error(ApiErrorCode.FORBIDDEN, "CSRF / Origin validation failed"),
                    contentType = ContentType.Application.Json,
                )
                return null
            }
        }

        val token = extractToken(call)
        val session = authManager.validateSession(token, clientIp)
        if (session == null) {
            call.respondText(
                status = HttpStatusCode.Unauthorized,
                text = ApiResponses.error(ApiErrorCode.UNAUTHORIZED, "Authentication token missing or expired"),
                contentType = ContentType.Application.Json,
            )
            return null
        }
        return session
    }

    private suspend fun requireVaultSession(call: ApplicationCall): PcSession? {
        val session = requireValidSession(call) ?: return null
        if (!session.hasVaultAccess || !vaultSession.isUnlocked.value) {
            call.respondText(
                status = HttpStatusCode.Forbidden,
                text = ApiResponses.error(ApiErrorCode.VAULT_LOCKED, "Vault authorization required for this resource"),
                contentType = ContentType.Application.Json,
            )
            return null
        }
        return session
    }

    private fun extractToken(call: ApplicationCall): String? {
        val auth = call.request.header(HttpHeaders.Authorization)
        if (auth != null && auth.startsWith("Bearer ", ignoreCase = true)) {
            return auth.substring(7).trim()
        }
        // Support token query parameter for media tags (<img>, <video>, <audio>, <a> download)
        return call.request.queryParameters["token"]?.trim()
    }

    private fun getClientIp(call: ApplicationCall): String {
        val rawRemote = try {
            val host = call.request.local.remoteHost
            if (!host.isNullOrBlank() && host != "unknown") host else "127.0.0.1"
        } catch (e: Throwable) {
            "127.0.0.1"
        }

        val normalized = NetworkUtils.normalizeIp(rawRemote)

        // Only honor X-Forwarded-For if request originates from loopback (e.g. test harness / local reverse proxy)
        // This prevents remote LAN clients from spoofing their IP to bypass throttling or subnet checks.
        if (normalized == "127.0.0.1" || normalized == "localhost" || normalized == "::1") {
            val forwarded = call.request.header("X-Forwarded-For")?.substringBefore(',')?.trim()
            if (!forwarded.isNullOrBlank()) {
                return NetworkUtils.normalizeIp(forwarded)
            }
        }
        return normalized
    }

    private fun handleCors(call: ApplicationCall) {
        val origin = call.request.header("Origin")
        if (origin != null && NetworkUtils.isPrivateSubnetAddress(origin)) {
            call.response.header("Access-Control-Allow-Origin", origin)
            call.response.header("Access-Control-Allow-Credentials", "true")
        } else if (origin == null) {
            call.response.header("Access-Control-Allow-Origin", "http://localhost:$boundPort")
        } else {
            call.response.header("Access-Control-Allow-Origin", "null")
        }
        call.response.header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        call.response.header("Access-Control-Allow-Headers", "Authorization, Content-Type, X-VaultSync-Client, Range")
        call.response.header("Access-Control-Expose-Headers", "Content-Range, Accept-Ranges, Content-Length, Content-Disposition")
    }

    private fun isPortConflict(t: Throwable?): Boolean {
        var current: Throwable? = t
        while (current != null) {
            if (current is BindException) return true
            val msg = current.message?.lowercase() ?: ""
            if (msg.contains("address already in use") ||
                msg.contains("bind failed") ||
                msg.contains("already in use") ||
                msg.contains("bindexception")
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun openMediaStream(uri: android.net.Uri): InputStream? {
        return try {
            if (uri.scheme == "file") {
                val path = uri.path
                if (!path.isNullOrBlank()) {
                    File(path).takeIf { it.exists() }?.inputStream()
                } else null
            } else {
                context.contentResolver?.openInputStream(uri)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun getOrGenerateMediaThumbnail(item: DeviceMediaItem): ByteArray? {
        val uri = item.uri ?: return null
        val baseCache = runCatching { context.cacheDir }.getOrNull()
            ?: File(System.getProperty("java.io.tmpdir", "."), "pc_cache")
        val cacheDir = File(baseCache, "pc_media_thumbnails").apply { mkdirs() }
        val safeId = item.id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val cacheFile = File(cacheDir, "thumb_$safeId.jpg")
        if (cacheFile.exists() && cacheFile.length() > 0L) {
            return try { cacheFile.readBytes() } catch (_: Exception) { null }
        }

        val bitmap: Bitmap? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver?.loadThumbnail(uri, Size(360, 360), null)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } ?: try {
            val isVideo = item.category == MediaCategory.Videos || (item.mimeType?.startsWith("video/") == true)
            if (isVideo) {
                val retriever = MediaMetadataRetriever()
                try {
                    if (uri.scheme == "content" || uri.scheme == "android.resource") {
                        retriever.setDataSource(context, uri)
                    } else {
                        retriever.setDataSource(uri.path ?: uri.toString())
                    }
                    retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.frameAtTime
                } finally {
                    try { retriever.release() } catch (_: Exception) {}
                }
            } else {
                openMediaStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }
        } catch (_: Exception) {
            null
        }

        if (bitmap != null) {
            try {
                val reqSize = 360
                val scaled = if (bitmap.width > reqSize || bitmap.height > reqSize) {
                    val scale = minOf(reqSize.toFloat() / bitmap.width, reqSize.toFloat() / bitmap.height)
                    val targetW = (bitmap.width * scale).toInt().coerceAtLeast(1)
                    val targetH = (bitmap.height * scale).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
                } else {
                    bitmap
                }

                val baos = java.io.ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                val bytes = baos.toByteArray()
                try {
                    FileOutputStream(cacheFile).use { it.write(bytes) }
                } catch (_: Exception) {}
                if (scaled != bitmap) scaled.recycle()
                bitmap.recycle()
                return bytes
            } catch (_: Exception) {
                return null
            }
        }
        return null
    }

    companion object {
        fun sanitizeFileName(rawName: String?, fallbackPrefix: String = "upload"): String {
            if (rawName.isNullOrBlank()) {
                return "${fallbackPrefix}_${System.currentTimeMillis()}"
            }
            val decoded = try {
                URLDecoder.decode(rawName, StandardCharsets.UTF_8.name())
            } catch (e: Exception) {
                rawName
            }

            // Normalize slashes and take the basename
            val normalized = decoded.replace('\\', '/')
            val base = normalized.substringAfterLast('/')

            // Strip null bytes, control characters, and illegal filename characters
            val cleaned = base.replace(Regex("[\\x00-\\x1F\\x7F:*?\"<>|]"), "").trim('.', ' ')

            return if (cleaned.isBlank() || cleaned == "." || cleaned == "..") {
                "${fallbackPrefix}_${System.currentTimeMillis()}"
            } else {
                cleaned
            }
        }
    }
}
