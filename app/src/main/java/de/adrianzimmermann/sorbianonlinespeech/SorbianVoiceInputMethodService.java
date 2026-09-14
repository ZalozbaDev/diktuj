package de.adrianzimmermann.sorbianonlinespeech;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.os.Build;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;

public class SorbianVoiceInputMethodService extends InputMethodService {
    private Spinner languageSpinner;
    private TextView preview;
    private ImageButton startButton;
    private ImageButton stopButton;
    private OnlineSpeechSession session;
    private int sessionGeneration;
    private boolean dictationActive;
    private boolean awaitingFinal;

    @Override
    public View onCreateInputView() {
        View view = getLayoutInflater().inflate(R.layout.input_method_voice, null);
        languageSpinner = view.findViewById(R.id.ime_language);
        preview = view.findViewById(R.id.ime_preview);
        startButton = view.findViewById(R.id.ime_start);
        stopButton = view.findViewById(R.id.ime_stop);

        configureLanguageSpinner();
        startButton.setOnClickListener(v -> startRecognition());
        stopButton.setOnClickListener(v -> stopRecognition());
        View chooseKeyboard = view.findViewById(R.id.ime_choose_keyboard);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            chooseKeyboard.setTooltipText(getString(R.string.ime_choose_keyboard));
        }
        chooseKeyboard.setOnClickListener(v -> {
            cancelRecognition();
            InputMethodManager manager = getSystemService(InputMethodManager.class);
            if (manager != null) {
                manager.showInputMethodPicker();
            }
        });
        view.findViewById(R.id.ime_open_settings).setOnClickListener(v -> openAppSettings());
        view.findViewById(R.id.ime_hide).setOnClickListener(v -> requestHideSelf(0));
        setRecording(false);
        return view;
    }

    @Override
    public void onStartInputView(android.view.inputmethod.EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        if (preview != null && !dictationActive && !awaitingFinal) {
            preview.setText(R.string.ime_tap_microphone);
        }
    }

    @Override
    public void onFinishInput() {
        cancelRecognition();
        super.onFinishInput();
    }

    @Override
    public void onDestroy() {
        cancelRecognition();
        super.onDestroy();
    }

    private void configureLanguageSpinner() {
        String[] languages = SpeechSettings.availableLanguages();
        languageSpinner.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, languages));
        String savedLanguage = SpeechSettings.language(this);
        for (int i = 0; i < languageSpinner.getCount(); i++) {
            if (savedLanguage.equals(languageSpinner.getItemAtPosition(i))) {
                languageSpinner.setSelection(i);
                break;
            }
        }
        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                SpeechSettings.saveLanguage(SorbianVoiceInputMethodService.this, selectedLanguage());
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void startRecognition() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            preview.setText(R.string.ime_microphone_permission_missing);
            return;
        }
        cancelRecognition();
        dictationActive = true;
        preview.setText(R.string.ime_connecting);
        setRecording(true);
        int generation = ++sessionGeneration;
        session = new OnlineSpeechSession(this,
                SpeechSettings.serverUrl(this), selectedLanguage(), new RecognitionListener() {
                    @Override
                    public void onReady() {
                        ifCurrent(generation, () -> preview.setText(R.string.ime_connected));
                    }

                    @Override
                    public void onSpeechStart() {
                        ifCurrent(generation, () -> preview.setText(R.string.ime_listening));
                    }

                    @Override
                    public void onResult(RecognitionResult result) {
                        ifCurrent(generation, () -> {
                            preview.setText(result.text);
                            if (!result.partial) {
                                awaitingFinal = false;
                                commitRecognizedText(result.text);
                            }
                        });
                    }

                    @Override
                    public void onNoSpeech() {
                        ifCurrent(generation, () -> {
                            session = null;
                            dictationActive = false;
                            preview.setText(R.string.status_no_speech);
                            setRecording(false);
                        });
                    }

                    @Override
                    public void onError(String message) {
                        ifCurrent(generation, () -> {
                            session = null;
                            dictationActive = false;
                            preview.setText(getString(R.string.status_error, message));
                            setRecording(false);
                        });
                    }

                    @Override
                    public void onComplete() {
                        ifCurrent(generation, () -> {
                            session = null;
                            dictationActive = false;
                            if (awaitingFinal) {
                                preview.setText(R.string.ime_stopped);
                            }
                            setRecording(false);
                        });
                    }
                });
        session.start();
    }

    private void stopRecognition() {
        dictationActive = false;
        OnlineSpeechSession localSession = session;
        if (localSession != null) {
            awaitingFinal = true;
            stopButton.setEnabled(false);
            preview.setText(R.string.ime_finishing);
            localSession.stop();
        } else {
            preview.setText(R.string.ime_stopped);
            setRecording(false);
        }
    }

    /**
     * Invalidates UI callbacks when input ends or a new session replaces this one.
     * Aborts the old session; any already queued results cannot reach the field.
     */
    private void cancelRecognition() {
        dictationActive = false;
        sessionGeneration++;
        OnlineSpeechSession localSession = session;
        session = null;
        if (localSession != null) {
            localSession.cancel();
        }
        if (startButton != null) {
            setRecording(false);
        }
    }

    /** Inserts at the current cursor using Android's editor connection, without clipboard access. */
    private void commitRecognizedText(String result) {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            preview.setText(R.string.ime_no_input_field);
            return;
        }
        String insertion = insertionText(connection.getTextBeforeCursor(1, 0), result);
        if (!insertion.isEmpty()) {
            connection.commitText(insertion, 1);
            preview.setText(getString(R.string.ime_inserted, result.trim()));
        }
    }

    /** Adds a separator if the previous character is not whitespace, plus a trailing space. */
    static String insertionText(CharSequence beforeCursor, String recognized) {
        String text = recognized == null ? "" : recognized.trim();
        if (text.isEmpty()) {
            return "";
        }
        boolean needsLeadingSpace = beforeCursor != null && beforeCursor.length() > 0
                && !Character.isWhitespace(beforeCursor.charAt(beforeCursor.length() - 1));
        return (needsLeadingSpace ? " " : "") + text + " ";
    }

    private void setRecording(boolean recording) {
        awaitingFinal = false;
        startButton.setEnabled(!recording);
        stopButton.setEnabled(recording);
        languageSpinner.setEnabled(!recording);
    }

    private String selectedLanguage() {
        Object selected = languageSpinner == null ? null : languageSpinner.getSelectedItem();
        return selected == null ? SpeechSettings.DEFAULT_LANGUAGE : selected.toString();
    }

    /** A generation token prevents delayed results from an old input session changing the new one. */
    private void ifCurrent(int generation, Runnable action) {
        if (generation == sessionGeneration) {
            action.run();
        }
    }

    private void openAppSettings() {
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }
}
