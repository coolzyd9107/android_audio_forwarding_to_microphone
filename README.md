# Android Audio Forwarder

This project is an Android audio player and a libxposed API 102 module. It decodes a selected audio file to PCM and, while forwarding is active, replaces samples returned by Android's Java `AudioRecord.read()` APIs. Media playback through the phone's normal output is optional.

## Build

Push to `main`/`master`, open a pull request, or run the `Android Audio Forwarder` workflow manually. GitHub Actions builds `app/build/outputs/apk/debug/app-debug.apk` and publishes it as an artifact.

## Install and use

1. Install the APK in a libxposed API 102-compatible framework and enable **Audio Forwarder**.
2. Add the apps that need microphone input to the module scope. The hook is generic and acts on the Android `AudioRecord` framework API; it does not contain app-specific hooks.
3. Open Audio Forwarder, choose an audio file, optionally enable audible media playback, then start forwarding.
4. Grant microphone permission to the receiving app as usual. Stop forwarding from the app or its notification.

The media checkbox controls only local playback. The selected file is still sent into microphone capture when local playback is disabled.

The mic forwarding gain is an independent 0–200% control. At 0% the injected signal is silent; the optional local player uses normal Android media output and follows the device's media volume without changing the forwarding gain.

The exported Binder endpoint only serves registered clients that hold Android's microphone permission. Playback controls remain in a non-exported service.

## Compatibility

MP3/FLAC decoding uses Android's `MediaCodec` audio decoders. The module uses libxposed API 102 metadata and entry registration, and installs one generic hook on Android's `AudioRecord.read()` framework API in the processes selected in the compatible framework. It does not target QQ, games, or other apps by name. A Java module API cannot replace samples inside the native AudioFlinger/audio HAL service itself; doing so would require a native system/audio-stack modification. Apps using native AAudio/Oboe capture are therefore not covered.
