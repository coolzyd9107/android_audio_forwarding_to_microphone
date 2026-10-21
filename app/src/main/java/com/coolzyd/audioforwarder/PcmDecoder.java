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
            updateSourceRate(inputFormat);
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
                    updateSourceRate(pcmFormat);
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

    private void updateSourceRate(MediaFormat format) {
        if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            output.setSourceSampleRate(generation, format.getInteger(MediaFormat.KEY_SAMPLE_RATE));
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
        int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
        int encoding = format.containsKey(MediaFormat.KEY_PCM_ENCODING)
                ? format.getInteger(MediaFormat.KEY_PCM_ENCODING) : android.media.AudioFormat.ENCODING_PCM_16BIT;
        int bytesPerSample = bytesPerSample(encoding);
        channels = Math.max(1, Math.min(channels, 8));
        int inputFrames = input.length / (bytesPerSample * channels);
        if (inputFrames == 0) {
            return new byte[0];
        }
        byte[] result = new byte[inputFrames * 4];
        ByteBuffer source = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer target = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        for (int frame = 0; frame < inputFrames; frame++) {
            int left = readSample(source, frame, 0, channels, encoding, bytesPerSample);
            int right = readSample(source, frame, channels > 1 ? 1 : 0,
                    channels, encoding, bytesPerSample);
            target.putShort((short) left);
            target.putShort((short) right);
        }
        return result;
    }

    private static int bytesPerSample(int encoding) {
        if (encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT
                || encoding == android.media.AudioFormat.ENCODING_PCM_32BIT) {
            return 4;
        }
        if (encoding == android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED) {
            return 3;
        }
        return encoding == android.media.AudioFormat.ENCODING_PCM_8BIT ? 1 : 2;
    }

    private static int readSample(ByteBuffer source, int frame, int channel, int channels,
                                  int encoding, int bytesPerSample) {
        int offset = (frame * channels + channel) * bytesPerSample;
        if (encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
            float value = source.getFloat(offset);
            return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value * 32767)));
        }
        if (encoding == android.media.AudioFormat.ENCODING_PCM_32BIT) {
            return source.getInt(offset) >> 16;
        }
        if (encoding == android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED) {
            int sample = (source.get(offset) & 0xff)
                    | ((source.get(offset + 1) & 0xff) << 8)
                    | (source.get(offset + 2) << 16);
            return sample >> 8;
        }
        if (encoding == android.media.AudioFormat.ENCODING_PCM_8BIT) {
            return ((source.get(offset) & 0xff) - 128) << 8;
        }
        return source.getShort(offset);
    }
}
