# 도구 레퍼런스 · Tool Reference

[← README(한국어)](../README.md) · [← README (English)](../README.en.md)

이 서버가 제공하는 MCP 도구 10종(시세 조회 5종 + 주문·계좌 5종)의 스키마와 요청/응답 예시입니다.
시세 조회 도구의 예시는 실제 토스 Open API 응답을 캡처한 것입니다(값 일부만 발췌). 시장이 닫힌
시각에는 마지막 세션 스냅샷이 반환됩니다. 주문·계좌 도구의 예시는 서버 코드가 실제로 만들어내는
응답 형식을 그대로 보여줍니다 — 토스에는 모의투자 환경이 없어 주문 응답을 안전하게 캡처할 수
없으므로, 아래 예시는 기본 설정(실매매 꺼짐)에서 나오는 `DRY_RUN` 응답입니다.
_10 MCP tools total (5 market-data + 5 trading/account) with schemas and request/response examples.
Market-data examples are captured live (trimmed); when the market is closed, the last session's
snapshot is returned. Trading examples show the server's actual response shape — Toss has no
paper-trading environment, so the example below is the `DRY_RUN` response produced with trading
disabled (the default)._

> **심볼 규칙 / Symbols:** 국내=6자리 코드(`005930`, 통화 KRW) · 미국=티커(`AAPL`, 통화 USD).
> 콤마로 여러 종목(최대 200). `getPrices`·`getStocks`는 다중, `getOrderbook`·`getTrades`·`getCandles`는 단일 종목.

MCP 도구 호출 형식 / MCP `tools/call` shape:

```json
{ "method": "tools/call", "params": { "name": "<도구명>", "arguments": { ... } } }
```

---

## `getPrices` — 현재가 / current price

`symbols` (string, required) — 콤마 구분, 최대 200종목.

**요청 / request**
```json
{ "name": "getPrices", "arguments": { "symbols": "005930,AAPL" } }
```
**응답 / response**
```json
{"result":[
  {"symbol":"005930","lastPrice":"252500","currency":"KRW","timestamp":"2026-07-24T19:59:59+09:00"},
  {"symbol":"AAPL","lastPrice":"333.8","currency":"USD","timestamp":"2026-07-25T08:59:48+09:00"}
]}
```

---

## `getOrderbook` — 호가 / bid-ask ladder

`symbol` (string, required) — 단일 종목.

**요청 / request**
```json
{ "name": "getOrderbook", "arguments": { "symbol": "005930" } }
```
**응답 / response** (10호가 중 상위 발췌 / top of a 10-level ladder)
```json
{"result":{
  "timestamp":"2026-07-24T20:00:00+09:00","currency":"KRW",
  "asks":[{"price":"252500","volume":"24240"},{"price":"253000","volume":"36239"}],
  "bids":[{"price":"252000","volume":"49537"},{"price":"251500","volume":"47192"}]
}}
```

---

## `getTrades` — 최근 체결 / recent trades

`symbol` (string, required) · `count` (integer, 기본 50·최대 50).

**요청 / request**
```json
{ "name": "getTrades", "arguments": { "symbol": "005930", "count": 3 } }
```
**응답 / response**
```json
{"result":[
  {"price":"252500","volume":"1","currency":"KRW","timestamp":"2026-07-24T19:59:59+09:00"},
  {"price":"252500","volume":"8","currency":"KRW","timestamp":"2026-07-24T19:59:59+09:00"},
  {"price":"252500","volume":"2","currency":"KRW","timestamp":"2026-07-24T19:59:59+09:00"}
]}
```

---

## `getCandles` — 캔들 차트 / OHLC candles

`symbol` (required) · `interval` (required, `1m` 분봉 또는 `1d` 일봉) · `count` (기본 100·최대 200) ·
`before` (ISO 8601, 페이지네이션) · `adjusted` (수정주가, 기본 true).

**요청 / request**
```json
{ "name": "getCandles", "arguments": { "symbol": "005930", "interval": "1d", "count": 2 } }
```
**응답 / response** (`nextBefore`로 과거로 페이지네이션 / paginate backwards with `nextBefore`)
```json
{"result":{
  "candles":[
    {"timestamp":"2026-07-24T00:00:00+09:00","openPrice":"268000","highPrice":"269500","lowPrice":"247000","closePrice":"252500","volume":"41226962","currency":"KRW"},
    {"timestamp":"2026-07-23T00:00:00+09:00","openPrice":"265000","highPrice":"273000","lowPrice":"257000","closePrice":"273000","volume":"29117852","currency":"KRW"}
  ],
  "nextBefore":"2026-07-16T00:00:00+09:00"
}}
```

---

## `getStocks` — 종목 기본정보 / instrument info

`symbols` (string, required) — 콤마 구분, 최대 200종목.

