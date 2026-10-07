# Signing key

`aurora-release.jks` is the key every build of Aurora Dialer is signed with, so an installed copy can
always be replaced — by `adb install -r`, by the app's own OTA updater or by simply opening the new
APK — without uninstalling it first. Building on another machine, in CI or from a copy of the sources that is missing
this file is what makes the signature differ and forces an uninstall, which is why the key lives in
the repository.

| | |
|---|---|
| Store file | `keystore/aurora-release.jks` |
| Store password | `android` |
| Key alias | `androiddebugkey` |
| Key password | `android` |
| SHA-256 | `72:E3:7B:E9:43:C9:59:33:90:5C:5B:D2:D2:B4:E5:14:51:EE:84:76:34:99:BB:F3:93:1C:9B:AD:AA:51:16:18` |

This is a development-grade key (the same kind Android Studio generates for debug builds); it carries
no security value. To sign with a private key instead, set `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS` and `KEY_PASSWORD` in the environment — `build.gradle` prefers those over this file.
