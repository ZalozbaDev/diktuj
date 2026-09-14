package de.adrianzimmermann.sorbianonlinespeech;

import android.content.Context;
import android.media.MediaPlayer;
import android.speech.tts.TextToSpeech;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

final class TtsPlayer implements TextToSpeech.OnInitListener {
    interface Listener {
        void onStatus(String message);

        void onError(String message);
    }

    private final Context context;
    private final Listener listener;
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .readTimeout(60, TimeUnit.SECONDS).build();
    private TextToSpeech textToSpeech;
    private boolean engineReady;
    private boolean shutdown;
    private MediaPlayer mediaPlayer;
    private Call externalCall;
    private String pendingText;
    private String pendingLanguage;

    TtsPlayer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    @Override
    public synchronized void onInit(int status) {
        if (shutdown) {
            return;
        }
        engineReady = status == TextToSpeech.SUCCESS;
        if (!engineReady) {
            pendingText = null;
            pendingLanguage = null;
            listener.onError(text(R.string.tts_unavailable));
            return;
        }
        if (pendingText != null) {
            String text = pendingText;
            String language = pendingLanguage;
            pendingText = null;
            pendingLanguage = null;
            speakWithEngine(text, language);
        }
    }

    /** Selects this app's Android engine explicitly; retains the latest text while it initializes. */
    synchronized void speakWithEngine(String text, String language) {
        if (shutdown) {
            return;
        }
        if (!hasText(text)) {
            listener.onError(text(R.string.tts_enter_text));
            return;
        }
        stop();
        if (textToSpeech == null) {
            pendingText = text;
            pendingLanguage = language;
            listener.onStatus(text(R.string.tts_starting));
            textToSpeech = new TextToSpeech(context, this, context.getPackageName());
            return;
        }
        if (!engineReady) {
            pendingText = text;
            pendingLanguage = language;
            listener.onStatus(text(R.string.tts_still_starting));
            return;
        }
        Locale locale = localeFor(language);
        int support = textToSpeech.setLanguage(locale);
        if (support == TextToSpeech.LANG_MISSING_DATA || support == TextToSpeech.LANG_NOT_SUPPORTED) {
            listener.onError(text(R.string.tts_voice_missing, locale.toLanguageTag()));
            return;
        }
        listener.onStatus(text(R.string.tts_reading, locale.toLanguageTag()));
        textToSpeech.speak(text.trim(), TextToSpeech.QUEUE_FLUSH, null, "sorbian-online-speech-tts");
    }

    /** Bypasses Android's TTS engine to test the server directly; downloads audio before playback. */
    synchronized void speakExternal(String text, String language, String endpointUrl) {
        if (shutdown) {
            return;
        }
        if (!hasText(text)) {
            listener.onError(text(R.string.tts_enter_text));
            return;
        }
        if (endpointUrl == null || endpointUrl.trim().isEmpty()) {
            listener.onError(text(R.string.tts_url_empty));
            return;
        }
        stop();
        Request request;
        try {
            request = ExternalTtsClient.request(context, endpointUrl, text, language);
        } catch (IllegalArgumentException e) {
            listener.onError(e.getMessage());
            return;
        }
        listener.onStatus(text(R.string.tts_requesting));
        externalCall = httpClient.newCall(request);
        externalCall.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                synchronized (TtsPlayer.this) {
                    if (call == externalCall && !call.isCanceled()) {
                        externalCall = null;
                        listener.onError(text(R.string.tts_network_failed, e.getMessage()));
                    }
                }
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response closeable = response) {
                    byte[] audio = ExternalTtsClient.readAudio(response);
                    synchronized (TtsPlayer.this) {
                        if (call == externalCall && !call.isCanceled()) {
                            playAudio(audio);
                            externalCall = null;
                        }
                    }
                } catch (IOException e) {
                    synchronized (TtsPlayer.this) {
                        if (call == externalCall && !call.isCanceled()) {
                            externalCall = null;
                            listener.onError(text(R.string.tts_audio_error, e.getMessage()));
                        }
                    }
                }
            }
        });
    }

    synchronized void stop() {
        pendingText = null;
        pendingLanguage = null;
        if (externalCall != null) {
            externalCall.cancel();
            externalCall = null;
        }
        if (textToSpeech != null) {
            textToSpeech.stop();
        }
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    synchronized void shutdown() {
        shutdown = true;
        stop();
        if (textToSpeech != null) {
            textToSpeech.shutdown();
            textToSpeech = null;
        }
    }

    private void playAudio(byte[] audio) throws IOException {
        File audioFile = new File(context.getCacheDir(), "external-tts-audio.bin");
        try (FileOutputStream out = new FileOutputStream(audioFile)) {
            out.write(audio);
        }
        MediaPlayer player = new MediaPlayer();
        try {
            player.setDataSource(audioFile.getAbsolutePath());
        } catch (IOException | RuntimeException e) {
            player.release();
            throw e;
        }
        player.setOnCompletionListener(completed -> {
            synchronized (TtsPlayer.this) {
                if (mediaPlayer == completed) {
                    completed.release();
                    mediaPlayer = null;
                    listener.onStatus(text(R.string.tts_finished));
                }
            }
        });
        player.setOnErrorListener((failed, what, extra) -> {
            synchronized (TtsPlayer.this) {
                if (mediaPlayer == failed) {
                    failed.release();
                    mediaPlayer = null;
                    listener.onError(text(R.string.tts_playback_failed));
                }
            }
            return true;
        });
        mediaPlayer = player;
        player.setOnPreparedListener(prepared -> {
            synchronized (TtsPlayer.this) {
                if (mediaPlayer == prepared) {
                    prepared.start();
                    listener.onStatus(text(R.string.tts_playing));
                }
            }
        });
        try {
            player.prepareAsync();
        } catch (RuntimeException e) {
            player.release();
            mediaPlayer = null;
            throw e;
        }
    }

    private static Locale localeFor(String language) {
        String normalized = SpeechSettings.normalizeLanguage(language);
        if ("hsb".equals(normalized)) {
            return Locale.forLanguageTag("hsb-DE");
        }
        if ("dsb".equals(normalized)) {
            return Locale.forLanguageTag("dsb-DE");
        }
        return Locale.forLanguageTag(normalized);
    }

    private static boolean hasText(String text) {
        return text != null && !text.trim().isEmpty();
    }

    private String text(int resourceId, Object... arguments) {
        return context.getString(resourceId, arguments);
    }

}
