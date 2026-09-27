# SoundHub

A music app for Google TV / Android TV and tablets that searches **Soulseek**, streams songs while
they download, sorts them by **format** (MP3, AAC/MP4, FLAC, ALAC, WAV/AIFF, Hi-Res, DSD, surround)
and has a **Dolby Atmos** page. The app sends Atmos to your AV receiver untouched (passthrough), so
the receiver does the decoding.

It is a separate app from StreamHub. It has its own launcher icon and its own APK.

## Features

- **Soulseek client built in.** Sign in with a Soulseek username and password. A new username is
  registered on first sign-in. Searches, downloads, resume after interruptions, and queue
  positions all work. The app reaches users directly, or through the server's "connect to me"
  relay when they are behind a firewall.
- **Plays while it downloads.** Pick a song or an album and playback starts as soon as the first
  bytes arrive. Finished songs go to the library in `Music/<Artist>/<Album>/`, on internal
  storage, an SD card or a USB drive.
- **Sorted by format.** Search results and the library are grouped by album folder and filtered
  with chips: MP3, AAC / MP4, FLAC, ALAC, WAV / AIFF, Hi-Res, DSD, Surround, Atmos, Other.
  - Labels come from file names and the attributes users' clients report (bitrate, sample rate,
    bit depth). Examples: "FLAC 24/96", "MP3 320", "DD+ Atmos".
  - The app then reads each file's header as soon as the first bytes arrive. This fixes labels
    that are wrong, for example "24-bit" files that are really 16-bit, or `.m4a` files that are
    AAC, ALAC or Dolby.
- **Atmos page.**
  - Shows what your HDMI output accepts.
  - Searches for Atmos releases.
  - Lists the Atmos albums you already have.
  - A ✓ means the file itself confirmed Atmos: the JOC flag in a Dolby Digital Plus stream, or the
    16-channel substream in TrueHD.
- **Now playing** shows whether audio goes to the receiver as a bitstream ("Bitstream to the
  receiver: Dolby Atmos (DD+)") or is decoded on the device. If Atmos gets lost on the way, it
  says why.
- **Other features:**
  - Background playback with system media controls.
  - Downloads keep running in a foreground service.
  - UPnP opens the listening port on your router.

## Getting Atmos to the receiver

| Connection | DD+ Atmos (streaming rips: .m4a/.ec3) | TrueHD Atmos (Blu-ray rips: .mka/.thd) | Multichannel PCM |
|---|---|---|---|
| Box → receiver HDMI input | ✅ | ✅ | ✅ |
| TV eARC ↔ receiver eARC | ✅ | ✅ | ✅ |
| TV → receiver over ARC | ✅ (if the TV passes DD+ through) | ❌ | ❌ (becomes stereo) |

A TV with eARC connected to a receiver with only ARC works as plain ARC. For TrueHD Atmos, plug
the Google TV box into the receiver's HDMI input. The receiver passes the picture on to the TV.

On the box, go to Settings → Display & Sound → Advanced sound settings → Surround sound and pick
**Auto**. If SoundHub's Atmos page still shows ✗, set SoundHub → Settings → Audio output to
**HDMI to the receiver**.

## Limits

- Soulseek is people sharing their own collections; it is not a streaming service. A song starts
  when its owner has a free upload slot. Folders with **Free slot** start at once; others wait in
  the user's queue. Seeking past what has arrived waits for the download.
- Some MP4/M4A files keep their index at the end. Those start playing once fully downloaded.
- SoundHub doesn't share files yet. Some users only send to people who share.
- DSD, APE, WavPack, WMA and AIFF can be downloaded but not played in the app.
- Only download music you are allowed to have where you live.

## Build

```bash
./gradlew :soundhub-core:test        # Soulseek protocol, format detection, library (plain JVM)
./gradlew :soundhub:assembleDebug    # soundhub/app/build/outputs/apk/debug/soundhub-debug.apk
```

Every push builds the APK on GitHub Actions (**Actions → Android build → Artifacts →
soundhub-apks**).

## Install

Every build on this branch (and on the default branch) is published on the
[Releases page](https://github.com/sreepv43-lab/Test/releases) as **SoundHub build N**. Its
direct link has this form:

`https://github.com/sreepv43-lab/Test/releases/download/soundhub-build-N/soundhub-debug.apk`

On Google TV, install the free **Downloader** app, allow it to install unknown apps, type the
link and install. With adb: `adb install -r soundhub-debug.apk`.

## Layout

```
soundhub/
├── core/   # Kotlin/JVM, no Android: slsk/ (protocol client, UPnP), audio/ (classify + probe), library/
└── app/    # Android: Compose TV UI, Media3 player with passthrough, services
```
