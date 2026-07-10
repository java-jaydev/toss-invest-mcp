# toss-invest-mcp

[![CI](https://github.com/java-jaydev/toss-invest-mcp/actions/workflows/ci.yml/badge.svg)](https://github.com/java-jaydev/toss-invest-mcp/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://adoptium.net/)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.x-green.svg)](https://docs.spring.io/spring-ai/reference/)

A **Model Context Protocol (MCP) server** that lets AI agents (Claude Code, Cursor, Codex, …) query **Korean stock-market data** through the official **Toss Securities Open API**.

Built with **Java 17 + Spring Boot + Spring AI**. Official API only (no unofficial WTS scraping). Read-only by default.

---

## Why

AI coding agents are great at reasoning but blind to live market data. `toss-invest-mcp` bridges that gap: expose Toss Securities' official data as MCP tools, so any MCP-capable agent can ask *"what's the current price of Samsung Electronics?"* and get a real answer.

## Features

- ✅ **Market data (read-only)** — `get_prices` (quotes, up to 200 symbols), `get_orderbook`, `get_trades`, `get_candles` (1m/1d), `get_stocks` (instrument info). Parameters verified against the official OpenAPI spec.
- 🔒 OAuth2 client-credentials with automatic token caching & refresh
- 🔑 Secrets via environment variables only (never committed)
- 🗺️ **Roadmap**: more read-only market-data tools → HTTP (Streamable) transport → caching + request-coalescing → load-tested for concurrency (see [Roadmap](#roadmap))

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

- **stdio transport** today; **Streamable HTTP** planned for multi-client / high-concurrency use.
- Thin, transparent layer — tools return raw JSON so the LLM reads the source of truth.

## Roadmap

| Phase | Goal |
|---|---|
| 1 ✅ | Read-only market-data tools: prices, orderbook, trades, candles, stocks — **done** |
| 2 | HTTP (Streamable) transport + caching + request-coalescing in front of the rate-limited upstream |
| 3 | Load testing (k6) + observability (Micrometer / Prometheus / Grafana) with published throughput & latency numbers |
| 4 | Account & order tools behind explicit opt-in safety gates (dry-run → confirm) |

## Contributing

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md). Good first issues are labeled [`good first issue`](https://github.com/java-jaydev/toss-invest-mcp/labels/good%20first%20issue).

## License

[Apache License 2.0](LICENSE) © 2026 Jinkyu Lee
