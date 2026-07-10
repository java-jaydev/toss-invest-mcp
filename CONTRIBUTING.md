# Contributing to toss-invest-mcp

Thanks for your interest! This project welcomes contributions of all sizes.

## Getting started

1. Fork and clone the repo.
2. Build: `./gradlew build` (Java 17 required).
3. Copy your Toss Open API credentials into environment variables — never commit secrets:
   ```bash
   export TOSS_CLIENT_ID=...
   export TOSS_CLIENT_SECRET=...
   ```

## Development workflow

- Create a branch: `feat/<short-name>` or `fix/<short-name>`.
- Keep changes focused; one logical change per pull request.
- Add or update tests for behavior changes.
- Run `./gradlew build` locally before pushing — CI must pass.
- Open a pull request against `main` with a clear description of *what* and *why*.

## Commit messages

Use [Conventional Commits](https://www.conventionalcommits.org/):

```
feat(tools): add orderbook lookup
fix(auth): refresh token 30s before expiry
docs(readme): clarify MCP setup
```

## Guidelines

- **Official API only.** This project uses the Toss Securities *Open API*; do not add unofficial/WTS scraping.
- **Read-only by default.** Anything that can place orders or move money must sit behind an explicit opt-in safety gate (dry-run → confirm).
- **No secrets in code.** All credentials come from environment variables.

## Good first issues

Look for issues labeled [`good first issue`](https://github.com/java-jaydev/toss-invest-mcp/labels/good%20first%20issue).
