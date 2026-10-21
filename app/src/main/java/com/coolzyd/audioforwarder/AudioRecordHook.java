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
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

public final class AudioRecordHook extends XposedModule {
    private static final String MODULE_PACKAGE = "com.coolzyd.audioforwarder";
    private static final Object LOCK = new Object();
    private static final Map<AudioRecord, Integer> CLIENTS = new WeakHashMap<>();
    private static volatile IForwardingAudio remote;
    private static volatile Context appContext;
    private static boolean binding;

    private boolean hooksInstalled;

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
    public void onPackageReady(PackageReadyParam param) {
        if (hooksInstalled || MODULE_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        hooksInstalled = true;
        hookApplicationAttach();
        hookRead("read", byte[].class, int.class, int.class);
        hookRead("read", byte[].class, int.class, int.class, int.class);
        hookRead("read", short[].class, int.class, int.class);
        hookRead("read", short[].class, int.class, int.class, int.class);
        hookRead("read", float[].class, int.class, int.class, int.class);
        hookRead("read", ByteBuffer.class, int.class);
        hookRead("read", ByteBuffer.class, int.class, int.class);
        hookRelease();
    }

    private void hookApplicationAttach() {
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            hook(attach).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                Object application = chain.getThisObject();
                if (application instanceof Application) {
                    Context context = ((Application) application).getApplicationContext();
                    appContext = context != null ? context : (Application) application;
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
                Object result = chain.proceed();
                if (chain.getThisObject() instanceof AudioRecord && result instanceof Integer
                        && (Integer) result > 0) {
                    try {
                        inject((AudioRecord) chain.getThisObject(), chain.getArgs().toArray(), (Integer) result);
                    } catch (Throwable error) {
                        log(android.util.Log.WARN, "AudioForwarder", "Audio injection failed", error);
                    }
                }
                return result;
            });
        } catch (NoSuchMethodException ignored) {
            // AudioRecord exposes different read overloads across Android releases.
        } catch (Throwable error) {
            log(android.util.Log.WARN, "AudioForwarder", "Could not hook AudioRecord." + name, error);
        }
    }

    private static void inject(AudioRecord record, Object[] args, int result) throws Exception {
        int channels = Math.max(1, record.getChannelCount());
        int sampleRate = Math.max(8000, record.getSampleRate());
        int encoding = record.getAudioFormat();
        if (encoding != AudioFormat.ENCODING_PCM_FLOAT && encoding != AudioFormat.ENCODING_PCM_8BIT) {
            encoding = AudioFormat.ENCODING_PCM_16BIT;
        }
        int bytesPerSample = bytesPerSample(encoding);
        int frames;
        if (args[0] instanceof byte[]) {
            frames = result / (channels * bytesPerSample);
        } else if (args[0] instanceof short[]) {
            encoding = AudioFormat.ENCODING_PCM_16BIT;
            bytesPerSample = 2;
            frames = result / channels;
        } else if (args[0] instanceof float[]) {
            encoding = AudioFormat.ENCODING_PCM_FLOAT;
            bytesPerSample = 4;
            frames = result / channels;
        } else if (args[0] instanceof ByteBuffer) {
            frames = result / (channels * bytesPerSample);
        } else {
            return;
        }
        if (frames <= 0 || frames > 8192) {
            return;
        }

        IForwardingAudio service = connectIfNeeded();
        if (service == null) {
            return;
        }
        Integer clientId;
        synchronized (LOCK) {
            clientId = CLIENTS.get(record);
        }
        if (clientId == null) {
            int created = service.registerClient(sampleRate, channels, encoding);
            synchronized (LOCK) {
                CLIENTS.put(record, created);
            }
            clientId = created;
        }
        byte[] pcm = service.readAudio(clientId, frames);
        int completeBytes = frames * channels * bytesPerSample;
        if (pcm == null || pcm.length < completeBytes) {
            return;
        }
        if (args[0] instanceof byte[]) {
            byte[] target = (byte[]) args[0];
            int offset = (Integer) args[1];
            System.arraycopy(pcm, 0, target, offset, completeBytes);
            int trailingBytes = result - completeBytes;
            if (trailingBytes > 0) {
                Arrays.fill(target, offset + completeBytes, offset + result,
                        encoding == AudioFormat.ENCODING_PCM_8BIT ? (byte) 0x80 : (byte) 0);
            }
        } else if (args[0] instanceof short[]) {
            short[] target = (short[]) args[0];
            int offset = (Integer) args[1];
            ByteBuffer source = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < frames * channels; i++) {
                target[offset + i] = source.getShort();
            }
        } else if (args[0] instanceof float[]) {
            float[] target = (float[]) args[0];
            int offset = (Integer) args[1];
            ByteBuffer source = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < frames * channels; i++) {
                target[offset + i] = source.getFloat();
            }
        } else if (args[0] instanceof ByteBuffer) {
            ByteBuffer target = ((ByteBuffer) args[0]).duplicate();
            target.order(ByteOrder.LITTLE_ENDIAN);
            if (completeBytes <= target.limit()) {
                target.put(pcm, 0, completeBytes);
            }
        }
    }

    private static IForwardingAudio connectIfNeeded() {
        IForwardingAudio current = remote;
        if (current != null) {
            return current;
        }
        Context context = appContext;
        if (context == null) {
            return null;
        }
        synchronized (LOCK) {
            if (remote != null) {
                return remote;
            }
            if (!binding) {
                Intent intent = new Intent().setComponent(new ComponentName(MODULE_PACKAGE,
                        MODULE_PACKAGE + ".AudioAccessService"));
                binding = context.bindService(intent, CONNECTION, Context.BIND_AUTO_CREATE);
            }
        }
        return remote;
    }

    private static int bytesPerSample(int encoding) {
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            return 4;
        }
        return encoding == AudioFormat.ENCODING_PCM_8BIT ? 1 : 2;
    }
}
