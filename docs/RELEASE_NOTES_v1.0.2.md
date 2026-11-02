# VaultSync v1.0.2 Release Notes 🚀

We are thrilled to announce **VaultSync v1.0.2**, delivering a comprehensive **Premium UI/UX Overhaul**. This release transforms VaultSync into a sleek, modern, and cohesive dark-mode experience inspired by top-tier privacy and media applications.

---

### 🎛️ 1. Interactive Rotary Brightness Dial
- **270° Rotary Knob**: Replaced generic horizontal sliders with a high-precision, Canvas-rendered circular dial in both the **Enhanced Video Player** and **Floating Popup Player**.
- **Intuitive Touch Controls**: Drag rotationally with real-time radial angle tracking (`atan2`), instant percentage feedback in the center, and a quick "Auto" button to restore system defaults.
- **Micro-Haptics**: Subtle haptic clicks on 5% brightness step thresholds for tactile confirmation.
- **Ambient Sweep Gradient**: Beautiful illuminated arc glow with dynamic status indicators (`Auto` / `Manual` / `Dim`).

---

### 🎬 2. Video Player & Floating Window Polish
- **Full Vector Icon Transformation**: Replaced all legacy unicode emojis and text glyphs (`"⏸"`, `"▶"`, `"⏪"`, `"⏩"`, `"🔊"`, `"🔁"`, `"☀️"`, `"⛶"`) with crisp, hardware-rendered `Icons.AutoMirrored.Rounded.*` and `Icons.Rounded.*` vectors.
- **Inline Speed Pill Carousel**: Removed intrusive modal dialogs in favor of a horizontally-scrollable speed selector (`0.25x` to `2.0x`) smoothly expanding within the controls sheet.
- **Dynamic Seek Badges**: Cinematic rewind/fast-forward HUD pill chips with icons and elapsed second counts.
- **Tactile Button Press Haptics**: Light haptic impact added across playback controls, loop toggles, and screen orientation switches.
- **Floating Window Glassmorphism & Resize Grip**: Subtle translucent framing, vector transport actions, and Canvas diagonal corner grip lines for window resizing.

---

### 🔐 3. Secret Vault & Lock Screen Experience
- **Vector Lock & Shield Badges**: Upgraded lock screens and stealth entry dialogs with illuminated, high-contrast security iconography.
- **Numeric Keypad Icons**: Backspace, fingerprint biometric trigger, and recovery action buttons upgraded to crisp vector graphics.
- **Smooth Animated Tab Indicator**: Vault album and audio tabs now glide seamlessly across categories using `animateDpAsState`.
- **Material 3 Dropdown & Context Menus**: All vault actions (`Move`, `Floating Window`, `Play`, `Edit`, `Copy`, `Info`, `Export`, `Delete`) now feature dedicated leading vector icons.
- **Polished Media Grid & Lists**: Vector category thumbnails, illuminated audio waveform containers, and smooth multi-select checkmarks.

---

### ⚙️ 4. Unified Settings, Stealth & Trash Surfaces
- **Sectional Icon System**: Every settings tile and toggle across Security, Biometrics, Storage, Maintenance, Wi-Fi Server, and Emergency Routes now features a dedicated, beautifully styled vector icon.
- **Security Toggles**: Password & PIN visibility toggles upgraded from `"👁️"`/`"🙈"` to vector `Visibility` and `VisibilityOff`.
- **Trash Screen Overhaul**: Multi-item batch recovery and permanent deletion with vector `Restore` and `DeleteForever` actions.
- **Emergency Stealth Suite**: Calculator disguise, Notes disguise, and secret entry dialogs refined with consistent vector styling.

---

### 🖼️ 5. Dynamic Album Preview Layout Fix
- **Eliminated Blank Grey Squares**: Fixed an issue where folders containing fewer than 3 media files displayed blank grey placeholder boxes. Albums with 1 file now cleanly render only that 1 thumbnail (maintaining proportional 1/3 row aspect ratio), albums with 2 files display only 2 thumbnails, and empty folders render an elegant folder icon.

---

### 📦 Release Asset Verification

- **APK File**: `app-release.apk`
- **Package ID**: `com.vaultsync.app`
- **Version Code**: `3`
- **Version Name**: `1.0.2`
- **Target SDK**: Android 16 (API 37) / Min SDK: Android 8.0 (API 26)
- **Signer**: `CN=VaultSync, OU=Production, O=VaultSync, C=US` (4096-bit RSA)
- **Certificate SHA-256**: `1A:99:59:05:4D:3B:0E:F0:B0:B7:8B:32:3B:96:78:EC:4C:19:3B:38:23:1D:9A:08:88:0D:22:38:25:F9:BD:9D`
- **APK SHA-256**: `CEEFF55FDD463120867B97A8A762434164E7AC043971E31988E5DB63B2C94484`

