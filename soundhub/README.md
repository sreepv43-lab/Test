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
- **Home** carries on where you were: what is playing, or **Resume** for the last session (nothing
  plays by itself at startup), then recently played, favourite and recently added albums.
- **Search by album.** Each album shows once, however many users have it. The album page plays from
  the best copy (playable, slot reported free, most songs, best quality, shortest queue); **Sources**
  lists every user's copy to pick another. Playing an album keeps the whole album in the queue and
  downloads the chosen song first.
- **Artists:** well-known artists by genre (pop, rock, hip-hop, jazz, classical, film scores, Indian
  film and classical, and "Dolby Atmos picks"). OK on one searches Soulseek for their music, for all
  music, lossless, Hi-Res or Dolby Atmos; Back returns to the list.
- **Filters** as separate choices instead of one row of overlapping chips: quality (lossless,
  Hi-Res, lossy), channels (stereo, surround, Dolby Atmos), format (MP3, AAC / MP4, FLAC, ALAC,
  WAV / AIFF, DSD, Dolby / DTS) and availability. The shortcuts All, Lossless, Hi-Res, Dolby Atmos
  and MP3 are one press away.
  - Labels come from file names and the attributes users' clients report (bitrate, sample rate,
    bit depth). Examples: "FLAC 24/96", "MP3 320", "DD+ Atmos".
  - The app then reads each file's header as soon as the first bytes arrive. This fixes labels
    that are wrong, for example "24-bit" files that are really 16-bit, or `.m4a` files that are
    AAC, ALAC or Dolby.
- **Library** with artwork (the folder's cover, or the one embedded in the songs), real names from
  the files' tags, its own search, sorting, and Albums, Songs, Artists, Playlists and Favourites.
  Songs on an unplugged USB drive stay listed as **Drive disconnected** until you remove them.
- **Playing:** a player bar under every page (Down from the end of a page reaches it), seek ±10 s
  with Left/Right on the seek bar, shuffle, repeat, a queue you can reorder, playlists, favourites
  and a sleep timer. A song the device can't play is never swapped for another one.
- **Atmos & sound page.**
  - Atmos music: its own search for Atmos releases, and the Atmos albums you already have.
  - Audio output: what the playing file is, what this device *reports* its HDMI output takes, the
    output mode you chose (Automatic, or forced HDMI / ARC), and what the player actually sends.
  - A ✓ on a format badge means the file itself confirmed Atmos: the JOC flag in a Dolby Digital
    Plus stream, or the 16-channel substream in TrueHD.
- **Now playing** shows whether audio goes to the receiver as a bitstream ("Bitstream to the
  receiver: Dolby Atmos (DD+)") or is decoded on the device. If Atmos gets lost on the way, it
  says why.
- **Other features:**
  - Background playback with system media controls. Notification permission is asked the first
    time something plays or downloads, with the reason.
  - Transfers are grouped: downloading, waiting in queues, needs attention, completed.
  - UPnP opens the listening port on your router.
  - Colour themes under **Settings → Appearance**: Teal, Ocean, Violet, Amber, Crimson, Pure black
    (for OLED TVs) and Light (for tablets in daylight). A theme applies at once.
  - If SoundHub ever closes because of an error, the details are kept under Settings → About (on the
    device only) so they can be reported.

## Using it with the remote

- **Left** at the left edge of a page opens the menu (Home, Search, Artists, Library, Transfers,
  Atmos & sound, Now playing, Settings). Use Up/Down to move and OK to choose. **Right** or **Back** closes
  the menu without changing page.
- **Back** goes to the previous page (also after choosing another section in the menu) and puts the
  selection back where it was. Home is at the bottom, and Back there leaves the app; music keeps
  playing.
- **Search box:** press OK on it to type, then press the keyboard's Search key. **Voice** appears
  when the box has voice input. Recent searches sit under the box and run again with one press.
- **Filter chips** always stay in the same places and show how many albums each one matches.
  Results that arrive later are added at the end, so the list doesn't move while you browse it.
- **Songs:** OK plays from that song; Right then OK opens **More** (play next, add to queue, add to
  playlist, favourite, download, delete).
- Pressing play opens **Now playing** with Play/Pause selected. Up reaches the seek bar, where
  Left/Right jump 10 seconds. Back returns to the album. The remote's play/pause key works
  everywhere.
- Menus open on a safe choice: removing or deleting anything asks again, with Keep selected.

## Getting Atmos to the receiver

| Connection | DD+ Atmos (streaming rips: .m4a/.ec3) | TrueHD Atmos (Blu-ray rips: .mka/.thd) | Multichannel PCM |
|---|---|---|---|
| Box → receiver HDMI input | ✅ | ✅ | ✅ |
| TV eARC ↔ receiver eARC | ✅ | ✅ | ✅ |
| TV → receiver over ARC | ✅ (if the TV passes DD+ through) | ❌ | ❌ (becomes stereo) |

A TV with eARC connected to a receiver with only ARC works as plain ARC. For TrueHD Atmos, plug
the Google TV box into the receiver's HDMI input. The receiver passes the picture on to the TV.

On the box, go to Settings → Display & Sound → Advanced sound settings → Surround sound and pick
**Auto**. If SoundHub's Atmos & sound page still says DD+ is not reported, set its Audio output →
Output mode to **HDMI to the receiver**.

## Limits

- Soulseek is people sharing their own collections; it is not a streaming service. A song starts
  when its owner has a free upload slot. **Slot free** is what the user's client reports, not a
  promise; other copies wait in the user's queue. Seeking past what has arrived waits for the download.
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

## Updates

SoundHub checks GitHub for a newer build when it starts (at most every six hours) and under
**Settings → Updates**. Home shows **Update** when one is ready; SoundHub downloads it and Android
asks you to confirm the install. Your library, playlists and sign-in stay. The first time, Android
asks you to allow SoundHub to install apps (Install unknown apps → SoundHub → Allowed).

Updates only install over a build signed with the same key. CI signs SoundHub with the permanent
key in the repository secret `SOUNDHUB_KEYSTORE` (a base64-encoded PKCS12 keystore with the alias
`soundhub`; its password is the secret `SOUNDHUB_KEYSTORE_PASSWORD`, or `soundhub-key` when that
secret isn't set). Without the secret, each build gets a throwaway key and must be installed after
uninstalling the previous one; the updater says so instead of failing.

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
