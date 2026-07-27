# MCP 주문/거래 도구 + 안전게이트 설계 (서브프로젝트 A)

**작성일:** 2026-07-27
**대상 저장소:** `toss-invest-mcp`
**상위 맥락:** [트레이딩 확장 백로그](../../roadmap-trading.md)

## 목표

`toss-invest-mcp`에 **주문 실행 능력**을 추가한다. 지금은 시세 읽기 전용 도구 5종만 있어
어떤 두뇌(앱이든 AI든)도 실제 매매를 할 수 없다. 이 서브프로젝트는 매매 한 사이클
(매수여력 확인 → 주문 → 주문상태 확인 → 보유 확인, 그리고 매도)을 돌릴 수 있는
최소 집합의 도구와, 실계좌 사고를 막는 안전장치를 만든다.

## 범위 밖 (명시적 비목표)

- **매매 전략·규칙은 넣지 않는다.** 하락폭 트리거, 분할매수 차수, 목표 수익률, 손실 한도
  같은 판단 로직은 이 저장소에 존재하지 않는다. MCP는 "손"이고 규칙은 "두뇌"(소비 앱 또는 AI)에 있다.
  이 저장소의 도구는 원시적이어야 한다: `placeOrder(symbol, side, quantity, ...)`.
- CLI 어댑터(서브프로젝트 B), 규칙 스킬(C), 플러그인 배포(D)는 각자의 spec을 가진다.
- 포지션 추적, 손익 계산, 주문 상태 폴링 루프 — 전부 두뇌의 몫이다.

## 배경: 확인된 사실

토스 Open API 공식 스펙(`https://openapi.tossinvest.com/openapi-docs/latest/openapi.json`)과
개요 문서로 2026-07-27 확인한 내용:

- **모의투자/샌드박스가 없다.** 서버는 프로덕션 `https://openapi.tossinvest.com` 하나뿐이고,
  주문 엔드포인트는 실계좌에 실주문을 넣는다. 앱 안의 "모의투자"는 API로 노출되지 않는다.
- 계좌에 영향을 주는 호출은 `Authorization: Bearer {token}` 외에
  **`X-Tossinvest-Account: {accountSeq}`** 헤더가 추가로 필요하다.
- 별도의 trading 스코프가 없다(스펙상 Scopes: N/A). 인증되는 자격증명이면 주문할 수 있다.
- 주문 레이트리밋: **6 req/s**, 단 **09:00–09:10 KST에는 3 req/s**.
- 장 시간 외 주문 접수/정정/취소는 거부된다. 예수금 부족도 API가 거부한다.
- 국내(KRX)와 미국 주식 모두 지원하며 매수여력은 KRW·USD 양쪽으로 관리된다.
- 에러 응답 형태: `{"error":{"requestId","code","message","data"}}`.

구현 시점에 필드명·enum 값은 라이브 `openapi.json`으로 재검증한다(추측 금지).

## 도구 세트 (5종)

| MCP 도구 | 토스 엔드포인트 | 성격 |
|---|---|---|
| `placeOrder` | `POST /api/v1/orders` | 쓰기 (게이트 필수) |
| `cancelOrder` | `DELETE /api/v1/orders/{orderId}` | 쓰기 (게이트 필수) |
| `getOpenOrders` | `GET /api/v1/order-history` | 읽기 |
| `getHoldings` | `GET /api/v1/assets` | 읽기 |
| `getBuyingPower` | `GET /api/v1/order-info` | 읽기 |

`placeOrder` 파라미터: `symbol`(필수), `side`(buy/sell, 필수), `quantity`(필수),
`orderType`(limit/market, 필수), `price`(지정가일 때 필수), `execute`(기본 false).

계좌 지정은 도구 파라미터가 아니라 **설정(`toss.trading.account-seq` / `TOSS_ACCOUNT`)**으로
주입한다. 두뇌가 계좌를 고르게 만들지 않는다. 미설정 상태에서 주문 시도 시 명확히 실패한다.

## 아키텍처

기존 읽기 계층은 그대로 두고 쓰기 계층을 나란히 추가한다.

