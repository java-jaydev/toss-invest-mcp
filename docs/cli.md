# CLI 어댑터 · Command-Line Adapter

[← README(한국어)](../README.md) · [← README (English)](../README.en.md)

이 프로젝트는 MCP(stdio·HTTP)와 **완전히 같은 코어**(`MarketDataService`·`TradingService`) 위에
한 가지 실행 모드를 더 제공합니다: **CLI**. AI 에이전트 없이, 터미널에서 명령 하나로 시세를
조회하거나 (안전게이트를 통과하면) 주문을 낼 수 있습니다. 사람이 직접 스크립트에 넣어 쓰거나,
MCP를 붙이기 부담스러운 상황에서 같은 코어를 그대로 재사용하고 싶을 때를 위한 것입니다.

**안전게이트는 어댑터가 아니라 코어에 있습니다.** CLI 명령은 `TradingService`(그리고 그 안의
`OrderGuard`)에 그대로 위임하는 얇은 껍데기이며, 스스로 판단하지 않습니다. 그래서 MCP 도구와
똑같은 규칙이 CLI에도 그대로 적용됩니다 — 주문이 실제로 전송되려면 ①서버 설정
`toss.trading.enabled=true`(킬스위치) ②명령에 `--execute` 플래그 ③설정된 금액·횟수 한도(및
선택적 종목 허용목록)를 모두 통과해야 합니다. `--execute` 없이는 항상 미리보기입니다.

_This CLI runs on exactly the same core the MCP tools use, so the same safety gates apply: an
order transmits only when the kill switch is on, `--execute` is passed, and the configured
guardrails allow it. Without `--execute` a command always previews._

> ⚠️ **토스증권은 모의투자·샌드박스 환경을 제공하지 않습니다. 실매매를 켠 뒤 전송되는 모든
> 주문은 실제 자금이 오가는 계좌에 그대로 반영됩니다.** 이는 CLI든 MCP든 동일하며, 완화할
> 방법이 없습니다.
> _Toss provides no sandbox or paper-trading environment. Every order that actually transmits
> hits a real, funded account — plainly, with no softer alternative._

---

## 빌드 · Build

CLI도 같은 jar 안에 들어 있습니다. 별도 빌드가 필요 없고, 평소처럼 빌드하면 됩니다.

```bash
git clone https://github.com/java-jaydev/toss-invest-mcp.git
cd toss-invest-mcp
./gradlew build
```

빌드 결과는 `build/libs/toss-invest-mcp-0.1.0.jar` 입니다(버전은 `build.gradle`의 `version`을
따릅니다).

## `toss` 래퍼 사용법 · Using the wrapper script

저장소 루트의 `toss` 스크립트는 `build/libs/`에서 jar를 찾아
`--spring.profiles.active=cli`를 자동으로 붙여 실행합니다. 이 프로파일 플래그를 직접 입력할
필요가 없습니다 — 스크립트가 매번 넣어줍니다.

```bash
./toss price 005930
```

실행 권한은 저장소에 이미 포함돼 있습니다. 만약 권한이 풀렸다면 `chmod +x toss`로 복구하세요.
jar가 없으면(`./gradlew build`를 아직 안 했으면) 스크립트는 안내 메시지를 stderr에 출력하고
종료 코드 4로 끝납니다.

래퍼 없이 직접 실행하려면(예: jar 경로가 다르거나 다른 프로파일 옵션을 더 주고 싶을 때):

```bash
java -jar build/libs/toss-invest-mcp-0.1.0.jar --spring.profiles.active=cli price 005930
```

### `--spring.*` 인자는 어떻게 되나 · What happens to `--spring.*` arguments

CLI는 스프링 부트 위에서 돈다. 그래서 `--spring.profiles.active=cli`처럼 스프링이 소비하는
인자도 `CommandLineRunner`에 원본 그대로 들어온다. 이 인자를 그대로 명령줄 파서(picocli)에
넘기면 "Unknown option" 으로 항상 실패한다 — 그래서 CLI는 picocli에 넘기기 전에
`--spring.`로 시작하는 인자를 걸러낸다(`CliRunner.filterSpringArgs`).

