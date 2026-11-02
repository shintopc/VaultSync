# VaultSync v1.0.3 Release Notes 🛡️

We are proud to announce **VaultSync v1.0.3**, a major security, resiliency, and multimedia engineering release. This version introduces an advanced in-vault trash bin, plausible deniability with duress decoy PINs, silent intruder camera capture, direct in-memory video decryption, one-click portable backup archives, intelligent storage deduplication, and emergency deep-scan data salvage.

---

### 🗑️ 1. In-Vault Recycle Bin (Trash & Auto-Purge)
- **Safe Staging**: Deleted encrypted vault files are safely staged in the isolated in-vault trash bin instead of being permanently unlinked immediately.
- **Dedicated Trash Tab**: Access all deleted vault items via the **Trash** tab in the top navigation bar, featuring dynamic remaining retention countdowns (e.g., `"29 days left"`).
- **Restore & Shred Actions**: Restore individual items back to their active vault folders with original metadata preserved, or permanently shred them immediately.
- **One-Tap Empty Trash**: Instantly overwrites and purges all trashed encrypted blobs from physical storage.
- **Automated Expired Purge**: Background auto-purge runs seamlessly on vault unlock to shred entries older than 30 days without UI stutter.
- **Database Schema Migration**: Room database safely migrated to version 2 (`MIGRATION_1_2`) with indexed trash columns, preserving all existing vault entries without data loss.

---

### 🎭 2. Duress / Decoy PIN (Coercion Protection)
- **Plausible Deniability**: Configure a secondary, independent Decoy PIN via Vault Settings (`"Set Decoy PIN"`).
- **Authentic Dummy Vault**: Entering the Decoy PIN unlocks the app into a fully authentic, clean, and empty dummy vault state.
- **Complete Isolation**: Real encryption keys (VMK) and real encrypted `.vsf` blobs are never loaded or unwrapped, preventing data exposure under duress.

---

### 📸 3. Break-In Attempt Logger & Intruder Snapshot
- **Silent Camera2 Capture**: Automatically triggers a silent front-facing camera capture after 3 consecutive failed PIN attempts (when camera permission is granted) with zero shutter sound or screen flicker.
- **Security Audit Log**: Review failed break-in attempts with exact timestamps and high-resolution intruder snapshots directly from the Vault menu (**Break-In Logs**).
- **Log Management**: Clear intruder logs with a single tap once reviewed.

---

### ⚡ 4. Direct In-Memory Video Decryption via Media3 `DataSource`
- **Zero Disk Write Wear**: Replaced legacy temporary video cache writing with `VaultMediaDataSource`, a custom Jetpack Media3 `BaseDataSource` implementation.
- **RAM Streaming & Chunk Seeking**: ExoPlayer streams decrypted AES-256-GCM chunks directly into memory buffers with instantaneous multi-second seek times.
- **Enhanced Privacy & Flash Longevity**: Eliminates unencrypted `.mp4` file creation on disk, protecting sensitive video playback against forensic file carving while sparing device flash storage.

---

### 💾 5. One-Click Encrypted Portable Backup (`.vsbackup`)
- **Single Portable Archive**: Export your complete vault (`manifest.vsm`, `.vsf` blobs, and `backup_metadata.json`) into a single compressed `.vsbackup` archive via Android Storage Access Framework (SAF).
- **One-Tap Restore**: Restore complete vaults onto fresh installations, secondary devices, or external SD cards with automatic verification.
- **Hardware-Protected Storage**: Backup files remain completely encrypted with your hardware-backed Vault Master Key.

---

### 🧹 6. Storage Optimizer & Deduplication Engine
- **Two-Tier Scanner**: Rapid $O(N)$ file size grouping followed by 4KB sample SHA-256 hashing detects exact duplicate files across all vault folders in seconds.
- **Interactive Cleanup Dialog**: Displays duplicate clusters, individual file locations, and total wasted storage in MB/GB.
- **One-Tap Clean All Duplicates**: Automatically retains the oldest original file and moves redundant duplicates to the in-vault Trash Bin for easy review or permanent shredding.

---

### 🛠️ 7. Emergency Deep Scan Raw Blob Salvage & SAF Custom Directory Picker
- **Emergency Raw Blob Salvage**: Deep-scans storage directories for unindexed `.vsf` blobs even when the vault manifest is missing or damaged, extracting original filenames and headers.
- **SAF Custom Directory Picker**: Select any folder across internal storage, external SD cards, or OTG drives using Android's system document picker.
- **Crash-Resilient Manifest Synchronization**: Background manifest writes feature atomic flush buffers, write-lock isolation, and automatic fallback to `manifest.vsm.bak`.
- **Lockout Recovery Fixes (Routes 1 & 2)**:
  - **Route 1 (Universal Web Gateway)**: Created `open.html` and `index.html` for instant one-tap browser launching, bypassing mobile browser address bar search overrides, and added web intent filters for `shintopc.github.io` and `vaultsync.app`. Added a direct **Test Launch** button in the dialog.
  - **Route 2 (Modern Android Telephony Secret Code)**: Added `android.telephony.action.SECRET_CODE` intent filters alongside legacy actions, implemented high-priority heads-up recovery notifications (`vaultsync_recovery`) to overcome Android 10+ background activity start restrictions, and added automatic icon un-hiding upon valid recovery trigger. Added a direct **Test Code** button in the dialog.

---

### 📦 Release Asset Verification

- **APK File**: `app-release.apk`
- **Package ID**: `com.vaultsync.app`
- **Version Code**: `4`
- **Version Name**: `1.0.3`
- **Target SDK**: Android 16 (API 37) / Min SDK: Android 8.0 (API 26)
- **Signer**: `CN=VaultSync, OU=Production, O=VaultSync, C=US` (4096-bit RSA)
- **APK SHA-256**: `F89024FA8173C11625C7AB30299BE6AD9894A7FADC3FE557650FE06CC9D526D7`
- **Test Suite**: 134 automated unit tests passing (100% pass rate)

