package de.adrianzimmermann.speechclient;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity implements RecognitionListener {
    static final ComponentName SERVICE = new ComponentName(
            "de.adrianzimmermann.sorbianonlinespeech",
            "de.adrianzimmermann.sorbianonlinespeech.OnlineRecognitionService");
    private TextView output;
    private SpeechRecognizer recognizer;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        addButton(layout, "Use Sorbisch service", () -> start(true));
        addButton(layout, "Use Android default", () -> start(false));
        addButton(layout, "Stop", () -> {
            if (recognizer != null) recognizer.stopListening();
        });
        addButton(layout, "Cancel", this::destroyRecognizer);
        output = new TextView(this);
        layout.addView(output);
        setContentView(layout);
        if (getIntent().getBooleanExtra("permission_probe", false)) {
            deleteFile("permission-probe.txt");
            start(true);
        }
    }

    private void addButton(LinearLayout layout, String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(v -> action.run());
        layout.addView(button);
    }

    private void start(boolean explicit) {
        if (!getIntent().getBooleanExtra("permission_probe", false)
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        destroyRecognizer();
        output.setText("");
        recognizer = explicit ? SpeechRecognizer.createSpeechRecognizer(this, SERVICE)
                : SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(this);
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hsb");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizer.startListening(intent);
    }

    private void show(String text) {
        output.append(text + "\n");
        if (getIntent().getBooleanExtra("permission_probe", false)) {
            try (FileOutputStream file = openFileOutput("permission-probe.txt", MODE_APPEND)) {
                file.write((text + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException("Cannot record permission test result", e);
            }
        }
    }
    @Override public void onReadyForSpeech(Bundle b) { show("Ready"); }
    @Override public void onBeginningOfSpeech() { show("Recording"); }
    @Override public void onEndOfSpeech() { show("Recording stopped"); }
    @Override public void onError(int code) { show("Error: " + code); destroyRecognizer(); }
    @Override public void onResults(Bundle b) {
        show("Final: " + b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION));
        destroyRecognizer();
    }
    @Override public void onPartialResults(Bundle b) {
        show("Partial: " + b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION));
    }
    @Override public void onRmsChanged(float rms) { }
    @Override public void onBufferReceived(byte[] audio) { }
    @Override public void onEvent(int type, Bundle b) { }

    private void destroyRecognizer() {
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
    }

    @Override public void onStop() {
        destroyRecognizer();
        super.onStop();
    }
}