사용자 입장에서 이건 신경 쓸 일이 아니다: `./toss price 005930`처럼 평소대로 쓰면 된다.
다만 알아두면 좋은 점 하나 — `--spring.`로 시작하는 인자를 직접 추가로 넣어도(예: 다른
설정 파일을 가리키려고) 명령줄 파서에는 전달되지 않고 조용히 사라지지만, 스프링 자체에는
여전히 적용된다(스프링이 `main(String[])`의 원본 인자를 직접 읽기 때문). 즉 이런 인자를
`price`·`order` 같은 명령의 옵션과 섞어 써도 "Unknown option" 오류가 나지 않는다는 뜻이다.

## 명령 표 · Command reference

모든 명령은 서비스 메서드에 그대로 위임한다(전략 판단 없음). 표의 인자·기본값은 소스에서
직접 확인한 것이다.

| 명령 | 인자 | 위임 대상 |
|---|---|---|
| `toss price <symbols>` | `symbols`(필수, 콤마 구분, 최대 200) | `MarketDataService.prices` |
| `toss orderbook <symbol>` | `symbol`(필수, 단일 종목) | `MarketDataService.orderbook` |
| `toss trades <symbol> [--count N]` | `symbol`(필수) · `--count`(선택, 정수) | `MarketDataService.trades` |
| `toss candles <symbol> --interval <1m\|1d> [--count N] [--before ISO8601] [--adjusted]` | `symbol`(필수) · `--interval`(필수) · `--count`(선택) · `--before`(선택, ISO 8601 시각) · `--adjusted`(선택, 플래그 — 값 없이 켜기만 함) | `MarketDataService.candles` |
| `toss stocks <symbols>` | `symbols`(필수, 콤마 구분, 최대 200) | `MarketDataService.stocks` |
| `toss holdings [--symbol S]` | `--symbol`(선택) | `TradingService.holdings` |
| `toss buying-power <KRW\|USD>` | `currency`(필수, 위치 인자) | `TradingService.buyingPower` |
| `toss orders [--symbol S] [--limit N]` | `--symbol`(선택) · `--limit`(선택, 정수) | `TradingService.openOrders` |
| `toss order <buy\|sell> <symbol> --qty Q --type <limit\|market> [--price P] [--execute]` | `side`·`symbol`(필수, 위치) · `--qty`(필수) · `--type`(필수) · `--price`(`--type limit`일 때 필수) · `--execute`(선택, 플래그, 기본 false) | `TradingService.placeOrder` |
| `toss cancel <orderId> [--execute]` | `orderId`(필수, 위치) · `--execute`(선택, 플래그, 기본 false) | `TradingService.cancelOrder` |

**`--execute`가 없으면 항상 미리보기다.** `order`·`cancel` 두 명령에만 있는 플래그이며, 이
CLI는 기본값을 바꾸거나 따로 판단하지 않고 있는 그대로 `TradingService`에 넘긴다.

`--type limit`인데 `--price`가 없으면 서비스를 부르기 전에 걸러져 종료 코드 3(사용법 오류)을
돌려준다 — 이건 안전 판단이 아니라 인자 조합 검증이다. `--type market`이면 `--price`가
없어도 된다.

시세 명령(`price`·`orderbook`·`trades`·`candles`·`stocks`)의 심볼 규칙은 MCP 도구와 같다:
국내는 6자리 코드(`005930`, KRW), 미국은 티커(`AAPL`, USD). 자세한 응답 형식은
[docs/tools.md](tools.md)를 참고하라 — 같은 서비스가 만드는 응답이므로 예시가 그대로
적용된다.

## 종료 코드 · Exit codes

스크립트에서 바로 분기할 수 있도록 결과를 종료 코드로 알린다.

