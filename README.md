# SiphonDSP

<p align="center">
  <img alt="RootlessJamesDSP icon" width="96" src="img/icons/web/icon-192.png">
</p>

<p align="center">
  <strong>Rootless, system-wide JamesDSP audio processing for Android.</strong>
</p>

<p align="center">
  <a href="https://github.com/Lost-Saint/RootlessJamesDSP/blob/master/LICENSE">
    <img alt="License" src="https://img.shields.io/github/license/Lost-Saint/RootlessJamesDSP">
  </a>
</p>

It uses Android internal audio capture to route app audio through [libjamesdsp](https://github.com/james34602/JamesDSPManager), so it can provide real DSP effects without depending only on Android's built-in `audiofx` effects.

This fork is not the upstream Google Play or F-Droid build. Install builds from this repository only if you trust this fork and understand the rootless capture limitations below.

<p align="center">
  <img alt="Screenshot" width="250" src="img/screenshot1.png">
  <img alt="Screenshot" width="250" src="img/screenshot7.png">
</p>

## Status

This project currently keeps the upstream SiphonDSP package structure and build flavors, while carrying fork-specific UI and theme changes. The default rootless package id is still:

```text
app.siphondsp
```

## Features

- System-wide JamesDSP processing without root
- Shizuku or ADB based setup flow
- Presets, backup/restore, IRS/DDC/Liveprog support
- Graphic EQ, parametric EQ, convolver, bass, compressor, reverb, crossfeed, tube, stereo wide, and other JamesDSP modules
- Android 15+ compatibility path for media projection restrictions

## Limitations

Rootless processing is based on Android internal audio capture. That means:

- Apps that block internal audio capture remain unprocessed, such as stock Spotify, Google Chrome, and SoundCloud.
- Some audio effect apps cannot coexist with it, especially apps using Android's `DynamicsProcessing` API.
- Audio latency is higher than a native/root audio effect.
- Hardware-accelerated playback or fast tracks can interfere with routing on some devices.
- Android 15 and newer may require disabling screen share protection in Developer options, or granting the required projection permission through Shizuku/ADB when prompted by the app.

Apps commonly reported to work include YouTube, YouTube Music, Amazon Music, Deezer, Poweramp, Substreamer, Twitch, Apple Music, Vinyl Music Player, and patched Spotify/ReVanced builds.

## Spotify And Unsupported Apps

Spotify blocks internal audio capture by default. To process Spotify audio, patch Spotify with ReVanced:

1. Install [ReVanced Manager](https://github.com/revanced/revanced-manager/releases).
2. Install the unpatched Spotify APK.
3. Open ReVanced Manager, select Spotify, and enable the `Remove screen capture restriction` patch.
4. Patch and install the generated APK.
5. Start Spotify again and enable RootlessJamesDSP processing.

The same universal patch can sometimes help other apps that block capture, but it cannot fix apps that use native AAudio playback or strong anti-tamper checks.

## Building

Requirements:

- JDK 17
- Android SDK with the compile SDK used by the project
- Android NDK and CMake, installed through Android Studio or `sdkmanager`

Useful build commands:

```bash
# Rootless debug APK
./gradlew :app:assembleRootlessFullDebug

# Rootless F-Droid-style debug APK without Firebase dependencies
./gradlew :app:assembleRootlessFdroidDebug

# Rooted JamesDSP/Magisk-compatible debug APK
./gradlew :app:assembleRootFullDebug
```

Generated APKs are written under:

```text
app/build/outputs/apk/
```

## BMW three-bank PEQ

See [Three-bank parametric EQ](docs/THREE_BANK_PEQ.md) for signal flow, graph
editing, undo/redo, copy tools, presets, import/export, limits, migration, and
troubleshooting.

Private builds and recovery steps are documented in
[PRIVATE_BUILD_AND_RECOVERY.md](PRIVATE_BUILD_AND_RECOVERY.md).

## Using Root

The main focus of this fork is the rootless build. A rooted/Magisk-compatible flavor still exists, but it follows the upstream SiphonDSP behavior and may not receive the same testing. See [BUILD_ROOT.md](BUILD_ROOT.md) for details.

## Upstream

This fork is based on SiphonDSP by Tim Schneeberger:

- Upstream repository: <https://github.com/timschneeb/RootlessJamesDSP>
- Original rootless implementation: SiphonDSP
- DSP engine: [JamesDSP / libjamesdsp](https://github.com/james34602/JamesDSPManager) by [James Fung](https://github.com/james34602)

Please report fork-specific issues in this repository. For upstream SiphonDSP behavior, check the upstream project first.

## Credits

- [RootlessJamesDSP](https://github.com/timschneeb/RootlessJamesDSP) for the Android app, rootless routing implementation, UI foundation, and build structure
- [JamesDSP](https://github.com/james34602/JamesDSPManager) for the DSP engine
- Tachiyomi for the theming and backup system foundations used upstream
- RootlessJamesDSP translators and contributors

## License

This project follows the upstream license. See [LICENSE](LICENSE).
