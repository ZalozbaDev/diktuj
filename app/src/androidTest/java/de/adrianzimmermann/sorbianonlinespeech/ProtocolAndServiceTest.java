package de.adrianzimmermann.sorbianonlinespeech;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.fail;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.speech.tts.TextToSpeech;

import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okio.Buffer;

public class ProtocolAndServiceTest {
    @Test
    public void bamborakRequestUsesSpeakerInsteadOfLanguage() throws Exception {
        Request request = ExternalTtsClient.request("https://example.org/api/tts/",
                "  Dobry dźeń  ", "hsb", "bamborak", " voice/subspeaker ");
        Buffer body = new Buffer();
        request.body().writeTo(body);
        JSONObject payload = new JSONObject(body.readUtf8());
        assertEquals("Dobry dźeń", payload.getString("text"));
        assertEquals("voice/subspeaker", payload.getString("speaker_id"));
        assertEquals("wav", payload.getString("format"));
        assertEquals(3, payload.length());
    }

    @Test(expected = IllegalArgumentException.class)
    public void bamborakRejectsMissingSpeaker() {
        ExternalTtsClient.request("https://example.org/api/tts/", "Text", "hsb", "bamborak", " ");
    }

    @Test
    public void bamborakJsonErrorsAreNotTreatedAsAudio() throws Exception {
        for (int status : new int[]{200, 400}) {
            try (Response response = response(status, "application/json",
                    "{\"errmsg\":\"invalid speaker_id\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                try {
                    ExternalTtsClient.readAudio(response);
                    fail("Expected a server error");
                } catch (java.io.IOException expected) {
                    assertEquals("invalid speaker_id", expected.getMessage());
                }
            }
        }
    }

    @Test
    public void bamborakWavIsReturnedUnchanged() throws Exception {
        byte[] wav = wav(new byte[9600], 48000, 1);
        try (Response response = response(200, "audio/wav", wav)) {
            byte[] audio = ExternalTtsClient.readAudio(response);
            assertArrayEquals(wav, audio);
            assertEquals(48000, PcmWav.parse(audio).sampleRate);
        }
    }

    private static Response response(int status, String type, byte[] data) {
        return new Response.Builder().request(new Request.Builder().url("http://localhost/tts").build())
                .protocol(Protocol.HTTP_1_1).code(status).message("Test")
                .header("Content-Type", type)
                .body(ResponseBody.create(data, MediaType.get(type))).build();
    }

    @Test
    public void ttsRequestEscapesTextAndNormalizesLanguage() throws Exception {
        String text = "  Dobry dźeń: \"quoted\" \\ path\n\t\b\f" + (char) 1 + " end  ";
        Request request = ExternalTtsClient.request(" https://example.org/tts ", text, "hsb-DE");
        Buffer body = new Buffer();
        request.body().writeTo(body);
        String encoded = body.readUtf8();
        JSONObject payload = new JSONObject(encoded);

        assertEquals("POST", request.method());
        assertEquals("https://example.org/tts", request.url().toString());
        assertEquals(text.trim(), payload.getString("text"));
        assertEquals("hsb", payload.getString("language"));
        assertFalse(encoded.contains(String.valueOf((char) 1)));
        assertFalse(encoded.contains("\b"));
        assertFalse(encoded.contains("\f"));
    }

    @Test
    public void buildsExpectedVoskConfiguration() throws Exception {
        JSONObject wrapper = new JSONObject(VoskJson.config("hsb", 16000));
        JSONObject config = wrapper.getJSONObject("config");

        assertEquals(16000, config.getInt("sample_rate"));
        assertEquals("hsb", config.getString("language"));
        assertEquals(48000, new JSONObject(VoskJson.config("hsb", 48000))
                .getJSONObject("config").getInt("sample_rate"));
    }

