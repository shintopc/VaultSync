# VaultSync

**VaultSync** is a zero-cloud, hardware-encrypted, privacy-first multimedia gallery, secure vault, and local Wi-Fi PC sync solution for Android.

## Core Features & Architecture

- **Hardware-Backed Encrypted Vault**: Chunked AES-256-GCM envelope encryption with Android KeyStore (StrongBox/TEE).
- **In-Memory Streaming Video Thumbnail Decryption**: RAM-only range decryption via Android `MediaDataSource` extracts video keyframes without temporary unencrypted files or flash memory wear.
- **Persistent 250MB Coil Disk Caching**: Dedicated LRU persistent disk and memory cache for butter-smooth 60/120fps gallery and vault browsing.
- **Interactive Pinch-to-Zoom Gallery Grid**: Natural multi-touch pinch gesture dynamically scales grid density from 2 columns (large detailed previews) to 5 columns (compact high-density overview).
- **Favorites & Starred Media System**: One-tap starring in viewer, gold star thumbnail badges, and dedicated gallery header filter chip to instantly isolate favorite media.
- **EXIF Metadata Inspector & Privacy Stripper**: Inspect camera make, aperture, ISO, exposure, resolution, and GPS coordinates; share clean sanitized photos via `FileProvider` with zero metadata or location leaks.
- **Adaptive Enhanced Video Player**: Automatic landscape orientation sizing without shrinkage, responsive Fit vs. Fill (crop/zoom) toggle, continuous loop playback, 90° manual rotation, and variable speed control.
- **Local PC Sync & QR Code Instant Pairing**: Zero-cloud desktop browser access via local Wi-Fi with foreground service, CPU WakeLock, Wi-Fi Lock, and on-screen QR code for zero-typing automatic login.
- **Configurable Background Auto-Lock**: Granular background timeout preferences (Immediate, 30s, 1m, 5m, Never) to protect open vault sessions during multitasking.
- **Vector Material Icons Modernization**: Upgraded interface with official Compose vector icons across all gallery, viewer, vault, settings, and dialog controls.
- **Uninstall Survival & Shared Storage**: Crash-safe migration to `Documents/VaultSync/` with encrypted manifests (`vault_manifest.vsm`) and zero-duplication storage.
- **Dual Vault Recovery**: Auto-detects existing vaults on fresh installs and recovers access via Master PIN or 128-bit Recovery Key.
- **App Camouflage & Crash-Safe Stealth**: Disguise app as functional Calculator or Notes with background alias synchronization (prevents process termination), secret multi-touch gestures, and long-press settings vault gateway.
- **Complete Settings & Credentials Manager**: Manage/change Master PIN inside Settings with cryptographic VMK re-wrapping, direct vault shortcut, biometric KeyStore pairing, and PIN-protected recovery key viewing.
- **Floating Multi-Window PiP**: Resizable multi-instance picture-in-picture media viewer.
- **Two-Stage Secure Trash**: Isolated soft-delete staging with 30-day auto-purge and zero-fill shredding.

📖 **For complete documentation and usage instructions, see [Complete User Guide](docs/VAULTSYNC_COMPLETE_USER_GUIDE.md).**

## Local Environment

Verified local tooling during setup:

- Android Studio: Panda 4 / 2025.3.4
- Android SDK: `C:\Users\shint\AppData\Local\Android\Sdk`
- Installed platforms include API 37
- Installed build tools include 36.0.0 and 36.1.0
- JDK: Java 21 is installed locally, while the Android Gradle Plugin uses a
  Java 17-compatible toolchain target.

The project uses:

- Application ID: `com.vaultsync.app`
- Package root: `com.vaultsync`
- Minimum SDK: 26
- Target SDK: 37
- Compile SDK: 37
- Android Gradle Plugin: 9.2.1
- Gradle wrapper: 9.4.1
- Kotlin: 2.2.10

## Verification

From the project root:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

If Android SDK environment variables are not configured, set:

```powershell
$env:ANDROID_HOME = "C:\Users\shint\AppData\Local\Android\Sdk"
$env:ANDROID_SDK_ROOT = "C:\Users\shint\AppData\Local\Android\Sdk"
```
