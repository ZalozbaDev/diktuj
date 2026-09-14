package de.adrianzimmermann.sorbianonlinespeech;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;

import java.util.ArrayList;
import java.util.Arrays;

public class TtsDataActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String action = getIntent().getAction();
        Intent result = new Intent();
        if (TextToSpeech.Engine.ACTION_CHECK_TTS_DATA.equals(action)) {
            result.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES,
                    new ArrayList<>(Arrays.asList(
                            "hsb-DEU", "dsb-DEU", "deu-DEU", "eng-USA")));
            result.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES,
                    new ArrayList<>());
            setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result);
        } else if (TextToSpeech.Engine.ACTION_GET_SAMPLE_TEXT.equals(action)) {
            result.putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT,
                    getString(R.string.tts_sample_text));
            setResult(TextToSpeech.LANG_AVAILABLE, result);
        } else {
            setResult(RESULT_CANCELED);
        }
        finish();
    }
}
