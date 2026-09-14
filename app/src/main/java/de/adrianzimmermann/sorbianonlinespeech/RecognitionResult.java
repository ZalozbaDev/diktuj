package de.adrianzimmermann.sorbianonlinespeech;

final class RecognitionResult {
    final String text;
    final boolean partial;

    RecognitionResult(String text, boolean partial) {
        this.text = text == null ? "" : text;
        this.partial = partial;
    }
}
