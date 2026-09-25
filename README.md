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
- **Built-in torrent engine.** Torrent (`infoHash` / magnet) streams from addons play and download
  directly, with no external streaming server. The engine is libtorrent (via libtorrent4j):
  - It fetches only the selected file, in order, and prioritises the pieces the player needs next,
    so playback starts quickly and seeking works.
  - A small local HTTP server makes torrent files look like normal video URLs. The same player,
    downloader and "Open in external player" work for torrents too.
  - Streaming data goes to a temporary cache on the drive with the most free space. It's deleted a
    few minutes after you stop, and you can also clear it in Settings.
  - While a torrent is connecting or buffering, the player shows peers, speed and progress.
- **Open any link.** The "Open link" screen plays or downloads any video URL (MP4/MKV/HLS/DASH…),
  magnet link, info hash or `.torrent` link. Magnet links, `.torrent` files, video links and shared
  text from other apps open there as well.

## Build

Requirements: JDK 17 and the Android SDK (API 35).

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # unit tests
```

Every push also builds the APKs on GitHub Actions (**Actions → Android build → Artifacts →
streamhub-apks**). There is one APK per CPU type plus `app-universal-*.apk`. Most Android TV boxes
need `armeabi-v7a` (or `arm64-v8a`). If you're unsure, use the universal APK.

The JVM unit tests include an end-to-end test of the torrent engine against a local libtorrent
seeder. It uses the desktop (Linux x86-64) libtorrent build and is skipped on other platforms.

## Install

The newest APK is always here (no sign-in needed):

**https://github.com/sreepv43-lab/Test/releases/latest/download/app-universal-debug.apk**

All builds and the smaller per-CPU APKs are on the
[Releases page](https://github.com/sreepv43-lab/Test/releases).

- **Android TV:**
  1. Install the free **Downloader** app (by AFTVnews) from the Play Store.
  2. Allow it to install unknown apps. The TV asks the first time, or go to Settings → Apps →
     Security & restrictions.
  3. Type the link above into Downloader and install the APK when the download finishes.
- **Tablet / phone:** open the link above in the browser, open the downloaded file, and allow
  "install unknown apps" when asked.
- **With adb:** `adb connect <tv-ip> && adb install -r app-universal-debug.apk`

## Project layout

```
app/src/main/java/io/github/sreepv43/streamhub/
├── addon/      Stremio addon protocol: models, URL building, HTTP client, repository
├── data/       Settings and watch history
├── download/   Download engine, storage (SAF + mounted volumes), foreground service
├── player/     ExoPlayer activity
├── torrent/    Torrent engine (libtorrent4j) and local HTTP streaming server
└── ui/         Compose UI: navigation, screens, TV-focus components
```

## Notes

Only stream or download content you have the rights to. Addons are third-party services, and
StreamHub doesn't host or provide any content. The torrent engine uploads to other peers while
it downloads, as all BitTorrent clients do.
