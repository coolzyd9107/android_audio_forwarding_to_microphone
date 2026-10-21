package com.coolzyd.audioforwarder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;

public final class ForwardingService extends Service {
    public static final String ACTION_START = "com.coolzyd.audioforwarder.START";
    public static final String ACTION_STOP = "com.coolzyd.audioforwarder.STOP";
    public static final String ACTION_SET_GAIN = "com.coolzyd.audioforwarder.SET_GAIN";
    public static final String EXTRA_AUDIBLE = "audible_output";
    public static final String EXTRA_GAIN = "forwarding_gain";

    private static final String CHANNEL_ID = "audio_forwarding";
    private static final int NOTIFICATION_ID = 41;

    private final PcmRingBuffer pcm = AudioSession.PCM;
    private MediaPlayer mediaPlayer;
    private Thread decoderThread;
    private long streamGeneration;

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
        if (ACTION_START.equals(action) && intent.getData() != null) {
            float gain = intent.getFloatExtra(EXTRA_GAIN, 1.0f);
            startForegroundCompat();
            startForwarding(intent.getData(), intent.getBooleanExtra(EXTRA_AUDIBLE, false), gain);
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopCurrentSession();
        super.onDestroy();
    }

    private void startForwarding(Uri uri, boolean audible, float gain) {
        stopCurrentSession();
        pcm.setGain(gain);
        streamGeneration = pcm.beginStream();
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
        streamGeneration++;
        pcm.stopStream();
        Thread currentDecoder = decoderThread;
        decoderThread = null;
        if (currentDecoder != null) {
            currentDecoder.interrupt();
        }
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

    private void startForegroundCompat() {
        Notification notification = buildNotification("正在准备音频");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
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
