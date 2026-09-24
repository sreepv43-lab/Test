# StreamHub

An Android TV and tablet media center that works with **Stremio addons**. It also downloads
videos to **any connected drive**, such as internal storage, an SD card or a USB drive.

## Features

- **Stremio addon support.** Install any addon by its manifest URL (`https://…/manifest.json`)
  or a `stremio://` link. The app implements the addon protocol's `catalog`, `meta`, `stream` and
  `subtitles` resources, catalog extras (`search`, `genre`, `skip`) and `idPrefixes`. It also
  applies `behaviorHints` such as `proxyHeaders`, `filename` and `configurable`. Cinemeta and
  OpenSubtitles are installed on first launch.
- **TV and tablet UI.** A Jetpack Compose UI with a side navigation rail and clear focus
  highlights for D-pad remotes. Touch works on tablets and phones. The app appears in both the
  Android TV (Leanback) launcher and the normal launcher.
- **Browsing.** Home rows for every addon catalog, "See all" grids with genre filters and
  infinite scroll, search across all addons, detail pages, and season/episode lists for series.
- **Playback.** A built-in Media3/ExoPlayer player with MP4, MKV, WebM, HLS and DASH support.
  It includes subtitles from the stream and from subtitle addons, audio and subtitle track
  selection, and resume positions. There's also a "Continue watching" row, and an "Open in
  external player" option (VLC, MX Player, …).
- **Downloads to any drive.**
  - *Choose a folder on any drive…* uses the system folder picker, so the app can write to any
    folder on a USB drive, SD card or internal storage.
  - Every mounted volume is also listed directly, so plugged-in USB drives work even on Android
    TV devices that have no folder picker. In that case files go to the app's folder on that
    drive.
  - Downloads resume after pauses, network drops or app restarts (HTTP range requests). They
    run in a foreground service with a progress notification.
  - Downloaded videos play from the Downloads screen.
- **Torrent streams.** Many addons return torrent (`infoHash`) streams. To play or download them
  in the app, enter the address of a Stremio streaming server in Settings, for example Stremio
  Service running on a PC or NAS: `http://192.168.1.20:11470`. Without one, torrent streams can
  be handed to an installed torrent app.

## Build

Requirements: JDK 17 and the Android SDK (API 35).

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # unit tests
```

Every push also builds the APKs on GitHub Actions (**Actions → Android build → Artifacts →
streamhub-apks**).

## Install

- **Tablet or phone:** copy the APK to the device and open it (allow "install unknown apps").
- **Android TV:**
  - `adb connect <tv-ip> && adb install app-debug.apk`, or
  - use a sideloading app such as *Downloader* or *Send Files to TV*.

## Project layout

```
app/src/main/java/io/github/sreepv43/streamhub/
├── addon/      Stremio addon protocol: models, URL building, HTTP client, repository
├── data/       Settings and watch history
├── download/   Download engine, storage (SAF + mounted volumes), foreground service
├── player/     ExoPlayer activity
└── ui/         Compose UI: navigation, screens, TV-focus components
```

## Notes

Only stream or download content you have the rights to. Addons are third-party services, and
StreamHub doesn't host or provide any content.
