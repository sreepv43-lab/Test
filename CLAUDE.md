# CLAUDE.md

This file provides guidance for AI assistants (Claude and others) working in this repository.

## Project Overview

- **Repository:** sreepv43-lab/Test
- **App:** StreamHub, an Android TV + tablet app compatible with Stremio addons, with downloads to any connected drive
- **Stack:** Kotlin 2.0, Jetpack Compose (Material 3), Media3 ExoPlayer, OkHttp, kotlinx.serialization, Coil
- **Build:** Gradle (wrapper 8.11.1), AGP 8.7, compileSdk/targetSdk 35, minSdk 23, single `:app` module

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
    │   └── ui/         # Compose navigation, screens, components (tvFocus)
    └── test/           # JVM unit tests (protocol, URLs, file names)
```

Conventions:
- Keep `addon/Models.kt`, `AddonUrls.kt`, `StreamResolver.kt`, `AddonClient.kt` and `download/FileNames.kt` free of Android imports so they stay unit-testable on the JVM.
- Every focusable UI element should use `Modifier.tvFocus()` (placed before `clickable`) so it is visible when navigating with a remote.
- Dependencies are wired manually in `AppContainer` (`StreamHubApp.kt`); ViewModels are created with `appViewModel { container, savedState -> ... }`.

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

GitHub Actions (`.github/workflows/android.yml`) runs unit tests and builds debug + release APKs on every push; APKs are uploaded as the `streamhub-apks` artifact.

## Updating This File

When significant changes are made to the project (new tech stack, new conventions, new tooling), update the relevant sections of this file so future AI assistants have accurate context.
