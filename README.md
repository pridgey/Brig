# Brig

Your lock screen, your rules. Brig is a fully customizable Android lock screen built with Jetpack Compose and a Material 3 Expressive look: morphing shapes, springy motion, and big bold type.

Built for a Pixel 8 Pro. It should work on any Android 10+ phone.

## Features

**Look**
- Five clock styles: Stacked, Digital, Analog, Words ("It's quarter past ten"), and Minimal. Digits roll like an odometer when they change.
- Animated backgrounds: Aurora (drifting light), Sky (follows the time of day), Starfield, a solid color, or your own photo with blur
- Accent color from your wallpaper (Material You) or a fixed color. It themes the settings app too.
- Second time zone, clock size, dim level, and an option to hide the status bar

**At a glance**
- Date, next alarm, battery (pulses while charging), weather (Open-Meteo, no API key), daily unlock and wake stats
- Calendar agenda, media controls (album art, plus a play button that morphs shape), and a quote of the day
- Widgets can be reordered and toggled one by one

**Notifications**
- Expand, open, swipe to dismiss, and reply without unlocking
- Hide all content globally, or set each app to show, hide content, or block
- Choose whether silent notifications show, and how many appear

**Unlock & gestures**
- Swipe, an extra Brig PIN (PBKDF2-hashed), or a math challenge
- Map swipe up/down/left/right and double-tap to: unlock, camera (opens over the lock without unlocking), flashlight, screen off, Do Not Disturb, or any app
- Fingerprint and system unlock dismiss Brig automatically, so you only unlock once

**Quick actions**
- Up to six app shortcuts in cookie-shaped badges
- Flashlight, Do Not Disturb and ringer toggles that morph shape when on

**Profiles (automation)**
- Triggers: time window (can wrap past midnight), charging, charging during a window, headphones connected
- Effects: bedside mode (dim red clock only), hide notifications, hide content, clock style, accent, dim
- Defaults: "Bedside" (charging from 10pm to 7am, on), "Work privacy", and "Listening"

**Maintenance**
- Built-in updater: checks GitHub Releases every 12 hours, downloads the update, and installs it through PackageInstaller
- Export, import, or reset settings as JSON

## How it works

Android doesn't let apps replace the real lock screen, so Brig draws **on top of** it:

1. An **accessibility service** listens for the screen turning off and opens `LockActivity`. Accessibility services are exempt from Android's background-launch limits, which makes this reliable. If the service is off, a foreground service with a full-screen-intent notification takes over as a fallback.
2. `LockActivity` uses `setShowWhenLocked(true)`, so it sits above the system keyguard.
3. Unlocking calls `KeyguardManager.requestDismissKeyguard()`. If you have a system PIN or fingerprint, Android asks for it. Unlocking with your fingerprint directly also closes Brig (`ACTION_USER_PRESENT`).

**Security:** Keep a system PIN or fingerprint. Brig's PIN and math gates are a convenience layer. The Home button can leave any app, including Brig, and the system keyguard underneath is what protects the phone.

## Install

1. Download the newest `Brig-1.N.apk` from [Releases](https://github.com/pridgey/Brig/releases) on the phone and install it.
2. Open Brig and work through **Setup**:
   - **Lock trigger:** Settings → Accessibility → Brig lock trigger
   - **Notification access**
   - If either toggle is greyed out, open App info → ⋮ → **Allow restricted settings**, then try again. Android does this for every sideloaded app.
   - The optional items (calendar, Do Not Disturb, install updates, unrestricted battery) turn on the matching features.
3. Turn Brig on, then tap **Preview lock screen**.

After that, updates show up in the app and as a notification.

## Development

```sh
./gradlew assembleDebug        # debug build (package dev.pridgey.brig.debug, installs side by side)
./gradlew testDebugUnitTest    # unit tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17+ and the Android SDK (compileSdk 35).

### Releases & signing

Every push to `main` runs `.github/workflows/release.yml`. It runs the tests, builds a signed release APK with `versionCode = run number`, and publishes it as release `v1.<run>`. The in-app updater reads the trailing number of the tag.

Every release must be signed with **the same key**, or updates won't install over each other. The key is stored in these repository secrets:

| Secret | Contents |
|---|---|
| `BRIG_KEYSTORE_B64` | base64 of the `.jks` keystore |
| `BRIG_KEYSTORE_PASSWORD` | keystore password |
| `BRIG_KEY_ALIAS` | key alias (`brig`) |
| `BRIG_KEY_PASSWORD` | key password (same as the keystore password) |

Keep a backup of the keystore. If it's lost, everyone has to uninstall and reinstall.

### Layout

```
app/src/main/java/dev/pridgey/brig/
├── BrigApp.kt              app init, settings repository
├── data/                   settings model (JSON in DataStore), profiles engine, apps/calendar/weather/stats
├── lock/                   LockActivity + controller, lock screen UI, clocks, backgrounds, PIN/math gates
├── service/                accessibility trigger, notification listener + media, fallback service, boot receiver
├── update/                 GitHub release parser, downloader, PackageInstaller, periodic check worker
├── ui/                     settings app (theme, components, screens)
└── util/                   device helpers, expressive shapes/motion, PIN hashing, word clock, etc.
```

## Ideas for later

- Shizuku mode for deeper system tweaks without root
- An LSPosed module that changes the real SystemUI lock screen (rooted phones)
- Intruder selfie after failed PIN attempts
- More widget types and community theme packs
