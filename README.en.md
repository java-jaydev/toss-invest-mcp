# toss-invest-mcp

[![CI](https://github.com/java-jaydev/toss-invest-mcp/actions/workflows/ci.yml/badge.svg)](https://github.com/java-jaydev/toss-invest-mcp/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net/)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.x-green.svg)](https://docs.spring.io/spring-ai/reference/)

[🇰🇷 한국어](README.md) · **🇬🇧 English**

A **Model Context Protocol (MCP) server** that lets AI agents (Claude Code, Cursor, Codex, …) query **Korean and US stock-market data** — and, once the safety gates are deliberately opened, **place orders** — through the official **Toss Securities Open API**. A **command-line mode** over the same core is included for use without an AI.

Built with **Java 21 + Spring Boot + Spring AI**. Official API only (no unofficial WTS scraping). Market data is always available; **orders are off by default** — the server switch and the call argument must both be on, and the configured notional and daily-count limits must allow it.

---

## 📖 What is this? (1-minute version)

- **MCP** is a standard for how AI reaches external tools and data — think USB-C for AI.
- Connect this server to an agent, ask *"what's Samsung's current price?"*, and it answers with **real quotes**.
- AI reasons well but can't see live market data. This server is its **eyes**.
- And only when you explicitly open the gates, its **hands** too — orders, cancellations, balances. Otherwise it returns a preview of what *would* have been sent.

## 🧭 Documentation map

| Doc | What's inside |
|---|---|
| **[docs/vibe-coding.md](docs/vibe-coding.md)** | 🌱 **Beginner guide** — set it up by *asking an AI to do it*, even if the terminal is new to you (Korean) |
| [docs/tools.md](docs/tools.md) | 🧰 Full reference for all 10 tools (5 market-data + 5 trading/account) + request/response examples |
| [docs/cli.md](docs/cli.md) | 💻 **CLI mode for using this without an AI agent** — command table, exit codes, credentials/IP allowlist, enabling live trading (Korean, with literal commands/flags in English) |
| [docs/install-plugins.md](docs/install-plugins.md) | 🔌 Install as a **plugin** for Claude Code or Codex CLI (build step, credentials, IP allowlist, enabling live trading — Korean) |
| [README.md](README.md) | 🇰🇷 Korean version |
| [llms.txt](llms.txt) | 🤖 Machine-readable index for LLMs |
| [AGENTS.md](AGENTS.md) | 🛠️ Guide for AI coding agents working on this repo |
| [skills/split-buy-strategy/SKILL.md](skills/split-buy-strategy/SKILL.md) | 📐 Split-buy (averaging-down) strategy skill — agent decision rules for trading (Korean) |

## ✨ Features

- ✅ **5 read-only market-data tools** — `getPrices` (quotes, up to 200 symbols), `getOrderbook`, `getTrades`, `getCandles` (1m/1d), `getStocks` (instrument info). Parameters verified against the official OpenAPI spec.
- 🛑 **5 order/account tools (off by default)** — `placeOrder`, `cancelOrder`, `getOpenOrders`, `getHoldings`, `getBuyingPower`. An order transmits only after clearing **three gates** ([details](#-tools-at-a-glance))
- 🌏 **Korea and US in one tool** — Korea uses 6-digit codes (`005930`, KRW), US uses tickers (`AAPL`, USD). The same tool handles both.
- 💻 **CLI mode** — `./toss price 005930`, no AI required. It runs the same core, so the same safety gates apply ([details](docs/cli.md))
- 📐 **Bundled strategy skill** — averaging-down decision rules as a skill the agent reads. **The server holds no strategy** ([details](skills/split-buy-strategy/SKILL.md))
- 🔌 **Plugin distribution** — installable via the Claude Code and Codex CLI marketplaces ([details](docs/install-plugins.md))
- 🔒 OAuth2 token auto-issue, caching & refresh
- 🔑 Secrets via environment variables only (never committed)
- ⚡ **Two-tier cache + request coalescing** — bursts of identical requests collapse to at most one upstream call ([details](#-caching--request-coalescing))
- 📊 **Observability** — cache-offload & throughput metrics at `/actuator/prometheus` ([details](#-observability--load-testing))

## 🚀 Quickstart

> 💡 **New to the terminal? That's fine.** Paste the commands below to an AI like Claude Code or
> Cursor and ask it to *"do this for me."* There's also a hand-holding [beginner guide](docs/vibe-coding.md).

### Prerequisites

1. **Java 21+** (to build *and* run). Check with `java -version`. Get it from [Adoptium](https://adoptium.net/).
2. **Toss Securities Open API keys** — from [openapi.tossinvest.com](https://openapi.tossinvest.com) (client id + secret). You must **allowlist your calling server's public IP** in the console.

### 1) Build

```bash
git clone https://github.com/java-jaydev/toss-invest-mcp.git
cd toss-invest-mcp
./gradlew build
```

### 2) Connect to Claude Code (or Cursor, …)

Add to your MCP config (`.mcp.json` or client settings). Make sure `java` resolves to **21+**.

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

### 3) Ask

> **"What's Samsung Electronics (005930) trading at?"**
> → `{"symbol":"005930","lastPrice":"252500","currency":"KRW"}`
>
> **"How much is Apple (AAPL) right now?"**
> → `{"symbol":"AAPL","lastPrice":"333.80","currency":"USD"}`

> ⚠️ **Java 21 runtime required.** The jar is class-file version 65; older JVMs fail immediately with
> `UnsupportedClassVersionError`. Verify the `"command": "java"` in your MCP config points to a 21+ JVM.

### 🔌 Installing as a plugin

Both Claude Code and Codex CLI can install this repo from its bundled marketplace (e.g. `/plugin
marketplace add java-jaydev/toss-invest-mcp`). That said, this server runs a Java build artifact
(a jar), so **installing the plugin does not remove the build step above** — this is not marketed
as zero-install. Credential handling, what's confirmed vs. unconfirmed on the Codex side, and how
to deliberately enable live trading are all covered in the
[plugin installation guide](docs/install-plugins.md) (Korean, with literal commands/config in
English).

## 🔌 Transports

One artifact serves both, selected by Spring profile.

- **stdio (default)** — for local MCP clients. `./gradlew bootRun`, no profile needed.
- **Streamable HTTP** — for remote / multi-client / load testing. Serves MCP spec 2025-03-26 at `/mcp`, requests handled on **Java 21 virtual threads**. `./gradlew bootRun --args='--spring.profiles.active=http'` (default port 8080).

> ⚠️ **The HTTP transport has no authentication.** The `/mcp` endpoint has no auth layer of its
> own — anyone who can reach that port can call any tool. If you run the http profile while live
> trading is enabled (`toss.trading.enabled=true`), **bind it to localhost only or put it behind
> a trusted network** — exposed to the open internet, anyone can place real orders with no login.

## 💻 CLI mode (no AI agent required)

The same core (`MarketDataService`, `TradingService`) is also reachable as a one-shot
command-line tool. After `./gradlew build`, the `./toss` wrapper script at the repository root
lets you run `./toss price 005930` directly, and place orders once the same safety gates allow
it — the kill switch and limits are identical to the MCP tools. See
[docs/cli.md](docs/cli.md) (Korean, with literal commands/flags in English) for the command
table, exit codes, credentials, and how to deliberately enable live trading and what that risks.

## 🧰 Tools at a glance

| Tool | Purpose | Key args |
|---|---|---|
| `getPrices` | Current price | `symbols` (comma, ≤200) |
| `getOrderbook` | Bid/ask ladder | `symbol` |
| `getTrades` | Recent trades | `symbol`, `count` |
| `getCandles` | OHLC candles | `symbol`, `interval` (1m/1d), `count` |
| `getStocks` | Instrument info | `symbols` (comma, ≤200) |
| `placeOrder` 🛑 | Place an order (previews by default) | `symbol`, `side`, `quantity`, `orderType`, `price`, `execute` |
| `cancelOrder` 🛑 | Cancel an order (previews by default) | `orderId`, `execute` |
| `getOpenOrders` | Unfilled orders | `symbol`, `limit` |
| `getHoldings` | Positions and unrealized P&L | `symbol` |
| `getBuyingPower` | Available buying power | `currency` (KRW/USD) |

→ Real request/response examples in **[docs/tools.md](docs/tools.md)**.

Only the two tools marked 🛑 change the account. **Orders are not transmitted by default** — the server
switch `toss.trading.enabled=true`, the call argument `execute=true`, and the configured
notional and daily-count limits must all allow it. `toss.trading.enabled` can also be flipped
with the `TOSS_TRADING_ENABLED=true` environment variable — the jar ships with it hard-coded to
`false`, so for most users running the stdio transport, that environment variable is effectively
the only kill switch. **Toss Securities provides no sandbox or paper-trading environment, so
once live trading is enabled every call hits a real, funded account.** For a strategy that splits
buys across several price levels by rule, see the
[split-buy strategy skill](skills/split-buy-strategy/SKILL.md) (Korean). See the
[tool reference](docs/tools.md) for full details.

## ⚡ Caching & request coalescing

Market-data calls pass through a cache, so identical-request bursts collapse to one upstream call and slow-changing data is not re-fetched every time. Order and account tools are never cached — see [Caching behavior](docs/tools.md#캐시-동작--caching-behavior) in the tool reference.

- **L1 — Caffeine `AsyncCache` (in-process):** concurrent identical requests share one in-flight future (single-flight). Loading runs off the map's monitor on a virtual thread, so blocking I/O never pins a JDK 21 carrier. Per-type TTL means a single node caches correctly **without Redis**.
- **Per-type TTLs:** quotes/orderbook 2s, trades 3s, intraday candles 10s, daily candles 1h, stock info 6h.
- **L2 — Redis (opt-in, shared):** multiple instances share cache state. Any Redis error degrades to a miss — it never breaks a tool call.

Symbols are normalized (trim + sort), so `005930,000660` and `000660,005930` share one entry.

## 📊 Observability & load testing

The HTTP profile exposes Micrometer metrics at `/actuator/prometheus`:

- `marketdata_upstream_calls_total` — actual upstream calls (fewer than requests = offload)
- `marketdata_l2_hits_total` — shared-cache hits
- Caffeine L1 stats (`cache_gets_total{result="hit"|"miss"}`, size, evictions)

[`loadtest/`](loadtest/) has k6 scripts, a Prometheus + Grafana stack, and an honest methodology. **Measured** (WSL dev box, cache + fixed-latency stub upstream — *ratios*, not absolute latency): 200 concurrent same-key requests → **1** upstream call; 157,476 requests on one hot key → **1** upstream call, L1 hit ratio ≈ **99.998%**. These offload/coalescing properties are guarded deterministically in CI by `LoadOffloadIT`.

## 🤝 Contributing

Welcome — see [CONTRIBUTING.md](CONTRIBUTING.md). Good first issues are labeled `good first issue`. AI coding agents working on this repo should read [AGENTS.md](AGENTS.md) first.

## 📜 License

[Apache License 2.0](LICENSE) © 2026 Jinkyu Lee
