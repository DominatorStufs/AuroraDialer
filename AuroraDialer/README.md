# Aurora Dialer

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Android API](https://img.shields.io/badge/API-30%2B%20(Android%2011--16)-3DDC84.svg)](https://github.com/DominatorStufs/AuroraDialer)

**Aurora Dialer** (`com.aurora.dialer`) is a modern, glassmorphic Android Phone & Dialer application adapted from the [Project-PenguinOS Dialer](https://github.com/Project-PenguinOS/penguin_packages_apps_Dialer) (built upon LineageOS and AOSP Dialer) and engineered to run seamlessly on **standard non-root Android devices** as well as custom ROM / system installations.

- **Repository**: [https://github.com/DominatorStufs/AuroraDialer](https://github.com/DominatorStufs/AuroraDialer)
- **Application ID / Package Name**: `com.aurora.dialer`
- **Minimum Android Version**: Android 11 (API 30)
- **Target / Compile SDK**: Android 16 (API 36)

---

## Key Features

- **Aurora In-Call Experience**: Animated Aurora gradient in-call background (`AuroraView`) with a refined glassmorphic call control sheet.
- **Glass UI & Real-Time Blur**: Translucent floating pill navigation bar and sticky search toolbar powered by `BlurView`.
- **Modern iOS-Inspired Dialpad**: Clean circular keypad buttons, smooth animations, and T9 smart-dial search.
- **Swipe-to-Call & Customizable Swipe Actions**:
  - Customize both **Swipe Right** and **Swipe Left** gestures on Call Log entries: *Voice Call*, *Open in WhatsApp* (direct chat without saving contact), *Open in Telegram*, *Send SMS*, *Video Call*, *Copy Number*, or *Delete*.
- **Smart Sensor Gestures**:
  - **Flip to Silence**: Place the phone face-down on a flat surface to silence incoming call ringtones with haptic confirmation.
  - **Auto-Speakerphone on Proximity**: Automatically switch to speakerphone when moving the phone away from your ear during an active voice call, and back to earpiece when held near your ear.
  - **Pocket Mode Ringer Boost**: Automatically boosts ringtone volume to maximum when the proximity sensor detects the phone is inside a pocket.
- **Auto Call Recording & Built-in Recordings Player**:
  - Automatic call recording with smart filters (*All Calls*, *Incoming Only*, *Outgoing Only*, *Unknown Numbers Only*, *Saved Contacts Only*).
  - Built-in **Call Recordings Manager & Audio Player** (`CallRecordingsActivity`) with date filter chips, seekbar playback, share, and delete controls.
- **Unknown & Spam Caller Protection**:
  - **Silence Unknown Callers**: Mute the ringer for incoming calls from numbers not saved in Contacts.
  - **Auto-Decline Private / Unsaved Numbers**: Automatically reject hidden/restricted caller IDs or all non-contact numbers with optional silent notifications.
- **Non-Root Device Compatibility**:
  - Works out of the box as a user-installed APK on normal, unrooted Android 11–16 devices.
  - Isolated `com.aurora.dialer.*` `ContentProvider` authorities so Aurora Dialer can be installed alongside the stock AOSP/OEM Dialer (`com.android.dialer` or `com.google.android.dialer`) without `INSTALL_FAILED_CONFLICTING_PROVIDER` errors.
  - Full `RoleManager` (`RoleManager.ROLE_DIALER`) and `TelecomManager` default dialer onboarding flow with automatic runtime permission prompts (`READ_CALL_LOG`, `READ_CONTACTS`, `CALL_PHONE`, `READ_PHONE_STATE`, `POST_NOTIFICATIONS`).
  - Safe fallbacks for privileged/system telephony APIs (`READ_PRIVILEGED_PHONE_STATE`, `MODIFY_PHONE_STATE`, `SystemProperties`, and `@SystemApi` network checks) so the app never crashes with `SecurityException` on non-root phones.
  - Multi-stage call recording audio source fallback (`VOICE_CALL` &rarr; `VOICE_RECOGNITION` &rarr; `VOICE_COMMUNICATION` &rarr; `MIC`) allowing call recording to function on non-root devices that restrict `VOICE_CALL` capture.
- **Dual Build System Support**: Includes both a standalone **Gradle** build configuration (`build.gradle`) with **GitHub Actions CI/CD** and an AOSP/LineageOS **Soong** configuration (`Android.bp`).

---

## Installation

1. Download the latest `AuroraDialer-*-release.apk` from the [GitHub Releases](https://github.com/DominatorStufs/AuroraDialer/releases) page or GitHub Actions artifacts. The APK is the only file you need — no source archive is attached to a release.
2. Install the APK on any Android 11+ (API 30+) device.
3. Open **Aurora Dialer**. When prompted, select **Aurora Dialer** as your **Default Phone App** and grant the requested Phone, Call Log, and Contacts permissions.

---

## Building from Source

### 1. Build via GitHub Actions (Recommended)

This repository includes a ready-to-use GitHub Actions workflow at [`.github/workflows/build.yml`](.github/workflows/build.yml).

**Nothing builds by itself.** Pushing code, uploading files or opening a pull request never starts a
build — the workflow has no `push`/`pull_request`/tag trigger at all. A build runs only when it is
started by hand *and* both version fields are typed in:

1. Go to the **Actions** tab in [https://github.com/DominatorStufs/AuroraDialer](https://github.com/DominatorStufs/AuroraDialer).
2. Select **Build Aurora Dialer APK (manual)** and click **Run workflow**.
3. Fill in the workflow inputs — `version_name` and `version_code` are **required**:
   - **`version_name`**: Supports numbers, dots, hyphens, and alphabetic characters (e.g., `26.8.25-Aurora`, `v1.0.0-beta2`, `2026.10.Aurora`).
   - **`version_code`**: Integer version code, must go up (e.g., `261014`). Anything else is rejected with a clear error.
   - **`changelog`**: Optional text that is attached to the release *and* shown on the phone's **Settings → Software update** screen.
   - **`build_type`**: `release` (default), `both`, or `debug`.
   - **`publish_release`**: Defaults to `true` — uploads everything below to a GitHub Release.
   - **`update_channel`**: `stable` / `beta` / `dev` — controls the generated `aurora-<channel>.json` OTA manifest.

Every run produces these, as build artifacts and — when publishing is on — as release assets:

| File | What it is |
|---|---|
| `AuroraDialer-<version>-<code>-release.apk` | the installable app — **the only download a user needs** |
| `aurora-stable.json`, `aurora-beta.json`, `aurora-dev.json` | the OTA manifests the phone's *Software update* screen reads |

No source archive is attached to a release. If you ever want one, `git archive --format=zip -o
source.zip HEAD` builds it locally: it packs the committed files only, so `.git` and every build
output are absent.

**The version you type in when starting the run is the only version input there is, and the manifests
are written from it automatically — nothing is edited by hand, ever.** The run reads the version back
out of the finished APK, stops with an error if it does not match what was typed, then hashes the APK,
fills in the size and the changelog (the `changelog` input, or the commit subject when that is left
empty), points `apkUrl` at the release asset, checks every file it wrote, and attaches all three
channel files to the release. A phone then only ever fetches
`https://github.com/<owner>/<repo>/releases/latest/download/aurora-<channel>.json`, which always
resolves to the newest release, so a new build is offered to every installed copy the moment it is
published.

Runs on the `beta` and `dev` channels are published as **pre-releases**, which keeps
`releases/latest` pointing at the newest *stable* build — otherwise a beta could be served to
everyone on the stable channel. Until the first stable release exists, `releases/latest` has nothing
to return, so a brand-new repository should publish one stable run first.

**Running the same version name and code again does not fail: it replaces the APK and the manifest of
that existing release in place**, so a rebuild of a shipped version can simply be re-uploaded.

#### Signing

Every build is signed with the key kept in [`keystore/aurora-release.jks`](keystore/README.md), which
is part of the repository on purpose: an APK can only replace an installed copy — through
`adb install -r`, the in-app updater, or by opening the new file — when both are signed with the
*same* key, and a key generated fresh on each machine breaks that. Passwords and alias are in
[`keystore/README.md`](keystore/README.md); it is a development-grade key with no security value.

To sign with a private key instead, use environment variables — `KEYSTORE_PATH`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` — which take precedence locally, or set the
repository secrets `AURORA_KEYSTORE_BASE64` (base64 of your `.jks`), `AURORA_KEYSTORE_PASSWORD`,
`AURORA_KEY_ALIAS` and `AURORA_KEY_PASSWORD` for GitHub Actions.

### 2. Build Locally with Gradle

#### Prerequisites
- JDK 17 or JDK 21
- Android SDK with **Platform 36** (`platforms;android-36`) and **Build-Tools 35.0.0** (`build-tools;35.0.0`)

#### Commands

```bash
# Build Release APK with default version (26.8.25-Aurora / 260825)
./gradlew assembleRelease

# Build Release & Debug APKs with custom versionName (alphabets supported) and versionCode
./gradlew assembleRelease assembleDebug \
  -PversionName="26.8.25-Aurora-RC1" \
  -PversionCode="260825"
```

The signed APKs will be generated at:
- `build/outputs/apk/release/AuroraDialer-release.apk` (~16 MB)
- `build/outputs/apk/debug/AuroraDialer-debug.apk` (~18 MB)

#### Optional Custom Keystore Signing
By default, `assembleRelease` signs with the Android debug key so the APK can be installed immediately. To sign with your own release keystore, set the following environment variables before running Gradle:

```bash
export KEYSTORE_PATH="/path/to/release.keystore"
export KEYSTORE_PASSWORD="your_store_password"
export KEY_ALIAS="your_key_alias"
export KEY_PASSWORD="your_key_password"
./gradlew assembleRelease -PversionName="1.0.0-Aurora" -PversionCode="100"
```

Workflow inputs: `version_name` and `version_code` (both required), plus optional `changelog`,
`build_type` (release/both/debug), `publish_release` (default `true`) and `update_channel`
(stable/beta/dev — controls the generated `aurora-<channel>.json` OTA manifest attached to the
release).

---

## OTA updates (built in)

- Periodic background check (`JobScheduler`, twice a day, Wi-Fi-only by default, survives reboots).
- Small JSON manifest — Aurora format **or** a GitHub releases API URL, auto-detected.
- Download is **SHA-256 verified** and the file is re-validated as an APK for `com.aurora.dialer`
  with the expected version code before the installer is opened.
- Channels: `stable` / `beta` / `dev`. Progress notification, one-tap install, optional
  install-immediately-after-download.
- The screen lists **what's new** in the build the last check found, right below *Check for updates
  now*: a preview line on the row itself and the complete changelog text (as published in the
  manifest / release body) in a scrollable dialog. It disappears again once the device is up to date.
- The GitHub Actions workflow now generates and publishes the manifest automatically, so devices can
  be pointed at
  `https://github.com/DominatorStufs/AuroraDialer/releases/latest/download/aurora-stable.json`.

Full documentation: **[docs/OTA.md](docs/OTA.md)**

### Enable it on a device

Nothing has to be configured: Aurora Dialer ships with this project's own update feed built in —
`https://github.com/DominatorStufs/AuroraDialer/releases/latest/download/aurora-stable.json` (the
beta and dev channels read `aurora-beta.json` / `aurora-dev.json` from the same place) — so *Settings
→ Software update → Check now* works on a fresh install, and it follows every release that is
published afterwards without anybody touching a file. Updates are on by default, Wi-Fi only.

Running your own or a community server instead? Type its URL into the **Update manifest URL** row;
the built-in feed is only used while that row is empty.

### Where the update manifest comes from

The workflow writes `aurora-<channel>.json` itself (the format is documented in
[docs/OTA.md](docs/OTA.md)); hosting a copy anywhere public and pointing the app's manifest URL at it
works just as well. Aurora Dialer ships no server of its own.

---

## Simulated call (built in, not listed in Settings)

A **simulated incoming call**: pick a caller name and number, an optional audio clip that plays once
the call is answered, keypad clips and how the call is timed — then let it arrive. It is placed
through the Android Telecom framework on the dialer's own phone account, so the ringing screen, the
ringtone, the vibration, the in-call timer, the audio routing and the call-log entry all come from
the platform and look exactly like a real call.

- **Timing**: either a countdown (right away, 10 s, 30 s, 1 min … 30 min) or an exact clock time
  picked in the phone's own time format (12-hour phones show AM/PM). A time that has already passed
  today means the call comes tomorrow. Delay is kept as an alarm, so nothing has to stay running and
  the call still arrives if the app is swiped away.
- **Audio**: one clip for after answering (optionally repeated until the call ends), a clip per
  keypad key (1–3), and optional microphone recording of the call, all in the app's private storage.
- **Presets**: up to three, holding the caller, the timing and the ring duration.
- **One-time setup**: Android keeps a phone account registered by a normal app switched off until
  the user turns it on — a third-party app cannot enable itself. The screen therefore shows a notice
  with an *Open calling accounts* button the first time; once the account is on, it stays on and
  every later call works straight away.
- The screen has no entry in Settings by design; it is reached from the About dialog through a
  gesture that is deliberately not written down anywhere in the app.

Implementation: `java/com/android/dialer/aurora/fakecall/` — ported from Phony, see credits below.

---

## Credits & Acknowledgments

Aurora Dialer stands on the shoulders of open-source work by the following projects and developers:

- **[Project-PenguinOS](https://github.com/Project-PenguinOS/penguin_packages_apps_Dialer)**: Original Celerity Dialer UI redesign, Aurora in-call view, glassmorphic navigation bar, BlurView integration, swipe-to-call gesture, and Android 16 adaptations by **[@jendermine](https://github.com/jendermine)**, **[@redducc](https://github.com/redducc)**, and the Project-PenguinOS team.
- **[The LineageOS Project](https://github.com/LineageOS/android_packages_apps_Dialer)**: Call recording engine, call statistics, forward/reverse lookup providers, sensitive number utilities, and extensive AOSP Dialer maintenance.
- **[The Android Open Source Project (AOSP)](https://source.android.com/)**: Core Android Dialer and InCallUI architecture.
- **[Phony](https://github.com/DDOneApps/Phony)** (GPL-3.0): some of its simulated-call features are built into this app — the screen is deliberately not listed in Settings. Its licence text is kept in [`licenses/PHONY-GPL-3.0.txt`](licenses/PHONY-GPL-3.0.txt).
- **[BlurView](https://github.com/Dimezis/BlurView)** by **[@Dimezis](https://github.com/Dimezis)**: Dynamic real-time view blur library for Android.
- **[Aurora Dialer](https://github.com/DominatorStufs/AuroraDialer)** maintained by **[@DominatorStufs](https://github.com/DominatorStufs)**: Standalone Gradle build system, non-root compatibility layer, provider authority isolation, and CI/CD automation.

---

## License

```text
Copyright (C) 2016-2024 The Android Open Source Project
Copyright (C) 2018-2025 The LineageOS Project
Copyright (C) 2025-2026 Project-PenguinOS
Copyright (C) 2026 Aurora Dialer Contributors
Copyright (C) 2019-2024 Prasad A. Wagle (simulated-call code ported from Phony, GPL-3.0)

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
