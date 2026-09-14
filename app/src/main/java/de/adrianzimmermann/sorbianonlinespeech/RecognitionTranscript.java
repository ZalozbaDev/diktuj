package de.adrianzimmermann.sorbianonlinespeech;

final class RecognitionTranscript {
    private final boolean continuous;
    private final StringBuilder completed = new StringBuilder();

    RecognitionTranscript(boolean continuous) {
        this.continuous = continuous;
    }

    /** Returns null for ignored messages; a non-partial result ends the session. */
    RecognitionResult accept(String message, boolean eofSent) {
        RecognitionResult result = VoskJson.parse(message);
        boolean end = eofSent && (VoskJson.isEmptyFinal(message) || VoskJson.isNoSpeech(message));
        if (result.text.isEmpty() && !end) {
            return null;
        }
        if (result.partial) {
            return new RecognitionResult(join(result.text), true);
        }
        if (!result.text.isEmpty()) {
            if (completed.length() > 0) {
                completed.append(' ');
            }
            completed.append(result.text.trim());
        }
        return new RecognitionResult(completed.toString(), continuous && !eofSent);
    }

    private String join(String partial) {
        return completed.length() == 0 ? partial : completed + " " + partial;
    }
}
