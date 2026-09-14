package de.adrianzimmermann.sorbianonlinespeech;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class MainActivityTest {
    private Instrumentation instrumentation;
    private MainActivity activity;

    @Before
    public void launchActivity() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        instrumentation.getUiAutomation().grantRuntimePermission(
                context.getPackageName(), Manifest.permission.RECORD_AUDIO);
        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = (MainActivity) instrumentation.startActivitySync(intent);
        instrumentation.waitForIdleSync();
    }

    @After
    public void finishActivity() {
        if (activity != null) {
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    @Test
    public void diagnosticIntentCannotChangeTtsSettings() {
        String oldUrl = SpeechSettings.ttsUrl(activity);
        for (String action : new String[]{Intent.ACTION_MAIN, Intent.ACTION_PROCESS_TEXT}) {
            Intent intent = new Intent(action)
                    .putExtra("tts_url", "https://example.invalid/tts")
                    .putExtra("tts_text", "Unexpected speech")
                    .putExtra("tts_mode", "external");
            if (Intent.ACTION_PROCESS_TEXT.equals(action)) {
                intent.putExtra(Intent.EXTRA_PROCESS_TEXT, "");
            }
            instrumentation.runOnMainSync(() -> activity.onNewIntent(intent));
            assertEquals(oldUrl, SpeechSettings.ttsUrl(activity));
            assertEquals(oldUrl, ((EditText) activity.findViewById(R.id.tts_url)).getText().toString());
            assertEquals("", ((EditText) activity.findViewById(R.id.tts_text)).getText().toString());
        }
    }

    @Test
    public void languageSelectionDoesNotSaveOtherFields() {
        String oldUrl = SpeechSettings.ttsUrl(activity);
        instrumentation.runOnMainSync(() -> {
            ((EditText) activity.findViewById(R.id.tts_url)).setText("https://example.invalid/tts");
            Spinner languages = activity.findViewById(R.id.language_spinner);
            languages.getOnItemSelectedListener().onItemSelected(languages, null, 0, 0);
        });
        assertEquals(oldUrl, SpeechSettings.ttsUrl(activity));
    }

    @Test
    public void allSaveActionsRejectMissingBamborakSpeaker() {
        String oldSpeaker = SpeechSettings.ttsSpeakerId(activity);
        String oldUrl = SpeechSettings.ttsUrl(activity);
        instrumentation.runOnMainSync(() -> {
            ((Spinner) activity.findViewById(R.id.tts_protocol)).setSelection(1);
            ((EditText) activity.findViewById(R.id.tts_speaker_id)).setText(" ");
            ((EditText) activity.findViewById(R.id.tts_url)).setText("https://example.invalid/tts");
        });
        instrumentation.waitForIdleSync();
        for (int button : new int[]{R.id.save_settings, R.id.speak_android,
                R.id.speak_external, R.id.start_test}) {
            instrumentation.runOnMainSync(() -> activity.findViewById(button).performClick());
            assertEquals(oldUrl, SpeechSettings.ttsUrl(activity));
            assertEquals(oldSpeaker, SpeechSettings.ttsSpeakerId(activity));
            assertTrue(((EditText) activity.findViewById(R.id.tts_speaker_id)).getError() != null);
            assertTrue(activity.findViewById(R.id.start_test).isEnabled());
        }
    }

    @Test
    public void recognitionControlsStartIdle() {
        Button start = activity.findViewById(R.id.start_test);
        Button stop = activity.findViewById(R.id.stop_test);

        assertTrue(start.isEnabled());
        assertFalse(stop.isEnabled());
    }

    @Test
    public void ttsInputStartsCompactAndSupportsMultipleLines() {
        EditText ttsText = activity.findViewById(R.id.tts_text);

        assertEquals(1, ttsText.getMinLines());
        assertEquals(3, ttsText.getMaxLines());
        assertTrue((ttsText.getInputType() & android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0);
    }

    @Test
    public void advancedDiagnosticsAreHiddenUntilRequested() {
        View advancedControls = activity.findViewById(R.id.advanced_controls);
        Button toggleAdvanced = activity.findViewById(R.id.toggle_advanced);

        assertEquals(View.GONE, advancedControls.getVisibility());

        activity.runOnUiThread(toggleAdvanced::performClick);
        instrumentation.waitForIdleSync();

        assertEquals(View.VISIBLE, advancedControls.getVisibility());
    }

    @Test
    public void bamborakSettingsSurviveActivityRestart() {
        String previousProtocol = SpeechSettings.ttsProtocol(activity);
        String previousSpeaker = SpeechSettings.ttsSpeakerId(activity);
        try {
            instrumentation.runOnMainSync(() -> activity.findViewById(R.id.toggle_advanced).performClick());
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                ((Spinner) activity.findViewById(R.id.tts_protocol)).setSelection(1);
                ((EditText) activity.findViewById(R.id.tts_speaker_id)).setText(" test-voice ");
            });
            instrumentation.waitForIdleSync();
            assertEquals(View.VISIBLE, activity.findViewById(R.id.tts_speaker_fields).getVisibility());
            instrumentation.runOnMainSync(() -> activity.findViewById(R.id.save_settings).performClick());
            assertEquals("bamborak", SpeechSettings.ttsProtocol(activity));
            assertEquals("test-voice", SpeechSettings.ttsSpeakerId(activity));
            instrumentation.runOnMainSync(activity::finish);
            activity = (MainActivity) instrumentation.startActivitySync(
                    new Intent(instrumentation.getTargetContext(), MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            instrumentation.waitForIdleSync();
            assertEquals(1, ((Spinner) activity.findViewById(R.id.tts_protocol)).getSelectedItemPosition());
            assertEquals("test-voice", ((EditText) activity.findViewById(R.id.tts_speaker_id)).getText().toString());
        } finally {
            SpeechSettings.saveTtsOptions(activity, previousProtocol, previousSpeaker);
        }
    }

    @Test
    public void recognitionOptionsSurviveActivityRestart() {
        int previousRate = SpeechSettings.sampleRate(activity);
        boolean previousContinuous = SpeechSettings.continuousRecognition(activity);
        try {
            instrumentation.runOnMainSync(() -> {
                ((Spinner) activity.findViewById(R.id.recognition_sample_rate)).setSelection(1);
                ((android.widget.CheckBox) activity.findViewById(R.id.continuous_recognition)).setChecked(true);
                activity.findViewById(R.id.save_settings).performClick();
            });
            assertEquals(48000, SpeechSettings.sampleRate(activity));
            assertTrue(SpeechSettings.continuousRecognition(activity));
            instrumentation.runOnMainSync(activity::finish);
            activity = (MainActivity) instrumentation.startActivitySync(
                    new Intent(instrumentation.getTargetContext(), MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            instrumentation.waitForIdleSync();
            assertEquals(1, ((Spinner) activity.findViewById(R.id.recognition_sample_rate)).getSelectedItemPosition());
            assertTrue(((android.widget.CheckBox) activity.findViewById(R.id.continuous_recognition)).isChecked());
        } finally {
            SpeechSettings.saveRecognitionOptions(activity, previousRate, previousContinuous);
        }
    }

}
