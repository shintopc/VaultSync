# VaultSync — Vault Security Foundation & Cryptographic Architecture

## 1. Threat Model & Security Goals

VaultSync is designed to protect private user media and sensitive files from unauthorized access, device theft, inspection by other apps on the device, and unauthorized remote access.

### Core Security Objectives
1. **Confidentiality:** Plaintext contents and sensitive metadata (original file names, folder paths, MIME types, dimensions) must never be readable without the user credential.
2. **Integrity & Authenticity:** Any modification, corruption, truncation, or reordering of ciphertext must be detected before plaintext is delivered to callers.
3. **Defense in Depth:** Hardware-backed key protection (Android Keystore / TEE / StrongBox) combined with user-derived key wrapping.
4. **Streaming & Large File Support:** Streaming encryption and random-access chunk decryption without loading entire files into memory.
5. **No Backdoors / Zero-Knowledge:** Keys are generated and maintained strictly on the user's device. No cloud sync, telemetries, or master backdoor keys exist.

---

## 2. Key Hierarchy

The cryptographic architecture uses a multi-tier key hierarchy:

```
                  +--------------------------------+
                  |      User PIN / Password       |
                  +--------------------------------+
                                  |
                   PBKDF2WithHmacSHA256 (120,000 iters)
                     + 32-byte Cryptographic Salt
                                  |
                                  v
                  +--------------------------------+
                  |  Key Wrapping Key (KWK, 256b)  |
                  +--------------------------------+
                                  |
                     AES-256-GCM Key Wrap / Unwrap
                                  |
                                  v
+------------------------+  Unwraps   +------------------------------------+
| Android Keystore Master |----------> |     Vault Master Key (VMK, 256b)   |
| Key (TEE / StrongBox)  | (Biometric)| (Held in VaultSession RAM only)     |
+------------------------+            +------------------------------------+
                                                |
                                    AES-256-GCM Wrap / Unwrap
                                                |
                                                v
                              +------------------------------------+
                              |  Per-File Data Key (DEK, 256-bit)  |
                              |  (Fresh random key per vault file) |
                              +------------------------------------+
                                                |
                                    AES-256-GCM Chunked Encryption
                                                |
                                                v
                              +------------------------------------+
                              |     Encrypted Vault File (.vsf)    |
                              |     Chunks with Individual Tags    |
                              +------------------------------------+
```

### 2.1 Vault Master Key (VMK)
- **Type:** 256-bit AES symmetric key generated with `java.security.SecureRandom`.
- **Storage:** Never stored in plaintext. Stored on disk wrapped with AES-256-GCM under the Key Wrapping Key (KWK).
- **Lifecycle:** Unwrapped into memory only when the user unlocks the vault. Zeroized/cleared when the vault is locked or the session expires.

### 2.2 Key Wrapping Key (KWK)
- **Derivation:** Derived from user PIN/password using `PBKDF2WithHmacSHA256` with 120,000 iterations and a 32-byte salt (`SecureRandom`).
- **Separation of Concerns:** The PIN is never used directly as an encryption key.
- **Verification Hash:** A separate verification token is derived using HKDF / secondary PBKDF2 salt to verify credential correctness before attempting VMK unwrapping.

### 2.3 Per-File Data Encryption Key (DEK)
- **Type:** 256-bit AES symmetric key generated afresh for every single imported file.
- **Storage:** Wrapped with the VMK using AES-256-GCM and stored directly inside the file's header.
- **Benefits:**
  - Compromise of one file's DEK does not impact any other file.
  - Two identical files imported into the vault produce completely different ciphertexts and file headers.
  - Fast deletion (deleting the header or file destroys the DEK permanently).

---

## 3. Encrypted File Format (`.vsf`)

To support large files (e.g. 4GB 4K videos) and random-access seeking (HTTP Range requests for streaming) without loading files into RAM or requiring whole-file re-decryption, VaultSync uses a **Chunked Authenticated Encryption** scheme based on AES-256-GCM.

### File Structure Diagram

