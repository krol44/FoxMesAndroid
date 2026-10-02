#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

GRADLE_TASK=":TMessagesProj_App:assembleAfatDebug"
APK_DIR="TMessagesProj_App/build/outputs/apk/afat/debug"
APK_DIR_ABI="TMessagesProj_App/build/intermediates/apk/afat/debug"
LOG_DIR="${FOXMES_LOG_DIR:-/tmp/foxmes-android-logs}"
SYSLOG_PID_FILE="$LOG_DIR/system-log.pid"
APPLOG_PID_FILE="$LOG_DIR/app-log.pid"
DEV_PORTS=(7034 5173 80 443)

DO_BUILD=1
DO_LAUNCH=1
ALL_ABIS=0
KEEP_GOING=0
FOLLOW=0
STOP=0
TARGET=""
AM_ARGS=()

while [ $# -gt 0 ]; do
    case "$1" in
        --no-build) DO_BUILD=0 ;;
        --build-only) DO_LAUNCH=0 ;;
        --all-abis) ALL_ABIS=1 ;;
        --keep-going) KEEP_GOING=1 ;;
        --follow) FOLLOW=1 ;;
        --stop) STOP=1 ;;
        device|emulator) TARGET="$1" ;;
        -h|--help) sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        --) shift; AM_ARGS=("$@"); break ;;
        *) echo "Unknown option: $1 (see --help)" >&2; exit 2 ;;
    esac
    shift
done

if [ "$TARGET" = device ]; then
    export FOXMES_ENV=prod
    unset FOXMES_URL FOXMES_WEB_URL
fi

log() { printf '\033[1;36m[dev-client]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[dev-client]\033[0m %s\n' "$*" >&2; exit 1; }

mkdir -p "$LOG_DIR"


if [ -z "${ANDROID_HOME:-}" ]; then
    sdk_dir="$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null | tail -1 || true)"
    if [ -n "$sdk_dir" ]; then
        ANDROID_HOME="$sdk_dir"
    elif [ -d "$HOME/Library/Android/sdk" ]; then
        ANDROID_HOME="$HOME/Library/Android/sdk"
    fi
fi
[ -n "${ANDROID_HOME:-}" ] && export ANDROID_HOME

if [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
    ADB="$ANDROID_HOME/platform-tools/adb"
else
    ADB="$(command -v adb || true)"
fi

require_sdk() {
    [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ] ||
        fail "Android SDK not found (set ANDROID_HOME or sdk.dir in local.properties)"
}

require_adb() {
    [ -n "$ADB" ] || fail "adb not found (install platform-tools or set ANDROID_HOME)"
}

require_java() {
    if [ -z "${JAVA_HOME:-}" ]; then
        JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17 2>/dev/null || true)"
    fi
    [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ] ||
        fail "JDK 17 or 21 not found (set JAVA_HOME)"
    export JAVA_HOME
}

package_name() {
    local apk="$1" aapt2
    aapt2="$(ls -d "${ANDROID_HOME:-/nonexistent}"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1 || true)"
    if [ -n "$aapt2" ] && [ -f "$apk" ]; then
        "$aapt2" dump packagename "$apk" 2>/dev/null && return
    fi
    echo "$(sed -n 's/^APP_PACKAGE=//p' gradle.properties).beta"
}

