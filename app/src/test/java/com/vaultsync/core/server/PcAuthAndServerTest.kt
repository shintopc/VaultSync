package com.vaultsync.core.server

import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import com.vaultsync.core.media.DeviceMediaItem
import com.vaultsync.core.media.MediaRepository
import com.vaultsync.core.model.VaultCategory
import com.vaultsync.core.model.VaultEntry
import com.vaultsync.core.model.VaultFolder
import com.vaultsync.core.security.VaultCredentialStore
import com.vaultsync.core.security.VaultFileDecryptor
import com.vaultsync.core.security.VaultFileEncryptor
import com.vaultsync.core.security.VaultKeyManager
import com.vaultsync.core.security.VaultSession
import com.vaultsync.core.storage.VaultStorageManager
import com.vaultsync.core.vault.VaultRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.ConnectException
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Random

class PcAuthAndServerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var credentialStore: VaultCredentialStore
    private lateinit var authManager: PcAuthManager
    private val masterPin = "1234"

    @Before
    fun setup() {
        val prefsMap = mutableMapOf<String, Any>()
        val dummyContext = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                return FakeSharedPreferences(prefsMap)
            }
        }

        credentialStore = VaultCredentialStore(dummyContext)
        val vmk = VaultKeyManager.generateVaultMasterKey()
        val wrapped = VaultKeyManager.wrapVaultMasterKey(vmk, masterPin.toCharArray())
        credentialStore.saveCredentials(wrapped)
        VaultKeyManager.zeroize(vmk)

        authManager = PcAuthManager(credentialStore, sessionTtlMillis = 1000L) // 1 second TTL for unit tests
    }

    // =========================================================================
    // 1. UNIT TESTS: AUTH, SECURITY & MODEL CONTRACTS
    // =========================================================================

    @Test
    fun loginWithCorrectPasscodeReturnsValidSession(): Unit {
        val clientIp = "192.168.1.50"
        val passcode = authManager.currentWebPasscode

        val session = authManager.authenticatePasscode(clientIp, passcode)
        assertNotNull(session)
        assertEquals(clientIp, session!!.clientIp)
        assertEquals(64, session.token.length) // 32 bytes hex = 64 chars (256-bit entropy)
        assertFalse(session.hasVaultAccess)

        val validated = authManager.validateSession(session.token, clientIp)
        assertNotNull(validated)
        assertEquals(session.token, validated!!.token)
    }

    @Test
    fun loginWithWrongPasscodeFailsAndTriggersThrottling(): Unit {
        val clientIp = "192.168.1.100"

        // 4 failed attempts should not lock out yet
        for (i in 1..4) {
            val session = authManager.authenticatePasscode(clientIp, "wrong_code")
            assertNull(session)
            assertEquals(0L, authManager.isThrottled(clientIp))
        }

        // 5th failed attempt triggers lockout
        val session5 = authManager.authenticatePasscode(clientIp, "wrong_code")
        assertNull(session5)
        assertTrue(authManager.isThrottled(clientIp) > 0)

        // Even with correct passcode, authentication is rejected while throttled
        val correctPasscode = authManager.currentWebPasscode
        val lockedAttempt = authManager.authenticatePasscode(clientIp, correctPasscode)
        assertNull(lockedAttempt)
    }

    @Test
    fun sessionValidationEnforcesIpAndTtl(): Unit {
        val clientIp = "192.168.1.60"
        val session = authManager.authenticatePasscode(clientIp, authManager.currentWebPasscode)!!

        // Reject if IP does not match (session hijacking defense)
        val hijackAttempt = authManager.validateSession(session.token, "192.168.1.99")
        assertNull(hijackAttempt)

        // Expire after TTL
        Thread.sleep(1100L)
        val expired = authManager.validateSession(session.token, clientIp)
        assertNull(expired)
    }

    @Test
    fun separateVaultAuthorizationRequiresMasterPin(): Unit {
        val clientIp = "192.168.1.70"
        val session = authManager.authenticatePasscode(clientIp, authManager.currentWebPasscode)!!

        // Initially no vault access
        assertFalse(session.hasVaultAccess)

        // Wrong PIN fails elevation
        val wrongPinSuccess = authManager.elevateSessionToVault(session.token, "9999")
        assertFalse(wrongPinSuccess)
        assertFalse(session.hasVaultAccess)

        // Correct PIN grants elevation (even with whitespace)
        val correctPinSuccess = authManager.elevateSessionToVault(session.token, "  $masterPin  ")
        assertTrue(correctPinSuccess)
        assertTrue(session.hasVaultAccess)

        // Revoke vault access
        authManager.revokeVaultAccess(session.token)
        assertFalse(session.hasVaultAccess)

        // Pairing passcode alone must NOT grant vault elevation even when phone vault is unlocked
        val testVmk = VaultKeyManager.generateVaultMasterKey()
        val fakeSession = VaultSession()
        fakeSession.unlock(testVmk)
        val passcodeSuccess = authManager.elevateSessionToVault(session.token, authManager.currentWebPasscode, fakeSession)
        assertFalse(passcodeSuccess)
        assertFalse(session.hasVaultAccess)
        VaultKeyManager.zeroize(testVmk)
    }

    @Test
    fun privateSubnetValidationAcceptsLanAndRejectsPublicIps(): Unit {
        assertTrue(NetworkUtils.isPrivateSubnetAddress("192.168.1.1"))
        assertTrue(NetworkUtils.isPrivateSubnetAddress("192.168.0.254"))
        assertTrue(NetworkUtils.isPrivateSubnetAddress("10.0.0.1"))
        assertTrue(NetworkUtils.isPrivateSubnetAddress("10.255.0.1"))
        assertTrue(NetworkUtils.isPrivateSubnetAddress("172.16.0.1"))
        assertTrue(NetworkUtils.isPrivateSubnetAddress("172.31.255.255"))
        assertTrue(NetworkUtils.isPrivateSubnetAddress("127.0.0.1"))
        assertTrue(NetworkUtils.isPrivateSubnetAddress("localhost"))
        assertTrue(NetworkUtils.isPrivateSubnetAddress("::1"))
        assertTrue(NetworkUtils.isPrivateSubnetAddress("fe80::1ff:fe23:4567:890a"))

        // URLs with schemes and ports
        assertTrue(NetworkUtils.isPrivateSubnetAddress("http://192.168.1.100:8080"))
        assertTrue(NetworkUtils.isPrivateSubnetAddress("http://localhost:8080"))

        // IPv4-mapped IPv6 from dual-stack sockets
        assertTrue(NetworkUtils.isPrivateSubnetAddress("::ffff:192.168.1.100"))
        assertTrue(NetworkUtils.isPrivateSubnetAddress("[::ffff:192.168.1.100]:8080"))
        assertTrue(NetworkUtils.isPrivateSubnetAddress("::FFFF:10.0.0.1"))

        // Mobile Hotspot & Link-Local ranges
        assertTrue(NetworkUtils.isPrivateSubnetAddress("100.64.1.5"))
        assertTrue(NetworkUtils.isPrivateSubnetAddress("169.254.10.20"))
        assertTrue(NetworkUtils.isPrivateSubnetAddress("fe80::89c5%17"))
        assertTrue(NetworkUtils.isPrivateSubnetAddress("[fe80::89c5%wlan0]:8080"))

        // Public WAN IPs rejected
        assertFalse(NetworkUtils.isPrivateSubnetAddress("8.8.8.8"))
        assertFalse(NetworkUtils.isPrivateSubnetAddress("1.1.1.1"))
        assertFalse(NetworkUtils.isPrivateSubnetAddress("172.32.0.1"))
        assertFalse(NetworkUtils.isPrivateSubnetAddress("203.0.113.5"))
        assertFalse(NetworkUtils.isPrivateSubnetAddress("::ffff:203.0.113.5"))
        assertFalse(NetworkUtils.isPrivateSubnetAddress("http://evil-tracker.com:8080"))
    }

    @Test
    fun originAndCsrfProtectionValidation(): Unit {
        val serverHost = "192.168.1.100:8080"

        // Custom client header allowed
        assertTrue(authManager.validateOriginAndCsrf(null, null, serverHost, "web"))

        // Matching Origin allowed
        assertTrue(authManager.validateOriginAndCsrf("http://192.168.1.100:8080", null, serverHost, null))

        // Localhost Origin allowed
        assertTrue(authManager.validateOriginAndCsrf("http://localhost:8080", null, serverHost, null))

        // Foreign origin without custom header rejected (CSRF blocked)
        assertFalse(authManager.validateOriginAndCsrf("http://evil-tracker.com", null, serverHost, null))
        assertFalse(authManager.validateOriginAndCsrf(null, "http://malicious-site.org/attack", serverHost, null))
    }

    @Test
    fun structuredErrorResponsesFollowStandardContract(): Unit {
        val errorString = ApiResponses.error(ApiErrorCode.VAULT_LOCKED, "Master PIN required")
        assertTrue(errorString.contains("\"success\":false") || errorString.contains("\"success\": false"))
        assertTrue(errorString.contains("VAULT_LOCKED"))
        assertTrue(errorString.contains("Master PIN required"))

        val successString = ApiResponses.success("all good")
        assertTrue(successString.contains("\"success\":true") || successString.contains("\"success\": true"))
        assertTrue(successString.contains("all good"))
    }

    @Test
    fun regeneratePasscodeInvalidatesOldSessions(): Unit {
        val clientIp = "192.168.1.80"
        val session = authManager.authenticatePasscode(clientIp, authManager.currentWebPasscode)!!
        assertNotNull(authManager.validateSession(session.token, clientIp))

        val newCode = authManager.regeneratePasscode()
        assertNotNull(newCode)

        // Prior token is invalidated
        assertNull(authManager.validateSession(session.token, clientIp))
    }

    @Test
    fun readRangeAccuratelyDecryptsSlicesAcrossChunkBoundaries(): Unit {
        val dataSize = 150 * 1024
        val originalBytes = ByteArray(dataSize)
        Random(42L).nextBytes(originalBytes)

        val plainFile = tempFolder.newFile("plain.dat")
        plainFile.writeBytes(originalBytes)
        val encryptedFile = tempFolder.newFile("vault_blob.enc")

        val vmk = VaultKeyManager.generateVaultMasterKey()
        try {
            VaultFileEncryptor.encryptFile(
                inputFile = plainFile,
                destinationEncryptedFile = encryptedFile,
                vmk = vmk,
            )

            // 1. Read slice in chunk 0 (bytes 1000..1999)
            val out1 = ByteArrayOutputStream()
            val delivered1 = VaultFileDecryptor.readRange(encryptedFile, vmk, 1000L, 1000L, out1)
            assertEquals(1000L, delivered1)
            val expected1 = originalBytes.copyOfRange(1000, 2000)
            assertTrue(expected1.contentEquals(out1.toByteArray()))

            // 2. Read slice crossing chunk boundary (offset 65000, length 5000)
            val out2 = ByteArrayOutputStream()
            val delivered2 = VaultFileDecryptor.readRange(encryptedFile, vmk, 65000L, 5000L, out2)
            assertEquals(5000L, delivered2)
            val expected2 = originalBytes.copyOfRange(65000, 70000)
            assertTrue(expected2.contentEquals(out2.toByteArray()))

            // 3. Read slice in last chunk
            val out3 = ByteArrayOutputStream()
            val delivered3 = VaultFileDecryptor.readRange(encryptedFile, vmk, 140000L, 10000L, out3)
            assertEquals(10000L, delivered3)
            val expected3 = originalBytes.copyOfRange(140000, 150000)
            assertTrue(expected3.contentEquals(out3.toByteArray()))

            // 4. Read entire file via readRange
            val outFull = ByteArrayOutputStream()
            val deliveredFull = VaultFileDecryptor.readRange(encryptedFile, vmk, 0L, dataSize.toLong(), outFull)
            assertEquals(dataSize.toLong(), deliveredFull)
            assertTrue(originalBytes.contentEquals(outFull.toByteArray()))

            // 5. Read out of range returns 0 bytes
            val outOob = ByteArrayOutputStream()
            val deliveredOob = VaultFileDecryptor.readRange(encryptedFile, vmk, (dataSize + 500).toLong(), 1000L, outOob)
            assertEquals(0L, deliveredOob)
        } finally {
            VaultKeyManager.zeroize(vmk)
        }
    }

    @Test
    fun pathTraversalPatternsAreRejected(): Unit {
        fun isPathSafe(assetName: String): Boolean {
            return !assetName.contains("..") && !assetName.startsWith("/") && !assetName.startsWith("\\")
        }

        assertFalse(isPathSafe("../secret.txt"))
        assertFalse(isPathSafe("web/../../etc/passwd"))
        assertFalse(isPathSafe("/root/file.txt"))
        assertFalse(isPathSafe("\\windows\\system32"))
        assertFalse(isPathSafe("..\\..\\windows\\system32"))

        assertTrue(isPathSafe("index.html"))
        assertTrue(isPathSafe("style.css"))
        assertTrue(isPathSafe("app.js"))
    }

    @Test
    fun sanitizeFileNameStripsDangerousCharacters(): Unit {
        assertEquals("passwd", PcServer.sanitizeFileName("../../../../etc/passwd"))
        assertEquals("calc.exe", PcServer.sanitizeFileName("..\\..\\Windows\\System32\\calc.exe"))
        assertEquals("foobar.png", PcServer.sanitizeFileName("foo\u0000bar.png"))
        assertEquals("illegalcolonsandchars.txt", PcServer.sanitizeFileName("illegal:colons*and?chars<>.txt"))
        assertEquals("my photo.jpg", PcServer.sanitizeFileName("my%20photo.jpg"))
        assertEquals("my photo.jpg", PcServer.sanitizeFileName("my photo.jpg"))

        // Blank, dot-only, or traversal-only names must safely fall back
        assertTrue(PcServer.sanitizeFileName("").startsWith("upload_"))
        assertTrue(PcServer.sanitizeFileName(null).startsWith("upload_"))
        assertTrue(PcServer.sanitizeFileName("...").startsWith("upload_"))
        assertTrue(PcServer.sanitizeFileName("../../").startsWith("upload_"))
    }

    // =========================================================================
    // 2. HTTP INTEGRATION TESTS: EMBEDDED SERVER & API CONTRACT
    // =========================================================================

    private fun findFreePort(): Int {
        ServerSocket(0).use { socket ->
            return socket.localPort
        }
    }

    @Test
    fun httpIntegrationCompleteServerLifecycleAndApiContract(): Unit {
        val testPort = findFreePort()
        val filesDirRoot = tempFolder.newFolder("files_dir")
        val prefsMap = mutableMapOf<String, Any>()
        val dummyContext = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                return FakeSharedPreferences(prefsMap)
            }
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir(): File = filesDirRoot
            override fun getExternalFilesDir(type: String?): File = filesDirRoot
        }

        val testCredStore = VaultCredentialStore(dummyContext)
        val vmk = VaultKeyManager.generateVaultMasterKey()
        val wrapped = VaultKeyManager.wrapVaultMasterKey(vmk, masterPin.toCharArray())
        testCredStore.saveCredentials(wrapped)

        val serverAuthManager = PcAuthManager(testCredStore, sessionTtlMillis = 30 * 60 * 1000L)
        val vaultSession = VaultSession()

        // Prepare sample vault storage and test encrypted file
        val storageRoot = tempFolder.newFolder("vault_storage")
        val storageManager = VaultStorageManager(storageRoot)
        storageManager.initDirectories()

        val sampleContent = "Confidential VaultSync Record Payload 1234567890".toByteArray(Charsets.UTF_8)
        val plainFile = tempFolder.newFile("sample.txt")
        plainFile.writeBytes(sampleContent)

        val entryId = "vault-item-uuid-1"
        val blobFile = storageManager.getEncryptedBlobFile(entryId)
        VaultFileEncryptor.encryptFile(plainFile, blobFile, vmk)

        val testFolder = VaultFolder(
            id = "folder-1",
            name = "Documents",
            parentId = null,
            createdAtMillis = 1000L,
            updatedAtMillis = 1000L,
        )
        val testEntry = VaultEntry(
            id = entryId,
            folderId = "folder-1",
            originalFileName = "sample.txt",
            category = VaultCategory.Documents,
            mimeType = "text/plain",
            originalSizeBytes = sampleContent.size.toLong(),
            encryptedSizeBytes = blobFile.length(),
            createdAtMillis = 1000L,
            dateModifiedMillis = 1000L,
        )

        val fakeVaultRepo = FakeVaultRepository(
            folders = listOf(testFolder),
            entries = listOf(testEntry),
        )

        val fakeMediaRepo = FakeMediaRepository(
            items = listOf(
                DeviceMediaItem(
                    id = "media-1",
                    uri = Uri.parse("content://media/external/images/media/1"),
                    displayName = "photo.jpg",
                    category = com.vaultsync.core.media.MediaCategory.Images,
                    mimeType = "image/jpeg",
                    folderName = "Camera",
                    folderKey = "Camera",
                    sizeBytes = 2048L,
                    dateModifiedMillis = 2000L,
                    source = com.vaultsync.core.media.MediaSource.MediaStore,
                )
            )
        )

        val server = PcServer(
            context = dummyContext,
            authManager = serverAuthManager,
            mediaRepository = fakeMediaRepo,
            vaultRepository = fakeVaultRepo,
            vaultSession = vaultSession,
            storageManager = storageManager,
            defaultPort = testPort,
        )

        val started = server.start()
        assertTrue("Server should start successfully", started)
        assertTrue(server.isRunning)
        assertEquals(testPort, server.boundPort)

        val httpClient = HttpClient.newHttpClient()
        val baseUrl = "http://127.0.0.1:$testPort"

        try {
            // -------------------------------------------------------------
            // STEP 1: Server Status API (Public LAN)
            // -------------------------------------------------------------
            val statusReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/status"))
                .GET()
                .build()
            val statusRes = httpClient.send(statusReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, statusRes.statusCode())
            val statusJson = JSONObject(statusRes.body())
            assertTrue(statusJson.getBoolean("success"))
            val statusData = statusJson.getJSONObject("data")
            assertTrue(statusData.getBoolean("isRunning"))
            assertEquals(testPort, statusData.getInt("port"))
            assertEquals(0, statusData.getInt("activeSessionsCount"))
            assertFalse(statusData.getBoolean("isVaultUnlockedOnPhone"))

            // -------------------------------------------------------------
            // STEP 1b: Bundled Web App Static Assets Delivery
            // -------------------------------------------------------------
            val indexReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/"))
                .GET()
                .build()
            val indexRes = httpClient.send(indexReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, indexRes.statusCode())
            assertTrue(indexRes.body().contains("VaultSync — PC Access"))
            assertTrue(indexRes.headers().firstValue("Content-Type").orElse("").contains("text/html"))

            val cssReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/style.css"))
                .GET()
                .build()
            val cssRes = httpClient.send(cssReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, cssRes.statusCode())
            assertTrue(cssRes.body().contains(":root"))

            val jsReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/app.js"))
                .GET()
                .build()
            val jsRes = httpClient.send(jsReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, jsRes.statusCode())
            assertTrue(jsRes.body().contains("VaultSync PC Client JavaScript"))

            // -------------------------------------------------------------
            // STEP 2: CSRF & Origin Validation on Login
            // -------------------------------------------------------------
            // Foreign untrusted origin without custom header -> 403 Forbidden
            val csrfReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/auth/login"))
                .header("Origin", "http://malicious-website.com")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSONObject().put("passcode", serverAuthManager.currentWebPasscode).toString()))
                .build()
            val csrfRes = httpClient.send(csrfReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(403, csrfRes.statusCode())
            val csrfJson = JSONObject(csrfRes.body())
            assertFalse(csrfJson.getBoolean("success"))
            assertEquals("FORBIDDEN", csrfJson.getJSONObject("error").getString("code"))

            // Public WAN IP rejection -> 403 Forbidden
            val wanReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/auth/login"))
                .header("X-Forwarded-For", "203.0.113.195")
                .header("X-VaultSync-Client", "web")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSONObject().put("passcode", serverAuthManager.currentWebPasscode).toString()))
                .build()
            val wanRes = httpClient.send(wanReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(403, wanRes.statusCode())
            val wanJson = JSONObject(wanRes.body())
            assertFalse(wanJson.getBoolean("success"))
            assertEquals("FORBIDDEN", wanJson.getJSONObject("error").getString("code"))

            // -------------------------------------------------------------
            // STEP 3: Wrong Passcode & Login Throttling
            // -------------------------------------------------------------
            // First 4 failed attempts from IP 192.168.1.200 -> 401 Unauthorized
            val testClientIp = "192.168.1.200"
            for (attempt in 1..4) {
                val badReq = HttpRequest.newBuilder()
                    .uri(URI.create("$baseUrl/api/auth/login"))
                    .header("X-Forwarded-For", testClientIp)
                    .header("X-VaultSync-Client", "web")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSONObject().put("passcode", "wrong_code").toString()))
                    .build()
                val badRes = httpClient.send(badReq, HttpResponse.BodyHandlers.ofString())
                assertEquals(401, badRes.statusCode())
                val badJson = JSONObject(badRes.body())
                assertFalse(badJson.getBoolean("success"))
                assertEquals("UNAUTHORIZED", badJson.getJSONObject("error").getString("code"))
            }

            // 5th failed attempt triggers 429 Too Many Requests
            val fifthReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/auth/login"))
                .header("X-Forwarded-For", testClientIp)
                .header("X-VaultSync-Client", "web")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSONObject().put("passcode", "wrong_code").toString()))
                .build()
            val fifthRes = httpClient.send(fifthReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(429, fifthRes.statusCode())
            val fifthJson = JSONObject(fifthRes.body())
            assertFalse(fifthJson.getBoolean("success"))
            assertEquals("THROTTLED", fifthJson.getJSONObject("error").getString("code"))

            // -------------------------------------------------------------
            // STEP 4: Successful Login & Session Creation
            // -------------------------------------------------------------
            val validClientIp = "192.168.1.55"
            val loginReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/auth/login"))
                .header("X-Forwarded-For", validClientIp)
                .header("X-VaultSync-Client", "web")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSONObject().put("passcode", serverAuthManager.currentWebPasscode).toString()))
                .build()
            val loginRes = httpClient.send(loginReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, loginRes.statusCode())
            val loginJson = JSONObject(loginRes.body())
            assertTrue(loginJson.getBoolean("success"))
            val sessionToken = loginJson.getJSONObject("data").getString("token")
            assertFalse(loginJson.getJSONObject("data").getBoolean("hasVaultAccess"))
            assertEquals(64, sessionToken.length)

            // -------------------------------------------------------------
            // STEP 5: Verify Session & Media Access (Scoped Normal Media)
            // -------------------------------------------------------------
            val sessionReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/auth/session"))
                .header("X-Forwarded-For", validClientIp)
                .header("Authorization", "Bearer $sessionToken")
                .GET()
                .build()
            val sessionCheckRes = httpClient.send(sessionReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, sessionCheckRes.statusCode())
            val sessionCheckJson = JSONObject(sessionCheckRes.body())
            assertTrue(sessionCheckJson.getJSONObject("data").getBoolean("active"))
            assertFalse(sessionCheckJson.getJSONObject("data").getBoolean("hasVaultAccess"))

            // Media list accessible with session token
            val mediaReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/media/list"))
                .header("X-Forwarded-For", validClientIp)
                .header("Authorization", "Bearer $sessionToken")
                .GET()
                .build()
            val mediaRes = httpClient.send(mediaReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, mediaRes.statusCode())
            val mediaJson = JSONObject(mediaRes.body())
            assertTrue(mediaJson.getBoolean("success"))
            val mediaArray = mediaJson.getJSONArray("data")
            assertEquals(1, mediaArray.length())
            assertEquals("photo.jpg", mediaArray.getJSONObject(0).getString("displayName"))

            // -------------------------------------------------------------
            // STEP 6: Vault Endpoints Strictly Protected (VAULT_LOCKED)
            // -------------------------------------------------------------
            // Accessing vault folders before elevation returns 403 VAULT_LOCKED
            val lockedFoldersReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/vault/folders"))
                .header("X-Forwarded-For", validClientIp)
                .header("Authorization", "Bearer $sessionToken")
                .GET()
                .build()
            val lockedFoldersRes = httpClient.send(lockedFoldersReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(403, lockedFoldersRes.statusCode())
            val lockedFoldersJson = JSONObject(lockedFoldersRes.body())
            assertFalse(lockedFoldersJson.getBoolean("success"))
            assertEquals("VAULT_LOCKED", lockedFoldersJson.getJSONObject("error").getString("code"))

            // Accessing encrypted file before elevation returns 403 VAULT_LOCKED
            val lockedFileReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/vault/file/$entryId"))
                .header("X-Forwarded-For", validClientIp)
                .header("Authorization", "Bearer $sessionToken")
                .GET()
                .build()
            val lockedFileRes = httpClient.send(lockedFileReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(403, lockedFileRes.statusCode())

            // -------------------------------------------------------------
            // STEP 6B: Malicious External Cross-Origin Requests Rejected
            // -------------------------------------------------------------
            val evilCorsReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/media/list"))
                .header("X-Forwarded-For", validClientIp)
                .header("Origin", "https://evil-attacker-site.com")
                .header("Authorization", "Bearer $sessionToken")
                .GET()
                .build()
            val evilCorsRes = httpClient.send(evilCorsReq, HttpResponse.BodyHandlers.ofString())
            assertEquals("null", evilCorsRes.headers().firstValue("Access-Control-Allow-Origin").orElse(""))
            assertFalse(evilCorsRes.headers().firstValue("Access-Control-Allow-Credentials").isPresent)

            // -------------------------------------------------------------
            // STEP 7: Vault Elevation via Master PIN
            // -------------------------------------------------------------
            // Wrong PIN returns 401 Unauthorized
            val wrongPinReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/vault/unlock"))
                .header("X-Forwarded-For", validClientIp)
                .header("X-VaultSync-Client", "web")
                .header("Authorization", "Bearer $sessionToken")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSONObject().put("pin", "9999").toString()))
                .build()
            val wrongPinRes = httpClient.send(wrongPinReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(401, wrongPinRes.statusCode())
            assertEquals("UNAUTHORIZED", JSONObject(wrongPinRes.body()).getJSONObject("error").getString("code"))

            // Correct PIN elevates session and unlocks phone vault session
            val correctPinReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/vault/unlock"))
                .header("X-Forwarded-For", validClientIp)
                .header("X-VaultSync-Client", "web")
                .header("Authorization", "Bearer $sessionToken")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSONObject().put("pin", masterPin).toString()))
                .build()
            val correctPinRes = httpClient.send(correctPinReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, correctPinRes.statusCode())
            assertTrue(JSONObject(correctPinRes.body()).getBoolean("success"))
            assertTrue(vaultSession.isUnlocked.value)

            // -------------------------------------------------------------
            // STEP 8: Elevated Access to Vault Folders & Entries
            // -------------------------------------------------------------
            val unlockedFoldersReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/vault/folders"))
                .header("X-Forwarded-For", validClientIp)
                .header("Authorization", "Bearer $sessionToken")
                .GET()
                .build()
            val unlockedFoldersRes = httpClient.send(unlockedFoldersReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, unlockedFoldersRes.statusCode())
            val foldersArray = JSONObject(unlockedFoldersRes.body()).getJSONArray("data")
            assertEquals(1, foldersArray.length())
            assertEquals("Documents", foldersArray.getJSONObject(0).getString("name"))

            val unlockedEntriesReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/vault/entries"))
                .header("X-Forwarded-For", validClientIp)
                .header("Authorization", "Bearer $sessionToken")
                .GET()
                .build()
            val unlockedEntriesRes = httpClient.send(unlockedEntriesReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, unlockedEntriesRes.statusCode())
            val entriesArray = JSONObject(unlockedEntriesRes.body()).getJSONArray("data")
            assertEquals(1, entriesArray.length())
            assertEquals("sample.txt", entriesArray.getJSONObject(0).getString("fileName"))

            // -------------------------------------------------------------
            // STEP 9: Dynamic Stream Decryption (No Static URLs) & Range Seeking
            // -------------------------------------------------------------
            // 9a. Full file stream decryption
            val fullStreamReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/vault/file/$entryId"))
                .header("X-Forwarded-For", validClientIp)
                .header("Authorization", "Bearer $sessionToken")
                .GET()
                .build()
            val fullStreamRes = httpClient.send(fullStreamReq, HttpResponse.BodyHandlers.ofByteArray())
            assertEquals(200, fullStreamRes.statusCode())
            assertEquals(sampleContent.size.toLong(), fullStreamRes.body().size.toLong())
            assertTrue(sampleContent.contentEquals(fullStreamRes.body()))

            // Verify security headers preventing caching of private vault media
            val cacheControl = fullStreamRes.headers().firstValue("Cache-Control").orElse("")
            assertTrue(cacheControl.contains("no-store"))
            val contentDisposition = fullStreamRes.headers().firstValue("Content-Disposition").orElse("")
            assertTrue(contentDisposition.contains("sample.txt"))

            // 9b. Range seek stream decryption (HTTP 206 Partial Content)
            val rangeStart = 10
            val rangeEnd = 30
            val rangeReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/vault/file/$entryId"))
                .header("X-Forwarded-For", validClientIp)
                .header("Authorization", "Bearer $sessionToken")
                .header("Range", "bytes=$rangeStart-$rangeEnd")
                .GET()
                .build()
            val rangeRes = httpClient.send(rangeReq, HttpResponse.BodyHandlers.ofByteArray())
            assertEquals(206, rangeRes.statusCode())
            val expectedSlice = sampleContent.copyOfRange(rangeStart, rangeEnd + 1)
            assertEquals(expectedSlice.size, rangeRes.body().size)
            assertTrue(expectedSlice.contentEquals(rangeRes.body()))
            val contentRangeHeader = rangeRes.headers().firstValue("Content-Range").orElse("")
            assertEquals("bytes $rangeStart-$rangeEnd/${sampleContent.size}", contentRangeHeader)

            // 9b-2. Out-of-bounds range request returns 416 Range Not Satisfiable
            val oobRangeReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/vault/file/$entryId"))
                .header("X-Forwarded-For", validClientIp)
                .header("Authorization", "Bearer $sessionToken")
                .header("Range", "bytes=999999-1000000")
                .GET()
                .build()
            val oobRangeRes = httpClient.send(oobRangeReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(416, oobRangeRes.statusCode())

            // 9b-3. Thumbnail endpoints verification (Vault and Media thumbnails)
            val vaultThumbReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/vault/thumbnail/$entryId?token=$sessionToken"))
                .header("X-Forwarded-For", validClientIp)
                .GET()
                .build()
            val vaultThumbRes = httpClient.send(vaultThumbReq, HttpResponse.BodyHandlers.ofString())
            // Non-image/video entry returns 404, but auth passes
            assertEquals(404, vaultThumbRes.statusCode())

            val unauthThumbReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/vault/thumbnail/$entryId"))
                .header("X-Forwarded-For", validClientIp)
                .GET()
                .build()
            val unauthThumbRes = httpClient.send(unauthThumbReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(401, unauthThumbRes.statusCode())

            val mediaThumbReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/media/thumbnail/photo-1?token=$sessionToken"))
                .header("X-Forwarded-For", validClientIp)
                .GET()
                .build()
            val mediaThumbRes = httpClient.send(mediaThumbReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(404, mediaThumbRes.statusCode())

            // -------------------------------------------------------------
            // STEP 9c: Upload File to Device Media
            // -------------------------------------------------------------
            val uploadMediaPayload = "Media file upload content 12345".toByteArray(Charsets.UTF_8)
            val uploadMediaReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/upload/media?filename=uploaded_pic.png"))
                .header("X-Forwarded-For", validClientIp)
                .header("X-VaultSync-Client", "web")
                .header("Authorization", "Bearer $sessionToken")
                .header("Content-Type", "image/png")
                .POST(HttpRequest.BodyPublishers.ofByteArray(uploadMediaPayload))
                .build()
            val uploadMediaRes = httpClient.send(uploadMediaReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, uploadMediaRes.statusCode())
            val uploadMediaJson = JSONObject(uploadMediaRes.body())
            assertTrue(uploadMediaJson.getBoolean("success"))
            assertEquals("uploaded_pic.png", uploadMediaJson.getJSONObject("data").getString("fileName"))

            // Verify the uploaded media file was saved to destination directory
            val savedMediaFile = File(File(filesDirRoot, "uploads"), "uploaded_pic.png")
            assertTrue("Uploaded media file should exist on disk", savedMediaFile.exists())
            assertEquals(uploadMediaPayload.size.toLong(), savedMediaFile.length())

            // 9c-2. Upload with path traversal sequence in filename must be safely sanitized and contained inside uploads
            val traversalPayload = "traversal content".toByteArray(Charsets.UTF_8)
            val traversalReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/upload/media?filename=../../../../traversal_test.png"))
                .header("X-Forwarded-For", validClientIp)
                .header("X-VaultSync-Client", "web")
                .header("Authorization", "Bearer $sessionToken")
                .header("Content-Type", "image/png")
                .POST(HttpRequest.BodyPublishers.ofByteArray(traversalPayload))
                .build()
            val traversalRes = httpClient.send(traversalReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, traversalRes.statusCode())
            val traversalJson = JSONObject(traversalRes.body())
            assertEquals("traversal_test.png", traversalJson.getJSONObject("data").getString("fileName"))
            val savedTraversalFile = File(File(filesDirRoot, "uploads"), "traversal_test.png")
            assertTrue("File must be saved inside uploads directory without escaping", savedTraversalFile.exists())

            // -------------------------------------------------------------
            // STEP 9d: Create Vault Folder
            // -------------------------------------------------------------
            val createFolderReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/vault/folders/create"))
                .header("X-Forwarded-For", validClientIp)
                .header("X-VaultSync-Client", "web")
                .header("Authorization", "Bearer $sessionToken")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSONObject().put("name", "Secret Work").toString()))
                .build()
            val createFolderRes = httpClient.send(createFolderReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, createFolderRes.statusCode())
            val createdFolderJson = JSONObject(createFolderRes.body()).getJSONObject("data")
            val newFolderId = createdFolderJson.getString("id")
            assertEquals("Secret Work", createdFolderJson.getString("name"))

            // -------------------------------------------------------------
            // STEP 9e: Upload Directly to Encrypted Vault
            // -------------------------------------------------------------
            val vaultUploadPayload = "Direct vault upload encrypted content 98765".toByteArray(Charsets.UTF_8)
            val uploadVaultReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/upload/vault?filename=confidential.pdf&folderId=$newFolderId"))
                .header("X-Forwarded-For", validClientIp)
                .header("X-VaultSync-Client", "web")
                .header("Authorization", "Bearer $sessionToken")
                .header("Content-Type", "application/pdf")
                .POST(HttpRequest.BodyPublishers.ofByteArray(vaultUploadPayload))
                .build()
            val uploadVaultRes = httpClient.send(uploadVaultReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, uploadVaultRes.statusCode())
            val uploadVaultJson = JSONObject(uploadVaultRes.body())
            assertTrue(uploadVaultJson.getBoolean("success"))
            val uploadedVaultEntry = uploadVaultJson.getJSONObject("data")
            val newEntryId = uploadedVaultEntry.getString("id")
            assertEquals("confidential.pdf", uploadedVaultEntry.getString("fileName"))
            assertEquals(newFolderId, uploadedVaultEntry.getString("folderId"))

            // -------------------------------------------------------------
            // STEP 9f: Download Vault Entries as ZIP Stream
            // -------------------------------------------------------------
            val vaultZipReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/vault/download/zip?ids=$entryId,$newEntryId"))
                .header("X-Forwarded-For", validClientIp)
                .header("Authorization", "Bearer $sessionToken")
                .GET()
                .build()
            val vaultZipRes = httpClient.send(vaultZipReq, HttpResponse.BodyHandlers.ofByteArray())
            assertEquals(200, vaultZipRes.statusCode())
            assertTrue(vaultZipRes.headers().firstValue("Content-Type").orElse("").contains("application/zip"))
            val zipBytes = vaultZipRes.body()
            assertTrue("ZIP output should be non-empty", zipBytes.isNotEmpty())
            // Verify ZIP header magic 0x50, 0x4B, 0x03, 0x04
            assertEquals(0x50.toByte(), zipBytes[0])
            assertEquals(0x4B.toByte(), zipBytes[1])

            // -------------------------------------------------------------
            // STEP 9g: Delete Vault Entry
            // -------------------------------------------------------------
            val deleteEntryReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/vault/entries/delete"))
                .header("X-Forwarded-For", validClientIp)
                .header("X-VaultSync-Client", "web")
                .header("Authorization", "Bearer $sessionToken")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSONObject().put("entryId", newEntryId).toString()))
                .build()
            val deleteEntryRes = httpClient.send(deleteEntryReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, deleteEntryRes.statusCode())
            assertTrue(JSONObject(deleteEntryRes.body()).getBoolean("success"))

            // -------------------------------------------------------------
            // STEP 9h: Delete Vault Folder
            // -------------------------------------------------------------
            val deleteFolderReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/vault/folders/delete"))
                .header("X-Forwarded-For", validClientIp)
                .header("X-VaultSync-Client", "web")
                .header("Authorization", "Bearer $sessionToken")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSONObject().put("folderId", newFolderId).toString()))
                .build()
            val deleteFolderRes = httpClient.send(deleteFolderReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, deleteFolderRes.statusCode())
            assertTrue(JSONObject(deleteFolderRes.body()).getBoolean("success"))

            // -------------------------------------------------------------
            // STEP 10: Revoking Vault Access (Lock) while Preserving Media Session
            // -------------------------------------------------------------
            val lockReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/vault/lock"))
                .header("X-Forwarded-For", validClientIp)
                .header("X-VaultSync-Client", "web")
                .header("Authorization", "Bearer $sessionToken")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build()
            val lockRes = httpClient.send(lockReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, lockRes.statusCode())

            // Vault endpoints immediately return 403 VAULT_LOCKED again
            val relockedReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/vault/folders"))
                .header("X-Forwarded-For", validClientIp)
                .header("Authorization", "Bearer $sessionToken")
                .GET()
                .build()
            assertEquals(403, httpClient.send(relockedReq, HttpResponse.BodyHandlers.ofString()).statusCode())

            // Media list still accessible
            val mediaStillReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/media/list"))
                .header("X-Forwarded-For", validClientIp)
                .header("Authorization", "Bearer $sessionToken")
                .GET()
                .build()
            assertEquals(200, httpClient.send(mediaStillReq, HttpResponse.BodyHandlers.ofString()).statusCode())

            // -------------------------------------------------------------
            // STEP 11: Logout API
            // -------------------------------------------------------------
            val logoutReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/auth/logout"))
                .header("X-Forwarded-For", validClientIp)
                .header("X-VaultSync-Client", "web")
                .header("Authorization", "Bearer $sessionToken")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build()
            val logoutRes = httpClient.send(logoutReq, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, logoutRes.statusCode())

            // Session check now fails with 401 Unauthorized
            val expiredSessionReq = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/auth/session"))
                .header("X-Forwarded-For", validClientIp)
                .header("Authorization", "Bearer $sessionToken")
                .GET()
                .build()
            assertEquals(401, httpClient.send(expiredSessionReq, HttpResponse.BodyHandlers.ofString()).statusCode())

        } finally {
            // -------------------------------------------------------------
            // STEP 12: Stop Server & Verify Request Rejection
            // -------------------------------------------------------------
            server.stop()
            assertFalse(server.isRunning)
            assertEquals(ServerState.STOPPED, server.state)
            assertEquals(0, serverAuthManager.getActiveSessionCount())

            // Requests after stop must fail with connection refusal
            try {
                val stoppedReq = HttpRequest.newBuilder()
                    .uri(URI.create("$baseUrl/api/status"))
                    .GET()
                    .build()
                httpClient.send(stoppedReq, HttpResponse.BodyHandlers.ofString())
                fail("Expected ConnectException since server was stopped")
            } catch (expected: Exception) {
                // Expected: connection refused
            }

            VaultKeyManager.zeroize(vmk)
        }
    }

    @Test
    fun httpIntegrationPortConflictAutomaticallyBindsNextAvailablePort(): Unit {
        val basePort = findFreePort()

        // Bind a dummy server socket on basePort to simulate port conflict
        ServerSocket(basePort).use { conflictSocket ->
            val dummyContext = object : ContextWrapper(null) {
                override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                    return FakeSharedPreferences(mutableMapOf())
                }
                override fun getApplicationContext(): android.content.Context = this
            }
            val testCredStore = VaultCredentialStore(dummyContext)
            val serverAuthManager = PcAuthManager(testCredStore)
            val vaultSession = VaultSession()
            val storageManager = VaultStorageManager(tempFolder.newFolder("vault_conflict"))

            val server = PcServer(
                context = dummyContext,
                authManager = serverAuthManager,
                mediaRepository = FakeMediaRepository(),
                vaultRepository = FakeVaultRepository(),
                vaultSession = vaultSession,
                storageManager = storageManager,
                defaultPort = basePort,
            )

            try {
                val started = server.start()
                assertTrue("Server should start on alternative port", started)
                assertTrue(server.isRunning)
                // Server should skip basePort and bind to basePort + 1
                assertEquals(basePort + 1, server.boundPort)

                // Verify the server is live and responsive on basePort + 1
                val client = HttpClient.newHttpClient()
                val req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:${server.boundPort}/api/status"))
                    .GET()
                    .build()
                val res = client.send(req, HttpResponse.BodyHandlers.ofString())
                assertEquals(200, res.statusCode())
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun testPcAccessStateAndServiceConstants() {
        val state = PcAccessState(
            serverState = ServerState.RUNNING,
            isWakeLockActive = true,
        )
        assertTrue(state.isWakeLockActive)
        assertEquals(ServerState.RUNNING, state.serverState)
        assertEquals("vaultsync_pc_access_channel", PcAccessService.CHANNEL_ID)
        assertEquals(8080, PcAccessService.NOTIFICATION_ID)
        assertEquals("com.vaultsync.action.START_PC_ACCESS", PcAccessService.ACTION_START)
        assertEquals("com.vaultsync.action.STOP_PC_ACCESS", PcAccessService.ACTION_STOP)
    }
}

// =========================================================================
// TEST REPOSITORY FAKES
// =========================================================================

private class FakeVaultRepository(
    folders: List<VaultFolder> = emptyList(),
    entries: List<VaultEntry> = emptyList(),
) : VaultRepository {
    val folders = folders.toMutableList()
    val entries = entries.toMutableList()

    override fun getFolders(parentId: String?): Flow<List<VaultFolder>> = flowOf(folders.filter { it.parentId == parentId })
    override fun getAllFolders(): Flow<List<VaultFolder>> = flowOf(folders)
    override suspend fun getFolder(folderId: String): VaultFolder? = folders.firstOrNull { it.id == folderId }
    override suspend fun createFolder(name: String, parentId: String?): VaultFolder {
        val f = VaultFolder(
            id = "folder-${System.currentTimeMillis()}-${folders.size}",
            name = name,
            parentId = parentId,
            createdAtMillis = System.currentTimeMillis(),
            updatedAtMillis = System.currentTimeMillis(),
        )
        folders.add(f)
        return f
    }
    override suspend fun renameFolder(folderId: String, newName: String) {}
    override suspend fun moveFolder(folderId: String, newParentId: String?) {}
    override suspend fun deleteFolder(folderId: String) {
        folders.removeAll { it.id == folderId }
    }
    override fun getEntries(folderId: String?): Flow<List<VaultEntry>> = flowOf(entries.filter { it.folderId == folderId })
    override fun getEntriesByCategory(category: VaultCategory): Flow<List<VaultEntry>> = flowOf(entries.filter { it.category == category })
    override fun getAllEntries(): Flow<List<VaultEntry>> = flowOf(entries)
    override suspend fun getEntry(id: String): VaultEntry? = entries.firstOrNull { it.id == id }
    override suspend fun importFile(sourceFile: File, displayName: String?, destinationFolderId: String?, vmk: ByteArray, preserveOriginal: Boolean): VaultEntry = throw UnsupportedOperationException()
    override suspend fun importStream(inputStream: InputStream, displayName: String, originalSize: Long, mimeType: String?, destinationFolderId: String?, vmk: ByteArray): VaultEntry {
        val bytes = inputStream.readBytes()
        val entry = VaultEntry(
            id = "entry-${System.currentTimeMillis()}-${entries.size}",
            folderId = destinationFolderId,
            originalFileName = displayName,
            category = VaultCategory.Documents,
            mimeType = mimeType,
            originalSizeBytes = bytes.size.toLong(),
            encryptedSizeBytes = bytes.size.toLong() + 28,
            createdAtMillis = System.currentTimeMillis(),
            dateModifiedMillis = System.currentTimeMillis(),
        )
        entries.add(entry)
        return entry
    }
    override suspend fun renameEntry(entryId: String, newName: String) {}
    override suspend fun copyEntry(entryId: String, targetFolderId: String?, vmk: ByteArray): VaultEntry = throw UnsupportedOperationException()
    override suspend fun deleteEntry(entryId: String) {
        entries.removeAll { it.id == entryId }
    }
    override suspend fun moveEntry(entryId: String, targetFolderId: String?) {}
    override suspend fun setEntryInUse(entryId: String, inUse: Boolean) {}
    override suspend fun exportOrDecrypt(entryId: String, destinationFile: File, vmk: ByteArray) {
        destinationFile.writeText("Decrypted content of entry $entryId")
    }
    override suspend fun cleanupOrphanedBlobs() {}
}

private class FakeMediaRepository(
    private val items: List<DeviceMediaItem> = emptyList(),
) : MediaRepository {
    override suspend fun loadMediaStoreItems(): List<DeviceMediaItem> = items
    override suspend fun loadSafFolderItems(folderUri: Uri): List<DeviceMediaItem> = emptyList()
}

private class FakeSharedPreferences(
    private val data: MutableMap<String, Any>,
) : SharedPreferences {
    override fun getAll(): MutableMap<String, *> = data
    override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = null
    override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor(data)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}

private class FakeEditor(
    private val data: MutableMap<String, Any>,
) : SharedPreferences.Editor {
    private val temp = mutableMapOf<String, Any?>()

    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
        if (key != null) temp[key] = value
        return this
    }
    override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
    override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
        if (key != null) temp[key] = value
        return this
    }
    override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
        if (key != null) temp[key] = value
        return this
    }
    override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
        if (key != null) temp[key] = value
        return this
    }
    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
        if (key != null) temp[key] = value
        return this
    }
    override fun remove(key: String?): SharedPreferences.Editor {
        if (key != null) temp[key] = null
        return this
    }
    override fun clear(): SharedPreferences.Editor {
        temp.clear()
        data.clear()
        return this
    }
    override fun commit(): Boolean {
        apply()
        return true
    }
    override fun apply() {
        for ((k, v) in temp) {
            if (v == null) data.remove(k) else data[k] = v
        }
    }
}
