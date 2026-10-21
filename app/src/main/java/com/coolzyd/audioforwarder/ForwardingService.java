package com.coolzyd.audioforwarder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;

public final class ForwardingService extends Service {
    public static final String ACTION_START = "com.coolzyd.audioforwarder.START";
    public static final String ACTION_START_CAPTURE = "com.coolzyd.audioforwarder.START_CAPTURE";
    public static final String ACTION_STOP = "com.coolzyd.audioforwarder.STOP";
    public static final String ACTION_SET_GAIN = "com.coolzyd.audioforwarder.SET_GAIN";
    public static final String EXTRA_AUDIBLE = "audible_output";
    public static final String EXTRA_GAIN = "forwarding_gain";
    public static final String EXTRA_TARGET_PACKAGE = "target_package";
    public static final String EXTRA_SOURCE_PACKAGE = "source_package";
    public static final String EXTRA_PROJECTION_RESULT_CODE = "projection_result_code";
    public static final String EXTRA_PROJECTION_DATA = "projection_data";

    private static final String CHANNEL_ID = "audio_forwarding";
    private static final int NOTIFICATION_ID = 41;

    private final PcmRingBuffer pcm = AudioSession.PCM;
    private final Object captureLock = new Object();
    private MediaPlayer mediaPlayer;
    private Thread decoderThread;
    private volatile boolean captureRunning;
    private volatile AudioRecord captureRecord;
    private volatile MediaProjection mediaProjection;
    private volatile MediaProjection.Callback projectionCallback;
    private volatile long streamGeneration;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopForwarding();
            return START_NOT_STICKY;
        }
        if (ACTION_SET_GAIN.equals(action)) {
            pcm.setGain(intent.getFloatExtra(EXTRA_GAIN, 1.0f));
            if (!pcm.isActive()) {
                stopSelf(startId);
            }
            return START_NOT_STICKY;
        }
        if ((ACTION_START.equals(action) || ACTION_START_CAPTURE.equals(action))) {
            startForegroundCompat(ACTION_START_CAPTURE.equals(action));
            float gain = intent.getFloatExtra(EXTRA_GAIN, 1.0f);
            String targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE);
            if (targetPackage == null || targetPackage.isEmpty()) {
                stopForwarding();
                return START_NOT_STICKY;
            }
            AudioSession.targetPackage = targetPackage;
            if (ACTION_START_CAPTURE.equals(action)) {
                int resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, 0);
                Intent resultData = getProjectionData(intent);
                String sourcePackage = intent.getStringExtra(EXTRA_SOURCE_PACKAGE);
                if (resultData == null || sourcePackage == null) {
                    stopForwarding();
                    return START_NOT_STICKY;
                }
                startApplicationCapture(sourcePackage, targetPackage, resultCode, resultData, gain);
            } else if (intent.getData() != null) {
                startFileForwarding(intent.getData(), targetPackage,
                        intent.getBooleanExtra(EXTRA_AUDIBLE, false), gain);
            } else {
                stopForwarding();
            }
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopCurrentSession();
        super.onDestroy();
    }

    private Intent getProjectionData(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent.class);
        }
        return intent.getParcelableExtra(EXTRA_PROJECTION_DATA);
    }

    private void startFileForwarding(Uri uri, String targetPackage, boolean audible, float gain) {
        stopCurrentSession();
        AudioSession.targetPackage = targetPackage;
        pcm.setGain(gain);
        streamGeneration = pcm.beginStream(false);
        long generation = streamGeneration;

        decoderThread = new Thread(() -> {
            new PcmDecoder(this, uri, pcm, generation).run();
            if (generation == streamGeneration && pcm.isActive()) {
                updateNotification("音频文件已结束");
            }
        }, "audio-forwarder-decoder");
        decoderThread.start();
        if (audible) {
            startMediaOutput(uri, generation);
        }
        updateNotification("正在转发音频");
    }

    private void startApplicationCapture(String sourcePackage, String targetPackage, int resultCode,
                                         Intent resultData, float gain) {
        stopCurrentSession();
        AudioSession.targetPackage = targetPackage;
        pcm.setGain(gain);
        long session = ++streamGeneration;
        Thread starter = new Thread(() -> runApplicationCapture(
                sourcePackage, resultCode, resultData, session), "audio-forwarder-capture");
        starter.start();
        updateNotification("正在捕获应用音频");
    }

    private void runApplicationCapture(String sourcePackage, int resultCode,
                                       Intent resultData, long session) {
        AudioRecord recorder = null;
        MediaProjection projection = null;
        MediaProjection.Callback localProjectionCallback = null;
        long ringGeneration = -1;
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                throw new UnsupportedOperationException("应用音频捕获需要 Android 10 或更高版本");
            }
            int sourceUid = getPackageManager().getApplicationInfo(sourcePackage, 0).uid;
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            projection = manager.getMediaProjection(resultCode, resultData);
            if (projection == null) {
                throw new SecurityException("系统未授予屏幕/音频捕获权限");
            }
            localProjectionCallback = new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    if (session == streamGeneration) {
                        stopApplicationCapture("系统已停止应用音频捕获");
                    }
                }
            };
            synchronized (captureLock) {
                if (session != streamGeneration) {
                    projection.stop();
                    return;
                }
                projection.registerCallback(localProjectionCallback,
                        new android.os.Handler(Looper.getMainLooper()));
                projectionCallback = localProjectionCallback;
                mediaProjection = projection;
            }

            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUid(sourceUid)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            int sampleRate = PcmRingBuffer.DEFAULT_SOURCE_RATE;
            int channelMask = AudioFormat.CHANNEL_IN_STEREO;
            int encoding = AudioFormat.ENCODING_PCM_16BIT;
            int minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding);
            if (minBuffer <= 0) {
                throw new IllegalStateException("无法创建应用音频采集缓冲区");
            }
            AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(encoding)
                    .build();
            recorder = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(Math.max(minBuffer * 2, sampleRate / 5 * 4))
                    .setAudioPlaybackCaptureConfig(config)
                    .build();
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("应用音频采集器初始化失败");
            }
            synchronized (captureLock) {
                if (session != streamGeneration) {
                    return;
                }
                captureRecord = recorder;
                recorder.startRecording();
                ringGeneration = pcm.beginStream(true);
                captureRunning = true;
            }
            byte[] buffer = new byte[Math.max(4096, minBuffer / 2) & ~3];
            while (captureRunning && session == streamGeneration && pcm.isCurrent(ringGeneration)) {
                int bytes = recorder.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (bytes > 0) {
                    int alignedBytes = bytes & ~3;
                    if (alignedBytes > 0) {
                        byte[] chunk = new byte[alignedBytes];
                        System.arraycopy(buffer, 0, chunk, 0, alignedBytes);
                        pcm.append(ringGeneration, chunk);
                    }
                } else if (bytes < 0 && captureRunning) {
                    throw new IllegalStateException("应用音频采集失败：" + bytes);
                }
            }
        } catch (Exception e) {
            android.util.Log.e("AudioForwarder", "Application audio capture failed", e);
            if (session == streamGeneration) {
                updateNotification("应用不允许捕获或采集已停止");
                pcm.stopStream();
                AudioSession.targetPackage = null;
            }
        } finally {
            if (session == streamGeneration) {
                captureRunning = false;
            }
            if (recorder != null) {
                try {
                    recorder.stop();
                } catch (Exception ignored) {
                }
                recorder.release();
            }
            if (captureRecord == recorder) {
                captureRecord = null;
            }
            if (session != streamGeneration || !pcm.isCurrent(ringGeneration)) {
                stopProjection(projection, localProjectionCallback);
            }
        }
    }

    private void stopApplicationCapture(String message) {
        AudioRecord recorder;
        MediaProjection currentProjection;
        MediaProjection.Callback currentCallback;
        synchronized (captureLock) {
            streamGeneration++;
            captureRunning = false;
            recorder = captureRecord;
            currentProjection = mediaProjection;
            currentCallback = projectionCallback;
            mediaProjection = null;
            projectionCallback = null;
        }
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Exception ignored) {
            }
        }
        pcm.stopStream();
        AudioSession.targetPackage = null;
        stopProjection(currentProjection, currentCallback);
        updateNotification(message);
    }

    private void stopProjection(MediaProjection projection, MediaProjection.Callback callback) {
        if (projection == null) {
            return;
        }
        if (callback != null) {
            try {
                projection.unregisterCallback(callback);
            } catch (Exception ignored) {
            }
        }
        synchronized (captureLock) {
            if (projectionCallback == callback) {
                projectionCallback = null;
            }
            if (mediaProjection == projection) {
                mediaProjection = null;
            }
        }
        try {
            projection.stop();
        } catch (Exception ignored) {
        }
    }

    private void releaseProjection() {
        MediaProjection currentProjection;
        MediaProjection.Callback currentCallback;
        synchronized (captureLock) {
            currentProjection = mediaProjection;
            currentCallback = projectionCallback;
            mediaProjection = null;
            projectionCallback = null;
        }
        stopProjection(currentProjection, currentCallback);
    }

    private void startMediaOutput(Uri uri, long generation) {
        try {
            MediaPlayer player = new MediaPlayer();
            mediaPlayer = player;
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            player.setOnPreparedListener(prepared -> {
                if (generation == streamGeneration) {
                    prepared.setVolume(1.0f, 1.0f);
                    prepared.start();
                } else {
                    prepared.release();
                }
            });
            player.setOnErrorListener((failed, what, extra) -> {
                failed.release();
                if (mediaPlayer == failed) {
                    mediaPlayer = null;
                }
                return true;
            });
            player.setDataSource(this, uri);
            player.prepareAsync();
        } catch (Exception e) {
            android.util.Log.e("AudioForwarder", "Media output failed", e);
            releaseMediaPlayer();
        }
    }

    private void stopForwarding() {
        stopCurrentSession();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void stopCurrentSession() {
        MediaProjection currentProjection;
        MediaProjection.Callback currentCallback;
        synchronized (captureLock) {
            streamGeneration++;
            captureRunning = false;
            currentProjection = mediaProjection;
            currentCallback = projectionCallback;
            mediaProjection = null;
            projectionCallback = null;
        }
        pcm.stopStream();
        AudioSession.targetPackage = null;
        Thread currentDecoder = decoderThread;
        decoderThread = null;
        if (currentDecoder != null) {
            currentDecoder.interrupt();
        }
        AudioRecord recorder = captureRecord;
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Exception ignored) {
            }
        }
        stopProjection(currentProjection, currentCallback);
        releaseMediaPlayer();
    }

    private void releaseMediaPlayer() {
        MediaPlayer player = mediaPlayer;
        mediaPlayer = null;
        if (player != null) {
            try {
                player.setOnPreparedListener(null);
                player.setOnErrorListener(null);
                player.release();
            } catch (Exception ignored) {
            }
        }
    }

    private void startForegroundCompat(boolean capture) {
        Notification notification = buildNotification("正在准备音频");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int type = capture ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    : ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            startForeground(NOTIFICATION_ID, notification, type);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private Notification buildNotification(String text) {
        Intent stopIntent = new Intent(this, ForwardingService.class).setAction(ACTION_STOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent stop = PendingIntent.getService(this, 0, stopIntent, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Audio Forwarder")
                .setContentText(text)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "停止", stop).build())
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Audio forwarding", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Audio forwarding playback session");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
