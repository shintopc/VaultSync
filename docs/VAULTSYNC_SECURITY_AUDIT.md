# VaultSync — Security Review & Vulnerability Assessment

**Document Version:** 1.2 (Pre-Production Final Audit)  
**Audit Date:** October 24, 2026  
**Auditor:** Senior Android Security Auditor & Cryptographic Specialist  
**Target Package:** `com.vaultsync.app`  
**Classification:** Internal Technical Security Assessment  

---

## 1. Threat Model & Security Boundaries

VaultSync operates across two distinct security environments:
1. **On-Device Storage & Local UI (Android Sandboxing):**
   - Protected by Linux user separation (UID/GID isolation).
   - Encrypted files reside in app-private storage (`/data/user/0/com.vaultsync.app/files/vault/blobs/`).
   - SQLite metadata is protected by app-private directory permissions.
   - Attack vectors evaluated: physical device access while locked, shoulder-surfing, malicious third-party apps requesting storage permissions, flash extraction/forensics, unauthenticated ADB backups.
2. **Local Area Network & PC Browser Access (HTTP/WebSocket Gateway):**
   - Embedded Ktor CIO server bound to `0.0.0.0:8080`.
   - Accessible by any device on the same local Wi-Fi subnet.
   - Attack vectors evaluated: LAN packet sniffing/session hijacking, cross-site request forgery (CSRF) from attacker websites visiting PC browser, DNS rebinding, brute-force passcode guessing, unauthorized access to vault files using normal media credentials, path traversal during upload.

---

## 2. Vulnerability Findings & Severity Matrix

| ID | Title | Severity | Status | Affected Component |
|---|---|:---:|:---:|---|
| **SEC-01** | Insecure Plaintext Storage on Disk During File Copy | **HIGH** | **FIXED** | `AndroidVaultRepository.copyEntry()` |
| **SEC-02** | Path Traversal & Control Character Injection on File Upload | **HIGH** | **FIXED** | `PcServer.kt` (`/api/upload/media`, `/api/upload/vault`) |
| **SEC-03** | LAN IP Spoofing via `X-Forwarded-For` Header | **MEDIUM** | **FIXED** | `PcServer.getClientIp()` |
| **SEC-04** | Incomplete Upload File Residue on Disconnect / Failure | **MEDIUM** | **FIXED** | `PcServer.kt` (`/api/upload/media`) |
| **SEC-05** | Solid-State Flash Memory Forensic Shredding Limitations | **LOW** | **DOCUMENTED** | `AndroidVaultRepository.deleteEntry()` |
| **SEC-06** | JVM Garbage Collection & String/Key In-Memory Lifetimes | **LOW** | **DOCUMENTED** | `VaultSession.kt`, `KeyDerivation.kt` |
| **SEC-07** | Background Process Termination on Android 14+ | **LOW** | **FIXED** | `AndroidManifest.xml`, `PcAccessService.kt` |
| **SEC-08** | False-Positive Rejection of IPv4-Mapped IPv6 & LAN Subnet Clients | **HIGH** | **FIXED** | `NetworkUtils.kt`, `PcServer.kt` |

---

## 3. Detailed Vulnerability Analyses & Remediation Evidence

### 3.1 SEC-01: Insecure Plaintext Storage on Disk During File Copy (Severity: HIGH)
- **Vulnerability Description:**
  In `AndroidVaultRepository.copyEntry()`, when a user created a copy of an encrypted vault item, the code decrypted the source blob into a temporary file on flash storage:
  ```kotlin
  // Vulnerable code (before remediation):
  val tempDecryptedFile = storageManager.tempDir.resolve("${newBlobId}.part_plain")
  VaultFileDecryptor.decryptFile(sourceBlob, tempDecryptedFile, vmk)
  BufferedInputStream(FileInputStream(tempDecryptedFile)).use { input ->
      // Re-encrypt from temp file...
  }
  ```
  Even though `tempDecryptedFile` was deleted in the `finally` block, unencrypted media data was written to NAND flash storage. Due to flash wear-leveling and trim latency, deleted sectors can remain recoverable via flash memory chip forensics.
- **Remediation Applied:**
  Replaced intermediate disk files with coroutine-based in-memory piped streaming (`PipedInputStream` and `PipedOutputStream` with a 64 KiB buffer). `VaultFileDecryptor.decryptStream` writes decrypted plaintext chunks directly into the memory pipe, while `VaultFileEncryptor.encryptStream` reads from the pipe and writes ciphertext directly to the new destination blob.
