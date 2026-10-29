# PC Access Security & Transport Architecture

## 1. Overview & Threat Model

VaultSync includes an embedded local HTTP server running directly on the Android device. This allows a user to access their media and securely view authorized vault content from any PC web browser connected to the same local Wi-Fi network without installing third-party desktop software or routing data through cloud servers.

### Threat Model & Boundaries
1. **Adversary on the Same LAN:** An attacker connected to the same Wi-Fi network could attempt to brute-force authentication, initiate CSRF attacks from a malicious tab on the PC, or sniff unencrypted packets.
2. **Adversary on the Internet:** An external attacker attempting to access the phone's open port.
3. **Malicious Web Pages (Cross-Site Requests):** A browser tab running a third-party website attempting to silently query `http://192.168.x.x:8080/api/*`.

---

## 2. The Local HTTP Transport Limitation

### Why Local HTTP is Used on LAN
- **No Public CA for Private IPs:** Trusted Certificate Authorities (e.g., Let's Encrypt, DigiCert) do not issue SSL/TLS certificates for RFC-1918 private IP addresses (such as `192.168.1.100` or `10.0.0.5`).
- **Self-Signed Certificate Friction:** Using a self-signed certificate on a local IP causes modern web browsers (Chrome, Edge, Safari, Firefox) to display severe, intimidating security interstitial screens (`NET::ERR_CERT_AUTHORITY_INVALID`) that confuse users and are actively blocked by some enterprise browser policies.
- **Offline Reliability:** Solutions relying on public Dynamic DNS (e.g., `*.local.vaultsync.app` resolving to LAN IPs) fail completely when the local Wi-Fi router does not have an active internet uplink.

### How VaultSync Compensates for Cleartext HTTP
Because TLS encryption is not present at the transport layer on raw LAN IP addresses:
1. **Private Network Isolation:** The server rejects any incoming connection whose remote address is not an RFC-1918 private address (`192.168.0.0/16`, `10.0.0.0/8`, `172.16.0.0/12`) or localhost (`127.0.0.1`).
2. **No Internet Exposure / No Port Forwarding:** The server never issues UPnP or NAT-PMP port mapping requests to the router. It is strictly bounded to the local subnet.
3. **High-Entropy Session Tokens:** Sessions use 256-bit cryptographically secure random tokens (`java.security.SecureRandom`) transmitted via the `Authorization: Bearer` header.
4. **Brute-Force & Rate Limiting:** Consecutive failed login attempts trigger exponential backoff and temporary IP lockouts.
5. **CSRF & Origin Enforcement:** All mutating API endpoints require explicit `Origin` validation matching the phone's host IP and custom client headers (`X-VaultSync-Client`). Browsers prohibit cross-origin scripts from setting forbidden custom headers without preflight approval.
6. **No Static URLs for Vault Content:** Vault content cannot be accessed via predictable URLs; every request is authenticated against active sessions and dynamic tokens.
7. **Scoped Authorization:** Logging in with the PC web passcode grants access **only** to normal device media. Accessing encrypted vault files requires an explicit secondary unlock step using the Vault Master PIN.

---

## 3. Pluggable HTTPS Architecture for Future Upgrades

The PC Access server is built on **Ktor CIO**, which decouples network routing from the underlying transport layer.

### Transitioning to HTTPS Later
To enable HTTPS in future milestones or enterprise environments:
1. **Keystore Generation:** Generate a local PKCS12 keystore containing a private key and self-signed X.509 certificate (or custom Root CA).
2. **Ktor SSL Connector:** In `PcServerManager.kt`, substitute the standard `cio { ... }` connector with `sslConnector`:
   ```kotlin
   sslConnector(
       keyStore = localKeyStore,
       keyAlias = "vaultsync_pc",
       keyStorePassword = { "keystore_pass".toCharArray() },
       privateKeyPassword = { "key_pass".toCharArray() }
   ) {
       port = httpsPort
   }
   ```
3. **Client Trust:** Provide an in-app "Download CA Certificate" button allowing advanced users to install the VaultSync Root CA into their PC's trusted certificate store, achieving clean HTTPS on LAN without browser warnings.

---

## 4. PC Access API Contract Specification

### Base Conventions
- All JSON responses follow explicit envelope models:
  - **Success envelope:** `{"success": true, "data": <payload>}`
  - **Error envelope:** `{"success": false, "error": {"code": "<ERROR_CODE>", "message": "<description>"}}`
- **Error Codes:**
  - `UNAUTHORIZED` (401): Missing, invalid, or expired session token, or invalid pairing passcode / Master PIN.
  - `FORBIDDEN` (403): Request originating from non-RFC-1918 IP address or failed Origin/CSRF validation.
  - `VAULT_LOCKED` (403): Resource requires vault elevation or phone vault session is locked.
  - `THROTTLED` (429): Consecutive failed attempts triggered a temporary lockout.
  - `INVALID_REQUEST` (400): Malformed JSON, missing required fields, or illegal parameter values.
  - `NOT_FOUND` (404): Media item, vault entry, or asset does not exist.
  - `INTERNAL_ERROR` (500): Unexpected server-side failure during streaming or decryption.

### Endpoints

#### 1. Server Status
- **Route:** `GET /api/status`
- **Auth:** None (Public LAN)
- **Response:**
  ```json
  {
    "success": true,
    "data": {
      "isRunning": true,
      "localIp": "192.168.1.50",
      "port": 8080,
      "url": "http://192.168.1.50:8080",
      "activeSessionsCount": 1,
      "isVaultUnlockedOnPhone": false
    }
  }
  ```

#### 2. Authentication: Login
- **Route:** `POST /api/auth/login`
- **Headers:** `Origin` or `X-VaultSync-Client: web`
- **Request Body:**
  ```json
  {
    "passcode": "123456"
  }
  ```
- **Response (200 OK):**
  ```json
  {
    "success": true,
    "data": {
      "token": "64-char-hex-256-bit-token",
      "expiresAt": 1729350000000,
      "hasVaultAccess": false
    }
  }
  ```
- **Throttled Response (429 Too Many Requests):**
  ```json
  {
    "success": false,
    "error": {
      "code": "THROTTLED",
      "message": "Too many failed attempts. Try again in 60 seconds."
    }
  }
  ```

#### 3. Authentication: Session Status
- **Route:** `GET /api/auth/session`
- **Headers:** `Authorization: Bearer <token>`
- **Response (200 OK):**
  ```json
  {
    "success": true,
    "data": {
      "active": true,
      "clientIp": "192.168.1.88",
      "hasVaultAccess": false,
      "remainingSeconds": 1740
    }
  }
  ```

#### 4. Authentication: Logout
- **Route:** `POST /api/auth/logout`
- **Headers:** `Authorization: Bearer <token>`, `X-VaultSync-Client: web`
- **Response (200 OK):**
  ```json
  {
    "success": true,
    "data": "Logged out successfully"
  }
  ```

#### 5. Vault Elevation: Unlock Vault
- **Route:** `POST /api/vault/unlock`
- **Headers:** `Authorization: Bearer <token>`, `X-VaultSync-Client: web`
- **Request Body:**
  ```json
  {
    "pin": "1234"
  }
  ```
- **Response (200 OK):**
  ```json
  {
    "success": true,
    "data": "Vault authorized for this PC session"
  }
  ```

#### 6. Vault Elevation: Lock Vault
- **Route:** `POST /api/vault/lock`
- **Headers:** `Authorization: Bearer <token>`, `X-VaultSync-Client: web`
- **Response (200 OK):**
  ```json
  {
    "success": true,
    "data": "Vault access revoked for this session"
  }
  ```

#### 7. Media Library
- **Route:** `GET /api/media/list`
- **Headers:** `Authorization: Bearer <token>`
- **Response (200 OK):** Array of MediaStore items.

- **Route:** `GET /api/media/file/{id}`
- **Headers:** `Authorization: Bearer <token>` (or `?token=`), `Range: bytes=start-end` (optional)
- **Response:** Binary stream with `Accept-Ranges: bytes`, `Content-Range`, `Content-Disposition`.

#### 8. Vault Library & Dynamic Decryption Streaming (No Static URLs)
- **Route:** `GET /api/vault/folders`
- **Headers:** `Authorization: Bearer <token>` (Session MUST have `hasVaultAccess: true` and phone vault unlocked)
- **Response (200 OK):** Array of folders.

- **Route:** `GET /api/vault/entries`
- **Headers:** `Authorization: Bearer <token>` (Session MUST have `hasVaultAccess: true` and phone vault unlocked)
- **Response (200 OK):** Array of encrypted vault item metadata.

- **Route:** `GET /api/vault/file/{id}`
- **Security Invariant:** Vault files are NEVER exposed via static filesystem URLs or static web directories.
- **Headers:** `Authorization: Bearer <token>` (or `?token=`), `Range: bytes=start-end` (optional)
- **Response Headers:**
  - `Cache-Control: no-store, no-cache, must-revalidate, private, max-age=0`
  - `Pragma: no-cache`
  - `Accept-Ranges: bytes`
- **Streaming Decryption:** On-the-fly authenticated decryption from encrypted chunk store via `VaultFileDecryptor.readRange()`. Plaintext data is streamed directly to the network socket without creating intermediate temporary plaintext files on disk.

