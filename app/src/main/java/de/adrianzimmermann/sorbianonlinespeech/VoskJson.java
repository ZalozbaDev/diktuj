package de.adrianzimmermann.sorbianonlinespeech;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

final class VoskJson {
    private VoskJson() {
    }

    static String config(String language, int sampleRate) {
        JSONObject config = new JSONObject();
        JSONObject wrapper = new JSONObject();
        try {
            config.put("sample_rate", sampleRate);
            config.put("language", language);
            wrapper.put("config", config);
        } catch (JSONException ignored) {
        }
        return wrapper.toString();
    }

    /** An empty final acknowledges EOF; filtered startup banners must not do so. */
    static boolean isEmptyFinal(String message) {
        try {
            JSONObject json = new JSONObject(message);
            return json.has("text") && json.get("text") instanceof String
                    && json.getString("text").trim().isEmpty();
        } catch (JSONException ignored) {
            return false;
        }
    }

    /** Reads partial, text or the first alternative; unknown/malformed messages yield empty text. */
    static RecognitionResult parse(String message) {
        try {
            JSONObject json = new JSONObject(message);
            if (json.has("partial")) {
                return filteredResult(json.optString("partial"), true);
            }
            if (json.has("text")) {
                return filteredResult(json.optString("text"), false);
            }
            if (json.has("alternatives")) {
                JSONArray alternatives = json.optJSONArray("alternatives");
                if (alternatives != null && alternatives.length() > 0) {
                    return filteredResult(alternatives.getJSONObject(0).optString("text"), false);
                }
            }
        } catch (JSONException ignored) {
        }
        return new RecognitionResult("", false);
    }

    private static RecognitionResult filteredResult(String text, boolean partial) {
        // Some servers send a Whisper startup banner in the final-result field.
        // Empty results are ignored by the session, leaving capture and the socket open.
        return new RecognitionResult(text.toLowerCase(Locale.ROOT).contains("whisper") ? "" : text, partial);
    }

    /** Recognizes the reference server's explicit no-speech extension, not a universal Vosk field. */
    static boolean isNoSpeech(String message) {
        try {
            return new JSONObject(message).optBoolean("no_speech", false);
        } catch (JSONException ignored) {
            return false;
        }
    }
}
