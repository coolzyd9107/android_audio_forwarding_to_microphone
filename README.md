# Android Audio Forwarder

This project is an Android audio player and a libxposed API 102 module. It decodes a selected audio file to PCM and, while forwarding is active, replaces samples returned by Android's Java `AudioRecord.read()` APIs. Media playback through the phone's normal output is optional.

## Build

Push to `main`/`master`, open a pull request, or run the `Android Audio Forwarder` workflow manually. GitHub Actions builds `app/build/outputs/apk/debug/app-debug.apk` and publishes it as an artifact.

## Install and use

1. Install the APK in a libxposed API 102-compatible framework and enable **Audio Forwarder**.
2. The module declares QQ (`com.tencent.mobileqq`) and Honor of Kings (`com.tencent.tmgp.sgame`) as recommended default scopes. Because `staticScope=false`, add or remove any microphone-capturing apps in the framework manager as needed; the hook implementation remains generic and does not contain per-app logic.
3. Open Audio Forwarder, choose an audio file, optionally enable audible media playback, then start forwarding.
4. Grant microphone permission to the receiving app as usual. Stop forwarding from the app or its notification.

The media checkbox controls only local playback. The selected file is still sent into microphone capture when local playback is disabled.

The mic forwarding gain is an independent 0–200% control. At 0% the injected signal is silent; the optional local player uses normal Android media output and follows the device's media volume without changing the forwarding gain.

The Xposed API 102 scope is declared in `app/src/main/resources/META-INF/xposed/scope.list`. This list supplies recommended defaults to the manager; `staticScope=false` keeps the scope editable. `AudioRecord.read()` is called in the capturing app process. `AudioService` in system_server handles control and routing, not the PCM samples, so hooking it alone cannot replace microphone audio.

## Compatibility

MP3/FLAC decoding uses Android's `MediaCodec` audio decoders. The module uses libxposed API 102 metadata and entry registration, and installs one generic hook on Android's `AudioRecord.read()` framework API in the processes selected in the compatible framework. It does not target QQ, games, or other apps by name. A Java module API cannot replace samples inside the native AudioFlinger/audio HAL service itself; doing so would require a native system/audio-stack modification. Apps using native AAudio/Oboe capture are therefore not covered.
