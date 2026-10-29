# VaultSync Stealth, Camouflage & Main App Access Architecture

This document specifies the technical design, security guarantees, user experience, and lockout recovery mechanisms for VaultSync's stealth camouflage, hidden launcher icon, and two-finger gesture entry for **accessing the MAIN APP (VaultSync Gallery)**.

---

## 1. Core Security Guarantees & Purpose

> [!IMPORTANT]
> **Front-Door Camouflage for the Main App**
> The camouflage, hidden launcher icon, and secret two-finger gesture operate at the **application boundary** to protect the entire application (photos, videos, media browser, vault, and PC sync) against unauthorized access, snooping, or casual inspection:
> 1. Anyone opening the device sees a harmless Calculator or Notes app.
> 2. The main VaultSync gallery is completely inaccessible until the owner performs the secret authentication action (two-finger swipe or secret PIN trigger).
> 3. Hiding or disguising the app icon **never replaces or weakens**:
>    - Authenticated hardware-backed AES-256-GCM key derivation and verification.
>    - Mandatory PIN or biometric credential verification.
>    - Zero-knowledge local storage architecture.
> Even if someone discovers the disguised app or activates a secret gesture, **the main app cannot be unlocked without valid credentials**.

---

## 2. Modes of Operation

### 2.1 Normal Mode
- **Launcher Alias**: `com.vaultsync.MainActivityNormal`
- **Application Label**: `VaultSync`
- **App Icon**: Standard VaultSync gallery and shield icon (`@mipmap/ic_launcher`)
- **Main App Access**:
  - By default, opens directly into the media gallery (`MediaScreen`).
  - Users can enable **"Require Secret Access on Launch (App Lock)"** in Settings to require PIN/biometric authentication or secret two-finger gesture before revealing the gallery.

### 2.2 Disguised: Calculator Mode
- **Launcher Alias**: `com.vaultsync.MainActivityCalculator`
- **Application Label**: `Calculator`
- **App Icon**: Dedicated calculator adaptive icon (`@mipmap/ic_launcher_calculator`)
- **Functional Disguise**: Launches a fully functional AMOLED dark calculator supporting standard decimal arithmetic (`+`, `-`, `×`, `÷`, `%`, `±`, `AC`). To anyone else, it is a 100% genuine calculator.
- **Main App Access Pathways**:
  - **Secret PIN Trigger**: Entering the secret master PIN followed by `=` evaluates against the wrapped VMK credential verifier using constant-time comparison. If correct, the VMK is unwrapped and the **MAIN APP (Media Gallery)** opens immediately. If incorrect or if standard math is calculated (e.g. `12 + 34 = 46`), normal math results are displayed with zero giveaway.
  - **Two-Finger Gesture**: Performing the configured two-finger gesture (e.g. swipe down) anywhere on the calculator screen reveals the **Secret App Entry Dialog** to authenticate via Biometrics or PIN and unlock the **MAIN APP**.

### 2.3 Disguised: Notes Mode
- **Launcher Alias**: `com.vaultsync.MainActivityNotes`
- **Application Label**: `Notes`
- **App Icon**: Dedicated memo pad adaptive icon (`@mipmap/ic_launcher_notes`)
- **Functional Disguise**: Presents a clean, modern note-taking facade with realistic sample notes ("Weekend Grocery List", "Meeting Notes - Sprint Planning", "Home Maintenance").
- **Main App Access Pathways**:
  - **Two-Finger Gesture**: Dragging two fingers down or up anywhere on the notes list triggers the **Secret App Entry Dialog** to authenticate and unlock the **MAIN APP**.
  - **Discreet Header Action**: Tapping the discreet edit action opens the secret entry dialog.

### 2.4 Hidden Mode
- **Launcher Aliases**: All launcher aliases (`MainActivityNormal`, `MainActivityCalculator`, `MainActivityNotes`) are set to `COMPONENT_ENABLED_STATE_DISABLED`.
- **System Footprint**: The application has no icon in the system launcher or app drawer.
- **Main App Access**: Accessing via secret dialer code (`*#*#82858#*#*`), deep link (`vaultsync://open`), or system settings opens the **Secret App Entry Screen**, requiring PIN or Biometrics to unlock the **MAIN APP**.
- **Mandatory Pre-flight Safeguard**: Before activating Hidden mode, an un-dismissable warning dialog educates the user on recovery routes and requires explicit confirmation.

---

## 3. Secret Two-Finger Gesture Detector

The gesture detector (`com.vaultsync.core.stealth.TwoFingerGestureDetector`) is implemented as a Jetpack Compose pointer input modifier (`Modifier.detectTwoFingerVaultGesture(...)`):

- **Non-Intrusive**: Single-finger touches and vertical scrolling in galleries and lists are completely unhindered (`activePointers.size == 1` passes through).
- **Simultaneous Multi-Touch Tracking**:
  - **Swipe Down**: Detects when two pointers touch down simultaneously and both exceed a downward vertical threshold (`> 100.dp`) with minimal horizontal drift.
  - **Swipe Up**: Detects when two pointers touch down and move upward (`< -100.dp`).
  - **Double Tap**: Detects when two fingers touch and release twice in rapid succession (`< 450ms`).
- **Configurable**: Users can choose their preferred gesture or disable it in **Settings → App Camouflage & Access Security**.
- **In-App Testing**: An interactive live testing box in the settings modal allows users to practice the gesture and verify tactile feedback.

---

## 4. Multi-Layer Lockout Recovery Routes

To guarantee that users can never be permanently locked out when running in Hidden mode or after forgetting their disguise settings, VaultSync provides four separate, documented recovery pathways:

| Recovery Route | Mechanism | Details |
| :--- | :--- | :--- |
| **Route 1: Browser Deep Link** | `vaultsync://open`<br>`vaultsync://recover` | Handled by `MainActivity` via browsable intent filters. Can be opened from Chrome, browser URL bars, notes apps, or messaging apps. |
| **Route 2: Secret Dialer Code** | `*#*#82858#*#*`<br>(`82858` = `VAULT`)<br>`*#*#1234#*#*` | Handled by `SecretCodeReceiver` listening for `android.provider.Telephony.SECRET_CODE`. Launches the app with high-priority flags. |
| **Route 3: Android App Info** | System Settings | Navigating to **Settings → Apps → VaultSync → Open** launches `MainActivity` directly. |
| **Route 4: Recovery Banner** | In-App Safeguard | When launched via recovery link or dialer code, a prominent recovery banner appears at the top displaying the current mode and offering a single-tap **[Restore Icon]** button. |

---

## 5. Locking and Disguising Back

- **Quick Action Button**: Inside the media gallery top bar and overflow menu, tapping the **"Lock & Disguise" (🧮 / 🔒)** button immediately locks the session and returns to the Calculator/Notes facade.
- **Auto-Lock on Background**: In [MainActivity.kt](file:///d:/vaultsync/app/src/main/java/com/vaultsync/MainActivity.kt), minimizing or switching away from the app automatically relocks the session so that reopening the app presents the disguise facade again.