    @Test
    public void continuousTranscriptCombinesServerPhrasesUntilEof() {
        RecognitionTranscript transcript = new RecognitionTranscript(true);
        assertNull(transcript.accept("{\"text\":\"Whisper ready\"}", false));
        assertNull(transcript.accept("{\"text\":\"\"}", false));
        RecognitionResult first = transcript.accept("{\"text\":\"dobry dzen\"}", false);
        assertTrue(first.partial);
        assertEquals("dobry dzen", first.text);
        assertEquals("dobry dzen to je", transcript.accept("{\"partial\":\"to je\"}", false).text);
        assertEquals("dobry dzen to je test", transcript.accept("{\"text\":\"to je test\"}", false).text);
        assertNull(transcript.accept("{\"text\":\"WHISPER ready\"}", true));
        RecognitionResult end = transcript.accept("{\"text\":\"\"}", true);
        assertFalse(end.partial);
        assertEquals("dobry dzen to je test", end.text);
    }

    @Test
    public void finalAfterEofIncludesPreviouslyCompletedPhrases() {
        RecognitionTranscript transcript = new RecognitionTranscript(true);
        transcript.accept("{\"text\":\"one\"}", false);
        RecognitionResult end = transcript.accept("{\"text\":\"two\"}", true);
        assertFalse(end.partial);
        assertEquals("one two", end.text);
    }

    @Test
    public void emptyEofDoesNotCommitUnconfirmedPartial() {
        RecognitionTranscript transcript = new RecognitionTranscript(true);
        transcript.accept("{\"partial\":\"unconfirmed\"}", false);
        RecognitionResult end = transcript.accept("{\"text\":\"\"}", true);
        assertFalse(end.partial);
        assertEquals("", end.text);
        assertFalse(VoskJson.isEmptyFinal("{\"text\":null}"));
        assertFalse(VoskJson.isEmptyFinal("not json"));
    }

    @Test
    public void oneShotStillFinishesOnFirstFinal() {
        RecognitionResult result = new RecognitionTranscript(false)
                .accept("{\"text\":\"finished\"}", false);
        assertFalse(result.partial);
        assertEquals("finished", result.text);
    }

    @Test
    public void parsesPartialFinalAndAlternativeResults() {
        RecognitionResult partial = VoskJson.parse("{\"partial\":\"dobry\"}");
        RecognitionResult result = VoskJson.parse("{\"text\":\"dobry dzen\"}");
        RecognitionResult alternative = VoskJson.parse(
                "{\"alternatives\":[{\"text\":\"witaj\"}]}");

        assertTrue(partial.partial);
        assertEquals("dobry", partial.text);
        assertFalse(result.partial);
        assertEquals("dobry dzen", result.text);
        assertEquals("witaj", alternative.text);
        assertEquals("", VoskJson.parse("not-json").text);
        assertTrue(VoskJson.isNoSpeech("{\"text\":\"\",\"no_speech\":true}"));
        assertFalse(VoskJson.isNoSpeech("{\"text\":\"witaj\"}"));
    }

    @Test
    public void ignoresWhisperBannersWithoutDiscardingNormalResults() {
        assertEquals("", VoskJson.parse("{\"text\":\"Whisper ready\"}").text);
        assertEquals("", VoskJson.parse("{\"text\":\"using WHISPER model\"}").text);
        assertEquals("", VoskJson.parse("{\"partial\":\"whisper initializing\"}").text);
        assertEquals("", VoskJson.parse("{\"alternatives\":[{\"text\":\"WhIsPeR ready\"}]}").text);
        assertEquals("dobry dzen", VoskJson.parse("{\"text\":\"dobry dzen\",\"model\":\"whisper\"}").text);
        assertEquals("witaj", VoskJson.parse("{\"text\":\"witaj\"}").text);
    }

    @Test
    public void normalizesRecognizerLanguages() {
        assertEquals("hsb", SpeechSettings.normalizeLanguage("hsb-DE"));
        assertEquals("dsb", SpeechSettings.normalizeLanguage(" dsb "));
        assertEquals(SpeechSettings.DEFAULT_LANGUAGE, SpeechSettings.normalizeLanguage(""));
    }

    @Test
    public void parsesConfiguredRecognitionLanguages() {
        assertEquals(2, SpeechSettings.parseLanguages("hsb, dsb,hsb").length);
        assertEquals("hsb", SpeechSettings.parseLanguages("hsb, dsb")[0]);
        assertEquals("dsb", SpeechSettings.parseLanguages("hsb, dsb")[1]);
        assertEquals("hsb", SpeechSettings.parseLanguages(" , ")[0]);
    }

    @Test
    public void preservesExplicitServerOverrides() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String recognizerUrl = "ws://10.0.2.2:2700";
        String ttsUrl = "http://10.0.2.2:2780/tts";

