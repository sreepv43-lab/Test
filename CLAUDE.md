# CLAUDE.md

This file provides guidance for AI assistants (Claude and others) working in this repository.

## Project Overview

> **Note:** This is a newly initialized repository. Update this section once the project purpose, language, and framework are defined.

- **Repository:** sreepv43-lab/Test
- **Status:** Initial setup — no source code yet
- **Purpose:** TBD

## Repository Structure

> Update this section as files and directories are added.

```
/
├── CLAUDE.md          # This file — AI assistant guidance
└── (add directories and files here as the project grows)
```

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

> Update this section once the tech stack is defined.

```bash
# Example (replace with actual commands):
# npm install
# pip install -r requirements.txt
# cargo build
```

## Running Tests

> Update this section once a test framework is in place.

```bash
# Example:
# npm test
# pytest
# cargo test
```

## CI/CD

> Update this section once a CI/CD pipeline is configured.

Planned locations for pipeline configuration:
- GitHub Actions: `.github/workflows/`
- GitLab CI: `.gitlab-ci.yml`
- Other: TBD

## Updating This File

When significant changes are made to the project (new tech stack, new conventions, new tooling), update the relevant sections of this file so future AI assistants have accurate context.
