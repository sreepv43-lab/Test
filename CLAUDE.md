# CLAUDE.md

This file provides guidance for AI assistants (Claude and others) working in this repository.

## Project Overview

- **Repository:** sreepv43-lab/Test
- **App:** StreamHub, an Android TV + tablet app compatible with Stremio addons, with downloads to any connected drive
- **Stack:** Kotlin 2.0, Jetpack Compose (Material 3), Media3 ExoPlayer, OkHttp, kotlinx.serialization, Coil, libtorrent4j (built-in torrent engine)
- **Build:** Gradle (wrapper 8.11.1), AGP 8.7, compileSdk/targetSdk 35, minSdk 23, single `:app` module

## Repository Structure

```
/
├── CLAUDE.md
├── README.md
├── .github/workflows/android.yml   # CI: unit tests + debug/release APKs
└── app/src/
    ├── main/java/io/github/sreepv43/streamhub/
    │   ├── addon/      # Stremio protocol (pure Kotlin + AddonRepository), StreamRanking, Episodes, AddonCatalog
    │   ├── data/       # Settings, WatchHistory, Library (My List), IntroMemory, CrashReports (SharedPreferences/files)
    │   ├── download/   # Downloader, DownloadService, DownloadStorage (SAF + volumes)
    │   ├── player/     # PlayerActivity (Media3: up next, skip intro, subtitle options), SubtitleShift
    │   ├── sync/       # StremioImport (one-time account copy), Trakt (device login + sync), SyncIds
    │   ├── torrent/    # TorrentEngine (libtorrent4j), TorrentHttpServer (local HTTP), TorrentLinks
    │   ├── update/     # UpdateCheck (pure) + Updater (GitHub releases -> APK install)
    │   └── ui/         # TvShell (side menu + TV focus), AppRoot (NavHost), screens, components
    └── test/           # JVM unit tests (protocol, URLs, file names, torrent engine end-to-end)
                        # + Robolectric Compose tests driving the UI with remote key presses (ui/)
```

Conventions:
- Keep `addon/Models.kt`, `AddonUrls.kt`, `StreamResolver.kt`, `StreamRanking.kt`, `Episodes.kt`, `AddonClient.kt`, `download/FileNames.kt`, `player/SubtitleShift.kt`, `sync/SyncIds.kt`, `update/UpdateCheck.kt` and everything in `torrent/` free of Android imports so they stay unit-testable on the JVM.
- Torrents are addressed inside the app by logical URLs `torrent:?src=<magnet or .torrent url>&file=<idx>` (stored in downloads, passed to the player); `AppContainer.playableUrl()` / `TorrentHttpServer.urlFor()` turn them into `http://127.0.0.1:<port>/stream?...` at use time because the port changes per launch.
- The torrent engine test needs the desktop libtorrent native library and `LD_PRELOAD=libjsig.so` (libtorrent installs signal handlers); `app/build.gradle.kts` sets both up for `Test` tasks.
- Every focusable UI element should use `Modifier.tvFocus()` (placed before `clickable`) so it is visible when navigating with a remote; it also lets `TvShell` remember and restore the selection per page. Elements in lazy lists pass a stable `key` (e.g. the meta or episode id, inside a `LocalFocusKeyScope` per row), because composite key hashes change with nested prefetch.
- Vertical pages of rows (Home, Search) provide `LocalBringIntoViewSpec provides TvScrolling.None` and move one row per press with `Modifier.alignRowOnFocus(listState, index)`; horizontal rows use `PosterRow` (edge scrolling + nested prefetch).
- Remote navigation lives in `ui/TvShell.kt`: the side menu can only take focus after a fresh Left at a page's left edge; every NavHost destination must be wrapped in `TvPage` (AppRoot's `page(...)` helper does it); horizontal lists use `Modifier.tvRow()` so Left/Right don't leave them. `TvShellTest` covers these flows — extend it when changing navigation.
- Dependencies are wired manually in `AppContainer` (`StreamHubApp.kt`); ViewModels are created with `appViewModel { container, savedState -> ... }`.
- New preferences go in `data/Settings.kt` as `Setting<T>` (`int`/`bool`/`string`/`float` helpers), read with `.flow`/`.value` and changed with `.set()`.
- UI tests: `TvShellTest` uses a fake app; `ScreensTest` runs real screens/components with `application = StreamHubApp::class` (the real `AppContainer`). Tests must not hit the network.
- Signing and API credentials only come from GitHub secrets / environment variables (`STREAMHUB_KEYSTORE` (base64 .jks), `STREAMHUB_KEYSTORE_PASSWORD`, optional `STREAMHUB_KEY_ALIAS`, `TRAKT_CLIENT_ID`, `TRAKT_CLIENT_SECRET`); never commit them. Without the keystore secrets, builds fall back to the debug key.
- `versionCode` is the CI run number (`GITHUB_RUN_NUMBER`); the in-app updater compares it with the `build-N` tag of the latest GitHub release.

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
./gradlew testDebugUnitTest
```

## CI/CD

GitHub Actions (`.github/workflows/android.yml`) runs unit tests and builds debug + release APKs on every push; APKs are uploaded as the `streamhub-apks` artifact, and pushes to the default branch or the app branch publish the release APKs as a `build-N` GitHub Release (the in-app updater reads the latest one).

## Updating This File

When significant changes are made to the project (new tech stack, new conventions, new tooling), update the relevant sections of this file so future AI assistants have accurate context.
