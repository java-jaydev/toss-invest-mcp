# AGENTS.md

Guidance for AI coding agents working on **toss-invest-mcp**. Human contributors: see [CONTRIBUTING.md](CONTRIBUTING.md).

## What this project is

An MCP server (Java 21 + Spring Boot + Spring AI) exposing the Toss Securities Open API: read-only market-data tools plus order/account tools that are off by default (see Scope below). See [llms.txt](llms.txt) for a compact fact sheet and [README.md](README.md) for the overview.

## Setup, build, run, test

```bash
./gradlew build                                          # compile + test (needs JDK 21)
./gradlew test                                           # tests only
./gradlew bootRun                                        # run as stdio MCP server (default)
./gradlew bootRun --args='--spring.profiles.active=http' # run Streamable HTTP on :8080
```

- **JDK 21 is required**, at runtime too (virtual threads; class-file version 65). Gradle's toolchain provisions 21 for build/test even if the default `java` is older.
- Provide credentials via env vars only: `TOSS_CLIENT_ID`, `TOSS_CLIENT_SECRET`, `TOSS_ACCOUNT`. Never hard-code secrets. A local `.env` is git-ignored.

## Project layout

```
src/main/java/dev/jaydev/tossmcp/
  TossMcpApplication.java      # @SpringBootApplication; registers @Tool beans
  tools/MarketDataTools.java   # MCP @Tool definitions (the 5 tools)
  tools/TradingTools.java      # MCP @Tool definitions for orders and account reads
  service/MarketDataService.java # per-type TTLs + symbol normalization
  service/TradingService.java  # order orchestration: validation, notional estimate, guardrails, response shape
  cache/                       # MarketDataCache (Caffeine AsyncCache L1 + single-flight), L2Cache/RedisL2Cache/NoOpL2Cache
  trading/                     # OrderGuard (safety gates), DailyOrderCounter, OrderRateLimiter, OrderResult
  client/TossApiClient.java    # REST wrapper over the Toss Open API
  auth/TossAuthService.java    # OAuth2 client-credentials token cache/refresh
  config/TossProperties.java   # toss.* config binding
  config/TossTradingProperties.java # toss.trading.* config binding (safety limits)
  loadtest/                    # stub upstream + HTTP shim (loadtest profile only; not in production)
src/main/resources/            # application.yml (stdio), application-http.yml, application-loadtest.yml
loadtest/                      # k6 scripts, Prometheus/Grafana, methodology
docs/                          # tools.md, vibe-coding.md
skills/                        # bundled MCP-agent skills (e.g. split-buy-strategy)
```

## Testing notes

- Run `./gradlew test` and make sure it stays green before proposing changes. Test logging prints `passed/skipped/failed` per test — a green build with a skipped integration test is **not** proof it ran.
- `cache/RedisL2CacheIT` uses Testcontainers and **self-skips without Docker** (e.g. on WSL). It runs for real in CI, which has Docker. To exercise a Docker-dependent path, open a PR so CI runs it.
- Tests that assert on metrics or scrape `/actuator/prometheus` must be annotated `@AutoConfigureObservability`; `@SpringBootTest` disables metrics export by default.
- Virtual-thread pinning is guarded by `vthread/VirtualThreadPinningTest` (JFR `jdk.VirtualThreadPinned` count must be 0). Keep blocking I/O off `synchronized` monitors — use `ReentrantLock` and Caffeine `AsyncCache`.

## Conventions

- **Commits:** Conventional Commits (`feat:`, `fix:`, `docs:`, `test:`…), imperative mood. Do not add auto-generated attribution or tool boilerplate to messages.
- **Docs honesty:** never document features that don't exist, and never present benchmark numbers without their environment caveat. Published load numbers are *ratios* (cache offload), not absolute latency — see [loadtest/README.md](loadtest/README.md).
- **Plain language:** spell out abbreviations on first use; assume the reader is new to the domain.
- **Scope:** official API only (no unofficial WTS scraping). Market-data tools are read-only.
  Order tools exist but are off by default: an order is transmitted only when
  `toss.trading.enabled=true` (also settable via the `TOSS_TRADING_ENABLED` environment
  variable), the call passes `execute=true`, and the configured notional and daily-count limits
  allow it. Never weaken those gates, never send `confirmHighValueOrder`, and never add
  automatic retries to the write path — `clientOrderId` makes a caller-driven retry safe
  instead. **The Streamable HTTP transport (`http` profile) has no authentication on `/mcp`** —
  never run it exposed beyond localhost while trading is enabled.

## Adding a tool

1. Add a method to `client/TossApiClient` (verify params against the official OpenAPI spec — don't guess).
2. Add a cached, TTL-tagged method to `service/MarketDataService`.
3. Expose it as an `@Tool` in `tools/MarketDataTools` with a clear description and schema.
4. Add tests (service-level cache behavior at minimum) and document it in [docs/tools.md](docs/tools.md).