```
+-----------------------------------------------------------------------+
|                              FILE HEADER                              |
|  - Magic Bytes: "VSF1" (4 bytes)                                      |
|  - Version: 0x0001 (2 bytes)                                          |
|  - Chunk Plaintext Size: 65,536 bytes (4 bytes)                       |
|  - File Nonce Prefix: 4 bytes random                                  |
|  - Header Nonce: 12 bytes random                                      |
|  - Wrapped DEK: 48 bytes (32 bytes key + 16 bytes GCM tag)            |
|  - Original File Size: 8 bytes (Long)                                 |
|  - Header Tag / Auth: Verified as part of header AES-GCM              |
+-----------------------------------------------------------------------+
|                               CHUNK 0                                 |
|  - Ciphertext: 65,536 bytes                                           |
|  - GCM Auth Tag: 16 bytes                                             |
|    Total Chunk 0 Size: 65,552 bytes                                   |
+-----------------------------------------------------------------------+
|                               CHUNK 1                                 |
|  - Ciphertext: 65,536 bytes                                           |
|  - GCM Auth Tag: 16 bytes                                             |
+-----------------------------------------------------------------------+
|                                 ...                                   |
+-----------------------------------------------------------------------+
|                           FINAL CHUNK (N - 1)                         |
|  - Ciphertext: 0 to 65,536 bytes (remainder)                          |
|  - GCM Auth Tag: 16 bytes                                             |
+-----------------------------------------------------------------------+
```

### Nonce & AAD Construction
- **Header Nonce:** 12 random bytes per file.
- **Chunk Nonce (12 bytes):**
  - Bytes 0..3: 4-byte random file nonce prefix (established in header).
  - Bytes 4..10: 7-byte chunk sequence index (`0, 1, 2, ...`).
  - Byte 11: Flag (`0x00` = intermediate chunk, `0x01` = final chunk).
- **Additional Authenticated Data (AAD):**
  - Chunk AAD binds: `File UUID (16 bytes) || Chunk Index (Long) || isFinal (Byte)`.
  - **Guarantees:**
    - Prevents chunk reordering or dropping.
    - Prevents swapping chunks between different files.
    - Prevents truncation attacks: the last chunk MUST have `isFinal = 0x01`.

---

## 4. Large-File Processing & Authenticated Streaming

### HTTP Range Seeking Algorithm
When the PC browser or Android ExoPlayer requests byte range `[startByte, endByte]`:
1. Calculate target chunk indices:
   $$\text{startChunk} = \lfloor \text{startByte} / 65536 \rfloor$$
   $$\text{endChunk} = \lfloor \text{endByte} / 65536 \rfloor$$
2. Seek file pointer directly to header size $+ (\text{startChunk} \times 65552)$.
3. Read the encrypted chunk and its 16-byte authentication tag.
4. Verify and decrypt the chunk in memory ($64\text{ KiB}$).
5. Slice only the required sub-range of plaintext bytes for delivery.
6. If any byte or tag is tampered with, `AEADBadTagException` is raised immediately; no corrupted or unauthenticated bytes are ever served.

---

## 5. Metadata Protection

1. **On-Disk Filenames:** Encrypted files are stored on internal storage using opaque UUIDs (e.g. `3a4f82c1-8419-4b67-9204-712349182312.vsf`). Original filenames are never visible on the filesystem.
2. **Metadata Repository:** File metadata (display name, folder ID, MIME type, size, modified timestamp, custom tags) is stored in a private metadata database protected with the VMK.
3. **No Public Storage Decryption:** Plaintext files and thumbnails are never written to shared or public storage (`/sdcard/`, `Downloads`, etc.). Plaintext previews are streamed in-memory or served from app-private cache directories that are purged upon session lock.

---

## 6. Biometric Authentication Behavior

- Biometrics is an **optional convenience layer**, not the master recovery key.
- When enabled:
  - An Android Keystore key is generated with `setUserAuthenticationRequired(true)`.
  - The VMK is wrapped with this biometric key and stored in app private storage.
  - Authenticators: Class 3 (Biometric Strong: Fingerprint / 3D Face Unlock).
- When biometrics fails, new biometric credentials are added to the device (which invalidates Keystore keys), or the device reboots:
  - The user enters their primary PIN / password to unlock and re-wrap the VMK.

---

## 7. Recovery Limitations & Device Loss Consequences

1. **Forgotten PIN / Password:**
   - Because VaultSync uses true zero-knowledge client-side encryption without backdoors, if the user loses their password and biometrics are unavailable, **vault contents cannot be recovered**.
2. **App Uninstallation / Data Clearing:**
   - Uninstalling the app causes Android to delete the app's private sandbox and Keystore keys.
   - All encrypted vault files and keys are permanently lost.
3. **Device Loss / Theft:**
   - Device theft leaves the attacker with encrypted AES-256-GCM blobs and a PBKDF2-stretched key wrapping. Without the PIN or breaking AES-256, the attacker cannot read any files.
