# CLAUDE.md

This file provides guidance for AI assistants (Claude and others) working in this repository.

## Project Overview

- **Repository:** sreepv43-lab/Test
- **Status:** Active
- **Purpose:** Ludo Circuit — an offline, single-file ludo variant played on a
  5x5 circuit (16-square outer ring, 8-square inner ring, centre home).
- **Stack:** Plain HTML, CSS and ES5-flavoured JavaScript. No dependencies, no
  build step, no network calls. Opened directly from disk via `file://`.

## Repository Structure

```
/
├── CLAUDE.md          # This file — AI assistant guidance
├── README.md          # Rules, options and controls
├── index.html         # The whole game (rules engine + UI)
└── test/
    └── simulate.mjs   # Head-less soak test for the rules engine
```

### How index.html is organised

The file carries two scripts, and the split matters:

- `<script id="ludo-rules">` — a **pure, DOM-free** rules engine: board
  geometry, move planning, capture/blockade resolution, win detection and the
  computer player. It attaches itself to `window` or `globalThis`, so Node can
  evaluate it as-is.
- `<script id="ludo-ui">` — everything visual: board construction, token
  layout and animation, the 3D dice, sound, overlays and turn flow.

Keep game logic in the rules block and DOM work in the UI block. The test
extracts the rules block by its `id`, so renaming that script tag breaks the
test.

## Design constraints

- **Single file, offline.** `index.html` must stay self-contained — no CDN
  links, no external fonts, images or scripts.
- **Flat design.** Solid fills, no gradients used for depth, no glossy effects.
  Colours come from CSS custom properties on `:root`; per-player colours are
  selected with `[data-color="..."]`, which sets `--c`, `--c-rgb` and `--c-d`.
- **No side panel.** Game state is communicated on the board itself: the active
  player's dice appears on their edge, their yard and the board outline light up
  in their colour, and a status pill sits under the board.
- **Board sizing** is driven by `--cs` (one cell) on `:root`, computed from the
  viewport so the whole table always fits without scrolling. Everything else is
  a multiple of `--cs`; avoid hard-coded pixel sizes inside the table.

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

No installation. Open `index.html` in a browser:

```bash
xdg-open index.html      # or: open index.html
```

Node (18+) is only needed to run the rules test.

## Running Tests

```bash
node test/simulate.mjs        # 300 games per configuration (default)
node test/simulate.mjs 1000   # longer soak
```

The test plays computer-vs-computer games across four rule configurations and
asserts the invariants after every move: token counts per colour, valid token
states, that no token reaches the inner ring without a completed lap and a cut,
and that opponents never occupy the same unsafe square. It exits non-zero if an
invariant fails or a game fails to finish. Run it after any change to the rules
engine.

## CI/CD

> Update this section once a CI/CD pipeline is configured.

Planned locations for pipeline configuration:
- GitHub Actions: `.github/workflows/`
- GitLab CI: `.gitlab-ci.yml`
- Other: TBD

## Updating This File

When significant changes are made to the project (new tech stack, new conventions, new tooling), update the relevant sections of this file so future AI assistants have accurate context.
