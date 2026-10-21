package com.coolzyd.audioforwarder;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

final class PcmDecoder implements Runnable {
    private final Context context;
    private final Uri uri;
    private final PcmRingBuffer output;
    private final long generation;
    private double resamplePhase;

    PcmDecoder(Context context, Uri uri, PcmRingBuffer output, long generation) {
        this.context = context.getApplicationContext();
        this.uri = uri;
        this.output = output;
        this.generation = generation;
    }

    @Override
    public void run() {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        try {
            extractor.setDataSource(context, uri, null);
            int trackIndex = findAudioTrack(extractor);
            if (trackIndex < 0) {
                throw new IllegalArgumentException("No audio track in selected file");
            }
            extractor.selectTrack(trackIndex);
            MediaFormat inputFormat = extractor.getTrackFormat(trackIndex);
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            MediaFormat pcmFormat = inputFormat;
            while (!outputDone && output.isCurrent(generation)) {
                if (!inputDone) {
                    int inputIndex = decoder.dequeueInputBuffer(10000);
                    if (inputIndex >= 0) {
                        ByteBuffer input = decoder.getInputBuffer(inputIndex);
                        if (input == null) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, 0);
                        } else {
                            input.clear();
                            int size = extractor.readSampleData(input, 0);
                            if (size < 0) {
                                decoder.queueInputBuffer(inputIndex, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                long presentationTime = extractor.getSampleTime();
                                decoder.queueInputBuffer(inputIndex, 0, size, presentationTime, 0);
                                extractor.advance();
                            }
                        }
                    }
                }

                int outputIndex = decoder.dequeueOutputBuffer(info, 10000);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    pcmFormat = decoder.getOutputFormat();
                } else if (outputIndex >= 0) {
                    if (info.size > 0) {
                        ByteBuffer buffer = decoder.getOutputBuffer(outputIndex);
                        if (buffer != null) {
                            buffer.position(info.offset);
                            buffer.limit(info.offset + info.size);
                            byte[] decoded = new byte[info.size];
                            buffer.get(decoded);
                            output.append(generation, convertToCanonical(decoded, pcmFormat));
                        }
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    decoder.releaseOutputBuffer(outputIndex, false);
                }
            }
        } catch (Exception e) {
            android.util.Log.e("AudioForwarder", "Audio decoding failed", e);
        } finally {
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (Exception ignored) {
                }
                try {
                    decoder.release();
                } catch (Exception ignored) {
                }
            }
            extractor.release();
            output.finish(generation);
        }
    }

    private static int findAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.toLowerCase(Locale.ROOT).startsWith("audio/")) {
                return i;
            }
        }
        return -1;
    }

    private byte[] convertToCanonical(byte[] input, MediaFormat format) {
        int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : PcmRingBuffer.OUTPUT_RATE;
        int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
        int encoding = format.containsKey(MediaFormat.KEY_PCM_ENCODING)
                ? format.getInteger(MediaFormat.KEY_PCM_ENCODING) : android.media.AudioFormat.ENCODING_PCM_16BIT;
        int bytesPerSample;
        if (encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
            bytesPerSample = 4;
        } else if (encoding == android.media.AudioFormat.ENCODING_PCM_8BIT) {
            bytesPerSample = 1;
        } else {
            bytesPerSample = 2;
        }
        channels = Math.max(1, Math.min(channels, 8));
        sampleRate = Math.max(8000, Math.min(192000, sampleRate));
        int inputFrames = input.length / (bytesPerSample * channels);
        if (inputFrames == 0) {
            return new byte[0];
        }
        double phase = resamplePhase;
        double step = sampleRate / (double) PcmRingBuffer.OUTPUT_RATE;
        int outputFrames = Math.max(1,
                (int) Math.ceil((inputFrames - phase) / step));
        byte[] result = new byte[outputFrames * 4];
        ByteBuffer source = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer target = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        for (int frame = 0; frame < outputFrames; frame++) {
            double position = phase + frame * step;
            int first = Math.min((int) position, inputFrames - 1);
            int second = Math.min(first + 1, inputFrames - 1);
            double fraction = position - first;
            int left = readSample(source, first, 0, channels, encoding, bytesPerSample);
            int right = readSample(source, first, channels > 1 ? 1 : 0, channels, encoding, bytesPerSample);
            int nextLeft = readSample(source, second, 0, channels, encoding, bytesPerSample);
            int nextRight = readSample(source, second, channels > 1 ? 1 : 0, channels, encoding, bytesPerSample);
            short outLeft = (short) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, Math.round(left + (nextLeft - left) * fraction)));
            short outRight = (short) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, Math.round(right + (nextRight - right) * fraction)));
            target.putShort(outLeft);
            target.putShort(outRight);
        }
        resamplePhase = phase + outputFrames * step - inputFrames;
        return result;
    }

    private static int readSample(ByteBuffer source, int frame, int channel, int channels,
                                  int encoding, int bytesPerSample) {
        int offset = (frame * channels + channel) * bytesPerSample;
        if (encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
            float value = source.getFloat(offset);
            return Math.max(-32768, Math.min(32767, Math.round(value * 32767)));
        }
        if (encoding == android.media.AudioFormat.ENCODING_PCM_8BIT) {
            return ((source.get(offset) & 0xff) - 128) << 8;
        }
        return source.getShort(offset);
    }
}
