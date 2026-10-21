package com.coolzyd.audioforwarder;

import android.Manifest;
import android.app.Service;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;

public final class AudioAccessService extends Service {
    private final IForwardingAudio.Stub binder = new IForwardingAudio.Stub() {
        @Override
        public boolean isForwarding() {
            return AudioSession.PCM.isActive();
        }

        @Override
        public int registerClient(int sampleRate, int channels, int encoding) {
            enforceMicrophonePermission();
            int id = AudioSession.PCM.registerClient(sampleRate, channels, encoding);
            synchronized (AudioSession.CLIENT_OWNERS) {
                AudioSession.CLIENT_OWNERS.put(id, Binder.getCallingUid());
            }
            return id;
        }

        @Override
        public byte[] readAudio(int clientId, int frames, boolean waitForData) {
            enforceClientOwner(clientId);
            if (!AudioSession.PCM.isActive()) {
                return null;
            }
            return AudioSession.PCM.read(clientId, frames, waitForData);
        }

        @Override
        public void unregisterClient(int clientId) {
            enforceClientOwner(clientId);
            AudioSession.PCM.unregisterClient(clientId);
            synchronized (AudioSession.CLIENT_OWNERS) {
                AudioSession.CLIENT_OWNERS.remove(clientId);
            }
        }
    };

    @Override
    public IBinder onBind(android.content.Intent intent) {
        return binder;
    }

    private void enforceMicrophonePermission() {
        if (Binder.getCallingUid() != Process.myUid()
                && checkCallingPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Microphone permission is required to receive forwarded audio");
        }
    }

    private void enforceClientOwner(int clientId) {
        int uid = Binder.getCallingUid();
        synchronized (AudioSession.CLIENT_OWNERS) {
            Integer owner = AudioSession.CLIENT_OWNERS.get(clientId);
            if (owner == null || owner != uid) {
                throw new SecurityException("Audio client does not belong to this caller");
            }
        }
    }
}
