# VaultSync v1.0.0 — Production Release 🚀

We are thrilled to announce the official **v1.0.0 production release** of **VaultSync** — a zero-cloud, hardware-encrypted, privacy-first multimedia gallery, secure vault, and local Wi-Fi PC sync solution for Android.

---

### 🛡️ Security & Encryption Architecture
- **Hardware-Backed Cryptography**: Chunked envelope encryption combining AES-256-GCM with hardware-backed Android KeyStore (TEE / StrongBox).
- **In-Memory Streaming Decryption**: Range-based RAM-only decryption (`MediaDataSource`) extracts video and photo keyframes without writing unencrypted temporary files to flash storage.
- **Biometric & Master PIN Re-Wrapping**: Biometric authentication and cryptographic Master PIN re-wrapping without re-encrypting underlying media files.
- **Uninstall Survival & Zero Duplication**: Safe migration to `Documents/VaultSync/` with encrypted manifests (`vault_manifest.vsm`) enabling full vault recovery across app re-installs.
- **Dual Vault Recovery**: Recover access via Master PIN or an offline 128-bit cryptographic Recovery Key.
- **Secure File Shredding**: Two-stage trash bin with 30-day auto-purge and zero-fill shredding.

### 🎭 Camouflage & Stealth Features
- **Functional App Camouflage**: Disguise VaultSync as a fully working Calculator or Notes app.
- **Discreet Entry Triggers**: Secret multi-touch two-finger gestures, secret dial codes, or header long-press gateways to access the real vault interface.
- **Background Alias Synchronization**: Safe component enablement preventing launcher-induced OS process termination.

### 💻 Zero-Cloud Local PC Web Access
- **Local Wi-Fi Browser Portal**: Access and manage your vault from any desktop computer without internet access or third-party cloud servers.
- **Instant QR Pairing**: Point your phone camera to pair seamlessly with zero manual typing.
- **WakeLock & Wi-Fi Lock Protection**: Rock-solid background transfer service ensuring reliable large media streaming and bulk transfers.

### 📸 Premium Media Gallery Experience
- **Interactive Multi-Touch Zoom Grid**: Smooth pinch-to-zoom scaling from 2-column detailed view to 5-column overview.
- **Favorites & Starred Media**: Instant one-tap starring with persistent filter chips.
- **EXIF Metadata Privacy Stripper**: Inspect camera metadata (ISO, exposure, GPS) and share sanitized photos stripped of identifying locations.
- **Advanced Video Player**: Loop playback, 90° manual rotation, variable playback speed, aspect ratio toggling (Fit vs. Fill), and floating multi-window picture-in-picture (PiP).

---

### 📦 Release Asset Verification

- **APK File**: `VaultSync-v1.0.0.apk`
- **Package ID**: `com.vaultsync.app`
- **Target SDK**: Android 16 (API 37) / Min SDK: Android 8.0 (API 26)
- **Signer**: `CN=VaultSync, OU=Production, O=VaultSync, C=US` (4096-bit RSA)
- **Certificate SHA-256**: `1A:99:59:05:4D:3B:0E:F0:B0:B7:8B:32:3B:96:78:EC:4C:19:3B:38:23:1D:9A:08:88:0D:22:38:25:F9:BD:9D`
- **APK SHA-256**: `C5A1CC7EB4B8D738969E71112D06C883FC2B2DA223EF647B6692E9A06E93D1E1`