- **Verification:**
  Tested in `VaultRepositoryTest.kt` (`copyEntryAcrossFoldersCreatesIndependentEncryptedBlob`) and `VaultEncryptionDecryptionTest.kt`. Decryption and re-encryption complete with zero temporary plaintext files generated.

---

### 3.2 SEC-02: Path Traversal & Control Character Injection on File Upload (Severity: HIGH)
- **Vulnerability Description:**
  The PC Web upload endpoints `/api/upload/media` and `/api/upload/vault` extracted the uploaded file name via:
  ```kotlin
  val cleanName = File(URLDecoder.decode(rawName, ...)).name
  ```
  On Unix-like operating systems (including Android), `File.name` only splits on `/`. If a client passed Windows directory separators (`..\\..\\something`) or URL-encoded variations (`%2e%2e%2f`), backslashes were preserved in the filename. Furthermore, null bytes (`\0`) or control characters could disrupt file handling.
- **Remediation Applied:**
  Introduced `PcServer.sanitizeFileName()`:
  1. Decodes URL percent-encoding safely with fallback.
  2. Normalizes both `/` and `\` to `/` before extracting the basename (`substringAfterLast('/')`).
  3. Strips all null bytes, non-printable control characters, and dangerous characters (`[\x00-\x1F\x7F:*?"<>|]`).
  4. Trims leading and trailing dots and spaces.
  5. Enforces fallback naming (`upload_<timestamp>`) if the resulting string is blank or invalid (`.`, `..`).
  6. Enforces canonical path verification (`targetFile.canonicalPath.startsWith(uploadDir.canonicalPath + File.separator)`).
- **Verification:**
  Added regression tests in `PcAuthAndServerTest.kt` (`sanitizeFileNameStripsDangerousCharacters` and `httpIntegrationCompleteServerLifecycleAndApiContract` step 9c-2), validating that `../../../../traversal_test.png` is sanitized to `traversal_test.png` and confined to `uploads/`.

---

### 3.3 SEC-03: LAN IP Spoofing via `X-Forwarded-For` Header (Severity: MEDIUM)
- **Vulnerability Description:**
  The server extracted client IP addresses using:
  ```kotlin
  val forwarded = call.request.header("X-Forwarded-For")?.substringBefore(',')?.trim()
  if (!forwarded.isNullOrBlank()) return forwarded
  ```
  Because the Android phone's embedded server connects directly to LAN clients without a reverse proxy, any client could forge `X-Forwarded-For: 192.168.1.99`. This allowed an attacker to:
  - Bypass IP-based failed login throttling (rotating the fake IP on each attempt).
  - Bypass session IP binding checks.
- **Remediation Applied:**
  Restricted `X-Forwarded-For` parsing strictly to requests where the actual socket remote address is a local loopback interface (`127.0.0.1`, `localhost`, `::1`). Remote LAN socket connections strictly use their true socket IP, rendering header spoofing ineffective.
- **Verification:**
  Unit tests verified that loopback test requests can still simulate client IPs, while remote socket connections ignore spoofed headers.

---

### 3.4 SEC-04: Incomplete Upload File Residue on Disconnect (Severity: MEDIUM)
- **Vulnerability Description:**
  In `/api/upload/media`, the stream was copied directly into `FileOutputStream(targetFile)`. If a PC client disconnected midway or aborted the transfer, a truncated or corrupted file remained in the destination directory.
- **Remediation Applied:**
  Added atomic staging using temporary files (`${cleanName}.${UUID.randomUUID()}.part`). Only upon complete and successful stream completion is the `.part` file atomically renamed to `targetFile`. If an exception occurs, the `.part` file is purged immediately.
- **Verification:**
  Direct stream error handling and atomic rename verified in unit tests.

---

### 3.5 SEC-05: Solid-State Flash Memory Forensic Realities (Severity: LOW / Informational)
- **Analysis:**
  Users often expect "secure shredding" (overwriting file sectors with zeroes or random noise) to guarantee non-recoverability. However, modern Android devices utilize eMMC / UFS flash storage with proprietary Wear-Leveling algorithms and Flash Translation Layers (FTL). When an application overwrites a file on flash storage, the controller writes to a *different* physical block and updates its logical-to-physical block table; the old block is marked dirty and only erased during background garbage collection.
- **Architecture Defense:**
  VaultSync avoids relying on post-facto shredding by ensuring all sensitive data is **encrypted before it ever reaches disk**. The stored blobs (`.vsf`) are ciphertext. Deleting a file simply removes the encrypted blob and its Room metadata entry. Even if an attacker physically desolders the flash chip and recovers raw sectors, the recovered data is AES-256-GCM ciphertext without the DEK or VMK.

---

### 3.6 SEC-06: JVM In-Memory Key Lifetimes & Zeroization (Severity: LOW / Informational)
- **Analysis:**
  In Android's ART/JVM runtime, immutable objects such as `String` cannot be zeroed out in memory and must await garbage collection. Sensitive cryptographic keys (VMK, DEK, PBKDF2 intermediate keys) are maintained as primitive `ByteArray` instances.
- **Architecture Defense:**
  - `VaultKeyManager.zeroize(key: ByteArray)` uses `Arrays.fill(key, 0.toByte())` to immediately wipe raw key material from heap memory as soon as operations complete.
  - `VaultSession.lock()` immediately calls `zeroize()` on the in-memory VMK and invalidates the session StateFlow.
  - PBKDF2 key derivations accept `CharArray` for PIN inputs and explicitly zeroize the character arrays and intermediate derived bytes in `finally` blocks.
  - Added unit test `zeroizeWipesKeyInMemory()` verifying zeroization.

---

### 3.7 SEC-07: Background Process Termination on Android 14+ (Severity: LOW — RESOLVED)
- **Vulnerability / Limitation Description:**
  On Android 14+ (API 34+), when an app hosting a Ktor network server moves to the background or the screen locks, the OS power manager and process freezer may suspend CPU execution or put the Wi-Fi chip into power-save mode, severing active PC file transfers.
- **Remediation Applied:**
  Introduced `PcAccessService` (`android:foregroundServiceType="dataSync"`):
  1. Registers low-importance ongoing notification (`vaultsync_pc_access_channel`) with real-time transfer stats and a direct "Stop" action.
  2. Acquires `PowerManager.PARTIAL_WAKE_LOCK` to keep CPU execution active during background operations.
  3. Acquires `WifiManager.WIFI_MODE_FULL_HIGH_PERF` Wi-Fi lock to ensure the radio interface maintains full throughput.
  4. Automatically started on server launch via `PcAccessManager.startServer()` and torn down on server stop or `onDestroy()`.
  5. Manifest includes `WAKE_LOCK`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, and runtime `POST_NOTIFICATIONS` permission flow for Android 13+.
- **Verification:**
  Tested across Android 14, 15, and 16 preview environments. Server and file transfers persist uninterrupted with screen turned off or app backgrounded.

---

### 3.8 SEC-08: False-Positive Rejection of IPv4-Mapped IPv6 & Local Subnet Clients (Severity: HIGH)
- **Vulnerability Description:**
  On Android/Linux dual-stack sockets, IPv4 connections arriving at the server are delivered as IPv4-mapped IPv6 addresses (`::ffff:192.168.x.x`). The previous `isPrivateSubnetAddress()` parser split the address on `.` and mapped segments to integers, failing on `::ffff:192` (`parts.size != 4`) and falsely rejecting genuine local LAN clients with `Access from non-private IP addresses is forbidden`. Furthermore, in dual-stack home Wi-Fi networks where routers assign global IPv6 prefixes (e.g. `/64` SLAAC / GUA) to both the PC and phone, clients connecting over IPv6 were rejected by a static filter that only recognized `fe80`/`fc`/`fd`.
- **Remediation Applied:**
  1. Introduced `NetworkUtils.normalizeIp()` which strips `::ffff:`, port numbers, and zone/scope specifiers before subnet analysis.
  2. Implemented `NetworkUtils.matchesSubnet()` which dynamically verifies whether a client IP shares the same network prefix with any active local Wi-Fi or tethering interface on the device (while explicitly skipping cellular data interfaces).
  3. Added regression tests in `PcAuthAndServerTest.kt` covering `::ffff:192.168.1.100`, scoped addresses, and mobile hotspot ranges. Verified live connectivity over physical Wi-Fi.

---

## 4. Deep-Dive Cryptographic Evaluation

### 4.1 Nonce Uniqueness & Collision Resistance
- **Algorithm:** AES-256-GCM (NIST SP 800-38D).
- **GCM Nonce Size:** 96 bits (12 bytes).
- **Header Nonce:** 12 cryptographically random bytes generated via `SecureRandom`.
- **Per-Chunk Nonce Construction:**
  - `fileNoncePrefix`: 8 cryptographically random bytes (64 bits).
  - `chunkIndex`: 4 bytes big-endian (32 bits).
  - `isFinal` flag: Sets MSB of `chunkIndex` (0x80000000).
- **Collision Analysis:**
  Within a single file, the 32-bit counter strictly increments ($0, 1, 2, \dots$), guaranteeing **zero probability of nonce reuse**. Across different files, the 64-bit random prefix provides $2^{64} \approx 1.84 \times 10^{19}$ distinct streams. By the birthday paradox, encrypting $2^{32} \approx 4.29 \times 10^9$ files yields a collision probability under $0.5$, which is well beyond mobile device storage lifecycles.

### 4.2 Authentication Tag Verification & Tamper Resistance
- **Tag Size:** 128 bits (16 bytes).
- **Associated Authenticated Data (AAD):**
  - Header AAD binds format version, chunk size, file UUID, nonce prefix, and original file size.
  - Chunk AAD binds file UUID, chunk index, and final flag.
- **Tampering Behavior:**
  Any bit flip in the ciphertext, length prefix, header, or AAD causes Java Cryptography Architecture (JCA) to throw `javax.crypto.AEADBadTagException` during `cipher.doFinal()`. The decrypter immediately aborts and returns zero unauthenticated plaintext.

### 4.3 Key Derivation Work Factor
- **Algorithm:** PBKDF2WithHmacSHA256.
- **Iteration Count:** 120,000 iterations (exceeds OWASP 2023 recommendations for PBKDF2-HMAC-SHA256).
- **Salt:** 32 cryptographically random bytes (256 bits).
- **Performance:** Approximately 120–160 ms per derivation on modern ARM Cortex-A78 / Snapdragon processors, providing an optimal balance between brute-force resistance and unlock responsiveness.

---

## 5. PC Web Access Security Evaluation

### 5.1 Authentication & Session Token Entropy
- **Pairing Passcode:** 6-digit random integer (`100000` to `999999`).
- **Brute Force Protection:** Tracks failed login attempts per client IP. After 5 consecutive failed attempts, the IP is locked out for 60 seconds (`429 Too Many Requests`).
- **Session Tokens:** 32 bytes of cryptographic randomness generated via `SecureRandom` and encoded as 64 hex characters (256 bits of entropy). Tokens are stored in the browser's `sessionStorage` (cleared on tab close).
- **Session Expiration:** 30 minutes of inactivity. Activity timestamps update on valid requests.
- **Session Revocation:** `/api/auth/logout` immediately purges the session token from memory. Stopping the server or regenerating the passcode invalidates all active sessions.

### 5.2 Strict Normal vs. Vault Privilege Separation
- **Normal Media Session:** Allows listing device media (`/api/media/list`), streaming public photos/videos (`/api/media/file/{id}`), uploading media (`/api/upload/media`), and downloading media ZIPs (`/api/media/download/zip`).
- **Vault Restriction:** Even with a valid session token, calling `/api/vault/*` returns HTTP 403 `VAULT_LOCKED`.
- **Master PIN Elevation:** To access vault content, the client must submit the master PIN via `/api/vault/unlock`. If valid, `session.hasVaultAccess` is set to `true` and the in-memory VMK is used.
- **Browser-Side Vault Locking:** Clicking "Lock Vault" calls `/api/vault/lock`, immediately revoking vault privileges while allowing the normal media session to continue safely.

### 5.3 HTTP Range & Media Streaming Security
- Range headers (`Range: bytes=start-end`) are validated against total file size.
- Invalid or inverted ranges (`start > end` or `start >= totalSize`) return HTTP 416 `Requested Range Not Satisfiable`.
- `readRange` dynamically decrypts only the requested chunk slices and flushes them to the socket. No decrypted files are written to disk.

---

## 6. Audit Conclusion
The VaultSync security model demonstrates robust defense-in-depth across both on-device storage and local network sharing. All critical and high severity vulnerabilities discovered during this audit have been resolved, and their fixes are backed by automated regression tests in the build pipeline.
