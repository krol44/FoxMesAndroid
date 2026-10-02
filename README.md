# FoxMes Android

FoxMes Android is built on the Telegram for Android core.

## Installing the APK

FoxMes Android 1.0.4 is distributed through [GitHub Releases](https://github.com/krol44/FoxMesAndroid/releases) rather than Google Play. Before installing, verify the downloaded file against `SHA256SUMS` from the same GitHub release:

```bash
sha256sum --check --ignore-missing SHA256SUMS
```

Then open `FoxMes-1.0.4-android.apk` on the phone. Android asks once to allow installing apps from the browser or file manager you opened it with: select **Settings → Allow from this source** and go back to finish the installation.

Every release is signed with the same FoxMes key, so a newer APK installs over the old one and keeps your data. The SHA-256 fingerprint of the signing certificate is:

```
25:FF:DF:1F:A1:E8:67:63:DF:D7:D2:3F:9D:5A:9E:EE:29:84:07:F0:78:E5:F7:D7:33:8F:53:A2:05:48:1D:EF
```

Check it with `apksigner`, which prints the same digest in lower case without colons:

```bash
apksigner verify --print-certs FoxMes-1.0.4-android.apk
```

If Android reports that the package conflicts with an existing one, a build signed with a different key is installed — uninstall it first.

## Updates

FoxMes checks for a new version once an hour and shows an **Update FoxMes** bar at the bottom of the chat list when one is released. It downloads the APK from GitHub Releases, verifies its SHA-256, package name and signing certificate, and then opens the Android installer. The installation itself is always confirmed by you in the system dialog; the first time, Android asks to allow FoxMes to install apps.

## Building from source

You will need Android Studio, JDK 17 or 21, Android SDK 36 and Android NDK 27.2.12479018.

```bash
git clone --recursive --shallow-submodules https://github.com/krol44/FoxMesAndroid.git
cd FoxMesAndroid
```

If the `--recursive` flag was forgotten, run `git submodule update --init --recursive --depth=1`.

Build a debug APK, install it on a device or emulator and launch it with the logs captured:

```bash
./dev-client.sh              # against the local FoxMes services
./prod-client.sh             # against production
./dev-client.sh --help       # all options
```

A release APK is built by `foxmes/build-android.sh`, either directly or in Docker:

```bash
docker build -t foxmes-android .
docker run --rm -v "$PWD":/home/source foxmes-android
```

The packages land in `artifacts/android`. Without `FOXMES_KEYSTORE_FILE` the APK is signed with the public dummy key from `TMessagesProj/config` — fine for a local check, never for publishing. Releases are built and published by the GitHub workflow, see `.github/workflows/foxmes-release.yml`.

## License

The source code is licensed under GNU GPL v2 or later. See [LICENSE](LICENSE) and [LEGAL](LEGAL). Telegram for Android copyright, license notices, and required upstream attribution are retained.