        String previousRecognizerUrl = SpeechSettings.serverUrl(context);
        String previousTtsUrl = SpeechSettings.ttsUrl(context);
        String previousLanguage = SpeechSettings.language(context);
        try {
            SpeechSettings.save(context, recognizerUrl, "hsb", ttsUrl);

            assertEquals(recognizerUrl, SpeechSettings.serverUrl(context));
            assertEquals(ttsUrl, SpeechSettings.ttsUrl(context));
        } finally {
            SpeechSettings.save(context, previousRecognizerUrl, previousLanguage, previousTtsUrl);
        }
    }

    @Test
    public void voiceInputMethodIsProtectedAndRegistered() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ComponentName component = new ComponentName(context, SorbianVoiceInputMethodService.class);
        ServiceInfo info = context.getPackageManager().getServiceInfo(component,
                PackageManager.GET_META_DATA);

        assertTrue(info.exported);
        assertEquals("android.permission.BIND_INPUT_METHOD", info.permission);
        assertEquals(R.xml.input_method, info.metaData.getInt("android.view.im"));
    }

    @Test
    public void voiceInputMethodProducesSafeInsertionText() {
        assertEquals("dobry dzen ",
                SorbianVoiceInputMethodService.insertionText(null, " dobry dzen "));
        assertEquals(" dobry dzen ",
                SorbianVoiceInputMethodService.insertionText("A", "dobry dzen"));
        assertEquals("dobry dzen ",
                SorbianVoiceInputMethodService.insertionText(" ", "dobry dzen"));
        assertEquals("", SorbianVoiceInputMethodService.insertionText("A", "  "));
    }

    @Test
    public void ttsAcceptsSorbianAndAccessibilityFallbackLocales() {
        assertTrue(SorbianTtsService.isSystemLanguageSupported("hsb"));
        assertTrue(SorbianTtsService.isSystemLanguageSupported("dsb"));
        assertTrue(SorbianTtsService.isSystemLanguageSupported("deu"));
        assertTrue(SorbianTtsService.isSystemLanguageSupported("eng"));
        assertFalse(SorbianTtsService.isSystemLanguageSupported("fra"));
    }

    @Test
    public void ttsDataCheckActivityIsRegistered() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent check = new Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA)
                .setPackage(context.getPackageName());

        assertEquals(TtsDataActivity.class.getName(),
                context.getPackageManager().resolveActivity(check, 0).activityInfo.name);
    }

    @Test
    public void parsesPcmWavForSystemTtsStreaming() throws Exception {
        byte[] pcm = {0, 0, 1, 0, -1, -1, 2, 0};
        PcmWav.Audio audio = PcmWav.parse(wav(pcm, 16000, 1));

        assertEquals(16000, audio.sampleRate);
        assertEquals(1, audio.channelCount);
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, audio.audioFormat);
        assertEquals(pcm.length, audio.pcm.length);
    }

    @Test
    public void selectedTextIntentResolvesToReadAloudActivity() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = new Intent(Intent.ACTION_PROCESS_TEXT)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, "Dobry dźeń");
        boolean registered = false;
        for (ResolveInfo candidate : context.getPackageManager().queryIntentActivities(intent,
                PackageManager.MATCH_DEFAULT_ONLY)) {
            if (context.getPackageName().equals(candidate.activityInfo.packageName)
                    && MainActivity.class.getName().equals(candidate.activityInfo.name)) {
                registered = true;
                break;
            }
        }

        assertTrue(MainActivity.isProcessTextIntent(intent));
        assertEquals("Dobry dźeń", MainActivity.selectedText(intent));
        assertTrue(registered);
    }

    private static byte[] wav(byte[] pcm, int sampleRate, int channels) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[]{'R', 'I', 'F', 'F'});
        header.putInt(36 + pcm.length);
        header.put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) channels);
        header.putInt(sampleRate);
        header.putInt(sampleRate * channels * 2);
        header.putShort((short) (channels * 2));
        header.putShort((short) 16);
        header.put(new byte[]{'d', 'a', 't', 'a'});
        header.putInt(pcm.length);
        output.write(header.array());
        output.write(pcm);
        return output.toByteArray();
    }
}
