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
- 🗺️ **Roadmap**: caching + request-coalescing (done) → HTTP (Streamable) transport (done) → load testing for concurrency (see [Roadmap](#roadmap))

## Quickstart

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
        │  MCP (stdio)
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
| 3 | Load testing (k6) + observability (Micrometer / Prometheus / Grafana) with published throughput & latency numbers |
| 4 | Account & order tools behind explicit opt-in safety gates (dry-run → confirm) |

## Caching

Read-only market-data calls pass through a cache so bursts of identical
requests collapse to at most one upstream call, and slow-changing data is not
re-fetched from the rate-limited upstream on every request:

- **L1 — Caffeine (in-process):** `get(key, loader)` is atomic per key, so
  concurrent identical requests on a node are single-flighted to one load.
  Each entry expires at its per-type TTL, so a single node caches correctly
  **without Redis**.
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

## Contributing

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md). Good first issues are labeled [`good first issue`](https://github.com/java-jaydev/toss-invest-mcp/labels/good%20first%20issue).

## License

[Apache License 2.0](LICENSE) © 2026 Jinkyu Lee
