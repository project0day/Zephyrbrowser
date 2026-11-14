# Zephyr Browser

<div align="center">

[![GitHub release](https://img.shields.io/github/v/release/project0day/Zephyrbrowser)](https://github.com/project0day/Zephyrbrowser/releases)
[![License: GPL-3.0](https://img.shields.io/github/license/project0day/Zephyrbrowser)](./LICENSE)
[![Android](https://img.shields.io/badge/Android-7.0%2B-%233DDC84)](https://www.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-%237F52FF)](https://kotlinlang.org)
[![Build](https://github.com/project0day/Zephyrbrowser/actions/workflows/build.yml/badge.svg)](https://github.com/project0day/Zephyrbrowser/actions/workflows/build.yml)

**A privacy-focused Android browser built entirely in Kotlin with System WebView.** No proprietary rendering engines, no telemetry, no bloatware. Just fast, clean browsing.

</div>

---

## Features

| Area | Highlights |
|---|---|
| **Search** | Google, Yandex, DuckDuckGo, Bing, Brave, Startpage, Ecosia — real favicon logos in the search bar, no text labels |
| **Privacy** | Incognito mode, per-site appearance overrides, cookie / history management, ad-blocking engine |
| **Downloads** | Built-in download manager with torrent support, filterable history, per-file actions |
| **Bookmarks & History** | Swipe-to-delete rows, persistent open-link delivery (avoids lost intents under panel), two feed layouts |
| **VPN** | WireGuard + AmneziaWG dual-backend; import `.conf` profiles directly from storage |
| **QR Scanner** | CameraX + ZXing, flashlight toggle, Wi-Fi credential parsing (SSID/password copy one-tap) |
| **Voice Input** | Offline speech recognition via Vosk (no Google services required), works on microG devices |
| **PDF Viewer** | In-app PDF text extraction and AcroForm field editing (Apache PDFBox port) |
| **AI Assistant** | Integrated AI chat panel; configurable model endpoints via Swarm settings |
| **Home Page** | Quick-access tile grid, recents, bookmarks feeds, live clock variant, custom HTML page support |
| **Theming** | 18 Material 3 color themes + incognito purple gradient; per-site dark/sepia override without DOM pollution |
| **Ad Block** | ABP-compatible rule matcher (`^` separator, `||` anchors, `$domain`, generic selectors) with auto-updating filter lists |

## Tech Stack

- **Language:** Kotlin (mixed with vendored Java for AmneziaWG VPN engine)
- **UI:** Android Views + Material 3 components (no Jetpack Compose)
- **Architecture:** Single-process, `AppCompatActivity` + fragment panels, Room DB for persistence, DataStore for preferences
- **WebView:** Android System WebView via `androidx.webkit`
- **Networking:** OkHttp-backed requests for filters, VPN configs, downloads
- **Build:** AGP 8.4.2 · Gradle 8.10 · KSP (not KAPT) · R8 shrinking

## Requirements

- Android 7.0 (API 24) minimum
- Android 14 (API 34) target
- ~40 MB installed size (arm64-v8a + armeabi-v7a native libs included)

## Building

### Prerequisites

```bash
# Install Android SDK command-line tools or use Android Studio's bundled SDK
sdkmanager "platforms;android-34" "build-tools;34.0.0"
```

### Build unsigned release APK

```bash
git clone https://github.com/project0day/Zephyrbrowser.git
cd Zephyrbrowser
chmod +x ./gradlew
./gradlew assembleRelease
```

The unsigned release APK lands at:

```
app/build/outputs/apk/release/app-release.apk
```

### Sign locally (optional)

Place a `keystore/keystore.properties` file pointing to your JKS:

```properties
storeFile=../my-release-key.jks
storePassword=your-store-password
keyAlias=my-key-alias
keyPassword=your-key-password
```

Then rebuild — the signing config is read automatically.

## Project Layout

```
app/src/main/
├── java/com/zephyr/browser/
│   ├── ui/              # Home page, tab switcher, camera/AI activities
│   ├── panels/          # Bookmarks, history, downloads, passwords panels
│   ├── proxy/           # WireGuard + AmneziaWG tunnel managers
│   ├── settings/        # Preference fragments (all categories)
│   ├── viewer/          # Photo/video/PDF viewers
│   └── ai/              # AI assistant sheet
├── res/
│   ├── drawable/        # Material-outline icon set (110+ icons)
│   └── values/          # Strings, themes, colors
└── AndroidManifest.xml
```

## License

GPL-3.0 — see [LICENSE](./LICENSE) for details.

## Credits

- Icon assets sourced from [Google Material Design Icons](https://github.com/google/material-design-icons)
- VPN tunnel backed by [WireGuard Go](https://github.com/wireguard/wg-go) and [AmneziaWG](https://github.com/amnezia-vpn/amnezia-wg)
- Voice powered by [Vosk](https://alphacephei.com/vosk/)
- QR scanning via [ZXing](https://github.com/zxing/zxing)

---

*Zephyr Browser — lightweight, private, yours.*

| | |
|---|---|
| Package | `com.zephyr.browser` |
| Version | **1.7.23** (versionCode 37) |
| SDK | minSdk 24 (Android 7.0) · targetSdk/compileSdk 34 (Android 14) |
| Stack | Kotlin 1.9.24 · AGP 8.4.2 · Gradle 8.7 (wrapper included) · KSP · Room · DataStore · Material 3 · ViewBinding |
| UI languages | Français · English · Russian |
| Theme | Material 3 with an Emerald accent, 18 themes + custom accent color, purple incognito gradient |

## Build

Requires **JDK 17+** (reference version — Temurin 21) and the **Android SDK** (platform 34, build-tools 34.0.0).

1. Create `local.properties` with the path to your SDK (Android Studio will create it automatically when you open the project):
   ```properties
   sdk.dir=/path/to/android-sdk
   ```
   It is not included in the archive because it is machine-specific.
2. Build:
   ```bash
   ./gradlew assembleDebug    # debug
   ./gradlew assembleRelease  # release (signed using keystore/ files)
   ```
   The first run downloads Gradle 8.7 and dependencies (internet access required).
3. The APKs will be located in `app/build/outputs/apk/`. Reference APKs for versions 1.2.1–1.3.2 are in `release/`.

## Release signing

- `keystore/keystore.properties` — passwords and alias (inside the file)
- `keystore/zephyr.keystore` — the key itself

All versions 1.3.x–1.4.x are signed with this key and can be installed over one another **without data loss**.  
Versions 1.2.1 and older were signed with a different, lost key. Upgrading from those versions requires uninstalling the app first  
(export bookmarks as HTML and passwords as CSV beforehand).

## Code structure

```text
app/src/main/java/com/zephyr/browser/
├── MainActivity.kt          # browser window: omnibox, menu, tabs, downloads, dialogs
├── TabManager.kt            # tabs (up to 32), LRU WebView unloading, session restoration
├── AdBlockEngine.kt         # EasyList-compatible parser (options, exceptions, element hiding)
├── AdBlockManager.kt        # list catalog, protection levels, counters
├── BrowserClient.kt         # request interception (blocking), WebView client
├── ChromeClient.kt          # geolocation/camera permissions, progress indicator
├── DownloadEngine.kt        # custom downloader with notifications
├── VaultCrypto.kt           # AES-256-GCM with Android Keystore (passwords)
├── Prefs.kt / SearchEngines.kt / Urls.kt / FaviconLoader.kt / JsBridge.kt / Scripts.kt
├── db/                      # Room: bookmarks+folders, history, passwords, scripts, quick sites,
│                            # downloads, filter lists, per-domain settings, autofill
├── ui/                      # screens: MenuSheets (menus), HomePageView, TabSwitcherView, suggestions
├── panels/                  # screens: History, Downloads, Bookmarks, Passwords, Scripts, Autofill
├── settings/                # all settings screens (+ proxy, adblock, site settings)
├── proxy/                   # HTTP proxy, Orbot/TOR, I2P (ProxyController), .ovpn import
├── player/                  # music/video player: codecs, audio focus, PiP
└── widget/                  # search widget + configurator
```

Resources: `app/src/main/res/` (themes, 49 vector icons, adaptive launcher icon, ru/en/fr strings).  
Icon specification: `ICONS_CONTRACT.md` (in the root directory).

## Context for further development (why 1.3.1 was “crashing”)

1. **“Settings won’t open” / options crashing.** The `allowMainThreadQueries` flag was lost from Room during project restoration → every synchronous query threw an `IllegalStateException`: wrapped calls left lists empty, while unwrapped calls crashed the screen. In 1.3.2, the flag was restored (as in 1.2.1).
2. **Startup crash in 1.3.0/1.3.1.** The omnibox suggestion panel was attached to `contentStage` using unsafe LayoutParams. Fixed (type-safe attachment).
3. **Main technical debt:** migrate synchronous Room calls to coroutines/main-safety — `allowMainThreadQueries` is currently a temporary workaround (as in the reference version 1.2.1). This is the first refactoring candidate.

## Not included in the archive

- `local.properties` — machine-specific (see above), created by the IDE
- `app/build/`, `.gradle/` — regenerable build artifacts
- Everything else is included: source code, resources, `.git/` (history), `keystore/`, `release/` (10 APKs), `gradle/wrapper/`, build logs `build_out.txt` / `build_debug.txt`