**요청 / request**
```json
{ "name": "getStocks", "arguments": { "symbols": "005930,AAPL" } }
```
**응답 / response** (발췌 / trimmed)
```json
{"result":[
  {"symbol":"AAPL","name":"애플","englishName":"Apple","market":"NASDAQ","securityType":"STOCK",
   "currency":"USD","isinCode":"US0378331005","listDate":"1980-12-12","sharesOutstanding":"14687356000"},
  {"symbol":"005930","name":"삼성전자","englishName":"SamsungElec","market":"KOSPI","securityType":"STOCK",
   "currency":"KRW","isinCode":"KR7005930003","listDate":"1975-06-11","sharesOutstanding":"5846278608",
   "koreanMarketDetail":{"nxtSupported":true,"krxTradingSuspended":false}}
]}
```

---

## 주문·계좌 도구 / trading & account tools

> **기본은 미리보기입니다.** 주문이 실제로 전송되려면 세 가지가 모두 참이어야 합니다:
> ① 서버 설정 `toss.trading.enabled=true` ② 도구 호출에 `execute=true`
> ③ 설정된 금액·횟수 한도 이내. 하나라도 어긋나면 전송되지 않습니다.
> _Order tools preview by default; a real order needs the server switch, `execute=true`, and the configured limits._

이 도구들은 계좌 일련번호가 필요합니다. 환경변수 `TOSS_ACCOUNT` 로 설정하세요.
계좌 상태(보유·주문·매수여력)는 캐시하지 않습니다. 주문 직후 값이 바뀌기 때문입니다.

### `placeOrder` — 주문 / place an order

`symbol`(필수) · `side`(필수, BUY 또는 SELL) · `quantity`(필수) ·
`orderType`(필수, LIMIT 또는 MARKET) · `price`(LIMIT 일 때 필수) · `execute`(기본 false)

**요청 / request**
```json
{ "name": "placeOrder", "arguments": { "symbol": "005930", "side": "BUY", "quantity": "1", "orderType": "LIMIT", "price": "50000" } }
```
**응답 / response** (실매매가 꺼져 있을 때 / trading switch off)
```json
{"status":"DRY_RUN",
 "reason":"실매매가 꺼져 있습니다(toss.trading.enabled=false). 미리보기만 수행합니다.",
 "wouldPlace":{"symbol":"005930","side":"BUY","orderType":"LIMIT","quantity":"1","price":"50000","estimatedNotional":"50000","currency":"KRW"},
 "guardrail":["주문금액 50000 KRW 가 한도 100000 이하","오늘 주문수 0 가 한도 20 미만","종목 허용목록 미설정(제한 없음)"],
 "orderId":null,"clientOrderId":null}
```

`status` 는 넷 중 하나입니다.

| status | 뜻 |
|---|---|
| `DRY_RUN` | 전송하지 않았습니다. 무엇이 전송될지 `wouldPlace` 로 보여줍니다. |
| `PLACED` | 접수되었습니다. `orderId` 가 채워집니다. |
| `REJECTED` | 안전게이트나 토스가 거부했습니다. `reason` 을 보세요. |
| `UNKNOWN` | 응답을 받지 못했습니다. 접수 여부를 알 수 없습니다(아래 참고). |

`UNKNOWN` 이면 자동으로 재시도하지 않습니다. 이중 주문이 되기 때문입니다. 대신 응답의
`clientOrderId` 를 그대로 써서 10분 안에 다시 시도하면 중복 없이 같은 결과를 받거나,
`getOpenOrders` 로 접수 여부를 직접 확인할 수 있습니다.

### `cancelOrder` — 주문 취소 / cancel an order

`orderId`(필수) · `execute`(기본 false). 취소는 금액·횟수 한도의 영향을 받지 않습니다 —
한도로 취소를 막으면 원치 않는 포지션에 갇히기 때문입니다.

### `getOpenOrders` · `getHoldings` · `getBuyingPower` — 계좌 조회 / account reads

- `getOpenOrders(symbol?, limit?)` — 미체결 주문
- `getHoldings(symbol?)` — 보유 주식과 평가 손익
- `getBuyingPower(currency)` — 매수 가능 금액. `currency` 는 필수(KRW 또는 USD)

### 안전 설정 / safety configuration

```yaml
toss:
  account: ${TOSS_ACCOUNT:}
  trading:
    enabled: false             # 이 값이 true 여야만 주문이 전송된다
    max-order-notional-krw: 100000
    max-order-notional-usd: 100
    daily-order-count: 20
    symbol-allowlist: []       # 비우면 종목 제한 없음
```

한도를 넘는 주문은 `enabled=true`, `execute=true` 여도 거부됩니다. 한도 위반은 미리보기
단계에서도 `REJECTED` 로 드러나므로, 실매매를 켠 뒤에 뒤늦게 알게 되는 일이 없습니다.

토스는 모의투자·샌드박스 환경을 제공하지 않습니다. 모든 실제 호출은 실계좌에 그대로
반영됩니다. _Toss provides no sandbox or paper-trading environment — every live call hits a funded account._

---

## 캐시 동작 / caching behavior

모든 도구는 항목별 TTL 캐시를 거칩니다(현재가·호가 2초, 체결 3초, 분봉 10초, 일봉 1시간,
종목정보 6시간). 같은 키의 동시요청은 single-flight로 상단 1회 호출로 병합됩니다. 자세히는
[README 캐시 섹션](../README.md#-캐시--요청병합) 참고.
_Each tool is cached with a per-type TTL; concurrent same-key requests coalesce to one upstream call._