```
[신규 @Tool]  TradingTools : placeOrder·cancelOrder·getOpenOrders·getHoldings·getBuyingPower
                    │ (얇은 위임)
[신규]        TradingService  ─ 입력검증 → OrderGuard → 실행 → 응답정규화
                    │                    │
                    │              [신규] OrderGuard (순수 로직, 단위테스트 대상)
                    ▼
[확장]        TossApiClient (주문 메서드 + X-Tossinvest-Account 헤더 + 쓰기 레이트리밋)
                    │
              토스 Open API (프로덕션)
```

### 신규/변경 파일

- `tools/TradingTools.java` (신규) — `@Tool` 5종. 로직 없이 `TradingService`에 위임.
- `service/TradingService.java` (신규) — 오케스트레이션: 입력검증 → 추정금액 산출 → 가드 판정 → 실행 → 정규화.
- `trading/OrderGuard.java` (신규) — 게이트·가드레일의 **순수 판정 로직**.
  입력=주문 명령 + 설정 + 오늘 주문수, 출력=`ALLOW` / `DRY_RUN` / `REJECT(사유)`.
  네트워크·스프링 컨텍스트 없이 전수 테스트 가능해야 한다.
- `trading/DailyOrderCounter.java` (신규) — 일자별 주문 수 카운터. 자정(KST) 경계에서 리셋.
  `synchronized` 대신 `ReentrantLock`/atomic 사용(가상스레드 피닝 금지 규약).
- `trading/OrderRateLimiter.java` (신규) — 쓰기 전용 레이트리밋. 기본 6/s,
  09:00–09:10 KST 구간은 3/s. `Clock` 주입으로 테스트 가능하게.
- `config/TossTradingProperties.java` (신규) — `toss.trading.*` 바인딩.
- DTO (신규): `OrderCommand`, `OrderResult`, `GuardDecision`, `OpenOrder`, `Holding`, `BuyingPower`.
- `client/TossApiClient.java` (변경) — 주문 생성/취소/조회 메서드 추가, 계좌 헤더 처리.

### 설계 판단: 왜 `OrderGuard`를 분리하는가

게이트 로직을 `@Tool` 메서드나 `TradingService` 안에 인라인하면 파일 수는 줄지만,
안전장치를 실 API 없이 촘촘히 테스트하기 어렵고 서브프로젝트 B(CLI 어댑터)가 같은
가드를 재사용할 수 없다. 안전장치는 이 프로젝트에서 가장 정확해야 하는 부분이므로
순수 컴포넌트로 분리한다.

## 안전게이트

### 판정 순서

```
1. 킬스위치     toss.trading.enabled == false  →  강제 DRY_RUN (실행하지 않음)
2. 가드레일     한도 위반                       →  REJECT(사유)  ← execute=true여도 통과 못 함
3. 실행 플래그  execute != true                →  DRY_RUN (미리보기)
   전부 통과                                   →  ALLOW → 실제 전송
```

세 게이트는 역할이 다르며, 각각이 막는 대상을 정직하게 구분한다:

- **킬스위치 `toss.trading.enabled` (기본 false)** — 사람이 설정 파일/환경변수로 의도적으로
  켜야만 실주문이 가능하다. 자율 AI에 대한 실질적인 사람 게이트는 이것이다.
- **가드레일** — 켜져 있어도 넘을 수 없는 하드 리밋. 두뇌가 폭주해도 여기서 막힌다.
- **실행 플래그 `execute` (기본 false)** — 기본값이 dry-run이라 실수 실행을 막고 미리보기를
  제공한다. 단, AI는 이 플래그를 스스로 켤 수 있으므로 **실질 안전은 ①②가 담당**하고
  ③은 안전한 기본값·미리보기 역할이다. 이 한계를 문서에도 그대로 적는다.

`cancelOrder`도 같은 게이트를 통과한다(킬스위치·execute 적용, 금액 가드레일은 해당 없음).
`cancelOrder`의 dry-run 응답은 `placeOrder`와 같은 `OrderResult` 형태를 쓰되, `wouldPlace`에는
"어떤 주문을 취소할 것인지"(주문번호와 조회된 주문 요약)를 담는다.

### 확정된 선택

- **confirm 방식 = 단일 `execute` 플래그.** dry-run→토큰 발급→토큰으로 실행하는 2단계 방식은
  AI가 두 스텝을 모두 밟으므로 안전 이득이 없고 복잡도만 늘어난다.
