package de.adrianzimmermann.sorbianonlinespeech;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

final class SpeechSettings {
    static final String DEFAULT_SERVER_URL = BuildConfig.DEFAULT_RECOGNIZER_URL;
    static final String DEFAULT_TTS_URL = BuildConfig.DEFAULT_TTS_URL;
    private static final String FALLBACK_LANGUAGE = "hsb";
    private static final String[] AVAILABLE_LANGUAGES = parseLanguages(
            BuildConfig.SUPPORTED_RECOGNITION_LANGUAGES);
    static final String DEFAULT_LANGUAGE = AVAILABLE_LANGUAGES[0];

    static int sampleRate(Context context) {
        int rate = prefs(context).getInt("recognizer_sample_rate", BuildConfig.DEFAULT_RECOGNIZER_SAMPLE_RATE);
        return rate == 48000 ? 48000 : 16000;
    }

    static boolean continuousRecognition(Context context) {
        return prefs(context).getBoolean("continuous_recognition", BuildConfig.DEFAULT_CONTINUOUS_RECOGNITION);
    }

    static void saveRecognitionOptions(Context context, int sampleRate, boolean continuous) {
        if (sampleRate != 16000 && sampleRate != 48000) {
            throw new IllegalArgumentException("Unsupported recognition sample rate");
        }
        prefs(context).edit().putInt("recognizer_sample_rate", sampleRate)
                .putBoolean("continuous_recognition", continuous).apply();
    }

    private static final String PREFS = "online_speech";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_TTS_URL = "tts_url";
    private static final String KEY_LANGUAGE = "language";
    static final String TTS_SIMPLE = "simple";
    static final String TTS_BAMBORAK = "bamborak";

    static String ttsProtocol(Context context) {
        return prefs(context).getString("tts_protocol", BuildConfig.DEFAULT_TTS_PROTOCOL);
    }

    static String ttsSpeakerId(Context context) {
        return prefs(context).getString("tts_speaker_id", BuildConfig.DEFAULT_TTS_SPEAKER_ID);
    }

    static void saveTtsOptions(Context context, String protocol, String speakerId) {
        prefs(context).edit().putString("tts_protocol", protocol)
                .putString("tts_speaker_id", speakerId.trim()).apply();
    }
    private SpeechSettings() {
    }

    static String serverUrl(Context context) {
        return prefs(context).getString(KEY_SERVER_URL, DEFAULT_SERVER_URL);
    }

    static String language(Context context) {
        String saved = normalizeLanguage(prefs(context).getString(KEY_LANGUAGE, DEFAULT_LANGUAGE));
        for (String available : AVAILABLE_LANGUAGES) {
            if (available.equals(saved)) {
                return saved;
            }
        }
        return DEFAULT_LANGUAGE;
    }

    static String ttsUrl(Context context) {
        return prefs(context).getString(KEY_TTS_URL, DEFAULT_TTS_URL);
    }

    static void saveLanguage(Context context, String language) {
        prefs(context).edit()
                .putString(KEY_LANGUAGE, language)
                .apply();
    }

    static void save(Context context, String serverUrl, String language, String ttsUrl) {
        prefs(context).edit()
                .putString(KEY_SERVER_URL, sanitizeServerUrl(serverUrl))
                .putString(KEY_LANGUAGE, language)
                .putString(KEY_TTS_URL, sanitizeOptionalUrl(ttsUrl))
                .apply();
    }

    /** Honors an external client's language hint; unlike the UI list, it is not an allowlist. */
    static String languageFromIntent(Context context, android.content.Intent intent) {
        String language = intent.getStringExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE);
        if (language == null || language.trim().isEmpty()) {
            language = SpeechSettings.language(context);
        }
        return normalizeLanguage(language);
    }

    /** Trims the ASR URL; blank input restores the packaged default, not an empty endpoint. */
    static String sanitizeServerUrl(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_SERVER_URL;
        }
        return raw.trim();
    }

    static String sanitizeOptionalUrl(String raw) {
        return raw == null ? "" : raw.trim();
    }

    /** Reduces a language tag such as hsb-DE to the base code used by these backend requests. */
    static String normalizeLanguage(String language) {
        String normalized = language == null ? DEFAULT_LANGUAGE : language.trim();
        if (normalized.contains("-")) {
            normalized = normalized.substring(0, normalized.indexOf('-'));
        }
        return normalized.isEmpty() ? DEFAULT_LANGUAGE : normalized.toLowerCase(Locale.ROOT);
    }

    static String[] availableLanguages() {
        return AVAILABLE_LANGUAGES.clone();
    }

    /** Parses the build-time UI language list, retaining order and falling back to Upper Sorbian. */
    static String[] parseLanguages(String configured) {
        Set<String> languages = new LinkedHashSet<>();
        if (configured != null) {
            for (String candidate : configured.split(",")) {
                String language = candidate.trim().toLowerCase(Locale.ROOT);
                int regionSeparator = language.indexOf('-');
                if (regionSeparator >= 0) {
                    language = language.substring(0, regionSeparator);
                }
                if (language.matches("[a-z]{2,8}")) {
                    languages.add(language);
                }
            }
        }
        if (languages.isEmpty()) {
            languages.add(FALLBACK_LANGUAGE);
        }
        return languages.toArray(new String[0]);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
