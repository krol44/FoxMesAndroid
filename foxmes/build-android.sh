#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

version="$(sed -n 's/^APP_VERSION_NAME=//p' gradle.properties)"
version_code="$(sed -n 's/^APP_VERSION_CODE=//p' gradle.properties)"
package="$(sed -n 's/^APP_PACKAGE=//p' gradle.properties)"
[ -n "$version" ] && [ -n "$version_code" ] && [ -n "$package" ] || {
  echo "APP_VERSION_NAME, APP_VERSION_CODE and APP_PACKAGE must be set in gradle.properties" >&2
  exit 1
}

if [ "${FOXMES_REQUIRE_RELEASE_KEY:-}" = "1" ]; then
  for name in FOXMES_KEYSTORE_FILE FOXMES_KEYSTORE_PASSWORD FOXMES_KEY_ALIAS FOXMES_KEY_PASSWORD; do
    [ -n "${!name:-}" ] || { echo "$name is not set - refusing to build a release with the dummy key" >&2; exit 1; }
  done
  [ -f "$FOXMES_KEYSTORE_FILE" ] || { echo "FOXMES_KEYSTORE_FILE does not exist: $FOXMES_KEYSTORE_FILE" >&2; exit 1; }
fi

if [ -z "${ANDROID_HOME:-}" ] && [ -f local.properties ]; then
  ANDROID_HOME="$(sed -n 's/^sdk\.dir=//p' local.properties)"
fi
[ -n "${ANDROID_HOME:-}" ] || { echo "ANDROID_HOME is not set and local.properties has no sdk.dir" >&2; exit 1; }
export ANDROID_HOME
build_tools="$ANDROID_HOME/build-tools/36.0.0"

out="$ROOT/artifacts/android"
apk_name="FoxMes-$version-android.apk"
rm -rf "$out" "$ROOT/artifacts/android-mapping"
mkdir -p "$out" "$ROOT/artifacts/android-mapping"

echo "==> Building FoxMes Android $version ($version_code)"
./gradlew --console=plain --stacktrace :TMessagesProj_App:assembleAfatRelease

cp TMessagesProj_App/build/outputs/apk/afat/release/app.apk "$out/$apk_name"
cp TMessagesProj_App/build/outputs/mapping/afatRelease/mapping.txt \
  "$ROOT/artifacts/android-mapping/FoxMes-$version-mapping.txt"

echo "==> Checking $apk_name"
badging="$("$build_tools/aapt2" dump badging "$out/$apk_name" | head -1)"
echo "$badging"
expected="package: name='$package' versionCode='$((version_code * 10 + 9))' versionName='$version'"
case "$badging" in
  "$expected"*) ;;
  *) echo "Unexpected package identity, wanted: $expected" >&2; exit 1 ;;
esac

"$build_tools/apksigner" verify --print-certs "$out/$apk_name" | tee "$ROOT/artifacts/android-signing.txt"
cert_sha256="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$ROOT/artifacts/android-signing.txt")"
[ -n "$cert_sha256" ] || { echo "apksigner printed no signer certificate" >&2; exit 1; }
echo "$cert_sha256" > "$ROOT/artifacts/android-signing-sha256.txt"

release_cert="25ffdf1fa1e86763dfd7d23f9d5a9eee298407f078e5f7d7338f53a205481def"
if [ "${FOXMES_REQUIRE_RELEASE_KEY:-}" = "1" ] && [ "$cert_sha256" != "$release_cert" ]; then
  echo "The APK is signed with $cert_sha256, not the FoxMes release certificate $release_cert" >&2
  exit 1
fi

echo
echo "Built $out/$apk_name"
echo "Signing certificate SHA-256: $cert_sha256"