| 코드 | 의미 | 해당 명령 |
|---|---|---|
| 0 | 성공 — 조회 성공, 또는 주문/취소 결과가 `PLACED`나 `DRY_RUN` | 전체 |
| 1 | `REJECTED` — 안전게이트나 토스가 거부 | `order`, `cancel` |
| 2 | `UNKNOWN` — 접수 여부 불명(응답을 받지 못함) | `order`, `cancel` |
| 3 | 사용법 오류 — 필수 인자 누락, 잘못된 값, 알 수 없는 하위 명령 | 전체 |
| 4 | 그 밖의 실패 — 처리되지 않은 예외(인증 실패, 네트워크 오류 등) | 전체 |

_Exit codes are meant for scripting: 0=success (or order `PLACED`/`DRY_RUN`), 1=`REJECTED`,
2=`UNKNOWN` (delivery unconfirmed), 3=usage error, 4=any other unhandled failure
(auth/network)._

`order`·`cancel`은 실행 전에 상태를 사람이 읽을 수 있게 먼저 찍는다(`status: ...`,
`reason: ...`), 그다음 상세(미리보기·가드레일 체크리스트·주문번호)를 JSON으로 덧붙인다.
`UNKNOWN`이 나오면 CLI는 재시도하지 않는다 — 중복 주문이 될 수 있기 때문이다. 응답 JSON의
`clientOrderId`를 그대로 써서 10분 안에 다시 시도하거나, `toss orders`로 접수 여부를 직접
확인하라.

## 자격증명과 IP 허용목록 · Credentials and the IP allowlist

CLI는 MCP와 같은 환경변수를 그대로 쓴다. **하드코딩은 지원하지 않으며, 셸 환경변수로만
전달한다:**

| 환경변수 | 용도 | 필수 여부 |
|---|---|---|
| `TOSS_CLIENT_ID` | 토스 Open API 클라이언트 ID | 필수 |
| `TOSS_CLIENT_SECRET` | 토스 Open API 시크릿 | 필수 |
| `TOSS_ACCOUNT` | 계좌 일련번호 | `holdings`·`buying-power`·`orders`·`order`·`cancel`에 필수(시세 조회만 쓸 거면 비워도 됨) |

```bash
export TOSS_CLIENT_ID=발급받은_클라이언트_ID
export TOSS_CLIENT_SECRET=발급받은_시크릿
export TOSS_ACCOUNT=계좌_일련번호
./toss price 005930
```

