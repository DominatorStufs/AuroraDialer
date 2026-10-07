# Aurora Dialer — OTA updates

In-app over-the-air updates: check → download → verify → install, with no Play Store and no manual
APK hunting.

```
 JobScheduler (12h)  ──▶ AuroraOtaJobService ──▶ AuroraOtaUpdater.checkForUpdate()
                                                    │  (manifest JSON)
                                                    ▼
                          update available? ──▶ AuroraOtaDownloadService (foreground, progress)
                                                    │  stream + SHA-256 verify + APK sanity check
                                                    ▼
                              notification "Update ready" ──▶ AuroraOtaInstallActivity ──▶ installer
```

## 0. Where the manifest comes from

Aurora Dialer has this project's feed built in — no setup on a fresh install:

```
stable  https://github.com/DominatorStufs/AuroraDialer/releases/latest/download/aurora-stable.json
beta    https://github.com/DominatorStufs/AuroraDialer/releases/latest/download/aurora-beta.json
dev     https://github.com/DominatorStufs/AuroraDialer/releases/latest/download/aurora-dev.json
```

Those are exactly the files every release of this repository carries — written by the release
workflow itself from the APK it just built, so nothing has to be edited after a build — so the check
works the moment the app is installed and keeps working for every release that follows. Stable runs
claim the `latest` slot; beta and dev runs are pre-releases, so `latest` never serves a beta. Typing a URL into *Settings → Software update → Update manifest URL* replaces
the built-in feed with that server (own or community) until the row is cleared again — the app talks
only to the URL it is given and hosts nothing itself.

## 1. The manifest

One small JSON file describes the newest build. Aurora's own format:

```json
{
  "schema": 1,
  "channel": "stable",
  "package": "com.aurora.dialer",
  "versionCode": 261003,
  "versionName": "26.10.3-Aurora",
  "minSdk": 30,
  "apkUrl": "https://github.com/DominatorStufs/AuroraDialer/releases/latest/download/AuroraDialer-26.10.3-261003-release.apk",
  "sha256": "7a0ae40cb5f56085ac3bab33bf1bea516d55169e8703fa1af49e38b7efcb8210",
  "sizeBytes": 18765432,
  "mandatory": false,
  "publishedAt": "2026-10-03T09:43:22Z",
  "changelog": "Caller ID engine + OTA updater",
  "releaseNotesUrl": "https://github.com/DominatorStufs/AuroraDialer/releases"
}
```

Fields marked *optional* can be omitted: `sha256` (verification is then skipped — not recommended),
`sizeBytes` (progress becomes indeterminate), `changelog`, `releaseNotesUrl`, `minSdk`, `mandatory`.

A **GitHub releases API URL** also works and is auto-detected — the app reads `tag_name`, the first
non-debug `.apk` asset and (when present) the asset digest:

```
https://api.github.com/repos/DominatorStufs/AuroraDialer/releases/latest
```

## 2. Generating the manifest

The release workflow writes all three channel files next to the APK on its own: it hashes the APK,
fills in the version, the size and the changelog (the `changelog` input, or the commit subject when
that is empty), points `apkUrl` at the release asset, and verifies the JSON it wrote before uploading
it. The
document it produces looks like this — any host serving the same JSON works, because the app only
fetches that one file:

```json
{
  "schema": 1,
  "channel": "stable",
  "package": "com.aurora.dialer",
  "versionCode": 261016,
  "versionName": "26.10.16-Aurora",
  "minSdk": 30,
  "apkUrl": "https://github.com/DominatorStufs/AuroraDialer/releases/latest/download/AuroraDialer-26.10.16-Aurora-261016-release.apk",
  "sha256": "…",
  "sizeBytes": 16031241,
  "mandatory": false,
  "publishedAt": "2026-10-03T17:06:00Z",
  "changelog": "…"
}
```

In CI (already wired into `.github/workflows/build.yml`): the **Generate OTA update manifest** step
runs on every build, writes `dist/aurora-<channel>.json`, uploads it as an artifact and attaches it
to the GitHub Release together with the APKs.

