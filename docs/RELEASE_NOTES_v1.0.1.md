# VaultSync v1.0.1 Release Notes 🚀

We are pleased to announce **VaultSync v1.0.1**, introducing in-player video brightness controls across all video playback surfaces (Main Gallery, Secret Vault, and Floating Popup Player), expanded audio format support, and enhanced storage reliability.

---

### ☀️ New Feature: Video Player Brightness Controls
- **Full-Screen Left Swipe Gesture**: In both the **Main Gallery** and **Secret Vault** video players, dragging vertically on the left side of the screen smoothly increases or decreases brightness with no interference to paging or zoom gestures.
- **On-Screen HUD Level Indicator**: Sleek vertical brightness bar with dynamic sun/moon icons and percentage feedback (`☀️ 85%`) displayed during adjustments.
- **Dedicated Controls Button & Slider**: A quick-access Brightness button in the playback control bar reveals a fine-grained slider and an **"Auto"** button to instantly restore system-preferred brightness.
- **Ultra-Night Dimming Scrim**: Soft dark scrim overlay for low brightness settings (< 20%) to enable comfortable viewing in pitch darkness.
- **Popup / Floating Player Brightness**: Full brightness control integrated directly into the floating Picture-in-Picture window, complete with an interactive slider, on-video dimming scrim, and percentage badge.
- **Safe Hardware Restoration**: Video player automatically clears brightness overrides and restores system defaults when closing or navigating away.

---

### 🎵 Audio & Import Reliability Enhancements
- **Extended Audio Format Support**: Added native detection, classification, and secure vault playback for `amr`, `3ga`, `m4r`, `aiff`, `caf`, `oga`, `mp2`, `mpga`, and `weba` audio files.
- **Stream Spooling**: Spools incoming SAF and MediaStore streams to verify exact disk byte sizes and validate available hardware storage before initiating AES-256-GCM chunked encryption.
- **Fault-Tolerant Folder Fallbacks**: Automatic fallback handling for root or deleted folder IDs during background imports.

---

### 📦 Release Asset Verification

- **APK File**: `VaultSync-v1.0.1.apk`
- **Package ID**: `com.vaultsync.app`
- **Version Code**: `2`
- **Version Name**: `1.0.1`
- **Target SDK**: Android 16 (API 37) / Min SDK: Android 8.0 (API 26)
- **Signer**: `CN=VaultSync, OU=Production, O=VaultSync, C=US` (4096-bit RSA)
- **Certificate SHA-256**: `1A:99:59:05:4D:3B:0E:F0:B0:B7:8B:32:3B:96:78:EC:4C:19:3B:38:23:1D:9A:08:88:0D:22:38:25:F9:BD:9D`
- **APK SHA-256**: `91019C2B3B98ED17D955B30E853D8C0ECE5D6AE33F55DDC1D8CA6689FA2A3D2A`
