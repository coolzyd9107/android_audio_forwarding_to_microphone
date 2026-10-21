package com.coolzyd.audioforwarder;

import android.media.AudioFormat;
import android.os.SystemClock;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

final class PcmRingBuffer {
    static final int DEFAULT_SOURCE_RATE = 48000;
    private static final int PREBUFFER_SECONDS = 2;
    private static final int CAPACITY_SECONDS = 6;
    private static final int BYTES_PER_FRAME = 4;

    private byte[] data = new byte[DEFAULT_SOURCE_RATE * CAPACITY_SECONDS * BYTES_PER_FRAME];
    private final Map<Integer, Client> clients = new HashMap<>();
    private long generation;
    private long baseFrame;
    private long writeFrame;
    private long streamStartedAt;
    private int sourceSampleRate = DEFAULT_SOURCE_RATE;
    private int capacityFrames = DEFAULT_SOURCE_RATE * CAPACITY_SECONDS;
    private boolean active;
    private boolean finished;
    private float gain = 1.0f;
    private int nextClientId = 1;

    synchronized long beginStream() {
        generation++;
        baseFrame = 0;
        writeFrame = 0;
        streamStartedAt = SystemClock.uptimeMillis();
        active = true;
        finished = false;
        setSourceRate(DEFAULT_SOURCE_RATE);
        for (Client client : clients.values()) {
            client.position = 0;
            client.updateFilter(sourceSampleRate);
        }
        notifyAll();
        return generation;
    }

    synchronized void stopStream() {
        generation++;
        active = false;
        finished = true;
        baseFrame = 0;
        writeFrame = 0;
        for (Client client : clients.values()) {
            client.position = 0;
        }
        notifyAll();
    }

    synchronized boolean setSourceSampleRate(long token, int sampleRate) {
        if (!active || token != generation || writeFrame != 0) {
            return false;
        }
        setSourceRate(sampleRate);
        for (Client client : clients.values()) {
            client.position = 0;
            client.updateFilter(sourceSampleRate);
        }
        streamStartedAt = SystemClock.uptimeMillis();
        return true;
    }

    private void setSourceRate(int sampleRate) {
        int newRate = Math.max(8000, Math.min(sampleRate, 192000));
        if (newRate == sourceSampleRate) {
            return;
        }
        sourceSampleRate = newRate;
        capacityFrames = sourceSampleRate * CAPACITY_SECONDS;
        data = new byte[capacityFrames * BYTES_PER_FRAME];
    }

    synchronized boolean isCurrent(long token) {
        return active && generation == token;
    }

    synchronized boolean isActive() {
        return active;
    }

    synchronized void setGain(float value) {
        gain = Math.max(0.0f, Math.min(2.0f, value));
    }

