# CLAUDE.md

This file provides guidance for AI assistants (Claude and others) working in this repository.

## Project Overview

- **Repository:** sreepv43-lab/Test
- **App:** StreamHub, an Android TV + tablet app compatible with Stremio addons, with downloads to any connected drive
- **Stack:** Kotlin 2.0, Jetpack Compose (Material 3), Media3 ExoPlayer, OkHttp, kotlinx.serialization, Coil, libtorrent4j (built-in torrent engine)
- **Build:** Gradle (wrapper 8.11.1), AGP 8.7, compileSdk/targetSdk 35, minSdk 23; modules `:app` (StreamHub), `:soundhub` and `:soundhub-core`
- **Second app — SoundHub** (`soundhub/`): a Soulseek music client for TV/tablets with format categories and Dolby Atmos passthrough. `:soundhub-core` (`soundhub/core`, plain Kotlin/JVM) holds the Soulseek protocol client, UPnP, format classification/header probing and the library; `:soundhub` (`soundhub/app`) is the Android app (Compose UI, Media3 player, MediaSessionService). See `soundhub/README.md`.

## Repository Structure

```
/
├── CLAUDE.md
├── README.md
├── .github/workflows/android.yml   # CI: unit tests + debug/release APKs
└── app/src/
    ├── main/java/io/github/sreepv43/streamhub/
    │   ├── addon/      # Stremio protocol (pure Kotlin + AddonRepository)
    │   ├── data/       # Settings, WatchHistory (SharedPreferences)
    │   ├── download/   # Downloader, DownloadService, DownloadStorage (SAF + volumes)
    │   ├── player/     # PlayerActivity (Media3)
    │   ├── torrent/    # TorrentEngine (libtorrent4j), TorrentHttpServer (local HTTP), TorrentLinks
    │   └── ui/         # Compose navigation, screens, components (tvFocus)
    └── test/           # JVM unit tests (protocol, URLs, file names, torrent engine end-to-end)
soundhub/
├── core/src/main/kotlin/io/github/sreepv43/soundhub/
│   ├── slsk/       # SoulseekClient (server, peers, transfers), Messages/Wire (protocol), GrowingFile, Upnp
│   ├── audio/      # AudioFormats (classify by name/attributes, FormatFilter), AudioProbe (file headers, Atmos)
│   └── library/    # LibraryStore, SearchResults/SearchSession, Releases (album grouping), LibraryViews, CollectionStore (favourites, playlists, history, resume), MusicDownloads, PathNames
└── app/src/main/java/io/github/sreepv43/soundhub/
    ├── player/     # PlaybackController (ExoPlayer), AudioOutput (passthrough), TransferDataSource, PlaybackService
    ├── service/    # TransferService (foreground while downloading)
    ├── data/       # Settings, LibraryEnricher (tags + embedded covers), CoverCache
    └── ui/         # Compose screens: Home, Search/Release, Library/Album/Artist/Playlist, Transfers, Atmos & sound, Now playing/Queue, Settings
```

