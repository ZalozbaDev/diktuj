#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
PACKAGE=de.adrianzimmermann.sorbianonlinespeech
APK=app/build/outputs/apk/debug/app-debug.apk
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
ADB="${SDK:+$SDK/platform-tools/adb}"
ADB="${ADB:-adb}"
EMULATOR="${SDK:+$SDK/emulator/emulator}"
EMULATOR="${EMULATOR:-emulator}"

usage() {
    printf '%s\n' \
        'Usage: scripts/android-dev.sh COMMAND [ARGUMENT]' \
        '  build          Build the debug APK' \
        '  run            Build, install and open on a connected device' \
        '  install        Install the existing APK and open it' \
        '  open           Open the installed app' \
        '  test           Run all app instrumentation tests and lint' \
        '  test-tts       Run only BamborakIntegrationTest' \
        '  devices        List connected devices' \
        '  avds           List installed Android virtual devices' \
        '  emulator NAME  Start that virtual device in the foreground' \
        '  logs           Follow logs from the running app' \
        'Set ANDROID_HOME or put SDK tools on PATH. Use ANDROID_SERIAL to select a device.'
}

require_device() {
    "$ADB" get-state >/dev/null
    if [[ -z "${ANDROID_SERIAL:-}" ]]; then
        export ANDROID_SERIAL="$("$ADB" get-serialno)"
    fi
}

open_app() {
    "$ADB" shell am start -n "$PACKAGE/.MainActivity"
}

install_app() {
    if [[ ! -f "$APK" ]]; then
        printf '%s\n' "No APK found. Run 'build' first." >&2
        exit 1
    fi
    "$ADB" install -r "$APK"
    open_app
}

case "${1:-help}" in
    help|-h|--help) usage ;;
    build) ./gradlew :app:assembleDebug ;;
    run) require_device; ./gradlew :app:assembleDebug; install_app ;;
    install) require_device; install_app ;;
    open) require_device; open_app ;;
    test) require_device; ./gradlew :app:connectedDebugAndroidTest :app:lintDebug ;;
    test-tts)
        require_device
        ./gradlew :app:connectedDebugAndroidTest \
            -Pandroid.testInstrumentationRunnerArguments.class="$PACKAGE.BamborakIntegrationTest"
        ;;
    devices) "$ADB" devices ;;
    avds) "$EMULATOR" -list-avds ;;
    emulator)
        if [[ $# != 2 ]]; then usage >&2; exit 2; fi
        exec "$EMULATOR" -avd "$2" -no-snapshot-load
        ;;
    logs)
        require_device
        pid="$("$ADB" shell pidof -s "$PACKAGE" | tr -d '\r' || true)"
        if [[ -z "$pid" ]]; then
            printf '%s\n' "App is not running. Run 'open' first." >&2
            exit 1
        fi
        exec "$ADB" logcat --pid="$pid"
        ;;
    *) usage >&2; exit 2 ;;
esac
