# toss-invest-mcp

[![CI](https://github.com/java-jaydev/toss-invest-mcp/actions/workflows/ci.yml/badge.svg)](https://github.com/java-jaydev/toss-invest-mcp/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net/)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.x-green.svg)](https://docs.spring.io/spring-ai/reference/)

**🇰🇷 한국어** · [🇬🇧 English](README.en.md)

AI 에이전트(Claude Code, Cursor, Codex …)가 **토스증권 공식 Open API**로 **한국·미국 주식 시세를 조회하고, 안전장치를 통과했을 때 주문까지 낼 수 있게** 해주는 **MCP(Model Context Protocol) 서버**입니다. AI 없이 터미널에서 쓰는 **CLI 모드**도 같은 코어 위에서 제공합니다.

**Java 21 + Spring Boot + Spring AI**로 만들었습니다. 공식 API만 사용합니다(비공식 WTS 미사용). 시세 조회는 항상 가능하고, **주문 전송은 기본적으로 꺼져 있습니다** — 서버 설정과 호출 인자를 모두 켜고, 설정한 금액·횟수 한도를 통과해야만 나갑니다.

---

## 📖 이게 뭔가요? (1분 설명)

- **MCP**는 AI가 외부 도구·데이터에 접근하는 표준 규격입니다. USB-C 같은 거라고 보면 됩니다.
- 이 서버를 AI 에이전트에 연결하면, 에이전트에게 *"삼성전자 현재가 알려줘"* 라고 물었을 때 **실제 시세**로 답합니다.
- AI는 똑똑하지만 실시간 시세는 못 봅니다. 이 서버가 그 **눈**이 되어줍니다.
- 그리고 안전장치를 명시적으로 열었을 때만, **손**도 되어줍니다 — 주문·취소·잔고 조회까지. 열지 않으면 "이렇게 주문될 예정"이라는 미리보기만 돌려줍니다.

## 🧭 문서 안내 (여기서 원하는 곳으로)

| 문서 | 내용 |
|---|---|
| **[docs/vibe-coding.md](docs/vibe-coding.md)** | 🌱 **비개발자용 초친절 가이드** — 터미널이 낯설어도 AI에게 부탁해서 세팅하는 법 |
| **[docs/SAFETY.md](docs/SAFETY.md)** | 🛡️ **안전 경계 설계** — 에이전트에게 어디까지 허용하고 어디서 막는지, 응답을 못 받았을 때 무엇을 하는지, 그리고 일부러 넣지 않은 것 (영문) |
| [docs/tools.md](docs/tools.md) | 🧰 도구 10개(시세 조회 5 + 주문·계좌 5) 상세 레퍼런스 + 요청/응답 예시 |
| [docs/cli.md](docs/cli.md) | 💻 **AI 없이 터미널에서 쓰는 CLI 모드** — 명령 표·종료 코드·자격증명/IP 허용목록·실매매 활성화 |
| [docs/install-plugins.md](docs/install-plugins.md) | 🔌 Claude Code·Codex CLI **플러그인/마켓플레이스**로 설치하기(빌드 단계·자격증명·IP 허용목록·실매매 활성화 포함) |
| [README.en.md](README.en.md) | 🇬🇧 English version |
| [llms.txt](llms.txt) | 🤖 LLM용 기계가독 인덱스 |
| [AGENTS.md](AGENTS.md) | 🛠️ 이 저장소에서 작업할 AI 코딩 에이전트용 안내 |
| [skills/split-buy-strategy/SKILL.md](skills/split-buy-strategy/SKILL.md) | 📐 분할매수(물타기) 전략 스킬 — 에이전트가 따르는 매매 판단 규칙 |

## ✨ 기능

- ✅ **시세 조회 5종(읽기 전용)** — `getPrices`(현재가, 최대 200종목), `getOrderbook`(호가), `getTrades`(체결), `getCandles`(1분/1일 봉), `getStocks`(종목정보). 파라미터는 공식 OpenAPI 스펙으로 검증했습니다.
- 🛑 **주문·계좌 5종(기본 꺼짐)** — `placeOrder`(주문), `cancelOrder`(취소), `getOpenOrders`(미체결), `getHoldings`(보유), `getBuyingPower`(매수여력). **3중 게이트**를 모두 통과해야만 전송됩니다 ([자세히](#-도구-요약))
- 🌏 **국내·해외 동시 지원** — 국내는 6자리 코드(예: `005930`, 원), 미국은 티커(예: `AAPL`, 달러). 같은 도구가 둘 다 처리합니다.
- 💻 **CLI 모드** — AI 없이 터미널에서 `./toss price 005930`. 같은 코어를 쓰므로 안전장치도 동일하게 적용됩니다 ([자세히](docs/cli.md))
- 📐 **전략 스킬 번들** — 분할매수 판단 규칙을 에이전트가 읽는 스킬로 제공. **서버에는 전략이 없습니다** ([자세히](skills/split-buy-strategy/SKILL.md))
- 🔌 **플러그인 배포** — Claude Code·Codex CLI 마켓플레이스로 설치 ([자세히](docs/install-plugins.md))
- 🔒 OAuth2 토큰 자동 발급·캐시·갱신 / 🔑 시크릿은 환경변수로만(코드에 절대 안 넣음)
- ⚡ **2계층 캐시 + 요청병합** — 같은 요청이 몰려도 상단(토스 API)은 한 번만 호출 ([자세히](#-캐시--요청병합))
- 📊 **관측성** — `/actuator/prometheus`로 캐시 오프로드·처리량 지표 노출 ([자세히](#-관측성--부하테스트))

## 🚀 5분 시작

> 💡 **터미널이 처음이라도 괜찮아요.** 아래 명령을 Claude Code나 Cursor 같은 AI에게 그대로 보여주며
> *"이거 대신 해줘"* 라고 부탁하면 됩니다. 손 잡고 알려주는 [비개발자 가이드](docs/vibe-coding.md)도 있습니다.

### 준비물

1. **Java 21 이상** (빌드·실행 모두). 확인: `java -version` → `21` 이상이어야 함. 없으면 [Adoptium](https://adoptium.net/)에서 설치.
2. **토스증권 Open API 키** — [openapi.tossinvest.com](https://openapi.tossinvest.com)에서 발급(클라이언트 ID·시크릿). 콘솔에서 **호출 서버의 공인 IP를 허용목록에 등록**해야 합니다.

### 1) 빌드

```bash
git clone https://github.com/java-jaydev/toss-invest-mcp.git
cd toss-invest-mcp
./gradlew build
```

### 2) Claude Code(또는 Cursor 등)에 연결

MCP 설정 파일(`.mcp.json` 또는 클라이언트 설정)에 추가합니다. **`java`가 21 이상을 가리키는지** 꼭 확인하세요.

```json
{
  "mcpServers": {
    "toss-invest": {
      "command": "java",
      "args": ["-jar", "/절대경로/build/libs/toss-invest-mcp-0.1.0.jar"],
      "env": {
        "TOSS_CLIENT_ID": "발급받은_클라이언트_ID",
        "TOSS_CLIENT_SECRET": "발급받은_시크릿"
      }
    }
  }
}
```

### 3) 물어보기

에이전트에게 자연어로 물어보면 됩니다:

> **"삼성전자(005930) 현재가 알려줘"**
> → `{"symbol":"005930","lastPrice":"252500","currency":"KRW"}`
>
> **"애플(AAPL) 지금 얼마야?"**
> → `{"symbol":"AAPL","lastPrice":"333.80","currency":"USD"}`
>
> **"삼성전자 최근 5일 일봉 보여줘"**
> → 시가·고가·저가·종가·거래량이 담긴 캔들 5개

> ⚠️ **Java 21 런타임 필수.** jar는 클래스파일 버전 65로 컴파일됩니다. 더 낮은 JVM에서 실행하면
> 즉시 `UnsupportedClassVersionError`로 실패합니다. MCP 설정의 `"command": "java"`가 21+를 가리키는지 확인하세요.

### 🔌 플러그인으로 설치하고 싶다면

Claude Code와 Codex CLI 양쪽 모두에 마켓플레이스로 설치할 수 있습니다(`/plugin marketplace add
java-jaydev/toss-invest-mcp` 등). 다만 이 서버는 Java 빌드 결과물(jar)을 실행하는 프로그램이라
**플러그인을 설치해도 위 1단계의 빌드는 그대로 필요합니다** — "제로 설치"라고 포장하지 않습니다.
자격증명 입력 방식, Codex 쪽에서 확인되지 않은 부분, 실매매를 의도적으로 켜는 법까지
[플러그인 설치 가이드](docs/install-plugins.md)에 정리했습니다.

## 🔌 전송 방식 (Transports)

하나의 아티팩트가 두 전송을 모두 서비스하며, Spring 프로파일로 고릅니다.

- **stdio (기본)** — 로컬 MCP 클라이언트용. 프로파일 지정 없이 `./gradlew bootRun`.
- **Streamable HTTP** — 원격·다중 클라이언트·부하테스트용. `/mcp`에서 MCP 스펙 2025-03-26을 서비스하며, 요청을 **Java 21 가상스레드**로 처리합니다. `./gradlew bootRun --args='--spring.profiles.active=http'` (기본 포트 8080).

> ⚠️ **HTTP 전송에는 인증이 없습니다.** `/mcp` 엔드포인트는 별도 인증 계층 없이 열려 있어,
> 그 포트에 접근할 수 있는 사람은 누구든 도구를 호출할 수 있습니다. 실매매가 켜진
> 상태(`toss.trading.enabled=true`)에서 HTTP 프로파일을 실행한다면 **반드시 localhost로만
> 열어두거나 신뢰할 수 있는 네트워크 뒤에 두세요** — 외부에 그대로 노출하면 누구나 인증 없이
> 실계좌로 주문을 낼 수 있습니다.

## 💻 CLI 모드 (AI 없이 쓰기)

같은 코어(`MarketDataService`·`TradingService`)를 CLI로도 쓸 수 있습니다. `./gradlew build`
후 저장소 루트의 `./toss` 래퍼로 `./toss price 005930`처럼 바로 시세를 조회하거나, 안전게이트를
통과하면 주문도 낼 수 있습니다 — MCP 도구와 같은 킬스위치·한도가 그대로 적용됩니다. 명령
표·종료 코드·자격증명·실매매를 켜는 법과 그 위험은 [docs/cli.md](docs/cli.md)를 보세요.

## 🧰 도구 요약

| 도구 | 설명 | 주요 인자 |
|---|---|---|
| `getPrices` | 현재가 | `symbols`(콤마, 최대 200) |
| `getOrderbook` | 호가(매수·매도 잔량) | `symbol` |
| `getTrades` | 최근 체결 | `symbol`, `count` |
| `getCandles` | 캔들(시고저종) | `symbol`, `interval`(1m/1d), `count` |
| `getStocks` | 종목 기본정보 | `symbols`(콤마, 최대 200) |
| `placeOrder` 🛑 | 주문(기본 미리보기) | `symbol`, `side`, `quantity`, `orderType`, `price`, `execute` |
| `cancelOrder` 🛑 | 주문 취소(기본 미리보기) | `orderId`, `execute` |
| `getOpenOrders` | 미체결 주문 | `symbol`, `limit` |
| `getHoldings` | 보유 주식·평가손익 | `symbol` |
| `getBuyingPower` | 매수 가능 금액 | `currency`(KRW/USD) |

→ 요청/응답 실제 예시는 **[docs/tools.md](docs/tools.md)** 참고.

🛑 표시된 두 도구만 계좌를 바꿉니다. **주문은 기본적으로 전송되지 않습니다** — 서버 설정 `toss.trading.enabled=true` 와
호출 인자 `execute=true` 가 모두 있어야 하고, 설정된 금액·횟수 한도 안이어야 합니다.
`toss.trading.enabled`는 환경변수 `TOSS_TRADING_ENABLED=true`로도 켤 수 있습니다 — jar에는
`false`로 고정 패키징되어 있으므로, stdio로 실행하는 대부분의 사용자에게는 이 환경변수가
사실상 유일한 킬스위치입니다. **토스증권은 모의투자·샌드박스 환경을 제공하지 않으므로,
실매매를 켜면 모든 호출이 실제 자금이 오가는 계좌에 그대로 반영됩니다.** 정해진 규칙에 따라
여러 차수로 나눠 매매하는 전략이 필요하면 [분할매수 전략 스킬](skills/split-buy-strategy/SKILL.md)도
참고하세요. 자세한 동작은 [도구 레퍼런스](docs/tools.md)를 보세요.

## ⚡ 캐시 & 요청병합

읽기 전용 시세 호출은 캐시를 거칩니다. 똑같은 요청이 몰려도 상단 호출은 최대 1회로 뭉치고, 잘 안 바뀌는 데이터는 매번 다시 가져오지 않습니다.

- **L1 — Caffeine `AsyncCache`(인프로세스):** 같은 키의 동시요청은 하나의 진행 중 future를 공유해 single-flight로 병합됩니다. 로딩은 모니터 밖 가상스레드에서 돌아, 블로킹 I/O가 JDK 21 캐리어를 핀하지 않습니다. 항목별 TTL로 만료돼 **Redis 없이도** 정상 캐시됩니다.
- **항목별 TTL:** 현재가·호가 2초, 체결 3초, 분봉 10초, 일봉 1시간, 종목정보 6시간.
- **L2 — Redis(선택, 공유):** 여러 인스턴스가 캐시를 공유. Redis 오류는 캐시 미스로 격하될 뿐 도구 호출을 깨지 않습니다.

심볼은 정규화(공백 제거·정렬)되어 `005930,000660`과 `000660,005930`이 한 항목을 공유합니다.

## 📊 관측성 & 부하테스트

HTTP 프로파일은 `/actuator/prometheus`로 Micrometer 지표를 노출합니다:

- `marketdata_upstream_calls_total` — 상단 실제 호출 수(요청보다 적을수록 오프로드가 큼)
- `marketdata_l2_hits_total` — 공유 캐시 히트
- Caffeine L1 통계(`cache_gets_total{result="hit"|"miss"}`, 크기, 축출)

[`loadtest/`](loadtest/)에 k6 스크립트·Prometheus+Grafana 스택·정직한 방법론이 있습니다. **실측(WSL 개발머신, 캐시+고정지연 스텁 상단 — 절대 지연이 아니라 비율):** 동일 키 200 동시요청 → 상단 **1회**, 핫키 157,476 요청 → 상단 **1회**·L1 히트율 ≈ **99.998%**. 오프로드·병합 성질은 `LoadOffloadIT`가 CI에서 결정론적으로 담보합니다.

## 🤝 기여

환영합니다 — [CONTRIBUTING.md](CONTRIBUTING.md)를 봐주세요. `good first issue` 라벨부터 시작하기 좋습니다. 이 저장소에서 작업하는 AI 코딩 에이전트는 [AGENTS.md](AGENTS.md)를 먼저 읽으세요.

## 📜 라이선스

[Apache License 2.0](LICENSE) © 2026 Jinkyu Lee