find_apk() {
    ls -t "$APK_DIR"/*.apk "$APK_DIR_ABI"/*.apk 2>/dev/null | head -1 || true
}


connected_devices() {
    "$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1 }'
}

target_devices() {
    case "$TARGET" in
        device) connected_devices | grep -v '^emulator-' || true ;;
        emulator) connected_devices | grep '^emulator-' || true ;;
        *) connected_devices ;;
    esac
}

fail_no_phone() {
    local unauthorized
    unauthorized="$("$ADB" devices | awk 'NR > 1 && $2 == "unauthorized" { print $1 }')"
    if [ -n "$unauthorized" ]; then
        fail "Phone $unauthorized has not allowed USB debugging: unlock it and tap \"Allow\" in the \"Allow USB debugging?\" dialog (tick \"Always allow from this computer\"), then run again"
    fi
    fail "No phone found by adb. On the phone: Settings > About phone > tap \"Build number\" 7 times; then Settings > System > Developer options > turn on \"USB debugging\"; reconnect the cable (a data cable, USB mode \"File transfer\"), unlock the phone and allow debugging for this computer"
}

boot_emulator() {
    require_sdk
    local emulator="$ANDROID_HOME/emulator/emulator" avd
    [ -x "$emulator" ] || fail "No device connected and no emulator in $ANDROID_HOME/emulator"
    avd="${AVD_NAME:-$("$emulator" -list-avds 2>/dev/null | head -1)}"
    [ -n "$avd" ] || fail "No device connected and no AVD found (create one or set AVD_NAME)"
    log "Booting emulator $avd -> $LOG_DIR/emulator.log"
    nohup "$emulator" -avd "$avd" > "$LOG_DIR/emulator.log" 2>&1 < /dev/null &
    disown 2>/dev/null || true
    "$ADB" wait-for-device
    local _
    for _ in $(seq 1 180); do
        [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && return
        sleep 1
    done
    fail "Emulator $avd did not finish booting (see $LOG_DIR/emulator.log)"
}

resolve_device() {
    require_adb
    if [ -n "${ANDROID_SERIAL:-}" ]; then
        "$ADB" -s "$ANDROID_SERIAL" get-state >/dev/null 2>&1 ||
            fail "Device $ANDROID_SERIAL is not connected"
        return
    fi
    local devices count
    devices="$(target_devices)"
    count="$(printf '%s' "$devices" | grep -c . || true)"
    if [ "$count" = 0 ]; then
        [ "$TARGET" = device ] && fail_no_phone
        boot_emulator
        devices="$(target_devices)"
        count="$(printf '%s' "$devices" | grep -c . || true)"
    fi
    [ "$count" = 1 ] || fail "Several devices connected ($(echo $devices)): use ./dev-client.sh device or emulator, or pick one with ANDROID_SERIAL"
    export ANDROID_SERIAL="$devices"
}

stop_capture() {
    local pid_file
    for pid_file in "$APPLOG_PID_FILE" "$SYSLOG_PID_FILE"; do
        if [ -f "$pid_file" ]; then
            kill "$(cat "$pid_file")" 2>/dev/null || true
            rm -f "$pid_file"
        fi
    done
}

if [ "$STOP" = 1 ]; then
    stop_capture
    if [ -n "$ADB" ] && [ "$(connected_devices | grep -c . || true)" != 0 ]; then
        resolve_device
        "$ADB" shell am force-stop "$(package_name "$(find_apk)")" || true
    fi
    log "Stopped app and log capture"
    exit 0
fi


if [ "$DO_LAUNCH" = 1 ]; then
    resolve_device
    log "Device $ANDROID_SERIAL"
fi

if [ "$DO_BUILD" = 1 ]; then
    require_sdk
    require_java
    GRADLE_ARGS=("$GRADLE_TASK" --console=plain)
    if [ "$ALL_ABIS" = 0 ] && [ -n "${ANDROID_SERIAL:-}" ]; then
        abi="$("$ADB" shell getprop ro.product.cpu.abi | tr -d '\r')"
        [ -n "$abi" ] && GRADLE_ARGS+=("-Pandroid.injected.build.abi=$abi")
    fi
    [ "$KEEP_GOING" = 1 ] && GRADLE_ARGS+=(--continue)

    log "Building $GRADLE_TASK -> $LOG_DIR/build.log"
    if ! ./gradlew "${GRADLE_ARGS[@]}" 2>&1 | tee "$LOG_DIR/build.log"; then
        fail "Build failed (errors: grep -nE '^e: |error:|FAILED' $LOG_DIR/build.log)"
    fi
fi

APK="$(find_apk)"
[ -n "$APK" ] || fail "No built APK in $APK_DIR (run without --no-build)"

[ "$DO_LAUNCH" = 1 ] || { log "Build done: $APK"; exit 0; }


PACKAGE="$(package_name "$APK")"

if ! "$ADB" install -r -d -t "$APK" > "$LOG_DIR/install.log" 2>&1; then
    cat "$LOG_DIR/install.log" >&2
    if grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE "$LOG_DIR/install.log"; then
        fail "Installed $PACKAGE has another signature; 'adb uninstall $PACKAGE' (wipes the login) and retry"
    fi
    fail "Install failed"
fi
log "Installed $PACKAGE"

if [ "${FOXMES_ENV:-dev}" != prod ] && [ -z "${FOXMES_URL:-}" ]; then
    for port in "${DEV_PORTS[@]}"; do
        "$ADB" reverse "tcp:$port" "tcp:$port" >/dev/null || fail "adb reverse tcp:$port failed"
    done
    log "Forwarded device ports ${DEV_PORTS[*]} to this machine (adb reverse)"
fi

EXTRAS=(--es foxmes_env "${FOXMES_ENV:-dev}")
[ -n "${FOXMES_URL:-}" ] && EXTRAS+=(--es foxmes_url "$FOXMES_URL")
[ -n "${FOXMES_WEB_URL:-}" ] && EXTRAS+=(--es foxmes_web_url "$FOXMES_WEB_URL")
case "${FOXMES_DISABLED:-}" in
    1|true|yes|on) EXTRAS+=(--ez foxmes_disabled true) ;;
esac
"$ADB" shell am broadcast -f 0x20 -n "$PACKAGE/org.telegram.messenger.foxmes.FoxMesDebugReceiver" \
    "${EXTRAS[@]}" > "$LOG_DIR/environment.log" 2>&1 || { cat "$LOG_DIR/environment.log" >&2; fail "Could not set the FoxMes environment"; }

ACTIVITY="$("$ADB" shell cmd package resolve-activity --brief -a android.intent.action.MAIN \
    -c android.intent.category.LAUNCHER "$PACKAGE" | tr -d '\r' | tail -1)"
case "$ACTIVITY" in
    */*) ;;
    *) fail "No launcher activity for $PACKAGE" ;;