**토스는 샌드박스가 없을 뿐 아니라, 호출하는 서버의 공인(public) IP를 콘솔에 미리 등록해야만
요청을 받아준다.** [openapi.tossinvest.com](https://openapi.tossinvest.com) 콘솔의 허용목록에
현재 공인 IP가 없으면, 인증 실패가 **원인을 알 수 없는 형태로** 난다(자격증명 자체는 맞는데도
401이 난다). IP를 확인하려면 `curl https://checkip.amazonaws.com` 등을 쓰면 된다. 이런 인증
실패는 CLI에서 종료 코드 4(그 밖의 실패)로 나타난다.

_Toss requires the caller's public IP to be allowlisted in its console — otherwise the CLI fails
with an opaque authentication error (exit code 4), even though the credentials themselves are
correct._

## 실매매를 켜는 법과 그 위험 · Enabling live trading, deliberately

기본값은 안전한 쪽이다. 아무것도 설정하지 않으면 `order`·`cancel`은 항상 미리보기만
수행한다. 실제로 전송하려면 **세 가지를 모두 의도적으로** 맞춰야 한다.

1. **킬스위치를 켠다** — 환경변수 `TOSS_TRADING_ENABLED=true` (jar에는 `false`로 고정
   패키징되어 있으므로, 이 환경변수가 사실상 유일한 켜는 방법이다).
2. **명령에 `--execute`를 붙인다** — 이게 없으면 킬스위치가 켜져 있어도 미리보기다.
3. **설정된 한도 안이어야 한다** — 주문 1건당 금액 한도(`toss.trading.max-order-notional-krw`/
   `-usd`, 기본 각각 100000 KRW / 100 USD), 하루 주문 횟수 한도
   (`toss.trading.daily-order-count`, 기본 20건), 그리고 설정했다면 종목 허용목록
   (`toss.trading.symbol-allowlist`, 기본 비어 있어 제한 없음). 하나라도 어긋나면 킬스위치와
   `--execute`가 모두 참이어도 `REJECTED`로 거부된다.

```bash
TOSS_TRADING_ENABLED=true ./toss order buy 005930 --qty 1 --type limit --price 50000 --execute
```

> ⚠️ **하루 주문 횟수 한도는 CLI에서 사실상 의미가 없다.** 이 한도는 서버 프로세스의
> 메모리(`DailyOrderCounter`)에서 세며, 프로세스가 재시작되면 0으로 되돌아간다. MCP
> stdio/HTTP 서버는 오래 떠 있는 프로세스라 그 안에서는 카운트가 누적되지만, **CLI는 명령
> 하나마다 완전히 새 프로세스를 띄우고 끝낸다** — 그래서 `./toss order ... --execute`를 몇
> 번을 연달아 실행해도 매번 카운터가 0에서 다시 시작하고, 하루 주문 횟수 한도는 절대
> 걸리지 않는다. CLI로 실매매를 쓸 때 실질적인 방어선은 **킬스위치**와 **주문 1건당 금액
> 한도**(그리고 설정했다면 종목 허용목록)뿐이다. 이 한도에 기대어 "하루에 이만큼만 나가겠지"
> 라고 안심하지 말 것.
>
> _The daily order-count guardrail is counted in the server process's memory and resets when the
> process restarts. The MCP stdio/HTTP server is a long-lived process, so the count accumulates
> there — but **a CLI invocation is a fresh process every single time**, so running
> `./toss order ... --execute` repeatedly resets the counter to zero each time and the
> daily-count limit effectively never triggers for CLI usage. For CLI trading, the real defenses
> are the kill switch and the per-order notional cap (plus the symbol allowlist, if configured) —
> do not rely on the daily count to bound how much can go out in a day._

## 예시 · Examples

### 미리보기 → 실행 흐름 · Preview, then execute

```bash
# 1) 먼저 미리보기(--execute 없음). 킬스위치가 꺼져 있어도 안전하게 확인 가능하다.
./toss order buy 005930 --qty 1 --type limit --price 50000
# status: DRY_RUN
# reason: 실매매가 꺼져 있습니다(toss.trading.enabled=false). 미리보기만 수행합니다.
# {"status":"DRY_RUN","reason":"...","wouldPlace":{...},"guardrail":[...],"orderId":null,"clientOrderId":null}

# 2) 내용을 확인했고 실제로 보낼 준비가 됐다면, 킬스위치를 켜고 --execute를 붙인다.
TOSS_TRADING_ENABLED=true ./toss order buy 005930 --qty 1 --type limit --price 50000 --execute
# status: PLACED
# reason: 주문이 접수되었습니다.
# {"status":"PLACED","reason":"...","wouldPlace":{...},"guardrail":[...],"orderId":"...","clientOrderId":"..."}

echo "종료 코드: $?"   # 0
```

### 파이프 예시 · Piping example

시세 조회 명령은 JSON 문자열을 그대로 stdout에 찍으므로 `jq` 같은 도구와 바로 연결된다.

```bash
./toss price 005930,AAPL | jq '.result[] | {symbol, lastPrice, currency}'
```

### 종료 코드로 분기하기 · Branching on exit codes

```bash
if ./toss order sell 005930 --qty 1 --type market --execute; then
  echo "접수 또는 미리보기 완료"
else
  case $? in
    1) echo "거부됨 — 안전게이트나 토스가 막았다" ;;
    2) echo "접수 여부 불명 — clientOrderId로 재시도하거나 toss orders로 확인" ;;
    3) echo "사용법 오류 — 인자를 확인" ;;
    4) echo "그 밖의 실패 — 인증(IP 허용목록 포함)·네트워크를 확인" ;;
  esac
fi
```
