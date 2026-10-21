package com.coolzyd.audioforwarder;

import android.media.AudioFormat;
import android.os.SystemClock;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

final class PcmRingBuffer {
    static final int OUTPUT_RATE = 48000;
    private static final int CAPACITY_FRAMES = OUTPUT_RATE * 6;
    private static final int BYTES_PER_FRAME = 4;

    private final byte[] data = new byte[CAPACITY_FRAMES * BYTES_PER_FRAME];
    private final Map<Integer, Client> clients = new HashMap<>();
    private long generation;
    private long baseFrame;
    private long writeFrame;
    private long streamStartedAt;
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
        for (Client client : clients.values()) {
            client.position = 0;
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
        long deadline = streamStartedAt + writeFrame * 1000L / OUTPUT_RATE;
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
        if (frames > CAPACITY_FRAMES) {
            sourceOffset += (frames - CAPACITY_FRAMES) * BYTES_PER_FRAME;
            frames = CAPACITY_FRAMES;
        }
        long newWrite = writeFrame + frames;
        long newBase = Math.max(baseFrame, newWrite - CAPACITY_FRAMES);
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
                Math.max(1, Math.min(channels, 8)), normalizeEncoding(encoding), writeFrame));
        return id;
    }

    synchronized void unregisterClient(int id) {
        clients.remove(id);
    }

    synchronized byte[] read(int id, int requestedFrames) {
        Client client = clients.get(id);
        if (client == null || requestedFrames <= 0 || requestedFrames > 8192 || !active) {
            return new byte[0];
        }
        long deadline = SystemClock.uptimeMillis() + 30;
        int available;
        do {
            if (client.position < baseFrame) {
                client.position = baseFrame;
            }
            available = availableFrames(client);
            if (available >= requestedFrames || finished || !active) {
                break;
            }
            long waitMs = deadline - SystemClock.uptimeMillis();
            if (waitMs <= 0) {
                break;
            }
            try {
                wait(Math.min(waitMs, 8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        } while (active);

        if (!active) {
            return new byte[0];
        }
        int bytesPerSample = bytesPerSample(client.encoding);
        int frames = Math.min(requestedFrames, availableFrames(client));
        if (frames <= 0) {
            return new byte[requestedFrames * client.channels * bytesPerSample];
        }
        byte[] result = new byte[requestedFrames * client.channels * bytesPerSample];
        if (client.encoding == AudioFormat.ENCODING_PCM_8BIT) {
            Arrays.fill(result, (byte) 0x80);
        }
        double step = OUTPUT_RATE / (double) client.sampleRate;
        int out = 0;
        for (int frame = 0; frame < frames; frame++) {
            double position = client.position;
            long first = (long) position;
            long second = Math.min(first + 1, writeFrame - 1);
            double fraction = position - first;
            int left = applyGain(interpolate(first, second, fraction, 0));
            int right = applyGain(interpolate(first, second, fraction, 1));
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
        return result;
    }

    private int availableFrames(Client client) {
        if (writeFrame <= 0) {
            return 0;
        }
        double step = OUTPUT_RATE / (double) client.sampleRate;
        double lastPosition = writeFrame - 1;
        if (!finished) {
            lastPosition -= 1;
        }
        if (client.position > lastPosition) {
            return 0;
        }
        return Math.max(0, (int) Math.floor((lastPosition - client.position) / step) + 1);
    }

    private int applyGain(int sample) {
        return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(sample * gain)));
    }

    private int interpolate(long first, long second, double fraction, int channel) {
        int a = sampleAt(first, channel);
        int b = sampleAt(second, channel);
        return (int) Math.round(a + (b - a) * fraction);
    }

    private int sampleAt(long frame, int channel) {
        long ringFrame = Math.max(baseFrame, Math.min(frame, writeFrame - 1));
        int index = (int) ((ringFrame % CAPACITY_FRAMES) * BYTES_PER_FRAME + channel * 2);
        return (short) ((data[index] & 0xff) | (data[index + 1] << 8));
    }

    private void copyIntoRing(byte[] source, int sourceOffset, int frames, long targetFrame) {
        int bytes = frames * BYTES_PER_FRAME;
        int target = (int) ((targetFrame % CAPACITY_FRAMES) * BYTES_PER_FRAME);
        int firstPart = Math.min(bytes, data.length - target);
        System.arraycopy(source, sourceOffset, data, target, firstPart);
        if (firstPart < bytes) {
            System.arraycopy(source, sourceOffset + firstPart, data, 0, bytes - firstPart);
        }
    }

    private static int normalizeEncoding(int encoding) {
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT || encoding == AudioFormat.ENCODING_PCM_8BIT) {
            return encoding;
        }
        return AudioFormat.ENCODING_PCM_16BIT;
    }

    private static int bytesPerSample(int encoding) {
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            return 4;
        }
        return encoding == AudioFormat.ENCODING_PCM_8BIT ? 1 : 2;
    }

    private static int writeSample(byte[] target, int offset, int sample, int encoding) {
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
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

        Client(int sampleRate, int channels, int encoding, long position) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.encoding = encoding;
            this.position = position;
        }
    }
}
