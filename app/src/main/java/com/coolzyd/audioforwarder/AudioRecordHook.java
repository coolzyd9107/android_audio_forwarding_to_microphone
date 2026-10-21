package com.coolzyd.audioforwarder;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.os.IBinder;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.locks.LockSupport;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

public final class AudioRecordHook extends XposedModule {
    private static final String MODULE_PACKAGE = "com.coolzyd.audioforwarder";
    private static final Object LOCK = new Object();
    private static final Map<AudioRecord, Integer> CLIENTS = new WeakHashMap<>();
    private static final Map<AudioRecord, ReadClock> READ_CLOCKS = new WeakHashMap<>();
    private static volatile IForwardingAudio remote;
    private static volatile Context appContext;
    private static volatile boolean lastKnownForwarding;
    private static boolean binding;

    private boolean hooksInstalled;
    private boolean attachHookInstalled;

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (LOCK) {
                remote = IForwardingAudio.Stub.asInterface(service);
                binding = false;
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            clearConnection();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            clearConnection();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            clearConnection();
        }

        private void clearConnection() {
            synchronized (LOCK) {
                remote = null;
                binding = false;
                CLIENTS.clear();
            }
        }
    };

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (MODULE_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        installAttachHook();
        installAudioHooks();
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (MODULE_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        installAudioHooks();
        Context context = resolveApplicationContext();
        if (context != null) {
            bindAccessService(context);
        }
    }

    private void installAudioHooks() {
        if (hooksInstalled) {
            return;
        }
        hooksInstalled = true;
        hookRead("read", byte[].class, int.class, int.class);
        hookRead("read", byte[].class, int.class, int.class, int.class);
        hookRead("read", short[].class, int.class, int.class);
        hookRead("read", short[].class, int.class, int.class, int.class);
        hookRead("read", float[].class, int.class, int.class, int.class);
        hookRead("read", ByteBuffer.class, int.class);
        hookRead("read", ByteBuffer.class, int.class, int.class);
        hookRelease();
    }

    private void installAttachHook() {
        if (attachHookInstalled) {
            return;
        }
        attachHookInstalled = true;
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            hook(attach).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                Object context = chain.getArg(0);
                if (context instanceof Context) {
                    Object application = chain.getThisObject();
                    Context app = application instanceof Application
                            ? ((Application) application).getApplicationContext() : null;
                    bindAccessService(app != null ? app : (Context) context);
                }
                return result;
            });
        } catch (Throwable error) {
            log(android.util.Log.ERROR, "AudioForwarder", "Could not hook Application.attach", error);
        }
    }

    private void hookRelease() {
        try {
            Method release = AudioRecord.class.getMethod("release");
            hook(release).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                if (chain.getThisObject() instanceof AudioRecord) {
                    Integer clientId;
                    synchronized (LOCK) {
                        clientId = CLIENTS.remove((AudioRecord) chain.getThisObject());
                        READ_CLOCKS.remove((AudioRecord) chain.getThisObject());
                    }
                    IForwardingAudio service = remote;
                    if (clientId != null && service != null) {
                        try {
                            service.unregisterClient(clientId);
                        } catch (Throwable ignored) {
                        }
                    }
                }
                return result;
            });
        } catch (Throwable error) {
            log(android.util.Log.WARN, "AudioForwarder", "Could not hook AudioRecord.release", error);
        }
    }

    private void hookRead(String name, Class<?>... parameterTypes) {
        try {
            Method method = AudioRecord.class.getMethod(name, parameterTypes);
            hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                IForwardingAudio service = remote;
                boolean forwarding = lastKnownForwarding;
                AudioRecord record = (AudioRecord) chain.getThisObject();
                Integer clientId;
                synchronized (LOCK) {
                    clientId = CLIENTS.get(record);
                }

                if (service != null && forwarding && clientId != null) {
                    ReadRequest request = ReadRequest.from(chain.getArgs().toArray());
                    if (request == null) {
                        return 0;
                    }
                    Integer result = readForwarded(record, request, service, clientId);
                    if (result != null) {
                        return result;
                    }
                    lastKnownForwarding = false;
                    request.fillSilence();
                    return request.returnCount;
                }

                if (service != null) {
                    try {
                        forwarding = service.isForwarding();
                        lastKnownForwarding = forwarding;
                    } catch (Throwable error) {
                        log(android.util.Log.WARN, "AudioForwarder", "Forwarding state unavailable", error);
                    }
                } else {
                    Context context = resolveApplicationContext();
                    if (context != null) {
                        bindAccessService(context);
                    }
                }
                if (!forwarding) {
                    return chain.proceed();
                }
                ReadRequest request = ReadRequest.from(chain.getArgs().toArray());
                if (request == null) {
                    return 0;
                }
                Integer result = readForwarded(record, request, service, clientId);
                if (result == null) {
                    lastKnownForwarding = false;
                    request.fillSilence();
                    return request.returnCount;
                }
                return result;
            });
        } catch (NoSuchMethodException ignored) {
            // AudioRecord exposes different read overloads across Android releases.
        } catch (Throwable error) {
            log(android.util.Log.WARN, "AudioForwarder", "Could not hook AudioRecord." + name, error);
        }
    }

    private Integer readForwarded(AudioRecord record, ReadRequest request, IForwardingAudio service,
                                  Integer clientId) {
        try {
            int channels = Math.max(1, Math.min(8, record.getChannelCount()));
            int encoding = request.encoding;
            if (request.buffer instanceof byte[] || request.buffer instanceof ByteBuffer) {
                encoding = supportedEncoding(record.getAudioFormat())
                        ? record.getAudioFormat() : AudioFormat.ENCODING_PCM_16BIT;
                request.encoding = encoding;
            }
            int sampleRate = Math.max(8000, record.getSampleRate());
            int frames = request.frames(channels);
            if (frames <= 0 || frames > 16384) {
                request.fillSilence();
                paceBlockingRead(record, request, sampleRate, Math.max(0, frames));
                return request.returnCount;
            }
            paceBlockingRead(record, request, sampleRate, frames);
            if (service == null) {
                request.fillSilence();
                return request.returnCount;
            }
            if (clientId == null) {
                clientId = service.registerClient(sampleRate, channels, encoding);
                synchronized (LOCK) {
                    CLIENTS.put(record, clientId);
                }
            }
            byte[] pcm = service.readAudio(clientId, frames);
            if (pcm == null) {
                request.fillSilence();
                lastKnownForwarding = false;
                return request.returnCount;
            }
            request.write(pcm, frames, channels);
            return request.returnCount;
        } catch (Throwable error) {
            log(android.util.Log.WARN, "AudioForwarder", "Forwarded read failed; returning silence", error);
            request.fillSilence();
            return request.returnCount;
        }
    }

    private static boolean supportedEncoding(int encoding) {
        return encoding == AudioFormat.ENCODING_PCM_8BIT
                || encoding == AudioFormat.ENCODING_PCM_16BIT
                || encoding == AudioFormat.ENCODING_PCM_FLOAT
                || encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED
                || encoding == AudioFormat.ENCODING_PCM_32BIT;
    }

    private static void paceBlockingRead(AudioRecord record, ReadRequest request,
                                         int sampleRate, int frames) {
        if (!request.blocking || frames <= 0) {
            return;
        }
        long durationNanos = Math.max(1L, frames * 1_000_000_000L / sampleRate);
        long deadline;
        synchronized (LOCK) {
            long now = System.nanoTime();
            ReadClock clock = READ_CLOCKS.get(record);
            if (clock == null || clock.sampleRate != sampleRate || clock.nextDeadline < now - durationNanos) {
                clock = new ReadClock(sampleRate, now);
                READ_CLOCKS.put(record, clock);
            }
            deadline = clock.nextDeadline + durationNanos;
            clock.nextDeadline = deadline;
        }
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) {
            LockSupport.parkNanos(remaining);
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
        }
    }

    private static final class ReadClock {
        final int sampleRate;
        long nextDeadline;

        ReadClock(int sampleRate, long nextDeadline) {
            this.sampleRate = sampleRate;
            this.nextDeadline = nextDeadline;
        }
    }

    private static void bindAccessService(Context context) {
        if (context == null) {
            return;
        }
        Context applicationContext = context.getApplicationContext();
        if (applicationContext != null) {
            context = applicationContext;
        }
        appContext = context;
        synchronized (LOCK) {
            if (remote != null || binding) {
                return;
            }
            Intent intent = new Intent().setComponent(new ComponentName(MODULE_PACKAGE,
                    MODULE_PACKAGE + ".AudioAccessService"));
            try {
                binding = context.bindService(intent, CONNECTION, Context.BIND_AUTO_CREATE);
            } catch (SecurityException | IllegalStateException error) {
                binding = false;
            }
        }
    }

    private static Context resolveApplicationContext() {
        Context context = appContext;
        if (context != null) {
            return context;
        }
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object application = currentApplication.invoke(null);
            if (application instanceof Application) {
                context = ((Application) application).getApplicationContext();
                if (context != null) {
                    appContext = context;
                    return context;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static final class ReadRequest {
        final Object buffer;
        final int offset;
        final int size;
        final boolean blocking;
        int encoding;
        final int returnCount;

        private ReadRequest(Object buffer, int offset, int size, boolean blocking,
                            int encoding, int returnCount) {
            this.buffer = buffer;
            this.offset = offset;
            this.size = size;
            this.blocking = blocking;
            this.encoding = encoding;
            this.returnCount = returnCount;
        }

        static ReadRequest from(Object[] args) {
            if (args.length < 2 || !(args[1] instanceof Integer)) {
                return null;
            }
            Object buffer = args[0];
            int offset = 0;
            int size;
            int encoding;
            int returnCount;
            if (buffer instanceof byte[]) {
                if (args.length < 3 || !(args[2] instanceof Integer)) return null;
                offset = (Integer) args[1];
                size = (Integer) args[2];
                encoding = AudioFormat.ENCODING_PCM_16BIT;
                returnCount = size;
                if (offset < 0 || size < 0 || size > ((byte[]) buffer).length - offset) return null;
            } else if (buffer instanceof short[]) {
                if (args.length < 3 || !(args[2] instanceof Integer)) return null;
                offset = (Integer) args[1];
                size = (Integer) args[2];
                encoding = AudioFormat.ENCODING_PCM_16BIT;
                returnCount = size;
                if (offset < 0 || size < 0 || size > ((short[]) buffer).length - offset) return null;
            } else if (buffer instanceof float[]) {
                if (args.length < 3 || !(args[2] instanceof Integer)) return null;
                offset = (Integer) args[1];
                size = (Integer) args[2];
                encoding = AudioFormat.ENCODING_PCM_FLOAT;
                returnCount = size;
                if (offset < 0 || size < 0 || size > ((float[]) buffer).length - offset) return null;
            } else if (buffer instanceof ByteBuffer) {
                size = (Integer) args[1];
                encoding = AudioFormat.ENCODING_PCM_16BIT;
                returnCount = size;
                if (size < 0 || !((ByteBuffer) buffer).isDirect()
                        || size > ((ByteBuffer) buffer).limit() - ((ByteBuffer) buffer).position()) {
                    return null;
                }
                offset = ((ByteBuffer) buffer).position();
            } else {
                return null;
            }
            boolean blocking = args.length < 4 || !(args[args.length - 1] instanceof Integer)
                    || (Integer) args[args.length - 1] == AudioRecord.READ_BLOCKING;
            if (args.length == 3 && buffer instanceof ByteBuffer && args[2] instanceof Integer) {
                blocking = (Integer) args[2] == AudioRecord.READ_BLOCKING;
            }
            return new ReadRequest(buffer, offset, size, blocking, encoding, returnCount);
        }

        int frames(int channels) {
            if (buffer instanceof byte[] || buffer instanceof ByteBuffer) {
                return size / (channels * bytesPerSample(encoding));
            }
            return size / channels;
        }

        void write(byte[] pcm, int frames, int channels) {
            int sampleCount = frames * channels;
            int completeBytes = sampleCount * bytesPerSample(encoding);
            if (pcm == null || pcm.length < completeBytes) {
                fillSilence();
                return;
            }
            if (buffer instanceof byte[]) {
                byte[] target = (byte[]) buffer;
                System.arraycopy(pcm, 0, target, offset, completeBytes);
                fillTrailingBytes(target, offset + completeBytes, size - completeBytes, encoding);
            } else if (buffer instanceof short[]) {
                short[] target = (short[]) buffer;
                ByteBuffer source = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < sampleCount; i++) target[offset + i] = source.getShort();
                Arrays.fill(target, offset + sampleCount, offset + size, (short) 0);
            } else if (buffer instanceof float[]) {
                float[] target = (float[]) buffer;
                ByteBuffer source = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < sampleCount; i++) target[offset + i] = source.getFloat();
                Arrays.fill(target, offset + sampleCount, offset + size, 0.0f);
            } else if (buffer instanceof ByteBuffer) {
                ByteBuffer direct = (ByteBuffer) buffer;
                ByteBuffer target = direct.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                target.position(offset);
                target.put(pcm, 0, completeBytes);
                for (int i = completeBytes; i < size; i++) target.put((byte) 0);
                direct.position(offset + size);
            }
        }

        void fillSilence() {
            if (buffer instanceof byte[]) {
                fillTrailingBytes((byte[]) buffer, offset, size, encoding);
            } else if (buffer instanceof short[]) {
                Arrays.fill((short[]) buffer, offset, offset + size, (short) 0);
            } else if (buffer instanceof float[]) {
                Arrays.fill((float[]) buffer, offset, offset + size, 0.0f);
            } else if (buffer instanceof ByteBuffer) {
                ByteBuffer direct = (ByteBuffer) buffer;
                ByteBuffer target = direct.duplicate();
                target.position(offset);
                for (int i = 0; i < size; i++) target.put((byte) 0);
                direct.position(offset + size);
            }
        }

        private static void fillTrailingBytes(byte[] target, int offset, int count, int encoding) {
            if (count > 0) {
                Arrays.fill(target, offset, offset + count,
                        encoding == AudioFormat.ENCODING_PCM_8BIT ? (byte) 0x80 : (byte) 0);
            }
        }

        private static int bytesPerSample(int encoding) {
            if (encoding == AudioFormat.ENCODING_PCM_FLOAT
                    || encoding == AudioFormat.ENCODING_PCM_32BIT) return 4;
            if (encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED) return 3;
            return encoding == AudioFormat.ENCODING_PCM_8BIT ? 1 : 2;
        }
    }
}
