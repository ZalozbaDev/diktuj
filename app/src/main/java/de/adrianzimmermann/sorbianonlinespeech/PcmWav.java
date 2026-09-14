package de.adrianzimmermann.sorbianonlinespeech;

import android.media.AudioFormat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class PcmWav {
    static final class Audio {
        final int sampleRate;
        final int channelCount;
        final int audioFormat;
        final byte[] pcm;

        Audio(int sampleRate, int channelCount, byte[] pcm) {
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
            this.audioFormat = AudioFormat.ENCODING_PCM_16BIT;
            this.pcm = pcm;
        }
    }

    private PcmWav() {
    }

    /** Accepts uncompressed 16-bit mono/stereo WAV; rejects unsupported or missing audio data. */
    static Audio parse(byte[] wav) throws IOException {
        if (wav == null || wav.length < 44 || !tag(wav, 0).equals("RIFF") || !tag(wav, 8).equals("WAVE")) {
            throw new IOException("External TTS must return a PCM WAV file");
        }
        ByteBuffer input = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        int sampleRate = 0;
        int channelCount = 0;
        int bitsPerSample = 0;
        int pcmFormat = 0;
        byte[] audio = null;
        int offset = 12;
        while (offset + 8 <= wav.length) {
            String chunk = tag(wav, offset);
            long unsignedSize = Integer.toUnsignedLong(input.getInt(offset + 4));
            if (unsignedSize > Integer.MAX_VALUE) {
                throw new IOException("WAV chunk is too large");
            }
            int size = (int) unsignedSize;
            int dataOffset = offset + 8;
            if (dataOffset + size > wav.length) {
                throw new IOException("WAV chunk is truncated");
            }
            if ("fmt ".equals(chunk)) {
                if (size < 16) {
                    throw new IOException("WAV format chunk is invalid");
                }
                pcmFormat = Short.toUnsignedInt(input.getShort(dataOffset));
                channelCount = Short.toUnsignedInt(input.getShort(dataOffset + 2));
                sampleRate = input.getInt(dataOffset + 4);
                bitsPerSample = Short.toUnsignedInt(input.getShort(dataOffset + 14));
            } else if ("data".equals(chunk)) {
                audio = Arrays.copyOfRange(wav, dataOffset, dataOffset + size);
            }
            // RIFF chunks are padded to even byte boundaries, including unrecognized chunks.
            offset = dataOffset + size + (size & 1);
        }
        if (pcmFormat != 1 || bitsPerSample != 16 || sampleRate <= 0
                || (channelCount != 1 && channelCount != 2) || audio == null || audio.length == 0) {
            throw new IOException("External TTS WAV must contain 16-bit mono or stereo PCM audio");
        }
        return new Audio(sampleRate, channelCount, audio);
    }

    private static String tag(byte[] input, int offset) {
        return new String(input, offset, 4, StandardCharsets.US_ASCII);
    }
}
