# Manual Recognition Client

This module is a separate APK with its own UID. It has no dependency on the
Sorbisch application code and accesses recognition only through Android's
`SpeechRecognizer` API. It is a development tool, not part of the end-user APK.

Build and install on a connected Android device or emulator, alongside Sorbisch:

```bash
./gradlew :recognition-client:assembleDebug
adb install -r recognition-client/build/outputs/apk/debug/recognition-client-debug.apk
adb shell am start -n de.adrianzimmermann.speechclient/.MainActivity
```

The test app has buttons for explicit and default service selection.
It needs microphone permission and a reachable recognizer configured in Sorbisch.

On the Android 14 demo emulator, the default provider can be selected for testing
with:

```bash
adb shell settings put secure voice_recognition_service \
  de.adrianzimmermann.sorbianonlinespeech/de.adrianzimmermann.sorbianonlinespeech.OnlineRecognitionService
```

Record the previous value first with `adb shell settings get secure voice_recognition_service`,
and restore it afterward. The explicit-selection
button does not require changing the system default. Vendor settings and apps
that explicitly choose another recognizer are unaffected by the default setting.
