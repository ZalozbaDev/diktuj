package de.adrianzimmermann.sorbianonlinespeech;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Bundle;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.widget.Button;
import android.widget.TextView;

import java.util.ArrayList;

public class ServiceTestActivity extends Activity {
    private static final int REQ_AUDIO = 20;
    private TextView status;
    private TextView transcript;
    private SpeechRecognizer recognizer;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_service_test);
        status = findViewById(R.id.service_status);
        transcript = findViewById(R.id.service_transcript);
        Button start = findViewById(R.id.start_service_test);
        start.setOnClickListener(v -> startRecognizer());
        Button stop = findViewById(R.id.stop_service_test);
        stop.setOnClickListener(v -> stopRecognizer());
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        }
    }

    @Override
    protected void onStop() {
        stopRecognizer();
        super.onStop();
    }

    private void startRecognizer() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        stopRecognizer();
        transcript.setText("");
        ComponentName service = new ComponentName(this, OnlineRecognitionService.class);
        recognizer = SpeechRecognizer.createSpeechRecognizer(this, service);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                status.setText(R.string.status_service_ready);
            }

            @Override
            public void onBeginningOfSpeech() {
                status.setText(R.string.status_speech_started);
            }

            @Override
            public void onRmsChanged(float rmsdB) {
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {
                status.setText(R.string.status_speech_ended);
            }

            @Override
            public void onError(int error) {
                status.setText(getString(R.string.status_recognizer_error, error));
            }

            @Override
            public void onResults(Bundle results) {
                appendResults(getString(R.string.result_final), results);
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                appendResults(getString(R.string.result_partial), partialResults);
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
            }
        });
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, SpeechSettings.language(this));
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizer.startListening(intent);
    }

    /** Cancels rather than requesting a final result; delays destruction to allow cancellation delivery. */
    private void stopRecognizer() {
        SpeechRecognizer localRecognizer = recognizer;
        if (localRecognizer == null) {
            return;
        }
        recognizer = null;
        localRecognizer.cancel();
        status.setText(R.string.status_stopped);
        handler.postDelayed(localRecognizer::destroy, 500);
    }

    private void appendResults(String kind, Bundle bundle) {
        ArrayList<String> results = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (results == null || results.isEmpty()) {
            return;
        }
        transcript.append(getString(R.string.transcript_result, kind, results.get(0)) + "\n");
        status.setText(getString(R.string.status_result_received_kind, kind));
    }

}
