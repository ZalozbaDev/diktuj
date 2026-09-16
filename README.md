# Sorbisch

Online Sorbian speech recognition and text-to-speech for Android 6.0 or newer.
A voice keyboard, Android recognition service and system TTS engine; models run
on the server, not the phone. The UI is German with an English fallback.

## Install

Open **Actions > Debug APK**, select a successful run and download the
`sorbisch-debug` artifact. Extract the ZIP and install `app-debug.apk` on the
phone, allowing installation from that source when asked. Builds run on pushes,
pull requests and manually through **Run workflow**.

Grant microphone permission in the app. Use **Spracheingabe einrichten** to enable
the voice keyboard in Android, then select it with the adjacent input-method
button. The keyboard icon lets you switch back to another keyboard. Google apps
may use their own recognizer instead of the selected system service.

For reading aloud, select Sorbisch as the preferred engine in Android's
text-to-speech settings. Then enable **Select to Speak** in Android's accessibility
settings and use its shortcut to read text on screen. No separate app is required.
Other apps that use Android's system text-to-speech engine can also use Sorbisch.

Debug APKs are for testing. Fresh CI runners generate different debug signing
keys, also different from local builds. If Android rejects an update because
signatures differ, uninstall the old app first (this removes saved settings).
Production updates require a stable release signing key, not included here.

## Configure

Edit [server-config.properties](server-config.properties) before building. This
file is the sole source of packaged defaults; all listed keys are required:

```properties
recognizerUrl=wss://webcaptioner-spoz.mudrowak.de/
recognizerSampleRate=48000
continuousRecognition=true
recognitionLanguages=hsb
ttsUrl=https://bamborakapi.mudrowak.de/api/tts/
ttsProtocol=bamborak
ttsSpeakerId=korla
```

Users can override these under **Erweiterte Einstellungen > Speichern**. Saved
values survive upgrades and take precedence over new APK defaults. The language
list controls available choices, not the server's models.

Recognition uses Vosk-style WebSocket JSON: `config` with `sample_rate` and
`language`, mono PCM16 binary audio, then `{"eof":1}` on Stop. The URL also gets
a `lang` parameter. Recording supports 16 or 48 kHz; the tested Mudrowak endpoint
needed 48 kHz. **Bis Stopp aufnehmen** collects server-finalized phrases until
Stop, showing them with the current partial. Otherwise the first final ends
dictation. Text containing `whisper` is filtered case-insensitively to suppress
server banners; this also filters actual dictation containing that word.

Bamborak receives `text`, `speaker_id`, `format: "wav"` and must return WAV bytes.
Its `/api/fetch_speakers/` endpoint lists voice IDs. `ttsProtocol=simple` instead
sends `text` and `language`. Authentication headers are not implemented.

Audio and text leave the device for the configured services. Default services
are externally operated; check their usage terms, availability and retention
policy. Use WSS/HTTPS publicly; cleartext is supported for local development.
No server software, model weights or recordings are included in this repository.

## Android Integration

- [SorbianVoiceInputMethodService](app/src/main/java/de/adrianzimmermann/sorbianonlinespeech/SorbianVoiceInputMethodService.java) implements Android's [InputMethodService](https://developer.android.com/reference/android/inputmethodservice/InputMethodService) and inserts recognized text through `InputConnection`.
- [OnlineRecognitionService](app/src/main/java/de/adrianzimmermann/sorbianonlinespeech/OnlineRecognitionService.java) implements [RecognitionService](https://developer.android.com/reference/android/speech/RecognitionService), mapping server events to Android callbacks and preserving caller microphone attribution.
- [SorbianTtsService](app/src/main/java/de/adrianzimmermann/sorbianonlinespeech/SorbianTtsService.java) implements [TextToSpeechService](https://developer.android.com/reference/android/speech/tts/TextToSpeechService), requesting speech over HTTP and streaming decoded PCM to Android.

[AndroidManifest.xml](app/src/main/AndroidManifest.xml) registers the services.
The app, keyboard and recognition service share [OnlineSpeechSession](app/src/main/java/de/adrianzimmermann/sorbianonlinespeech/OnlineSpeechSession.java)
(`AudioRecord` and OkHttp), [RecognitionTranscript](app/src/main/java/de/adrianzimmermann/sorbianonlinespeech/RecognitionTranscript.java)
(partial/final handling) and [SpeechSettings](app/src/main/java/de/adrianzimmermann/sorbianonlinespeech/SpeechSettings.java)
(`SharedPreferences`). Both TTS paths use [ExternalTtsClient](app/src/main/java/de/adrianzimmermann/sorbianonlinespeech/ExternalTtsClient.java).
Architectural references: [Koenele](https://github.com/Kaljurand/K6nele) and
[vosk-android-service](https://github.com/alphacep/vosk-android-service).
This is a separate implementation, not a fork or dependency of either.

## Development

Use JDK 17 and Android SDK platform 34/build-tools 35.0.0, with `ANDROID_HOME` set,
or open the project in Android Studio:

```sh
./gradlew :app:assembleDebug :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On macOS/Linux, [scripts/android-dev.sh](scripts/android-dev.sh) wraps common tasks:

```sh
./scripts/android-dev.sh avds           # List virtual devices created in Android Studio
./scripts/android-dev.sh emulator NAME # Start one; keep this terminal open
# In another terminal, once Android has booted:
./scripts/android-dev.sh run            # Build, install and open
./scripts/android-dev.sh test           # App tests and lint
./scripts/android-dev.sh test-tts       # Bamborak integration tests only
./scripts/android-dev.sh logs           # Logs from the current app process
```

Set `ANDROID_SERIAL` when multiple devices are connected. The helper does not
create virtual devices, configure microphone forwarding, or restart a closed emulator.
Run it without arguments for all commands. Windows users can use `gradlew.bat`
and `adb` directly.

The [workflow](.github/workflows/debug-apk.yml) installs prerequisites and uploads
the APK; it does not run an emulator or contact speech services. With an emulator,
run `./gradlew :app:connectedDebugAndroidTest`. For manual cross-app checks,
see the [test client](recognition-client/README.md).
`BamborakIntegrationTest` lives in `app/src/androidTest`: Gradle packages these
tests in a separate instrumentation APK, not in the end-user app. It has no
launcher UI. The `recognition-client` module is a different, standalone test app
with its own UI and UID, used to check calls from another app.
Tests use scripted server responses. macOS emulator microphone forwarding has
shown intermittent failures; verify real audio on a physical device too.

## License

Original project code: [MIT](LICENSE). Third-party code retains its own licenses;
see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). This license does not cover
external models or grant access to hosted services.