Conventions:
- Keep `addon/Models.kt`, `AddonUrls.kt`, `StreamResolver.kt`, `AddonClient.kt`, `download/FileNames.kt` and everything in `torrent/` free of Android imports so they stay unit-testable on the JVM.
- Torrents are addressed inside the app by logical URLs `torrent:?src=<magnet or .torrent url>&file=<idx>` (stored in downloads, passed to the player); `AppContainer.playableUrl()` / `TorrentHttpServer.urlFor()` turn them into `http://127.0.0.1:<port>/stream?...` at use time because the port changes per launch.
- The torrent engine test needs the desktop libtorrent native library and `LD_PRELOAD=libjsig.so` (libtorrent installs signal handlers); `app/build.gradle.kts` sets both up for `Test` tasks.
- Every focusable UI element should use `Modifier.tvFocus()` (placed before `clickable`) so it is visible when navigating with a remote.
- Dependencies are wired manually in `AppContainer` (`StreamHubApp.kt`); ViewModels are created with `appViewModel { container, savedState -> ... }`.
- SoundHub: keep everything Android-free in `:soundhub-core` (tests use a fake Soulseek server/peer on localhost, `FakeNetwork.kt`). The app wires it in `AppContainer` (`SoundHubApp.kt`) and screens read its StateFlows directly. Songs being downloaded play through `slskstream://transfer/<id>/…` URIs (`TransferDataSource`), which block until the bytes arrive.
- SoundHub passthrough: never put FFmpeg ahead of the platform renderers (`EXTENSION_RENDERER_MODE_ON`, not `PREFER`), or Dolby audio gets decoded and Atmos is lost.
- SoundHub remote navigation (`ui/TvShell.kt`, `ui/Navigation.kt`): pages live in a `Navigator` stack (Home is at the bottom; Back pops) inside `SoundHubShell`, which wraps each page in `TvPage` + a `SaveableStateProvider` and puts the player bar (`bottomBar`) under the page. The side menu only takes focus on Left at the page edge; a control that uses Left/Right itself (the seek bar) marks itself with `tvClaimHorizontalKeys()`. Every focusable uses `tvFocus` (pass `key` for list rows so the selection is restored after Back; `pageDefault = true` for the element a page should start on). Use `ListRow`/`RowWithMore`/`Chip`/`ActionButton`/`TvDialog`/`OptionsDialog` (it opens on the first non-destructive option; destructive actions ask again with Keep first); never put a bare `TextField` on a page (it pops the keyboard when passed over) — use `SearchBar` or `TextEntryDialog`. Plain text after the last focusable element can't be scrolled to with a remote; use `ReadableText`. `RemoteNavigationTest` (Robolectric) drives the shell and the real page layouts (`SearchLayout`, `ReleaseLayout`, `TransfersLayout`, `PlayerControls`, `SeekBar`, `PlayerBarLayout`; keep those free of `container` access) with key presses; extend it when navigation changes.
- SoundHub playback: never play a different song than the one chosen (`AppContainer.playLibrary`/`playFolder` return false and the UI says why). Library entries whose files are missing (unplugged drive) are kept and shown as "Drive disconnected"; only `LibraryStore.removeMissing()`, on the listener's request, drops them.

## Development Workflow

### Branch Naming

Feature branches follow the pattern:

```
claude/<short-description>-<sessionId>
```

Example: `claude/add-claude-documentation-wrtGA`

- AI-driven branches always start with `claude/`
- Human feature branches: `feature/<description>` or `fix/<description>`
- Never push directly to `main` or `master` without explicit permission

### Committing

Use clear, descriptive commit messages with a conventional prefix where appropriate:

```
docs: add CLAUDE.md with project documentation
feat: add user authentication module
fix: resolve null pointer in payment processor
refactor: simplify database connection pooling
test: add unit tests for order service
```

### Pushing

Always set the upstream on first push:

```bash
git push -u origin <branch-name>
```

**Retry logic for network failures** (exponential backoff):

```bash
# Attempt 1: immediate
git push -u origin <branch>
# Attempt 2: wait 2s
# Attempt 3: wait 4s
# Attempt 4: wait 8s
# Attempt 5: wait 16s
```

Only retry on network errors, not on authentication (403) or permission failures.

### Pull Requests

- Fetch specific branches rather than all: `git fetch origin <branch-name>`
- Open PRs from your feature branch into `main` (or as specified)
- PRs require review before merge

## Key Conventions for AI Assistants

### General

- **Always read files before editing them** — never modify code you haven't seen
- **Prefer editing existing files** over creating new ones
- **Minimal changes** — only change what is directly requested; avoid refactoring surrounding code
- **No unnecessary comments or docstrings** on code you didn't change
- **Avoid over-engineering** — three similar lines is better than a premature abstraction

### Security

- Never introduce command injection, XSS, SQL injection, or other OWASP Top 10 vulnerabilities
- Only validate at system boundaries (user input, external APIs); trust internal code
- Never commit secrets, credentials, or `.env` files with real values

### Git Safety

- **Never force-push** to `main`/`master`
- **Never skip hooks** (`--no-verify`) unless explicitly instructed
- **Never amend published commits** — create new ones instead
- **Confirm before destructive operations**: `git reset --hard`, `git clean -f`, deleting branches
- Stage specific files by name rather than `git add -A` or `git add .`

### Working on This Repository

1. Develop on the designated branch specified in your task instructions
2. Commit incrementally with clear messages
3. Push to the designated branch when changes are complete
4. Never push to a different branch without explicit permission

## Environment Setup

Requires JDK 17 and the Android SDK (API 35).

```bash
./gradlew assembleDebug
```

## Running Tests

```bash
./gradlew testDebugUnitTest :soundhub-core:test
```

## CI/CD

GitHub Actions (`.github/workflows/android.yml`) runs unit tests and builds debug + release APKs on every push; APKs are uploaded as the `streamhub-apks` artifact.

## Updating This File

When significant changes are made to the project (new tech stack, new conventions, new tooling), update the relevant sections of this file so future AI assistants have accurate context.
