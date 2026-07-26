# toss-invest-mcp

[![CI](https://github.com/java-jaydev/toss-invest-mcp/actions/workflows/ci.yml/badge.svg)](https://github.com/java-jaydev/toss-invest-mcp/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net/)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.x-green.svg)](https://docs.spring.io/spring-ai/reference/)

A **Model Context Protocol (MCP) server** that lets AI agents (Claude Code, Cursor, Codex, …) query **Korean stock-market data** through the official **Toss Securities Open API**.

Built with **Java 21 + Spring Boot + Spring AI**. Official API only (no unofficial WTS scraping). Read-only by default.

---

## Why

AI coding agents are great at reasoning but blind to live market data. `toss-invest-mcp` bridges that gap: expose Toss Securities' official data as MCP tools, so any MCP-capable agent can ask *"what's the current price of Samsung Electronics?"* and get a real answer.

## Features

- ✅ **Market data (read-only)** — `getPrices` (quotes, up to 200 symbols), `getOrderbook`, `getTrades`, `getCandles` (1m/1d), `getStocks` (instrument info). Parameters verified against the official OpenAPI spec.
- 🔒 OAuth2 client-credentials with automatic token caching & refresh
- 🔑 Secrets via environment variables only (never committed)
- 📊 **Observability** — Micrometer metrics (cache offload, single-flight, Caffeine stats) at `/actuator/prometheus` (HTTP profile); load-test harness in [`loadtest/`](loadtest/)
- 🗺️ **Roadmap**: caching + coalescing (done) → HTTP transport (done) → virtual-thread pinning fix + load testing & observability (done) → order tools behind safety gates (see [Roadmap](#roadmap))

## Quickstart

> **Requires Java 21 or newer at runtime**, not just to build. The jar is compiled
> to class-file version 65; launching it on an older JVM fails immediately with
> `UnsupportedClassVersionError`. Check with `java -version`, and make sure the
> `"command": "java"` in your MCP config resolves to a 21+ JVM.

### 1. Build

```bash
./gradlew build
```

### 2. Configure (environment variables)

```bash
export TOSS_CLIENT_ID=your_client_id
export TOSS_CLIENT_SECRET=your_client_secret
```

Get credentials from the [Toss Securities Open API](https://openapi.tossinvest.com).

### 3. Connect to Claude Code

Add to your MCP config (`.mcp.json` or Claude Code settings):

```json
{
  "mcpServers": {
    "toss-invest": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/build/libs/toss-invest-mcp-0.1.0.jar"],
      "env": {
        "TOSS_CLIENT_ID": "your_client_id",
        "TOSS_CLIENT_SECRET": "your_client_secret"
      }
    }
  }
}
```

Then ask your agent: *"삼성전자(005930) 현재가 알려줘."*

## Transports

One artifact serves both transports, selected by Spring profile.

### stdio (default)

For local MCP clients (Claude Code, Cursor, Codex, …). No profile needed —
this is the default, so it behaves exactly as before:

```bash
./gradlew bootRun
```

### Streamable HTTP

For remote access and load testing. Serves MCP spec 2025-03-26's Streamable
HTTP at `/mcp`, with requests handled on Java 21 virtual threads.

```bash
./gradlew bootRun --args='--spring.profiles.active=http'
# Default port 8080, override with the PORT env var
```

## Architecture

```
AI Agent (Claude Code / Cursor / …)
        │  MCP over stdio (default)  or  Streamable HTTP at /mcp
        ▼
 MarketDataTools  ──▶  TossApiClient  ──▶  Toss Open API
   (@Tool)               (RestClient)         (REST)
                              │
                        TossAuthService  (OAuth2 token cache)
```

- **stdio transport** by default; **Streamable HTTP** (see [Transports](#transports)) for multi-client / high-concurrency use.
- Thin, transparent layer — tools return raw JSON so the LLM reads the source of truth.

## Roadmap

| Phase | Goal |
|---|---|
| 1 ✅ | Read-only market-data tools: prices, orderbook, trades, candles, stocks — **done** |
| 2 ✅ | Two-tier cache (Caffeine L1 + Redis L2) + per-node single-flight coalescing in front of the rate-limited upstream — **done** |
| 2.5 ✅ | HTTP (Streamable) transport (WebMVC) alongside stdio — **done** |
| 3a ✅ | Remove virtual-thread carrier pinning at the cache loader and token refresh, proven with JFR pin-count tests — **done** |
| 3b ✅ | Load testing (k6) + observability (Micrometer / Prometheus / Grafana); measured cache-offload & single-flight ratios — **done** (see [Observability & load testing](#observability--load-testing)) |
| 4 | Account & order tools behind explicit opt-in safety gates (dry-run → confirm) |

## Caching

Read-only market-data calls pass through a cache so bursts of identical
requests collapse to at most one upstream call, and slow-changing data is not
re-fetched from the rate-limited upstream on every request:

- **L1 — Caffeine `AsyncCache` (in-process):** concurrent identical requests
  on a node share one in-flight future, so they are single-flighted to one
  load. Loading runs off the map's monitor on a virtual thread, so blocking
  upstream I/O never pins a JDK 21 carrier. Each entry expires at its per-type
  TTL, so a single node caches correctly **without Redis**.
- **Per-type TTLs:** quotes/orderbook 2s, trades 3s, intraday candles 10s,
  daily candles 1h, stock info 6h.
- **L2 — Redis (opt-in, shared):** the same entries in a shared cache, so
  multiple instances share cache state and a cold node warms instantly. Any
  Redis error degrades to a cache miss — it never breaks a tool call.

Cache keys normalize comma-separated symbols (trim + sort), so `005930,000660`
and `000660,005930` share one entry.

Enable L2 with env vars:

```bash
export TOSS_CACHE_L2=true
export REDIS_HOST=localhost   # default
export REDIS_PORT=6379        # default
```

Scope note: L1 single-flight is per-node. Cross-node request coalescing is not
implemented; the shared L2 narrows (but does not eliminate) the concurrent-miss
window when running multiple instances. The Redis L2 path is covered by
`RedisL2CacheIT` (Testcontainers), which requires Docker to run.

## Observability & load testing

The HTTP profile exposes Micrometer metrics at `/actuator/prometheus`, including
domain counters that make cache behavior legible:

- `marketdata_upstream_calls_total` — actual upstream calls (fewer than requests = offload)
- `marketdata_l2_hits_total` — shared-cache hits
- Caffeine L1 stats (`cache_gets_total{result="hit"|"miss"}`, size, evictions)

[`loadtest/`](loadtest/) has k6 scripts, a Prometheus + Grafana stack, and a
`loadtest` profile with a fixed-latency **stub** upstream, so the cache /
coalescing / virtual-thread path can be driven without real credentials.

**Measured** (WSL2 dev box; cache + 40 ms stub upstream — these are *ratios*, not
absolute latency claims; full honesty caveats in [loadtest/README](loadtest/README.md)):

- 200 concurrent cold-key requests → **1** upstream call (single-flight)
- 157,476 requests on one hot key → **1** upstream call, L1 hit ratio ≈ 99.998%

These offload and coalescing properties are guarded deterministically in CI by
`LoadOffloadIT` — no k6 or Docker required.

## Contributing

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md). Good first issues are labeled [`good first issue`](https://github.com/java-jaydev/toss-invest-mcp/labels/good%20first%20issue).

## License

[Apache License 2.0](LICENSE) © 2026 Jinkyu Lee
