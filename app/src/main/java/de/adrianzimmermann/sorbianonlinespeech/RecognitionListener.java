package de.adrianzimmermann.sorbianonlinespeech;

interface RecognitionListener {
    /** The WebSocket is open and its configuration has been queued. */
    void onReady();

    /** AudioRecord has started; this does not mean speech or a valid host signal was detected. */
    void onSpeechStart();

    /** A server partial for display, or a final transcript for insertion/delivery. */
    void onResult(RecognitionResult result);

    void onNoSpeech();

    void onError(String message);

    /** Normal session closure, possibly after a final result; closure alone is not a transcript. */
    void onComplete();
}
