package de.adrianzimmermann.sorbianonlinespeech;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Intent;

import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.ByteString;

public class StreamingRecognitionTest {
    @Test
    public void repeatedRecordingsCombinePhrasesAndFinishOnEmptyEof() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.getUiAutomation().grantRuntimePermission(
                instrumentation.getTargetContext().getPackageName(), Manifest.permission.RECORD_AUDIO);
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(
                new Intent(instrumentation.getTargetContext(), MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
        int previousRate = SpeechSettings.sampleRate(activity);
        boolean previousContinuous = SpeechSettings.continuousRecognition(activity);
        try {
            SpeechSettings.saveRecognitionOptions(activity, 48000, true);
            for (int attempt = 0; attempt < 2; attempt++) {
                exerciseSession(instrumentation, activity);
            }
        } finally {
            SpeechSettings.saveRecognitionOptions(activity, previousRate, previousContinuous);
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    private void exerciseSession(Instrumentation instrumentation, MainActivity activity) throws Exception {
        CountDownLatch preview = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        AtomicReference<String> finalText = new AtomicReference<>();
        AtomicInteger sampleRate = new AtomicInteger();
        AtomicInteger terminalCount = new AtomicInteger();
        AtomicInteger audioFrames = new AtomicInteger();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override
                public void onOpen(WebSocket socket, Response response) {
                    socket.send("{\"partial\":\"\",\"listen\":\"false\"}");
                    socket.send("{\"text\":\"Whisper ready\"}");
                }

                @Override
                public void onMessage(WebSocket socket, String text) {
                    try {
                        JSONObject message = new JSONObject(text);
                        if (message.has("config")) {
                            sampleRate.set(message.getJSONObject("config").getInt("sample_rate"));
                        } else if (message.has("eof")) {
                            socket.send("{\"text\":\"\"}");
                        }
                    } catch (Exception e) {
                        error.set(e.toString());
                    }
                }

                @Override
                public void onMessage(WebSocket socket, ByteString bytes) {
                    if (audioFrames.incrementAndGet() == 1) {
                        socket.send("{\"text\":\"dobry dzen\"}");
                        socket.send("{\"partial\":\"to je\"}");
                        socket.send("{\"text\":\"to je test\"}");
                    }
                }

                @Override
                public void onClosing(WebSocket socket, int code, String reason) {
                    socket.close(code, reason);
                }
            }));
            server.start();
            OnlineSpeechSession session = new OnlineSpeechSession(activity,
                    server.url("/").toString().replace("http://", "ws://"), "hsb", new RecognitionListener() {
                public void onReady() { }
                public void onSpeechStart() { }
                public void onResult(RecognitionResult result) {
                    if (result.partial && "dobry dzen to je test".equals(result.text)) {
                        preview.countDown();
                    } else if (!result.partial) {
                        finalText.set(result.text);
                    }
                }
                public void onNoSpeech() { error.set("No speech"); done.countDown(); }
                public void onError(String message) { error.set(message); done.countDown(); }
                public void onComplete() { terminalCount.incrementAndGet(); done.countDown(); }
            });
            try {
                instrumentation.runOnMainSync(session::start);
                assertTrue("Missing combined preview: " + error.get(), preview.await(10, TimeUnit.SECONDS));
                assertEquals(48000, sampleRate.get());
                assertFalse("Session ended before Stop", done.await(250, TimeUnit.MILLISECONDS));
                assertTrue(audioFrames.get() > 1);
                instrumentation.runOnMainSync(session::stop);
                assertTrue("EOF did not finish", done.await(10, TimeUnit.SECONDS));
                assertNull(error.get());
                assertEquals("dobry dzen to je test", finalText.get());
                assertEquals(1, terminalCount.get());
            } finally {
                instrumentation.runOnMainSync(session::cancel);
            }
        }
    }
}