- **`symbol-allowlist` 미설정 = 제한 없음.** 킬스위치가 이미 기본 차단이므로 allowlist까지
  필수화하면 실익 없이 번거롭다. 대신 금액·횟수 상한에 보수적 기본값을 두어, 켰을 때도
  처음부터 조여진 상태에서 사용자가 의도적으로 올리게 한다.

### 가드레일

| 항목 | 동작 | 기본값 |
|---|---|---|
| `max-order-notional-krw` | 추정 주문금액 초과 시 REJECT | 100,000 |
| `max-order-notional-usd` | 추정 주문금액 초과 시 REJECT | 100 |
| `daily-order-count` | 당일 누적 주문수 초과 시 REJECT | 20 |
| `symbol-allowlist` | 비어 있지 않으면 목록 밖 종목 REJECT | `[]` (제한 없음) |

추정 주문금액은 지정가면 `price × quantity`, **시장가면 읽기 계층 `getPrices`의 최근가 × quantity**로
산출한다. 시세 조회가 실패하면 금액을 검증할 수 없으므로 REJECT한다(모르면 막는다).

### 설정

```yaml
toss:
  trading:
    enabled: false                 # 킬스위치
    account-seq: ${TOSS_ACCOUNT:}  # 미설정 시 주문 도구는 명확히 실패
    max-order-notional-krw: 100000
    max-order-notional-usd: 100
    daily-order-count: 20
    symbol-allowlist: []
```

## 데이터 흐름과 규칙

### placeOrder 흐름

```
두뇌 → placeOrder(symbol, side, quantity, orderType, price?, execute?)
  → 입력검증 (quantity > 0, limit이면 price 필수, side/orderType 유효값)
  → 추정금액 산출 (market이면 getPrices 최근가 사용)
  → OrderGuard.evaluate(...) → ALLOW / DRY_RUN / REJECT
  → ALLOW일 때만: 레이트리밋 통과 → TossApiClient.placeOrder(계좌 헤더 포함)
  → OrderResult 정규화 반환
```

### 절대 규칙: 쓰기는 캐시·요청병합을 타지 않는다

`MarketDataCache`의 single-flight는 같은 키의 동시요청을 한 번의 상위 호출로 병합한다.
주문에 이것이 적용되면 서로 다른 두 주문이 하나로 합쳐지거나 한 주문이 중복 반환되는
재앙이 발생한다. **`TradingService`의 쓰기 경로는 `MarketDataCache`를 우회한다.**
캐시를 사용하는 지점은 시장가 추정을 위한 시세 조회 하나뿐이다.

### 절대 규칙: 계좌 상태 조회는 캐시하지 않는다

`getOpenOrders`·`getHoldings`·`getBuyingPower`는 읽기 도구지만 **계좌 상태**를 반환한다.
시세와 달리 주문 직후 즉시 바뀌므로, 캐시된 값을 돌려주면 두뇌가 방금 낸 주문이 반영되지
않은 상태를 보고 잘못 판단한다(같은 주문을 두 번 내는 등). 따라서 이 세 도구는
`MarketDataCache`를 거치지 않고 매번 조회한다. 이들 역시 `X-Tossinvest-Account` 헤더가 필요하다.

### 절대 규칙: 쓰기는 자동 재시도하지 않는다

`POST /orders` 호출이 타임아웃되면 주문이 접수됐는지 알 수 없다. 이때 재시도하면
이중 주문이 된다. 따라서 **쓰기 경로에는 재시도 로직을 넣지 않는다.**
타임아웃/불확실 상황에서는 `status = UNKNOWN`으로 반환하고, 응답 메시지에
"`getOpenOrders`로 실제 접수 여부를 확인하라"고 명시한다. 읽기 도구의 재시도 정책과는
의도적으로 다르다.

### 레이트리밋

쓰기 경로 전용 리미터를 둔다. 기본 6 req/s, `09:00–09:10 KST`에는 3 req/s.
짧은 한도 내에서는 대기 후 진행하고, 대기로 해결되지 않으면 REJECT하며 사유를 알린다.
시각 판단은 주입된 `Clock`을 사용해 테스트 가능하게 한다.

