package de.adrianzimmermann.sorbianonlinespeech;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_AUDIO = 10;
    private EditText serverUrl;
    private EditText ttsText;
    private EditText ttsUrl;
    private Spinner ttsProtocol;
    private EditText ttsSpeakerId;
    private Spinner languageSpinner;
    private Spinner recognitionSampleRate;
    private CheckBox continuousRecognition;
    private TextView status;
    private TextView transcript;
    private Button startRecognitionButton;
    private Button stopRecognitionButton;
    private OnlineSpeechSession session;
    private TtsPlayer ttsPlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ttsPlayer = new TtsPlayer(this, new TtsPlayer.Listener() {
            @Override
            public void onStatus(String message) {
                runOnUiThread(() -> setStatus(message));
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> setStatus(getString(R.string.tts_error, message)));
            }
        });
        setContentView(R.layout.activity_main);
        bindViews();
        configureControls();
        loadSettings();
        if (!isProcessTextIntent(getIntent())
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        }
        handleTtsIntent(getIntent());
    }

    @Override
    protected void onStop() {
        stopSession();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (ttsPlayer != null) {
            ttsPlayer.shutdown();
            ttsPlayer = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleTtsIntent(intent);
    }

    private void bindViews() {
        serverUrl = findViewById(R.id.server_url);
        ttsText = findViewById(R.id.tts_text);
        ttsUrl = findViewById(R.id.tts_url);
        ttsProtocol = findViewById(R.id.tts_protocol);
        ttsSpeakerId = findViewById(R.id.tts_speaker_id);
        languageSpinner = findViewById(R.id.language_spinner);
        recognitionSampleRate = findViewById(R.id.recognition_sample_rate);
        continuousRecognition = findViewById(R.id.continuous_recognition);
        status = findViewById(R.id.status);
        transcript = findViewById(R.id.transcript);
    }

    private void configureControls() {
        recognitionSampleRate.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, new String[]{"16 kHz", "48 kHz"}));
        ttsProtocol.setAdapter(ArrayAdapter.createFromResource(this,
                R.array.tts_protocol_options, android.R.layout.simple_spinner_dropdown_item));
        ttsProtocol.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                findViewById(R.id.tts_speaker_fields).setVisibility(
                        position == 1 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        View advancedControls = findViewById(R.id.advanced_controls);
        Button toggleAdvanced = findViewById(R.id.toggle_advanced);
        toggleAdvanced.setOnClickListener(v -> {
            boolean show = advancedControls.getVisibility() != View.VISIBLE;
            advancedControls.setVisibility(show ? View.VISIBLE : View.GONE);
            toggleAdvanced.setText(show
                    ? R.string.hide_advanced_settings
                    : R.string.show_advanced_settings);
        });
        String[] languages = SpeechSettings.availableLanguages();
        languageSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, languages));
        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                SpeechSettings.saveLanguage(MainActivity.this, selectedLanguage());
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        Button save = findViewById(R.id.save_settings);
        save.setOnClickListener(v -> {
            if (saveSettings()) {
                setStatus(getString(R.string.status_settings_saved));
            }
        });
        startRecognitionButton = findViewById(R.id.start_test);
        startRecognitionButton.setOnClickListener(v -> startSession());
        stopRecognitionButton = findViewById(R.id.stop_test);
        stopRecognitionButton.setOnClickListener(v -> stopSession());
        setRecognitionControls(false);
        Button serviceTest = findViewById(R.id.open_service_test);
        serviceTest.setOnClickListener(v -> startActivity(new Intent(this, ServiceTestActivity.class)));
        Button imeSettings = findViewById(R.id.open_ime_settings);
        imeSettings.setOnClickListener(v -> startActivity(
                new Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS)));
        Button imePicker = findViewById(R.id.show_ime_picker);
        imePicker.setOnClickListener(v -> {
            InputMethodManager manager = getSystemService(InputMethodManager.class);
            if (manager != null) {
                manager.showInputMethodPicker();
            }
        });
        Button speakWithEngine = findViewById(R.id.speak_android);
        speakWithEngine.setOnClickListener(v -> {
            if (saveSettings()) {
                ttsPlayer.speakWithEngine(ttsText.getText().toString(), selectedLanguage());
            }
        });
        Button speakExternal = findViewById(R.id.speak_external);
        speakExternal.setOnClickListener(v -> {
            if (saveSettings()) {
                ttsPlayer.speakExternal(ttsText.getText().toString(), selectedLanguage(), ttsUrl.getText().toString());
            }
        });
        Button stopSpeech = findViewById(R.id.stop_speech);
        stopSpeech.setOnClickListener(v -> {
            ttsPlayer.stop();
            setStatus(getString(R.string.status_speech_stopped));
        });
        Button ttsSettings = findViewById(R.id.open_tts_settings);
        ttsSettings.setOnClickListener(v -> openTtsSettings());
    }

    /** Starts one direct dictation with the current settings; terminal callbacks re-enable the UI. */
    private void startSession() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        if (session != null) {
            return;
        }
        if (!saveSettings()) {
            return;
        }
        transcript.setText("");
        setRecognitionControls(true);
        OnlineSpeechSession newSession = new OnlineSpeechSession(
                this, serverUrl.getText().toString(), selectedLanguage(),
                new RecognitionListener() {
                    @Override
                    public void onReady() {
                        status.setText(R.string.status_connected);
                    }

                    @Override
                    public void onSpeechStart() {
                        status.setText(R.string.status_recording);
                    }

                    @Override
                    public void onResult(RecognitionResult result) {
                        if (result.partial) {
                            status.setText(getString(R.string.status_partial, result.text));
                        } else {
                            transcript.append(result.text + "\n");
                            status.setText(R.string.status_result_received);
                        }
                    }

                    @Override
                    public void onNoSpeech() {
                        status.setText(R.string.status_no_speech);
                        finishSession();
                    }

                    @Override
                    public void onError(String message) {
                        status.setText(getString(R.string.status_error, message));
                        finishSession();
                    }

                    @Override
                    public void onComplete() {
                        status.setText(R.string.status_stopped);
                        finishSession();
                    }
                });
        session = newSession;
        newSession.start();
    }

    /** Ends capture but keeps the session until the server finishes or times out. */
    private void stopSession() {
        OnlineSpeechSession localSession = session;
        if (localSession != null) {
            stopRecognitionButton.setEnabled(false);
            status.setText(R.string.status_stopping);
            localSession.stop();
        }
    }

    private void finishSession() {
        session = null;
        setRecognitionControls(false);
    }

    private void setRecognitionControls(boolean recording) {
        startRecognitionButton.setEnabled(!recording);
        stopRecognitionButton.setEnabled(recording);
    }

    private void loadSettings() {
        recognitionSampleRate.setSelection(SpeechSettings.sampleRate(this) == 48000 ? 1 : 0);
        continuousRecognition.setChecked(SpeechSettings.continuousRecognition(this));
        serverUrl.setText(SpeechSettings.serverUrl(this));
        ttsUrl.setText(SpeechSettings.ttsUrl(this));
        ttsProtocol.setSelection(SpeechSettings.TTS_BAMBORAK.equals(
                SpeechSettings.ttsProtocol(this)) ? 1 : 0);
        ttsSpeakerId.setText(SpeechSettings.ttsSpeakerId(this));
        String language = SpeechSettings.language(this);
        for (int i = 0; i < languageSpinner.getCount(); i++) {
            if (languageSpinner.getItemAtPosition(i).equals(language)) {
                languageSpinner.setSelection(i);
                break;
            }
        }
    }

    private boolean saveSettings() {
        if (ttsProtocol.getSelectedItemPosition() == 1
                && ttsSpeakerId.getText().toString().trim().isEmpty()) {
            findViewById(R.id.advanced_controls).setVisibility(View.VISIBLE);
            ((Button) findViewById(R.id.toggle_advanced)).setText(R.string.hide_advanced_settings);
            ttsSpeakerId.setError(getString(R.string.tts_speaker_required));
            ttsSpeakerId.requestFocus();
            return false;
        }
        ttsSpeakerId.setError(null);
        SpeechSettings.saveRecognitionOptions(this,
                recognitionSampleRate.getSelectedItemPosition() == 1 ? 48000 : 16000,
                continuousRecognition.isChecked());
        SpeechSettings.save(this, serverUrl.getText().toString(), selectedLanguage(), ttsUrl.getText().toString());
        SpeechSettings.saveTtsOptions(this, ttsProtocol.getSelectedItemPosition() == 1
                ? SpeechSettings.TTS_BAMBORAK : SpeechSettings.TTS_SIMPLE,
                ttsSpeakerId.getText().toString());
        return true;
    }

    /** Reads selected text without allowing the calling app to change server settings. */
    private void handleTtsIntent(Intent intent) {
        if (!isProcessTextIntent(intent)) {
            return;
        }
        String text = selectedText(intent);
        if (text == null) {
            return;
        }
        ttsText.setText(text);
        ttsPlayer.speakWithEngine(text, SpeechSettings.language(this));
    }

    static boolean isProcessTextIntent(Intent intent) {
        return intent != null && Intent.ACTION_PROCESS_TEXT.equals(intent.getAction());
    }

    static String selectedText(Intent intent) {
        CharSequence text = intent == null ? null : intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        return text == null ? null : text.toString();
    }

    private String selectedLanguage() {
        Object selected = languageSpinner.getSelectedItem();
        return selected == null ? SpeechSettings.DEFAULT_LANGUAGE : selected.toString();
    }

    private void setStatus(String message) {
        if (status != null) {
            status.setText(message);
        }
    }

    private void openTtsSettings() {
        Intent intent = new Intent("com.android.settings.TTS_SETTINGS");
        if (intent.resolveActivity(getPackageManager()) == null) {
            intent = new Intent(android.provider.Settings.ACTION_SETTINGS);
        }
        startActivity(intent);
    }
}