    synchronized void append(long token, byte[] pcm) {
        if (!active || token != generation || pcm == null || pcm.length < BYTES_PER_FRAME) {
            return;
        }
        long mediaFrame = Math.max(0, writeFrame - sourceSampleRate * PREBUFFER_SECONDS);
        long deadline = streamStartedAt + mediaFrame * 1000L / sourceSampleRate;
        while (active && token == generation && SystemClock.uptimeMillis() < deadline) {
            long waitMs = deadline - SystemClock.uptimeMillis();
            try {
                wait(Math.max(1, Math.min(waitMs, 100)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (!active || token != generation) {
            return;
        }
        int frames = pcm.length / BYTES_PER_FRAME;
        int sourceOffset = 0;
        if (frames > capacityFrames) {
            sourceOffset += (frames - capacityFrames) * BYTES_PER_FRAME;
            frames = capacityFrames;
        }
        long newWrite = writeFrame + frames;
        long newBase = Math.max(baseFrame, newWrite - capacityFrames);
        baseFrame = newBase;
        copyIntoRing(pcm, sourceOffset, frames, writeFrame);
        writeFrame = newWrite;
        notifyAll();
    }

    synchronized void finish(long token) {
        if (active && generation == token) {
            finished = true;
            notifyAll();
        }
    }

    synchronized int registerClient(int sampleRate, int channels, int encoding) {
        int id = nextClientId++;
        clients.put(id, new Client(Math.max(8000, Math.min(sampleRate, 192000)),
                Math.max(1, Math.min(channels, 8)), normalizeEncoding(encoding),
                Math.max(baseFrame, writeFrame - sourceSampleRate * PREBUFFER_SECONDS), sourceSampleRate));
        return id;
    }

    synchronized void unregisterClient(int id) {
        clients.remove(id);
    }

    synchronized byte[] read(int id, int requestedFrames) {
        Client client = clients.get(id);
        if (client == null || requestedFrames <= 0 || requestedFrames > 16384 || !active) {
            return new byte[0];
        }
        if (client.position < baseFrame) {
            client.position = baseFrame;
        }

        int bytesPerSample = bytesPerSample(client.encoding);
        int available = availableFrames(client);
        int frames = Math.min(requestedFrames, available);
        byte[] result = new byte[requestedFrames * client.channels * bytesPerSample];
        if (client.encoding == AudioFormat.ENCODING_PCM_8BIT) {
            Arrays.fill(result, (byte) 0x80);
        }
        double step = sourceSampleRate / (double) client.sampleRate;
        if (frames <= 0) {
            client.position += requestedFrames * step;
            return result;
        }
        int out = 0;
        PolyphaseFilter.SampleSource sampleSource = this::sampleAt;
        for (int frame = 0; frame < frames; frame++) {
            double position = client.position;
            long first = (long) position;
            int left = applyGain(client.filter(first, position, 0, sampleSource));
            int right = applyGain(client.filter(first, position, 1, sampleSource));
            for (int channel = 0; channel < client.channels; channel++) {
                int sample;
                if (client.channels == 1) {
                    sample = (left + right) / 2;
                } else if (channel == 0) {
                    sample = left;
                } else if (channel == 1) {
                    sample = right;
                } else {
                    sample = 0;
                }
                out = writeSample(result, out, sample, client.encoding);
            }
            client.position += step;
        }
        client.position += (requestedFrames - frames) * step;
        return result;
    }

    private int availableFrames(Client client) {
        if (writeFrame <= 0) {
            return 0;
        }
        double step = sourceSampleRate / (double) client.sampleRate;
        double lookahead = client.filter == null ? 0.0 : PolyphaseFilter.RIGHT_LOOKAHEAD;
        double lastPosition = writeFrame - 1 - lookahead;
        if (finished) {
            lastPosition = writeFrame - 1;
        }
        if (client.position > lastPosition) {
            return 0;
        }
        return Math.max(0, (int) Math.floor((lastPosition - client.position) / step) + 1);
    }

    private int applyGain(int sample) {
        return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(sample * gain)));
    }

    private int sampleAt(long frame, int channel) {
        int capacityFrames = data.length / BYTES_PER_FRAME;
        long ringFrame = Math.max(baseFrame, Math.min(frame, writeFrame - 1));
        int index = (int) ((ringFrame % capacityFrames) * BYTES_PER_FRAME + channel * 2);
        return (short) ((data[index] & 0xff) | (data[index + 1] << 8));
    }

    private void copyIntoRing(byte[] source, int sourceOffset, int frames, long targetFrame) {
        int capacityFrames = data.length / BYTES_PER_FRAME;
        int bytes = frames * BYTES_PER_FRAME;
        int target = (int) ((targetFrame % capacityFrames) * BYTES_PER_FRAME);
        int firstPart = Math.min(bytes, data.length - target);
        System.arraycopy(source, sourceOffset, data, target, firstPart);
        if (firstPart < bytes) {
            System.arraycopy(source, sourceOffset + firstPart, data, 0, bytes - firstPart);
        }
    }

    private static int normalizeEncoding(int encoding) {
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT
                || encoding == AudioFormat.ENCODING_PCM_8BIT
                || encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED
                || encoding == AudioFormat.ENCODING_PCM_32BIT) {
            return encoding;
        }
        return AudioFormat.ENCODING_PCM_16BIT;
    }

    private static int bytesPerSample(int encoding) {
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT || encoding == AudioFormat.ENCODING_PCM_32BIT) {
            return 4;
        }
        if (encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED) {
            return 3;
        }
        return encoding == AudioFormat.ENCODING_PCM_8BIT ? 1 : 2;
    }

    private static int writeSample(byte[] target, int offset, int sample, int encoding) {
        if (encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED) {
            int value = sample << 8;
            target[offset++] = (byte) value;
            target[offset++] = (byte) (value >>> 8);
            target[offset++] = (byte) (value >>> 16);
        } else if (encoding == AudioFormat.ENCODING_PCM_32BIT) {
            int value = sample << 16;
            target[offset++] = (byte) value;
            target[offset++] = (byte) (value >>> 8);
            target[offset++] = (byte) (value >>> 16);
            target[offset++] = (byte) (value >>> 24);
        } else if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            float value = sample / 32768.0f;
            int bits = Float.floatToIntBits(value);
            target[offset++] = (byte) bits;
            target[offset++] = (byte) (bits >>> 8);
            target[offset++] = (byte) (bits >>> 16);
            target[offset++] = (byte) (bits >>> 24);
        } else if (encoding == AudioFormat.ENCODING_PCM_8BIT) {
            target[offset++] = (byte) ((sample >> 8) + 128);
        } else {
            target[offset++] = (byte) sample;
            target[offset++] = (byte) (sample >>> 8);
        }
        return offset;
    }

    private static final class Client {
        final int sampleRate;
        final int channels;
        final int encoding;
        double position;
        PolyphaseFilter filter;

        Client(int sampleRate, int channels, int encoding, long position, int sourceSampleRate) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.encoding = encoding;
            this.position = position;
            updateFilter(sourceSampleRate);
        }

        void updateFilter(int sourceSampleRate) {
            filter = sourceSampleRate == sampleRate ? null
                    : new PolyphaseFilter(sourceSampleRate, sampleRate);
        }

        int filter(long center, double position, int channel, PolyphaseFilter.SampleSource source) {
            if (filter == null) {
                return source.sampleAt(center, channel);
            }
            return filter.filter(position, channel, source);
        }
    }
}