esac

stop_capture
rm -f "$LOG_DIR/app.log" "$LOG_DIR/system.log"
"$ADB" logcat -c || true
nohup "$ADB" logcat -v threadtime > "$LOG_DIR/system.log" 2>&1 < /dev/null &
echo $! > "$SYSLOG_PID_FILE"

"$ADB" shell am start -S -W -n "$ACTIVITY" \
    -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
    ${AM_ARGS[@]+"${AM_ARGS[@]}"} > "$LOG_DIR/launch.log" 2>&1 ||
    { cat "$LOG_DIR/launch.log" >&2; fail "am start failed"; }

APP_PID=""
for _ in $(seq 1 20); do
    APP_PID="$("$ADB" shell pidof -s "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"
    [ -n "$APP_PID" ] && break
    sleep 0.5
done
[ -n "$APP_PID" ] || { tail -40 "$LOG_DIR/system.log" >&2; fail "App did not start (see $LOG_DIR/system.log)"; }

nohup "$ADB" logcat -v threadtime --pid="$APP_PID" > "$LOG_DIR/app.log" 2>&1 < /dev/null &
echo $! > "$APPLOG_PID_FILE"
disown -a 2>/dev/null || true

log "Launched $PACKAGE (pid $APP_PID, FOXMES_ENV=${FOXMES_ENV:-dev})"
log "Logs: $LOG_DIR/app.log  $LOG_DIR/system.log"
log "Crashes: grep -nE 'FATAL EXCEPTION|Fatal signal|ANR in' $LOG_DIR/system.log"
log "FileLog: adb pull /sdcard/Android/data/$PACKAGE/files/logs"

if [ "$FOLLOW" = 1 ]; then
    tail -n +1 -f "$LOG_DIR/app.log"
fi
