# Brig

Your lock screen, your rules. Brig is a fully customizable Android lock screen built with Jetpack Compose and a Material 3 Expressive look: morphing shapes, springy motion, and big bold type.

Built for a Pixel 8 Pro. It should work on any Android 10+ phone.

## Features

**Look**
- 14 clock styles, plus a **clock builder**: stack text and shape layers with any font, weight, color, size, position and rotation. Start from a template, then share your design as a `brig-clock:` code anyone can paste in.
- Backgrounds: Aurora, Sky (follows the time of day), Starfield, a solid color, your own photo, or a looping video
- **Photo sources** that rotate on a schedule: your own picks, "On this day" from your library, an Immich album, NASA's picture of the day, Bing daily, Unsplash (your key), or a subreddit
- **Depth effect**: the photo's subject is cut out on-device (ML Kit) and drawn in front of the clock
- Photo effects (Mono, Noir, Duotone…), Ken Burns drift, vignette and grain
- **Album art background** while music plays, and **match the photo**: the accent color comes from your background photo or the album art
- **Tilt parallax**, **weather effects** (rain, snow, storms, fog that match the forecast), and **adapt to surroundings** (dimmer and warmer in the dark, clearer in sunlight)
- Accent color from your wallpaper (Material You) or a fixed color

**At a glance**
- A date line that **says what's next**: "Standup in 25 min", "Leave in 10 min for Dentist"
- Glance chips: date, next alarm, battery, weather, air quality, UV index, pollen (Europe only), daily stats
- **Alarm card** with countdown and Skip (asks your clock app to dismiss the next alarm)
- **Hourly weather** with rain alerts ("Rain in 15 min")
- Calendar, media controls, quote of the day
- **Steps & activity** rings from Health Connect
- **Sports scores** for the teams you follow (ESPN): live, final and next game
- **Packages**: picked up from shipping notifications, or added by tracking number
- **On this day**: photos from today's date in past years
- **Mood check-in**: one tap a day, with a month of colored dots and a streak
- **Word of the day**, or your own flashcards (front | back), flipped with a tap
- Widgets can sit at the top or bottom, in any order

**Notifications**
- Grouped by app, with **smart priority**: people and conversations first, promos and low-priority ones folded into a Quiet stack
- **Conversation view**: message notifications open as chat bubbles with an inline reply box
- **Live activities**: timers, downloads, rides and navigation with live progress
- **Glance mode**: only app icons until you tap
- **Code card**: one-time codes shown big for 5 minutes; copying waits until you unlock
- Hide content globally or per app; per-app Always on top, Quiet, or Block

**Unlock & gestures**
- Swipe, an extra Brig PIN (PBKDF2-hashed), or a math challenge
- Map swipes and double-tap to unlock, camera, flashlight, screen off, Do Not Disturb, or any app
- **Guest mode**: hides personal widgets and only shows the apps you allow; leaving it needs your PIN or system lock
- Fingerprint and system unlock dismiss Brig automatically

**Profiles (automation)**
- Triggers: time window, charging, charging during a window, headphones, **daytime / night** (sunrise and sunset for your weather city, with an offset)
- **Bedtime nudge**: a gentle note plus a dimmer, warmer screen after bedtime; Brig can suggest a bedtime from when you usually put the phone down
- Effects: bedside mode, hide notifications or content, clock style, accent, dim, background, and a photo of its own

**Setup & maintenance**
- A **setup assistant** on first run: permissions, then a starter layout (Clean, Productive, Photo, Night owl)
- **Diagnostics** (Permissions → "Brig not showing up?"): checks every moving part with one-tap fixes, and a log that flags screen-offs where Brig didn't open
- Built-in updater from GitHub Releases; export, import or reset settings as JSON

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