### 응답 형태

`OrderResult`는 무슨 일이 일어날 예정인지 또는 일어났는지가 항상 드러나야 한다.

- `status`: `DRY_RUN` | `PLACED` | `REJECTED` | `UNKNOWN`
- `wouldPlace`: 정규화된 주문 내용 (종목·side·수량·주문유형·가격·추정금액·통화)
- `guardrail`: 각 검사 항목의 통과/실패 내역
- `reason`: `REJECTED`·`UNKNOWN`일 때의 사람이 읽는 사유
- `orderId`: `PLACED`일 때 토스 주문번호

### 에러 매핑

토스의 에러 봉투를 그대로 흘리지 않고 사람이 읽을 수 있는 사유로 변환한다.

| 상황 | 반환 |
|---|---|
| 장 시간 외 | `REJECTED` — "지금은 주문을 접수할 수 있는 시간이 아닙니다" |
| 예수금 부족 | `REJECTED` — "주문 가능 금액이 부족합니다" |
| 계좌 미설정 | 명확한 실패 — `TOSS_ACCOUNT`(계좌 일련번호)가 설정되지 않음 |
| 인증 실패 | 기존 `TossAuthService` 토큰 갱신 경로 재사용 |
| 그 외 API 오류 | `REJECTED` — 토스 `code`와 `message`를 함께 노출 |

## 테스트 전략

토스에 샌드박스가 없으므로 **CI는 실 API를 호출하지 않는다.**

- **`OrderGuard` 단위테스트 (핵심)** — 스프링·네트워크 없이:
  킬스위치 on/off × execute true/false 조합 전수, 가드레일 경계값(한도 정확히 / 한도+1),
  allowlist 설정/미설정, 시세 조회 실패 시 REJECT, 일일 카운터 자정 리셋.
- **`TradingService` 테스트** — HTTP 레벨 모킹으로 정상 접수, API 거부(장 시간 외·예수금 부족),
  타임아웃 시 `UNKNOWN` 반환(재시도하지 않음을 단언).
- **`OrderRateLimiter` 테스트** — `Clock` 주입으로 09:00–09:10 구간 3/s, 그 외 6/s.
- **도구 등록 테스트** — 기존 방식대로 5개 도구의 스키마·등록 확인.
- **가상스레드 피닝 0 유지** — 기존 `VirtualThreadPinningTest`가 계속 0을 단언해야 한다.
  새 코드에서 blocking 호출을 `synchronized` 안에 넣지 않는다.

**라이브 검증은 수동·선택 사항이며 문서로만 안내한다:** 시장가에서 충분히 떨어진 지정가로
1주 주문을 넣어 접수를 확인한 뒤 즉시 취소한다(체결되지 않도록). CI에는 절대 포함하지 않는다.

## 문서 변경

- `docs/tools.md` — 신규 5도구 스키마와 예시 추가, 안전게이트 동작 설명 포함.
- `AGENTS.md` — "주문 도구는 명시적 게이트 뒤" 규약을 실제 구현에 맞게 갱신.
- `README.md` / `README.en.md` — "읽기 전용" 표현을 정확하게 정정한다.
  (쓰기 도구가 존재하되 기본 비활성이라는 사실을 명시.)
- `llms.txt` — 도구 목록과 안전 모델 갱신.
- **`CLAUDE.md` 신설** — 현재 `AGENTS.md`만 있어 Claude Code 사용자에게는 저장소 규약이
  잡히지 않는다. 내용을 복제하지 않고 `AGENTS.md`를 가리키는 짧은 포인터 문서로 만들어
  단일 출처를 유지한다.

## 성공 기준

1. 5개 도구가 MCP `tools/list`에 노출되고 스키마가 유효하다.
2. `toss.trading.enabled=false`(기본)에서는 `execute=true`를 줘도 실주문이 전송되지 않는다.
3. 가드레일 초과 주문은 `enabled=true`, `execute=true`에서도 REJECT된다.
4. 쓰기 경로가 캐시·single-flight·자동재시도를 타지 않음이 테스트로 단언된다.
5. `./gradlew build`가 초록불이고 가상스레드 피닝이 0이다.
6. CI가 실 토스 API를 호출하지 않는다.