## 3. Where to host it

| Option | Manifest URL to put in the app | Notes |
| --- | --- | --- |
| GitHub Release asset (recommended) | `https://github.com/<owner>/<repo>/releases/latest/download/aurora-stable.json` | Always the newest release; no extra hosting. Enabled by the workflow. |
| GitHub API | `https://api.github.com/repos/<owner>/<repo>/releases/latest` | Simple, but unauthenticated requests are rate limited per IP. |
| GitHub Pages | `https://<owner>.github.io/<repo>/stable.json` | Nice when you want per-channel files in a repo. |
| Any web server / CDN / object storage | `https://updates.example.com/aurora/stable.json` | Most control; serve over HTTPS only. |

Channels: `stable`, `beta`, `dev` — one manifest per channel, selected in the app.

## 4. What the app does before installing

1. **Version check** — manifest `versionCode` (or the digits of `versionName`) must be greater than
   the installed build's; otherwise "Up to date".
2. **Package check** — a manifest for another package id is refused.
3. **minSdk check** — updates that need a newer Android are skipped with a clear message.
4. **Download** — streamed to `files/aurora_updates/`, resumable-ish (partial files are discarded on
   failure), progress in a notification.
5. **Verification** — SHA-256 must match the manifest, and the file must parse as an APK for
   `com.aurora.dialer` with the expected version code.
6. **Install** — `PackageInstaller` session via a `FileProvider` URI
   (`com.aurora.dialer.files`), falling back to a plain `ACTION_VIEW` installer intent.

### The one thing that cannot be automated
Android requires the user to confirm installation of an APK (and, on Android 8+, to allow
"Install unknown apps" for the installing app). For a **user-installed app** that confirmation is
unavoidable — the updater makes it one tap. A **privileged/system build** (as in `Android.bp`, where
the app is a system app) can install silently, and this code already uses the same
`PackageInstaller` path, so a ROM build gets the silent behaviour automatically.

## 5. Settings

Settings → **Software update**:

* *Check for updates automatically* — schedules/cancels the 12-hour `JobScheduler` job (persisted
  across reboots; also rescheduled by `AuroraOtaBootReceiver`).
* *Update manifest URL* — see section 3.
* *Update channel* — stable / beta / dev.
* *Download only on Wi-Fi* — the job requires an unmetered network and downloads are skipped on
  mobile data.
* *Install without asking* — starts the installer the moment a verified download completes.
* *Check for updates now* — immediate one-off check.
* Live status line: installed version, last check time and the last result.

## 6. Files added by this feature

| File | Purpose |
| --- | --- |
| `aurora/ota/AuroraOtaUpdater.java` | Manifest parsing, version logic, download + SHA-256 + APK validation, install |
| `aurora/ota/AuroraOtaJobService.java` | Periodic job, foreground download service, notifications, install result receiver, install activity |
| `aurora/ota/AuroraOtaEvents.java` | Live status/progress for the settings screen |
| `aurora/ota/AuroraOtaBootReceiver.java` | Re-schedules the job after boot / app update, cache housekeeping |
| `aurora/ota/AuroraOtaSettingsFragment.java` | Settings screen |

## 7. Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| "No update server configured" | Set the manifest URL in Settings → Software update. |
| "Cannot reach update server" | Bad URL / no network / private repo without a token (raw GitHub content and release assets are public). |
| "APK version mismatch" | The manifest's `versionCode` and the uploaded APK disagree — regenerate the manifest in CI rather than editing it by hand. |
| "Checksum mismatch" | A proxy/CDN mangled the download, or the APK was replaced after the manifest was generated. Re-run the release job. |
| Install does nothing | Allow "Install unknown apps" for Aurora Dialer, or install as a system app. |
| Stuck downloads | Old files are cleaned automatically after an update; `AuroraOtaUpdater.cleanupDownloads()` also removes leftovers at boot. |
