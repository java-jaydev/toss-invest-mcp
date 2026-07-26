# 도구 레퍼런스 · Tool Reference

[← README(한국어)](../README.md) · [← README (English)](../README.en.md)

이 서버가 제공하는 MCP 도구 5종의 스키마와 **실제 요청/응답 예시**입니다. 모든 예시는
실제 토스 Open API 응답을 캡처한 것입니다(값 일부만 발췌). 시장이 닫힌 시각에는 마지막
세션 스냅샷이 반환됩니다.
_The 5 MCP tools with their schemas and real request/response examples (captured live; trimmed).
When the market is closed, the last session's snapshot is returned._

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

## 캐시 동작 / caching behavior

모든 도구는 항목별 TTL 캐시를 거칩니다(현재가·호가 2초, 체결 3초, 분봉 10초, 일봉 1시간,
종목정보 6시간). 같은 키의 동시요청은 single-flight로 상단 1회 호출로 병합됩니다. 자세히는
[README 캐시 섹션](../README.md#-캐시--요청병합) 참고.
_Each tool is cached with a per-type TTL; concurrent same-key requests coalesce to one upstream call._
