# Android Audio Forwarder

This project is an Android audio forwarder and a libxposed API 102 module. It supports local audio files and Android's app playback capture as sources, forwarding PCM to a selected app's Java `AudioRecord.read()` capture stream.

## Build

Push to `main`/`master`, open a pull request, or run the `Android Audio Forwarder` workflow manually. GitHub Actions builds `app/build/outputs/apk/debug/app-debug.apk` and publishes it as an artifact.

## Install and use

1. Install the APK in a libxposed API 102-compatible framework and enable **Audio Forwarder**.
2. `scope.list` recommends QQ and Honor of Kings. Add the selected target application to the module scope in the libxposed manager, then open Audio Forwarder.
3. Choose **Local file** or **App audio**. For app audio, select the music source and microphone target, grant the system capture prompt, then start forwarding.
4. The target app still needs Android microphone permission. Stop forwarding from the app or its notification.

The app-source mode receives PCM from Android's playback capture pipeline. It does not hook the source music app or read its internal decoder buffer. Android captures the app's rendered output, which may already include source-app/system processing; the target app can also re-encode voice recordings at a lower bitrate. The source must use a capturable playback usage (`MEDIA`, `GAME`, or `UNKNOWN`) and allow capture; Android or the source app may deny it. Captured source audio remains audible according to the source app/system policy. Capture is 48 kHz stereo PCM16; conversion is only applied as required by the target `AudioRecord` format.

The mic forwarding gain is an independent 0–200% control. At 0% the injected signal is silent; the optional local player uses normal Android media output and follows the device's media volume without changing the forwarding gain.

The Xposed API 102 scope is declared in `app/src/main/resources/META-INF/xposed/scope.list`. This list supplies recommended defaults to the manager; `staticScope=false` keeps the scope editable. `AudioRecord.read()` is called in the capturing app process. `AudioService` in system_server handles control and routing, not the PCM samples, so hooking it alone cannot replace microphone audio.

## Compatibility

MP3/FLAC decoding uses Android's `MediaCodec` audio decoders. The module uses libxposed API 102 metadata and entry registration, and installs one generic hook on Android's `AudioRecord.read()` framework API in the processes selected in the compatible framework. It does not target QQ, games, or other apps by name. A Java module API cannot replace samples inside the native AudioFlinger/audio HAL service itself; doing so would require a native system/audio-stack modification. Apps using native AAudio/Oboe capture are therefore not covered.
