package de.adrianzimmermann.sorbianonlinespeech;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

public class BamborakIntegrationTest {
    @Test
    public void androidEngineUsesSavedBamborakSettings() throws Exception {
        exercise(true);
    }

    @Test
    public void directPlaybackUsesSavedBamborakSettings() throws Exception {
        exercise(false);
    }

    private void exercise(boolean systemEngine) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String oldUrl = SpeechSettings.ttsUrl(context);
        String oldProtocol = SpeechSettings.ttsProtocol(context);
        String oldSpeaker = SpeechSettings.ttsSpeakerId(context);
        String asrUrl = SpeechSettings.serverUrl(context);
        String language = SpeechSettings.language(context);
        ArrayBlockingQueue<String> completion = new ArrayBlockingQueue<>(8);
        TextToSpeech engine = null;
        TtsPlayer player = null;
        File output = new File(context.getCacheDir(), "bamborak-test.wav");
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "audio/wav")
                    .setBody(new Buffer().write(silentWav())));
            String url = server.url("/api/tts/").toString();
            SpeechSettings.save(context, asrUrl, language, url);
            SpeechSettings.saveTtsOptions(context, "bamborak", "test-voice/subspeaker");
            if (systemEngine) {
                ArrayBlockingQueue<Integer> initialized = new ArrayBlockingQueue<>(1);
                engine = new TextToSpeech(context, initialized::offer, context.getPackageName());
                assertEquals(Integer.valueOf(TextToSpeech.SUCCESS), initialized.poll(15, TimeUnit.SECONDS));
                engine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { }
                    @Override public void onDone(String id) { completion.offer("done"); }
                    @Override public void onError(String id) { completion.offer("error"); }
                });
                assertEquals(TextToSpeech.SUCCESS,
                        engine.synthesizeToFile("Test", new Bundle(), output, "bamborak"));
            } else {
                player = new TtsPlayer(context, new TtsPlayer.Listener() {
                    @Override public void onStatus(String message) {
                        if (message.equals(context.getString(R.string.tts_finished))) {
                            completion.offer("done");
                        }
                    }
                    @Override public void onError(String message) { completion.offer(message); }
                });
                player.speakExternal("Test", "hsb", url);
            }
            RecordedRequest received = server.takeRequest(20, TimeUnit.SECONDS);
            assertNotNull("Missing TTS request", received);
            assertEquals("POST", received.getMethod());
            assertEquals("/api/tts/", received.getPath());
            JSONObject request = new JSONObject(received.getBody().readUtf8());
            assertEquals("Test", request.getString("text"));
            assertEquals("test-voice/subspeaker", request.getString("speaker_id"));
            assertEquals("wav", request.getString("format"));
            assertEquals(3, request.length());
            assertEquals("done", completion.poll(20, TimeUnit.SECONDS));
            if (systemEngine) {
                assertTrue(output.length() > 44);
            }
        } finally {
            if (engine != null) engine.shutdown();
            if (player != null) player.shutdown();
            output.delete();
            SpeechSettings.save(context, asrUrl, language, oldUrl);
            SpeechSettings.saveTtsOptions(context, oldProtocol, oldSpeaker);
        }
    }

    @Test
    public void stoppedDownloadDoesNotPlayAndNextRequestStillWorks() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ArrayBlockingQueue<String> events = new ArrayBlockingQueue<>(16);
        TtsPlayer player = new TtsPlayer(context, new TtsPlayer.Listener() {
            @Override public void onStatus(String message) { events.offer(message); }
            @Override public void onError(String message) { events.offer(message); }
        });
        String protocol = SpeechSettings.ttsProtocol(context);
        String speaker = SpeechSettings.ttsSpeakerId(context);
        try (MockWebServer server = new MockWebServer()) {
            SpeechSettings.saveTtsOptions(context, "bamborak", "test-voice");
            server.enqueue(new MockResponse().setHeader("Content-Type", "audio/wav")
                    .setBody(new Buffer().write(silentWav())).setBodyDelay(1, TimeUnit.SECONDS));
            server.enqueue(new MockResponse().setHeader("Content-Type", "audio/wav")
                    .setBody(new Buffer().write(silentWav())));
            String url = server.url("/api/tts/").toString();
            player.speakExternal("Cancelled", "hsb", url);
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
            player.stop();
            events.clear();
            assertNull("Event after Stop", events.poll(1500, TimeUnit.MILLISECONDS));
            player.speakExternal("Next", "hsb", url);
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
            assertEquals(context.getString(R.string.tts_requesting), events.poll(5, TimeUnit.SECONDS));
            assertEquals(context.getString(R.string.tts_playing), events.poll(5, TimeUnit.SECONDS));
            assertEquals(context.getString(R.string.tts_finished), events.poll(5, TimeUnit.SECONDS));
        } finally {
            player.shutdown();
            SpeechSettings.saveTtsOptions(context, protocol, speaker);
        }
    }

    @Test
    public void stopClearsSpeechWaitingForEngineInitialization() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ArrayBlockingQueue<String> events = new ArrayBlockingQueue<>(16);
        TtsPlayer player = new TtsPlayer(context, new TtsPlayer.Listener() {
            @Override public void onStatus(String message) { events.offer(message); }
            @Override public void onError(String message) { events.offer(message); }
        });
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                player.speakWithEngine("Cancelled", "hsb");
                player.stop();
                events.clear();
                player.onInit(TextToSpeech.SUCCESS);
            });
            assertNull("Speech resumed after Stop", events.poll(1, TimeUnit.SECONDS));
        } finally {
            player.shutdown();
        }
    }

    private static byte[] silentWav() {
        int size = 9600;
        ByteBuffer wav = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + size);
        wav.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        wav.putShort((short) 1).putShort((short) 1).putInt(48000).putInt(96000);
        wav.putShort((short) 2).putShort((short) 16);
        wav.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(size);
        return wav.array();
    }
}
