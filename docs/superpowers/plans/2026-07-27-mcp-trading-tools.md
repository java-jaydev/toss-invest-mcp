# MCP 주문/거래 도구 + 안전게이트 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `toss-invest-mcp`에 주문 실행 도구 5종과 다층 안전게이트를 추가해, 외부 두뇌(앱 또는 AI)가 매매 한 사이클을 돌릴 수 있게 한다.

**Architecture:** 기존 읽기 계층은 그대로 두고 쓰기 계층을 나란히 추가한다. `TradingTools`(@Tool 껍데기) → `TradingService`(오케스트레이션) → `OrderGuard`(순수 안전판정) → `TossApiClient`(REST). 안전 판정은 네트워크·시간에 의존하지 않는 순수 컴포넌트로 분리해 실 API 없이 전수 테스트한다.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring AI 1.1 (MCP server), Gradle, Jackson, JUnit 5 + AssertJ + Mockito, OkHttp MockWebServer.

**설계 문서:** [2026-07-27-mcp-trading-tools-design.md](../specs/2026-07-27-mcp-trading-tools-design.md)

## Global Constraints

- **전략·규칙 로직을 이 저장소에 넣지 않는다.** 도구는 원시적이어야 한다(하락폭 트리거·차수·목표수익률 등 금지).
- **시크릿을 코드·커밋·로그·터미널 출력·문서에 절대 노출하지 않는다.** 자격증명은 환경변수로만(`TOSS_CLIENT_ID`, `TOSS_CLIENT_SECRET`, `TOSS_ACCOUNT`).
- **CI는 실제 토스 API를 호출하지 않는다.** 모든 테스트는 모킹된 클라이언트나 로컬 MockWebServer를 쓴다.
- **쓰기 경로는 캐시·single-flight를 타지 않는다.** `MarketDataCache`는 시장가 추정용 시세 조회에만 쓴다.
- **쓰기 경로는 자동 재시도하지 않는다.** 대신 모든 주문에 멱등성 키 `clientOrderId`를 실어 보내고 결과에 담아 돌려준다.
- **`confirmHighValueOrder`를 자동으로 `true`로 보내지 않는다.** 1억원 이상 주문에 대한 토스 자체 방어막을 무력화하면 안 된다.
- **블로킹 호출을 `synchronized` 안에 넣지 않는다.** 상호배제가 필요하면 `ReentrantLock`을 쓴다(가상스레드 피닝 0 유지).
- 커밋은 Conventional Commits, 명령형. **자동 생성 서명이나 AI 공동저자 표기를 넣지 않는다.**
- 문서에 없는 기능을 적지 않는다. 약어는 처음 쓸 때 풀어 쓴다.
- 계좌 설정은 새로 만들지 않고 기존 `TossProperties.account()`를 재사용한다.

## 검증 기준 (Definition of Done)

사람의 승인 대신 이 기준이 관문이다. 각 태스크는 아래를 **실제로 실행해 통과시킨 뒤** 다음으로 넘어간다.
"통과했다"는 주장만으로는 부족하고, 실행한 명령과 출력이 근거로 남아야 한다.

**전 태스크 공통**

1. `./gradlew build` 가 BUILD SUCCESSFUL. 기존 테스트가 하나도 깨지지 않는다(회귀 없음).
2. `VirtualThreadPinningTest` 의 피닝 수가 여전히 0이다. 새 코드가 블로킹을 `synchronized` 안에 넣지 않았다는 증거다.
3. 테스트 로그에 `skipped` 로 빠진 신규 테스트가 없다. 초록불이 곧 실행됨을 뜻하지는 않으므로 개별 결과를 확인한다.
4. `git diff` 에 자격증명·계좌번호·토큰이 들어가지 않았다.

**안전장치 단언 (이 프로젝트에서 가장 중요한 관문)**

다음은 "해야 할 일"이 아니라 **"하지 말아야 할 일을 정말 안 하는지"**를 단언한다. 전부 테스트로 증명한다.

| 단언 | 어디서 |
|---|---|
| `toss.trading.enabled=false` 면 `execute=true` 여도 주문이 **전송되지 않는다** (`verify(api, never()).placeOrder(any())`) | `TradingServiceTest.disabledTradingNeverSendsTheOrder` |
| 한도를 넘는 주문은 실매매가 꺼져 있어도 `REJECT` 로 드러난다 | `OrderGuardTest.notionalOverCapIsRejectedEvenWhileTradingDisabled` |
| 금액을 산출하지 못하면 거부한다(모르면 막는다) | `OrderGuardTest.unknownNotionalIsRejected`, `TradingServiceTest.marketOrderIsRejectedWhenPriceLookupFails` |
| 타임아웃 시 **재시도하지 않는다** (`verify(api, times(1))`) 그리고 `UNKNOWN` + 멱등성 키를 돌려준다 | `TradingServiceTest.timeoutReturnsUnknownAndDoesNotRetry` |
| 주문 본문에 `confirmHighValueOrder` 를 **넣지 않는다** | `TradingServiceTest.enabledWithExecuteSendsIdempotencyKeyAndNoHighValueConfirm` |
| 계좌 상태 조회는 **캐시되지 않는다**(2회 호출 → 상위 2회) | `TradingServiceTest.accountStateReadsAreNotCached` |
| 취소는 금액·횟수 한도에 막히지 않는다(포지션에 갇히지 않도록) | `OrderGuardTest.cancelIsNotSubjectToNotionalOrCountGuardrails` |
| 계좌 헤더 `X-Tossinvest-Account` 가 실제로 전송된다 | `TossApiClientOrderTest.placeOrderPostsBodyWithAccountHeader` |
| 계좌 미설정 시 조용히 실패하지 않고 무엇을 설정할지 알려준다 | `TossApiClientOrderTest.missingAccountFailsWithClearMessage` |

**종단간 (Task 6 완료 후 1회)**

MCP 도구가 실제로 등록되고 응답하는지 프로토콜 수준에서 확인한다. 실 토스 API는 호출하지 않는다.

```bash
./gradlew bootRun --args='--spring.profiles.active=http' &
# initialize → tools/list 로 10개 도구(시세 5 + 주문·계좌 5)가 보이는지 확인
# placeOrder 를 execute 없이 호출해 status=DRY_RUN 과 wouldPlace 가 오는지 확인
```

기대: `tools/list` 에 `placeOrder`·`cancelOrder`·`getOpenOrders`·`getHoldings`·`getBuyingPower` 가 포함되고,
`placeOrder` 호출이 `DRY_RUN` 을 돌려준다(자격증명이 없어도 게이트가 먼저 걸리므로 실 API를 타지 않는다).

**실계좌 주문은 이 기준에 포함되지 않는다.** 토스에 샌드박스가 없어 자동 검증이 불가능하고
되돌릴 수 없으므로, 문서 맨 아래의 수동 절차로만 남긴다.

## 확정된 토스 API 사실 (라이브 `openapi.json` 파싱으로 확인, 2026-07-27)

| 용도 | 메서드 + 경로 | 필수 |
|---|---|---|
| 주문 생성 | `POST /api/v1/orders` | 헤더 `X-Tossinvest-Account` |
| 주문 취소 | `POST /api/v1/orders/{orderId}/cancel` | 헤더 + 경로 `orderId`, 본문 `{}` |
| 주문 목록 | `GET /api/v1/orders` | 헤더 + 쿼리 `status`(OPEN\|CLOSED) |
| 보유 주식 | `GET /api/v1/holdings` | 헤더 (쿼리 `symbol` 선택) |
| 매수 가능 금액 | `GET /api/v1/buying-power` | 헤더 + 쿼리 `currency` |

주문 생성 본문(수량 기반) 필드: `symbol`, `side`(`BUY`\|`SELL`), `orderType`(`LIMIT`\|`MARKET`), `quantity`(문자열 십진수) 필수. `price`는 `LIMIT`일 때 필수이고 `MARKET`에 넣으면 오류. `clientOrderId`는 10분간 유효한 멱등성 키. `timeInForce` 미전달 시 `DAY`. 주문 생성 응답 결과는 `{orderId, clientOrderId}`, 취소 응답 결과는 `{orderId}`(취소로 새로 발급된 식별자라 원주문 번호와 다름).

## File Structure

**생성**
- `src/main/java/dev/jaydev/tossmcp/config/TossTradingProperties.java` — `toss.trading.*` 설정 바인딩. 기본값은 "꺼짐 + 조여짐".
- `src/main/java/dev/jaydev/tossmcp/trading/OrderCommand.java` — 검증된 주문 명령 + `Side`/`OrderType` enum.
- `src/main/java/dev/jaydev/tossmcp/trading/GuardDecision.java` — 안전게이트 판정 결과 + `Outcome` enum.
- `src/main/java/dev/jaydev/tossmcp/trading/OrderGuard.java` — 가드레일·킬스위치·실행플래그 순수 판정.
- `src/main/java/dev/jaydev/tossmcp/trading/DailyOrderCounter.java` — 한국시간 자정 기준 하루 주문 수.
- `src/main/java/dev/jaydev/tossmcp/trading/OrderRateLimiter.java` — 쓰기 전용 초당 한도.
- `src/main/java/dev/jaydev/tossmcp/trading/OrderResult.java` — 도구 응답 형태 + `Status` enum + `OrderPreview`.
- `src/main/java/dev/jaydev/tossmcp/service/TradingService.java` — 검증 → 추정 → 판정 → 실행 → 정규화.
- `src/main/java/dev/jaydev/tossmcp/tools/TradingTools.java` — `@Tool` 5종(얇은 위임).
- `CLAUDE.md` — `AGENTS.md`를 가리키는 짧은 포인터.

**수정**
- `src/main/java/dev/jaydev/tossmcp/client/TossApiClient.java` — 주문 관련 메서드 5개 + 계좌 헤더.
- `src/main/java/dev/jaydev/tossmcp/TossMcpApplication.java` — `Clock` 빈, 도구 등록에 `TradingTools` 추가.
- `src/main/resources/application.yml` — `toss.trading.*` 기본값.
- `build.gradle` — 테스트 의존성 MockWebServer.
- `docs/tools.md`, `AGENTS.md`, `README.md`, `README.en.md`, `llms.txt` — 신규 도구와 안전 모델 반영.

---

### Task 1: 안전게이트와 설정

가장 먼저 만든다. 이 저장소에서 가장 정확해야 하는 코드이며, 네트워크 없이 전수 테스트할 수 있다.

**Files:**
- Create: `src/main/java/dev/jaydev/tossmcp/config/TossTradingProperties.java`
- Create: `src/main/java/dev/jaydev/tossmcp/trading/OrderCommand.java`
- Create: `src/main/java/dev/jaydev/tossmcp/trading/GuardDecision.java`
- Create: `src/main/java/dev/jaydev/tossmcp/trading/OrderGuard.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/dev/jaydev/tossmcp/trading/OrderGuardTest.java`

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces:
  - `TossTradingProperties(boolean enabled, BigDecimal maxOrderNotionalKrw, BigDecimal maxOrderNotionalUsd, int dailyOrderCount, List<String> symbolAllowlist)`
  - `OrderCommand(String symbol, Side side, BigDecimal quantity, OrderType orderType, BigDecimal price, boolean execute)`, `Side{BUY,SELL}`, `OrderType{LIMIT,MARKET}`
  - `GuardDecision(Outcome outcome, String reason, List<String> checks)`, `Outcome{ALLOW,DRY_RUN,REJECT}`
  - `OrderGuard.evaluatePlace(OrderCommand cmd, BigDecimal notional, String currency, int todayOrderCount) -> GuardDecision`
  - `OrderGuard.evaluateCancel(boolean execute) -> GuardDecision`

- [ ] **Step 1: 타입 3개를 만든다**

`src/main/java/dev/jaydev/tossmcp/config/TossTradingProperties.java`:

```java
package dev.jaydev.tossmcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * 실매매 안전장치 설정. 기본값은 "꺼짐 + 조여짐" 이며 사용자가 의도적으로 열어야 한다.
 * enabled 가 false 면 어떤 요청도 실제로 전송되지 않는다.
 */
@ConfigurationProperties(prefix = "toss.trading")
public record TossTradingProperties(
        boolean enabled,
        BigDecimal maxOrderNotionalKrw,
        BigDecimal maxOrderNotionalUsd,
        int dailyOrderCount,
        List<String> symbolAllowlist
) {
    public TossTradingProperties {
        if (maxOrderNotionalKrw == null) {
            maxOrderNotionalKrw = new BigDecimal("100000");
        }
        if (maxOrderNotionalUsd == null) {
            maxOrderNotionalUsd = new BigDecimal("100");
        }
        if (dailyOrderCount <= 0) {
            dailyOrderCount = 20;
        }
        symbolAllowlist = symbolAllowlist == null ? List.of() : List.copyOf(symbolAllowlist);
    }
}
```

`src/main/java/dev/jaydev/tossmcp/trading/OrderCommand.java`:

```java
package dev.jaydev.tossmcp.trading;

import java.math.BigDecimal;

/**
 * 검증을 마친 주문 명령. 도구 파라미터(문자열)를 파싱·검증한 결과이며 전략 판단은 담지 않는다.
 */
public record OrderCommand(
        String symbol,
        Side side,
        BigDecimal quantity,
        OrderType orderType,
        BigDecimal price,
        boolean execute
) {
    public enum Side { BUY, SELL }

    public enum OrderType { LIMIT, MARKET }
}
```

`src/main/java/dev/jaydev/tossmcp/trading/GuardDecision.java`:

```java
package dev.jaydev.tossmcp.trading;

import java.util.List;

/**
 * 안전게이트 판정 결과.
 * checks 는 통과한 검사를 사람이 읽을 수 있게 남긴 것이라 응답에 그대로 실린다.
 */
public record GuardDecision(Outcome outcome, String reason, List<String> checks) {
    public enum Outcome { ALLOW, DRY_RUN, REJECT }
}
```

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`src/test/java/dev/jaydev/tossmcp/trading/OrderGuardTest.java`:

```java
package dev.jaydev.tossmcp.trading;

import dev.jaydev.tossmcp.config.TossTradingProperties;
import dev.jaydev.tossmcp.trading.GuardDecision.Outcome;
import dev.jaydev.tossmcp.trading.OrderCommand.OrderType;
import dev.jaydev.tossmcp.trading.OrderCommand.Side;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderGuardTest {

    private static final BigDecimal KRW_CAP = new BigDecimal("100000");
    private static final BigDecimal USD_CAP = new BigDecimal("100");

    private OrderGuard guard(boolean enabled, List<String> allowlist) {
        return new OrderGuard(new TossTradingProperties(enabled, KRW_CAP, USD_CAP, 20, allowlist));
    }

    private OrderCommand buy(boolean execute) {
        return new OrderCommand("005930", Side.BUY, new BigDecimal("1"),
                OrderType.LIMIT, new BigDecimal("50000"), execute);
    }

    @Test
    void killSwitchOffForcesDryRunEvenWhenExecuteRequested() {
        GuardDecision d = guard(false, List.of())
                .evaluatePlace(buy(true), new BigDecimal("50000"), "KRW", 0);

        assertThat(d.outcome()).isEqualTo(Outcome.DRY_RUN);
        assertThat(d.reason()).contains("toss.trading.enabled");
    }

    @Test
    void enabledWithoutExecuteFlagIsDryRun() {
        GuardDecision d = guard(true, List.of())
                .evaluatePlace(buy(false), new BigDecimal("50000"), "KRW", 0);

        assertThat(d.outcome()).isEqualTo(Outcome.DRY_RUN);
    }

    @Test
    void enabledWithExecuteFlagIsAllowed() {
        GuardDecision d = guard(true, List.of())
                .evaluatePlace(buy(true), new BigDecimal("50000"), "KRW", 0);

        assertThat(d.outcome()).isEqualTo(Outcome.ALLOW);
    }

    @Test
    void notionalOverCapIsRejectedEvenWhileTradingDisabled() {
        // 한도 위반은 미리보기에서도 드러나야 한다. 실매매를 켠 뒤에 놀라면 늦다.
        GuardDecision d = guard(false, List.of())
                .evaluatePlace(buy(false), new BigDecimal("100001"), "KRW", 0);

        assertThat(d.outcome()).isEqualTo(Outcome.REJECT);
        assertThat(d.reason()).contains("한도");
    }

    @Test
    void notionalExactlyAtCapIsNotRejected() {
        GuardDecision d = guard(true, List.of())
                .evaluatePlace(buy(true), KRW_CAP, "KRW", 0);

        assertThat(d.outcome()).isEqualTo(Outcome.ALLOW);
    }

    @Test
    void unknownNotionalIsRejected() {
        // 시세를 못 구해 금액을 모르면 막는다.
        GuardDecision d = guard(true, List.of())
                .evaluatePlace(buy(true), null, "KRW", 0);

        assertThat(d.outcome()).isEqualTo(Outcome.REJECT);
    }

    @Test
    void usdCapAppliesToUsdOrders() {
        OrderCommand aapl = new OrderCommand("AAPL", Side.BUY, new BigDecimal("1"),
                OrderType.LIMIT, new BigDecimal("101"), true);

        GuardDecision d = guard(true, List.of())
                .evaluatePlace(aapl, new BigDecimal("101"), "USD", 0);

        assertThat(d.outcome()).isEqualTo(Outcome.REJECT);
    }

    @Test
    void dailyOrderCountAtLimitIsRejected() {
        GuardDecision d = guard(true, List.of())
                .evaluatePlace(buy(true), new BigDecimal("50000"), "KRW", 20);

        assertThat(d.outcome()).isEqualTo(Outcome.REJECT);
    }

    @Test
    void symbolOutsideAllowlistIsRejected() {
        GuardDecision d = guard(true, List.of("000660"))
                .evaluatePlace(buy(true), new BigDecimal("50000"), "KRW", 0);

        assertThat(d.outcome()).isEqualTo(Outcome.REJECT);
        assertThat(d.reason()).contains("허용목록");
    }

    @Test
    void emptyAllowlistMeansNoRestriction() {
        GuardDecision d = guard(true, List.of())
                .evaluatePlace(buy(true), new BigDecimal("50000"), "KRW", 0);

        assertThat(d.outcome()).isEqualTo(Outcome.ALLOW);
    }

    @Test
    void cancelIsNotSubjectToNotionalOrCountGuardrails() {
        // 취소를 한도로 막으면 포지션에 갇힌다.
        GuardDecision d = guard(true, List.of("000660")).evaluateCancel(true);

        assertThat(d.outcome()).isEqualTo(Outcome.ALLOW);
    }

    @Test
    void cancelStillRespectsKillSwitch() {
        GuardDecision d = guard(false, List.of()).evaluateCancel(true);

        assertThat(d.outcome()).isEqualTo(Outcome.DRY_RUN);
    }
}
```

- [ ] **Step 3: 테스트가 실패하는지 확인한다**

Run: `./gradlew test --tests '*OrderGuardTest'`
Expected: FAIL — `OrderGuard` 클래스가 없어 컴파일 오류.

- [ ] **Step 4: OrderGuard 를 구현한다**

`src/main/java/dev/jaydev/tossmcp/trading/OrderGuard.java`:

```java
package dev.jaydev.tossmcp.trading;

import dev.jaydev.tossmcp.config.TossTradingProperties;
import dev.jaydev.tossmcp.trading.GuardDecision.Outcome;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 실주문 직전에 통과해야 하는 안전게이트.
 *
 * 판정 순서는 가드레일 → 킬스위치 → execute 플래그다. 가드레일을 먼저 보는 이유는
 * 한도 위반이 미리보기 단계에서도 REJECT 로 드러나야 하기 때문이다. 킬스위치를 먼저 보면
 * 한도를 넘는 주문도 "이렇게 주문됩니다"로 보이고, 실매매를 켠 순간에야 거부당한다.
 *
 * 네트워크·시계에 의존하지 않으므로 실 API 없이 전수 테스트할 수 있다.
 * 전략 판단은 하지 않는다 — 한도와 스위치만 본다.
 */
@Component
public class OrderGuard {

    private final TossTradingProperties props;

    public OrderGuard(TossTradingProperties props) {
        this.props = props;
    }

    /**
     * @param notional        추정 주문금액. 산출하지 못했으면 null 을 넘긴다(모르면 막는다).
     * @param currency        "KRW" 또는 "USD"
     * @param todayOrderCount 오늘 이미 보낸 주문 수
     */
    public GuardDecision evaluatePlace(OrderCommand cmd, BigDecimal notional, String currency, int todayOrderCount) {
        List<String> checks = new ArrayList<>();

        if (notional == null) {
            return new GuardDecision(Outcome.REJECT,
                    "주문 금액을 산출하지 못해(시세 조회 실패) 한도를 검증할 수 없습니다.", checks);
        }

        BigDecimal cap = "USD".equals(currency) ? props.maxOrderNotionalUsd() : props.maxOrderNotionalKrw();
        if (notional.compareTo(cap) > 0) {
            return new GuardDecision(Outcome.REJECT,
                    "주문 금액 " + notional.toPlainString() + " " + currency
                            + " 이 한도 " + cap.toPlainString() + " 을 넘습니다.", checks);
        }
        checks.add("주문금액 " + notional.toPlainString() + " " + currency
                + " 가 한도 " + cap.toPlainString() + " 이하");

        if (todayOrderCount >= props.dailyOrderCount()) {
            return new GuardDecision(Outcome.REJECT,
                    "오늘 주문 수가 한도 " + props.dailyOrderCount() + " 건에 도달했습니다.", checks);
        }
        checks.add("오늘 주문수 " + todayOrderCount + " 가 한도 " + props.dailyOrderCount() + " 미만");

        List<String> allowlist = props.symbolAllowlist();
        if (!allowlist.isEmpty() && !allowlist.contains(cmd.symbol())) {
            return new GuardDecision(Outcome.REJECT,
                    "종목 " + cmd.symbol() + " 은 허용목록에 없습니다.", checks);
        }
        checks.add(allowlist.isEmpty() ? "종목 허용목록 미설정(제한 없음)" : "종목이 허용목록에 있음");

        return killSwitchAndExecuteFlag(cmd.execute(), checks);
    }

    /**
     * 취소는 금액·횟수 가드레일 대상이 아니다. 한도로 취소를 막으면 원치 않는 포지션에
     * 갇히게 되어 오히려 위험하다. 킬스위치와 execute 플래그만 적용한다.
     */
    public GuardDecision evaluateCancel(boolean execute) {
        List<String> checks = new ArrayList<>();
        checks.add("취소는 금액·횟수 가드레일 대상이 아님");
        return killSwitchAndExecuteFlag(execute, checks);
    }

    private GuardDecision killSwitchAndExecuteFlag(boolean execute, List<String> checks) {
        if (!props.enabled()) {
            return new GuardDecision(Outcome.DRY_RUN,
                    "실매매가 꺼져 있습니다(toss.trading.enabled=false). 미리보기만 수행합니다.", checks);
        }
        if (!execute) {
            return new GuardDecision(Outcome.DRY_RUN,
                    "execute=true 가 아니므로 미리보기만 수행합니다.", checks);
        }
        return new GuardDecision(Outcome.ALLOW, "모든 안전게이트를 통과했습니다.", checks);
    }
}
```

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

Run: `./gradlew test --tests '*OrderGuardTest'`
Expected: PASS — 12개 테스트 전부 통과.

- [ ] **Step 6: application.yml 에 기본값을 적는다**

`src/main/resources/application.yml`의 `toss:` 블록 안, `cache:` 앞에 다음을 추가한다.
(`account:` 줄은 이미 존재하므로 새로 만들지 않는다.)

```yaml
  # 실매매 안전장치. 기본은 꺼짐이며, 켜더라도 아래 한도를 넘는 주문은 거부된다.
  trading:
    enabled: ${TOSS_TRADING_ENABLED:false}
    max-order-notional-krw: 100000
    max-order-notional-usd: 100
    daily-order-count: 20
    symbol-allowlist: []
```

- [ ] **Step 7: 전체 빌드를 확인하고 커밋한다**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

```bash
git add src/main/java/dev/jaydev/tossmcp/config/TossTradingProperties.java \
        src/main/java/dev/jaydev/tossmcp/trading/OrderCommand.java \
        src/main/java/dev/jaydev/tossmcp/trading/GuardDecision.java \
        src/main/java/dev/jaydev/tossmcp/trading/OrderGuard.java \
        src/main/resources/application.yml \
        src/test/java/dev/jaydev/tossmcp/trading/OrderGuardTest.java
git commit -m "feat(trading): add order safety guard with kill switch and hard guardrails"
```

---

### Task 2: 하루 주문 수 카운터

**Files:**
- Create: `src/main/java/dev/jaydev/tossmcp/trading/DailyOrderCounter.java`
- Modify: `src/main/java/dev/jaydev/tossmcp/TossMcpApplication.java`
- Test: `src/test/java/dev/jaydev/tossmcp/trading/DailyOrderCounterTest.java`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `DailyOrderCounter(Clock clock)` 생성자
  - `int current()` — 오늘 주문 수
  - `void increment()` — 1 증가
  - `TossMcpApplication` 의 `Clock` 빈 (`Clock.systemUTC()`)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/dev/jaydev/tossmcp/trading/DailyOrderCounterTest.java`:

```java
package dev.jaydev.tossmcp.trading;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class DailyOrderCounterTest {

    /** 테스트에서 "지금"을 임의로 옮기기 위한 시계. */
    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(String iso) {
            this.now = Instant.parse(iso);
        }

        void moveTo(String iso) {
            this.now = Instant.parse(iso);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @Test
    void countsIncrementsWithinTheSameDay() {
        DailyOrderCounter counter = new DailyOrderCounter(new MovableClock("2026-07-27T01:00:00Z"));

        counter.increment();
        counter.increment();

        assertThat(counter.current()).isEqualTo(2);
    }

    @Test
    void resetsAtKoreanMidnight() {
        // 2026-07-27T14:59Z 는 한국시간 07-27 23:59
        MovableClock clock = new MovableClock("2026-07-27T14:59:00Z");
        DailyOrderCounter counter = new DailyOrderCounter(clock);
        counter.increment();
        counter.increment();
        assertThat(counter.current()).isEqualTo(2);

        // 2026-07-27T15:01Z 는 한국시간 07-28 00:01 — 날짜가 바뀌었으므로 리셋
        clock.moveTo("2026-07-27T15:01:00Z");

        assertThat(counter.current()).isZero();
    }

    @Test
    void doesNotResetAtUtcMidnight() {
        // 2026-07-27T23:00Z 는 한국시간 07-28 08:00
        MovableClock clock = new MovableClock("2026-07-27T23:00:00Z");
        DailyOrderCounter counter = new DailyOrderCounter(clock);
        counter.increment();

        // 2026-07-28T00:30Z 는 한국시간 07-28 09:30 — 같은 한국 날짜라 유지되어야 한다
        clock.moveTo("2026-07-28T00:30:00Z");

        assertThat(counter.current()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `./gradlew test --tests '*DailyOrderCounterTest'`
Expected: FAIL — `DailyOrderCounter` 클래스가 없어 컴파일 오류.

- [ ] **Step 3: 카운터를 구현한다**

`src/main/java/dev/jaydev/tossmcp/trading/DailyOrderCounter.java`:

```java
package dev.jaydev.tossmcp.trading;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 하루에 보낸 주문 수를 센다. 한국 시장을 기준으로 하므로 한국시간 자정에 리셋한다.
 *
 * synchronized 대신 ReentrantLock 을 쓰는 이유는 이 저장소가 가상스레드 피닝 0 을
 * 테스트로 강제하기 때문이다(VirtualThreadPinningTest 참고).
 */
@Component
public class DailyOrderCounter {

    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private LocalDate day;
    private int count;

    public DailyOrderCounter(Clock clock) {
        this.clock = clock;
        this.day = today();
    }

    public int current() {
        lock.lock();
        try {
            rollOverIfNewDay();
            return count;
        } finally {
            lock.unlock();
        }
    }

    public void increment() {
        lock.lock();
        try {
            rollOverIfNewDay();
            count++;
        } finally {
            lock.unlock();
        }
    }

    private void rollOverIfNewDay() {
        LocalDate now = today();
        if (!now.equals(day)) {
            day = now;
            count = 0;
        }
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), KOREA);
    }
}
```

- [ ] **Step 4: Clock 빈을 등록한다**

`src/main/java/dev/jaydev/tossmcp/TossMcpApplication.java`에 import `java.time.Clock` 를 추가하고, `tossTools` 빈 메서드 아래에 다음을 넣는다.

```java
    /**
     * 시간에 의존하는 로직(주문 카운터·레이트리밋)이 테스트에서 시간을 고정할 수 있도록
     * 시계를 주입 가능한 빈으로 둔다.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
```

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

Run: `./gradlew test --tests '*DailyOrderCounterTest'`
Expected: PASS — 3개 테스트 통과.

- [ ] **Step 6: 커밋한다**

```bash
git add src/main/java/dev/jaydev/tossmcp/trading/DailyOrderCounter.java \
        src/main/java/dev/jaydev/tossmcp/TossMcpApplication.java \
        src/test/java/dev/jaydev/tossmcp/trading/DailyOrderCounterTest.java
git commit -m "feat(trading): count daily orders with Korean midnight rollover"
```

---

### Task 3: 주문 전용 레이트리밋

**Files:**
- Create: `src/main/java/dev/jaydev/tossmcp/trading/OrderRateLimiter.java`
- Test: `src/test/java/dev/jaydev/tossmcp/trading/OrderRateLimiterTest.java`

**Interfaces:**
- Consumes: `TossMcpApplication` 의 `Clock` 빈 (Task 2에서 추가됨)
- Produces: `OrderRateLimiter(Clock clock)`, `boolean tryAcquire()`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/dev/jaydev/tossmcp/trading/OrderRateLimiterTest.java`:

```java
package dev.jaydev.tossmcp.trading;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class OrderRateLimiterTest {

    /** 테스트에서 "지금"을 임의로 옮기기 위한 시계. */
    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(String iso) {
            this.now = Instant.parse(iso);
        }

        void moveTo(String iso) {
            this.now = Instant.parse(iso);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @Test
    void allowsSixPerSecondOutsideTheOpeningRush() {
        // 2026-07-27T05:00Z 는 한국시간 14:00 — 혼잡 구간이 아니다
        OrderRateLimiter limiter = new OrderRateLimiter(new MovableClock("2026-07-27T05:00:00Z"));

        for (int i = 0; i < 6; i++) {
            assertThat(limiter.tryAcquire()).as("%d번째 호출", i + 1).isTrue();
        }
        assertThat(limiter.tryAcquire()).as("7번째 호출은 한도 초과").isFalse();
    }

    @Test
    void allowsOnlyThreePerSecondDuringTheOpeningRush() {
        // 2026-07-27T00:05Z 는 한국시간 09:05 — 개장 직후 혼잡 구간
        OrderRateLimiter limiter = new OrderRateLimiter(new MovableClock("2026-07-27T00:05:00Z"));

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire()).as("%d번째 호출", i + 1).isTrue();
        }
        assertThat(limiter.tryAcquire()).as("4번째 호출은 한도 초과").isFalse();
    }

    @Test
    void windowSlidesAfterOneSecond() {
        MovableClock clock = new MovableClock("2026-07-27T05:00:00Z");
        OrderRateLimiter limiter = new OrderRateLimiter(clock);
        for (int i = 0; i < 6; i++) {
            limiter.tryAcquire();
        }
        assertThat(limiter.tryAcquire()).isFalse();

        clock.moveTo("2026-07-27T05:00:01.500Z");

        assertThat(limiter.tryAcquire()).as("1초가 지나면 다시 통과").isTrue();
    }

    @Test
    void rushWindowEndsAtNineTen() {
        // 2026-07-27T00:10Z 는 한국시간 09:10 — 혼잡 구간의 끝(포함되지 않음)
        OrderRateLimiter limiter = new OrderRateLimiter(new MovableClock("2026-07-27T00:10:00Z"));

        for (int i = 0; i < 6; i++) {
            assertThat(limiter.tryAcquire()).as("%d번째 호출", i + 1).isTrue();
        }
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `./gradlew test --tests '*OrderRateLimiterTest'`
Expected: FAIL — `OrderRateLimiter` 클래스가 없어 컴파일 오류.

- [ ] **Step 3: 리미터를 구현한다**

`src/main/java/dev/jaydev/tossmcp/trading/OrderRateLimiter.java`:

```java
package dev.jaydev.tossmcp.trading;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 주문 API 전용 초당 요청 한도. 토스 규정은 초당 6건이며, 개장 직후 09:00~09:10 구간만
 * 초당 3건으로 줄어든다.
 *
 * 한도를 넘으면 기다리지 않고 즉시 거절한다. 도구 호출 안에서 잠들면 호출자가 영문도 모른 채
 * 묶이기 때문에, 곧바로 거절하고 다시 시도하게 하는 편이 낫다.
 */
@Component
public class OrderRateLimiter {

    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final LocalTime RUSH_START = LocalTime.of(9, 0);
    private static final LocalTime RUSH_END = LocalTime.of(9, 10);
    private static final int LIMIT_NORMAL = 6;
    private static final int LIMIT_RUSH = 3;

    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<Instant> recent = new ArrayDeque<>();

    public OrderRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** 한도 안이면 호출을 기록하고 true, 초당 한도를 넘으면 false 를 돌려준다. */
    public boolean tryAcquire() {
        lock.lock();
        try {
            Instant now = clock.instant();
            Instant oneSecondAgo = now.minusSeconds(1);
            while (!recent.isEmpty() && !recent.peekFirst().isAfter(oneSecondAgo)) {
                recent.pollFirst();
            }
            if (recent.size() >= limitAt(now)) {
                return false;
            }
            recent.addLast(now);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** 개장 직후 혼잡 구간에는 한도가 절반으로 줄어든다. */
    private int limitAt(Instant now) {
        LocalTime time = LocalTime.ofInstant(now, KOREA);
        boolean openingRush = !time.isBefore(RUSH_START) && time.isBefore(RUSH_END);
        return openingRush ? LIMIT_RUSH : LIMIT_NORMAL;
    }
}
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `./gradlew test --tests '*OrderRateLimiterTest'`
Expected: PASS — 4개 테스트 통과.

- [ ] **Step 5: 커밋한다**

```bash
git add src/main/java/dev/jaydev/tossmcp/trading/OrderRateLimiter.java \
        src/test/java/dev/jaydev/tossmcp/trading/OrderRateLimiterTest.java
git commit -m "feat(trading): throttle order calls with market-open aware limits"
```

---

### Task 4: 토스 주문 API 클라이언트

**Files:**
- Modify: `src/main/java/dev/jaydev/tossmcp/client/TossApiClient.java`
- Modify: `build.gradle`
- Test: `src/test/java/dev/jaydev/tossmcp/client/TossApiClientOrderTest.java`

**Interfaces:**
- Consumes: 기존 `TossProperties(String baseUrl, String clientId, String clientSecret, String account)`, `TossAuthService.accessToken()`
- Produces:
  - `String placeOrder(Map<String, Object> body)`
  - `String cancelOrder(String orderId)`
  - `String getOrders(String status, String symbol, Integer limit)`
  - `String getHoldings(String symbol)`
  - `String getBuyingPower(String currency)`

- [ ] **Step 1: 테스트 의존성을 추가한다**

`build.gradle`의 `dependencies` 블록에서 `testImplementation 'org.springframework.boot:spring-boot-starter-test'` 아래에 추가한다.

```gradle
    // 주문 경로는 계좌 헤더가 실제로 실려 나가는지까지 확인해야 해서 HTTP 수준으로 검증한다.
    testImplementation 'com.squareup.okhttp3:mockwebserver:4.12.0'
```

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`src/test/java/dev/jaydev/tossmcp/client/TossApiClientOrderTest.java`:

```java
package dev.jaydev.tossmcp.client;

import dev.jaydev.tossmcp.auth.TossAuthService;
import dev.jaydev.tossmcp.config.TossProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TossApiClientOrderTest {

    private MockWebServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() throws Exception {
        server.shutdown();
    }

    private TossApiClient clientWithAccount(String account) {
        TossAuthService auth = mock(TossAuthService.class);
        when(auth.accessToken()).thenReturn("test-token");
        TossProperties props = new TossProperties(
                server.url("/").toString(), "test-id", "test-secret", account);
        return new TossApiClient(props, auth);
    }

    private void enqueueJson(String body) {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(body));
    }

    @Test
    void placeOrderPostsBodyWithAccountHeader() throws Exception {
        enqueueJson("{\"result\":{\"orderId\":\"order-1\"}}");

        String response = clientWithAccount("42").placeOrder(Map.of(
                "symbol", "005930",
                "side", "BUY",
                "orderType", "MARKET",
                "quantity", "1"));

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/api/v1/orders");
        assertThat(request.getHeader("X-Tossinvest-Account")).isEqualTo("42");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-token");
        assertThat(request.getBody().readUtf8()).contains("\"symbol\":\"005930\"");
        assertThat(response).contains("order-1");
    }

    @Test
    void cancelOrderPostsToCancelSubPath() throws Exception {
        enqueueJson("{\"result\":{\"orderId\":\"cancel-1\"}}");

        clientWithAccount("42").cancelOrder("abc-123");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/api/v1/orders/abc-123/cancel");
        assertThat(request.getHeader("X-Tossinvest-Account")).isEqualTo("42");
    }

    @Test
    void getOrdersSendsRequiredStatusQuery() throws Exception {
        enqueueJson("{\"result\":{\"items\":[]}}");

        clientWithAccount("42").getOrders("OPEN", null, null);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getPath()).isEqualTo("/api/v1/orders?status=OPEN");
    }

    @Test
    void getHoldingsAndBuyingPowerUseTheirOwnPaths() throws Exception {
        enqueueJson("{\"result\":{}}");
        clientWithAccount("42").getHoldings(null);
        assertThat(server.takeRequest().getPath()).isEqualTo("/api/v1/holdings");

        enqueueJson("{\"result\":{}}");
        clientWithAccount("42").getBuyingPower("KRW");
        assertThat(server.takeRequest().getPath()).isEqualTo("/api/v1/buying-power?currency=KRW");
    }

    @Test
    void missingAccountFailsWithClearMessage() {
        assertThatThrownBy(() -> clientWithAccount("").getHoldings(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TOSS_ACCOUNT");
    }
}
```

- [ ] **Step 3: 테스트가 실패하는지 확인한다**

Run: `./gradlew test --tests '*TossApiClientOrderTest'`
Expected: FAIL — `placeOrder` 등 메서드가 없어 컴파일 오류.

- [ ] **Step 4: 클라이언트에 주문 메서드를 추가한다**

`src/main/java/dev/jaydev/tossmcp/client/TossApiClient.java`를 수정한다.

import 에 다음을 추가:

```java
import org.springframework.http.MediaType;

import java.util.Map;
```

클래스 상단 필드와 생성자를 다음으로 바꾼다(계좌를 보관한다):

```java
    /** 계좌에 영향을 주는 요청에 반드시 실어야 하는 헤더. */
    private static final String ACCOUNT_HEADER = "X-Tossinvest-Account";

    private final RestClient http;
    private final TossAuthService auth;
    private final String account;

    public TossApiClient(TossProperties props, TossAuthService auth) {
        this.http = RestClient.create(props.baseUrl());
        this.auth = auth;
        this.account = props.account();
    }
```

기존 `private String get(...)` 메서드 아래에 다음을 추가한다:

```java
    /** POST /api/v1/orders — 주문 생성. 재시도하지 않는다(이중 주문 방지). */
    public String placeOrder(Map<String, Object> body) {
        return http.post()
                .uri("/api/v1/orders")
                .header("Authorization", "Bearer " + auth.accessToken())
                .header(ACCOUNT_HEADER, requireAccount())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
    }

    /**
     * POST /api/v1/orders/{orderId}/cancel — 주문 취소.
     * 응답의 orderId 는 취소로 새로 발급된 식별자라 원주문 번호와 다르다.
     */
    public String cancelOrder(String orderId) {
        return http.post()
                .uri(uriBuilder -> uriBuilder.path("/api/v1/orders/{orderId}/cancel").build(orderId))
                .header("Authorization", "Bearer " + auth.accessToken())
                .header(ACCOUNT_HEADER, requireAccount())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of())
                .retrieve()
                .body(String.class);
    }

    /** GET /api/v1/orders — 주문 목록. status 는 OPEN 또는 CLOSED 이며 필수다. */
    public String getOrders(String status, String symbol, Integer limit) {
        return accountGet("/api/v1/orders", uri -> {
            uri.queryParam("status", status);
            if (symbol != null) uri.queryParam("symbol", symbol);
            if (limit != null) uri.queryParam("limit", limit);
        });
    }

    /** GET /api/v1/holdings — 보유 주식. symbol 을 주면 해당 종목만. */
    public String getHoldings(String symbol) {
        return accountGet("/api/v1/holdings", uri -> {
            if (symbol != null) uri.queryParam("symbol", symbol);
        });
    }

    /** GET /api/v1/buying-power — 매수 가능 금액. currency 는 필수다. */
    public String getBuyingPower(String currency) {
        return accountGet("/api/v1/buying-power", uri -> uri.queryParam("currency", currency));
    }

    /** 계좌 헤더가 필요한 GET 공통 처리. */
    private String accountGet(String path, Consumer<UriBuilder> query) {
        return http.get()
                .uri(uriBuilder -> {
                    uriBuilder.path(path);
                    query.accept(uriBuilder);
                    return uriBuilder.build();
                })
                .header("Authorization", "Bearer " + auth.accessToken())
                .header(ACCOUNT_HEADER, requireAccount())
                .retrieve()
                .body(String.class);
    }

    /** 계좌가 없으면 조용히 실패하지 않고 무엇을 설정해야 하는지 알려준다. */
    private String requireAccount() {
        if (account == null || account.isBlank()) {
            throw new IllegalStateException(
                    "계좌가 설정되지 않았습니다. 환경변수 TOSS_ACCOUNT 에 계좌 일련번호를 넣어주세요.");
        }
        return account;
    }
```

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

Run: `./gradlew test --tests '*TossApiClientOrderTest'`
Expected: PASS — 5개 테스트 통과.

- [ ] **Step 6: 커밋한다**

```bash
git add build.gradle \
        src/main/java/dev/jaydev/tossmcp/client/TossApiClient.java \
        src/test/java/dev/jaydev/tossmcp/client/TossApiClientOrderTest.java
git commit -m "feat(client): add order endpoints with required account header"
```

---

### Task 5: 주문 오케스트레이션 서비스

**Files:**
- Create: `src/main/java/dev/jaydev/tossmcp/trading/OrderResult.java`
- Create: `src/main/java/dev/jaydev/tossmcp/service/TradingService.java`
- Test: `src/test/java/dev/jaydev/tossmcp/service/TradingServiceTest.java`

**Interfaces:**
- Consumes: `OrderGuard.evaluatePlace/evaluateCancel`, `DailyOrderCounter.current/increment`, `OrderRateLimiter.tryAcquire`, `TossApiClient.placeOrder/cancelOrder/getOrders/getHoldings/getBuyingPower`, `MarketDataService.prices(String)`
- Produces:
  - `OrderResult(Status status, String reason, OrderPreview wouldPlace, List<String> guardrail, String orderId, String clientOrderId)`, `Status{DRY_RUN,PLACED,REJECTED,UNKNOWN}`, `OrderPreview(String symbol, String side, String orderType, String quantity, String price, String estimatedNotional, String currency)`
  - `TradingService.placeOrder(String symbol, String side, String quantity, String orderType, String price, Boolean execute) -> OrderResult`
  - `TradingService.cancelOrder(String orderId, Boolean execute) -> OrderResult`
  - `TradingService.openOrders(String symbol, Integer limit) -> String`
  - `TradingService.holdings(String symbol) -> String`
  - `TradingService.buyingPower(String currency) -> String`

- [ ] **Step 1: 응답 타입을 만든다**

`src/main/java/dev/jaydev/tossmcp/trading/OrderResult.java`:

```java
package dev.jaydev.tossmcp.trading;

import java.util.List;

/**
 * 주문 도구의 응답. 무엇이 일어날 예정인지(DRY_RUN) 또는 일어났는지(PLACED)가 항상 드러나야 한다.
 *
 * clientOrderId 는 토스의 멱등성 키다. UNKNOWN 인 경우 같은 값으로 10분 안에 다시 시도하면
 * 중복 주문 없이 같은 결과를 받는다.
 */
public record OrderResult(
        Status status,
        String reason,
        OrderPreview wouldPlace,
        List<String> guardrail,
        String orderId,
        String clientOrderId
) {
    public enum Status { DRY_RUN, PLACED, REJECTED, UNKNOWN }

    public record OrderPreview(
            String symbol,
            String side,
            String orderType,
            String quantity,
            String price,
            String estimatedNotional,
            String currency
    ) {}
}
```

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`src/test/java/dev/jaydev/tossmcp/service/TradingServiceTest.java`:

```java
package dev.jaydev.tossmcp.service;

import dev.jaydev.tossmcp.client.TossApiClient;
import dev.jaydev.tossmcp.config.TossTradingProperties;
import dev.jaydev.tossmcp.trading.DailyOrderCounter;
import dev.jaydev.tossmcp.trading.OrderGuard;
import dev.jaydev.tossmcp.trading.OrderRateLimiter;
import dev.jaydev.tossmcp.trading.OrderResult;
import dev.jaydev.tossmcp.trading.OrderResult.Status;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradingServiceTest {

    // 한국시간 14:00 — 개장 직후 혼잡 구간이 아니라 레이트리밋 여유가 있다.
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-07-27T05:00:00Z"), ZoneOffset.UTC);

    private final TossApiClient api = mock(TossApiClient.class);
    private final MarketDataService market = mock(MarketDataService.class);

    private TradingService service(boolean tradingEnabled) {
        TossTradingProperties props = new TossTradingProperties(
                tradingEnabled, new BigDecimal("100000"), new BigDecimal("100"), 20, List.of());
        return new TradingService(api, market, new OrderGuard(props),
                new DailyOrderCounter(FIXED), new OrderRateLimiter(FIXED));
    }

    @Test
    void disabledTradingNeverSendsTheOrder() {
        OrderResult result = service(false)
                .placeOrder("005930", "BUY", "1", "LIMIT", "50000", true);

        assertThat(result.status()).isEqualTo(Status.DRY_RUN);
        verify(api, never()).placeOrder(any());
    }

    @Test
    void enabledWithoutExecuteIsPreviewOnly() {
        OrderResult result = service(true)
                .placeOrder("005930", "BUY", "1", "LIMIT", "50000", null);

        assertThat(result.status()).isEqualTo(Status.DRY_RUN);
        assertThat(result.wouldPlace().estimatedNotional()).isEqualTo("50000");
        verify(api, never()).placeOrder(any());
    }

    @Test
    void enabledWithExecuteSendsIdempotencyKeyAndNoHighValueConfirm() {
        when(api.placeOrder(any())).thenReturn("{\"result\":{\"orderId\":\"order-1\"}}");

        OrderResult result = service(true)
                .placeOrder("005930", "BUY", "1", "LIMIT", "50000", true);

        assertThat(result.status()).isEqualTo(Status.PLACED);
        assertThat(result.orderId()).isEqualTo("order-1");
        assertThat(result.clientOrderId()).isNotBlank();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(api).placeOrder(body.capture());
        assertThat(body.getValue()).containsEntry("symbol", "005930");
        assertThat(body.getValue()).containsEntry("side", "BUY");
        assertThat(body.getValue()).containsEntry("orderType", "LIMIT");
        assertThat(body.getValue()).containsEntry("price", "50000");
        assertThat(body.getValue()).containsKey("clientOrderId");
        // 1억원 이상 주문에 대한 토스 자체 방어막을 우리가 켜면 안 된다.
        assertThat(body.getValue()).doesNotContainKey("confirmHighValueOrder");
    }

    @Test
    void marketOrderEstimatesNotionalFromLastPrice() {
        when(market.prices("005930")).thenReturn("{\"result\":[{\"lastPrice\":\"60000\"}]}");

        OrderResult result = service(true)
                .placeOrder("005930", "BUY", "1", "MARKET", null, false);

        assertThat(result.status()).isEqualTo(Status.DRY_RUN);
        assertThat(result.wouldPlace().estimatedNotional()).isEqualTo("60000");
    }

    @Test
    void marketOrderIsRejectedWhenPriceLookupFails() {
        when(market.prices("005930")).thenThrow(new RuntimeException("upstream down"));

        OrderResult result = service(true)
                .placeOrder("005930", "BUY", "1", "MARKET", null, true);

        assertThat(result.status()).isEqualTo(Status.REJECTED);
        verify(api, never()).placeOrder(any());
    }

    @Test
    void limitOrderWithoutPriceIsRejected() {
        OrderResult result = service(true)
                .placeOrder("005930", "BUY", "1", "LIMIT", null, true);

        assertThat(result.status()).isEqualTo(Status.REJECTED);
        assertThat(result.reason()).contains("price");
        verify(api, never()).placeOrder(any());
    }

    @Test
    void marketOrderWithPriceIsRejected() {
        OrderResult result = service(true)
                .placeOrder("005930", "BUY", "1", "MARKET", "50000", true);

        assertThat(result.status()).isEqualTo(Status.REJECTED);
        verify(api, never()).placeOrder(any());
    }

    @Test
    void timeoutReturnsUnknownAndDoesNotRetry() {
        when(api.placeOrder(any()))
                .thenThrow(new ResourceAccessException("timeout", new SocketTimeoutException()));

        OrderResult result = service(true)
                .placeOrder("005930", "BUY", "1", "LIMIT", "50000", true);

        assertThat(result.status()).isEqualTo(Status.UNKNOWN);
        assertThat(result.clientOrderId()).isNotBlank();
        assertThat(result.reason()).contains("clientOrderId");
        verify(api, times(1)).placeOrder(any());
    }

    @Test
    void cancelIsPreviewedWhenTradingDisabled() {
        OrderResult result = service(false).cancelOrder("abc-123", true);

        assertThat(result.status()).isEqualTo(Status.DRY_RUN);
        verify(api, never()).cancelOrder(anyString());
    }

    @Test
    void cancelIsSentWhenEnabledAndExecuted() {
        when(api.cancelOrder("abc-123")).thenReturn("{\"result\":{\"orderId\":\"cancel-1\"}}");

        OrderResult result = service(true).cancelOrder("abc-123", true);

        assertThat(result.status()).isEqualTo(Status.PLACED);
        verify(api).cancelOrder("abc-123");
    }

    @Test
    void accountStateReadsAreNotCached() {
        // 주문 직후 상태가 바뀌므로 매 호출이 그대로 상위로 나가야 한다.
        when(api.getHoldings(null)).thenReturn("{}");
        TradingService service = service(true);

        service.holdings(null);
        service.holdings(null);

        verify(api, times(2)).getHoldings(null);
    }

    @Test
    void openOrdersAsksForOpenStatus() {
        when(api.getOrders("OPEN", null, null)).thenReturn("{}");

        service(true).openOrders(null, null);

        verify(api).getOrders("OPEN", null, null);
    }
}
```

- [ ] **Step 3: 테스트가 실패하는지 확인한다**

Run: `./gradlew test --tests '*TradingServiceTest'`
Expected: FAIL — `TradingService` 클래스가 없어 컴파일 오류.

- [ ] **Step 4: 서비스를 구현한다**

`src/main/java/dev/jaydev/tossmcp/service/TradingService.java`:

```java
package dev.jaydev.tossmcp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jaydev.tossmcp.client.TossApiClient;
import dev.jaydev.tossmcp.trading.DailyOrderCounter;
import dev.jaydev.tossmcp.trading.GuardDecision;
import dev.jaydev.tossmcp.trading.OrderCommand;
import dev.jaydev.tossmcp.trading.OrderCommand.OrderType;
import dev.jaydev.tossmcp.trading.OrderCommand.Side;
import dev.jaydev.tossmcp.trading.OrderGuard;
import dev.jaydev.tossmcp.trading.OrderRateLimiter;
import dev.jaydev.tossmcp.trading.OrderResult;
import dev.jaydev.tossmcp.trading.OrderResult.OrderPreview;
import dev.jaydev.tossmcp.trading.OrderResult.Status;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 주문 도구의 오케스트레이션. 입력 검증 → 금액 추정 → 안전게이트 → 전송 → 응답 정규화.
 *
 * 전략 판단은 하지 않는다. 무엇을 살지 팔지는 이 서버를 쓰는 두뇌가 정한다.
 *
 * 쓰기 경로는 캐시와 요청병합을 타지 않는다. 서로 다른 두 주문이 하나로 병합되면 재앙이다.
 * 계좌 상태 조회도 캐시하지 않는다 — 방금 낸 주문이 보이지 않으면 두뇌가 중복 주문을 낸다.
 */
@Service
public class TradingService {

    /** 국내 종목은 6자리 숫자, 그 밖은 미국 티커로 본다. */
    private static final Pattern KOREAN_SYMBOL = Pattern.compile("\\d{6}");

    private final TossApiClient api;
    private final MarketDataService market;
    private final OrderGuard guard;
    private final DailyOrderCounter counter;
    private final OrderRateLimiter limiter;
    private final ObjectMapper json = new ObjectMapper();

    public TradingService(TossApiClient api, MarketDataService market, OrderGuard guard,
                          DailyOrderCounter counter, OrderRateLimiter limiter) {
        this.api = api;
        this.market = market;
        this.guard = guard;
        this.counter = counter;
        this.limiter = limiter;
    }

    public OrderResult placeOrder(String symbol, String side, String quantity,
                                  String orderType, String price, Boolean execute) {
        OrderCommand cmd;
        try {
            cmd = validate(symbol, side, quantity, orderType, price, execute);
        } catch (IllegalArgumentException e) {
            return new OrderResult(Status.REJECTED, e.getMessage(), null, List.of(), null, null);
        }

        String currency = KOREAN_SYMBOL.matcher(cmd.symbol()).matches() ? "KRW" : "USD";
        BigDecimal notional = estimateNotional(cmd);
        OrderPreview preview = new OrderPreview(
                cmd.symbol(),
                cmd.side().name(),
                cmd.orderType().name(),
                cmd.quantity().toPlainString(),
                cmd.price() == null ? null : cmd.price().toPlainString(),
                notional == null ? null : notional.toPlainString(),
                currency);

        GuardDecision decision = guard.evaluatePlace(cmd, notional, currency, counter.current());
        switch (decision.outcome()) {
            case REJECT -> {
                return new OrderResult(Status.REJECTED, decision.reason(), preview, decision.checks(), null, null);
            }
            case DRY_RUN -> {
                return new OrderResult(Status.DRY_RUN, decision.reason(), preview, decision.checks(), null, null);
            }
            case ALLOW -> { /* 아래로 진행 */ }
        }

        if (!limiter.tryAcquire()) {
            return new OrderResult(Status.REJECTED,
                    "주문 요청이 너무 잦습니다. 잠시 후 다시 시도하세요.", preview, decision.checks(), null, null);
        }

        String clientOrderId = "mcp-" + UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientOrderId", clientOrderId);
        body.put("symbol", cmd.symbol());
        body.put("side", cmd.side().name());
        body.put("orderType", cmd.orderType().name());
        body.put("quantity", cmd.quantity().toPlainString());
        if (cmd.orderType() == OrderType.LIMIT) {
            body.put("price", cmd.price().toPlainString());
        }
        // confirmHighValueOrder 는 일부러 넣지 않는다. 1억원 이상 주문을 토스가 막아주는
        // 장치인데 우리가 자동으로 켜면 그 보호가 사라진다.

        // 한도는 보수적으로 센다: 보냈으면 셌다고 본다(응답을 못 받아도 접수됐을 수 있다).
        counter.increment();
        try {
            String raw = api.placeOrder(body);
            return new OrderResult(Status.PLACED, "주문이 접수되었습니다.", preview, decision.checks(),
                    extractOrderId(raw), clientOrderId);
        } catch (RestClientResponseException e) {
            return new OrderResult(Status.REJECTED, describeApiError(e), preview, decision.checks(),
                    null, clientOrderId);
        } catch (ResourceAccessException e) {
            return new OrderResult(Status.UNKNOWN,
                    "응답을 받지 못해 접수 여부를 알 수 없습니다. getOpenOrders 로 확인하거나, "
                            + "같은 clientOrderId(" + clientOrderId + ")로 10분 안에 다시 시도하면 "
                            + "중복 주문 없이 같은 결과를 받습니다.",
                    preview, decision.checks(), null, clientOrderId);
        }
    }

    public OrderResult cancelOrder(String orderId, Boolean execute) {
        if (orderId == null || orderId.isBlank()) {
            return new OrderResult(Status.REJECTED, "orderId 가 필요합니다.", null, List.of(), null, null);
        }

        GuardDecision decision = guard.evaluateCancel(Boolean.TRUE.equals(execute));
        if (decision.outcome() != GuardDecision.Outcome.ALLOW) {
            return new OrderResult(Status.DRY_RUN,
                    decision.reason() + " 취소 대상 주문: " + orderId, null, decision.checks(), orderId, null);
        }

        if (!limiter.tryAcquire()) {
            return new OrderResult(Status.REJECTED,
                    "주문 요청이 너무 잦습니다. 잠시 후 다시 시도하세요.", null, decision.checks(), orderId, null);
        }

        try {
            String raw = api.cancelOrder(orderId);
            return new OrderResult(Status.PLACED, "취소가 접수되었습니다.", null, decision.checks(),
                    extractOrderId(raw), null);
        } catch (RestClientResponseException e) {
            return new OrderResult(Status.REJECTED, describeApiError(e), null, decision.checks(), orderId, null);
        } catch (ResourceAccessException e) {
            return new OrderResult(Status.UNKNOWN,
                    "응답을 받지 못해 취소 접수 여부를 알 수 없습니다. getOpenOrders 로 확인하세요.",
                    null, decision.checks(), orderId, null);
        }
    }

    /** 계좌 상태 조회는 캐시하지 않는다. 주문 직후 값이 바뀌기 때문이다. */
    public String openOrders(String symbol, Integer limit) {
        return api.getOrders("OPEN", symbol, limit);
    }

    public String holdings(String symbol) {
        return api.getHoldings(symbol);
    }

    public String buyingPower(String currency) {
        return api.getBuyingPower(currency);
    }

    private OrderCommand validate(String symbol, String side, String quantity,
                                  String orderType, String price, Boolean execute) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol 이 필요합니다.");
        }
        Side parsedSide = parseEnum(Side.class, side, "side 는 BUY 또는 SELL 이어야 합니다.");
        OrderType parsedType = parseEnum(OrderType.class, orderType,
                "orderType 은 LIMIT 또는 MARKET 이어야 합니다.");

        BigDecimal parsedQuantity = parseDecimal(quantity, "quantity");
        if (parsedQuantity.signum() <= 0) {
            throw new IllegalArgumentException("quantity 는 0보다 커야 합니다.");
        }

        BigDecimal parsedPrice = null;
        if (parsedType == OrderType.LIMIT) {
            if (price == null || price.isBlank()) {
                throw new IllegalArgumentException("지정가(LIMIT) 주문에는 price 가 필요합니다.");
            }
            parsedPrice = parseDecimal(price, "price");
            if (parsedPrice.signum() <= 0) {
                throw new IllegalArgumentException("price 는 0보다 커야 합니다.");
            }
        } else if (price != null && !price.isBlank()) {
            throw new IllegalArgumentException(
                    "시장가(MARKET) 주문에는 price 를 넣을 수 없습니다. 토스가 거부합니다.");
        }

        return new OrderCommand(symbol.trim(), parsedSide, parsedQuantity, parsedType,
                parsedPrice, Boolean.TRUE.equals(execute));
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String message) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(message);
        }
    }

    private static BigDecimal parseDecimal(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(field + " 가 필요합니다.");
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " 는 숫자여야 합니다: " + raw);
        }
    }

    /**
     * 추정 주문금액. 지정가는 가격이 확정돼 있고, 시장가는 최근 체결가로 추정한다.
     * 추정에 실패하면 null 을 돌려주고, 안전게이트가 이를 거부한다(모르면 막는다).
     */
    private BigDecimal estimateNotional(OrderCommand cmd) {
        if (cmd.orderType() == OrderType.LIMIT) {
            return cmd.price().multiply(cmd.quantity());
        }
        try {
            JsonNode lastPrice = json.readTree(market.prices(cmd.symbol()))
                    .path("result").path(0).path("lastPrice");
            if (lastPrice.isMissingNode() || lastPrice.asText().isBlank()) {
                return null;
            }
            return new BigDecimal(lastPrice.asText()).multiply(cmd.quantity());
        } catch (Exception e) {
            return null;
        }
    }

    private String extractOrderId(String rawJson) {
        try {
            JsonNode id = json.readTree(rawJson).path("result").path("orderId");
            return id.isMissingNode() ? null : id.asText();
        } catch (Exception e) {
            return null;
        }
    }

    /** 토스의 에러 봉투를 사람이 읽을 수 있는 사유로 바꾼다. */
    private String describeApiError(RestClientResponseException e) {
        try {
            JsonNode error = json.readTree(e.getResponseBodyAsString()).path("error");
            String code = error.path("code").asText("");
            String message = error.path("message").asText("");
            if (!code.isBlank() || !message.isBlank()) {
                return "토스가 주문을 거부했습니다: " + message + " (" + code + ")";
            }
        } catch (Exception ignored) {
            // 봉투를 못 읽으면 상태 코드만 알린다.
        }
        return "토스가 주문을 거부했습니다: HTTP " + e.getStatusCode().value();
    }
}
```

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

Run: `./gradlew test --tests '*TradingServiceTest'`
Expected: PASS — 12개 테스트 통과.

- [ ] **Step 6: 커밋한다**

```bash
git add src/main/java/dev/jaydev/tossmcp/trading/OrderResult.java \
        src/main/java/dev/jaydev/tossmcp/service/TradingService.java \
        src/test/java/dev/jaydev/tossmcp/service/TradingServiceTest.java
git commit -m "feat(trading): orchestrate orders with idempotency key and no write-path retry"
```

---

### Task 6: MCP 도구로 노출

**Files:**
- Create: `src/main/java/dev/jaydev/tossmcp/tools/TradingTools.java`
- Modify: `src/main/java/dev/jaydev/tossmcp/TossMcpApplication.java`
- Test: `src/test/java/dev/jaydev/tossmcp/tools/TradingToolsTest.java`

**Interfaces:**
- Consumes: `TradingService` 의 다섯 메서드 (Task 5)
- Produces: MCP 도구 `placeOrder`, `cancelOrder`, `getOpenOrders`, `getHoldings`, `getBuyingPower`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/dev/jaydev/tossmcp/tools/TradingToolsTest.java`:

```java
package dev.jaydev.tossmcp.tools;

import dev.jaydev.tossmcp.service.TradingService;
import dev.jaydev.tossmcp.trading.OrderResult;
import dev.jaydev.tossmcp.trading.OrderResult.Status;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradingToolsTest {

    private final TradingService trading = mock(TradingService.class);
    private final TradingTools tools = new TradingTools(trading);

    @Test
    void allFiveToolsAreRegistered() {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build()
                .getToolCallbacks();

        assertThat(callbacks).extracting(callback -> callback.getToolDefinition().name())
                .containsExactlyInAnyOrder(
                        "placeOrder", "cancelOrder", "getOpenOrders", "getHoldings", "getBuyingPower");
    }

    @Test
    void placeOrderSerializesTheResultAsJson() {
        when(trading.placeOrder(any(), any(), any(), any(), any(), any()))
                .thenReturn(new OrderResult(Status.DRY_RUN, "미리보기입니다.", null, List.of("검사 통과"), null, null));

        String json = tools.placeOrder("005930", "BUY", "1", "LIMIT", "50000", null);

        assertThat(json).contains("\"status\":\"DRY_RUN\"");
        assertThat(json).contains("미리보기입니다.");
    }

    @Test
    void readToolsDelegateStraightToTheService() {
        when(trading.holdings(isNull())).thenReturn("{\"result\":{}}");
        when(trading.buyingPower(eq("KRW"))).thenReturn("{\"result\":{}}");

        tools.getHoldings(null);
        tools.getBuyingPower("KRW");

        verify(trading).holdings(null);
        verify(trading).buyingPower("KRW");
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `./gradlew test --tests '*TradingToolsTest'`
Expected: FAIL — `TradingTools` 클래스가 없어 컴파일 오류.

- [ ] **Step 3: 도구를 구현한다**

`src/main/java/dev/jaydev/tossmcp/tools/TradingTools.java`:

```java
package dev.jaydev.tossmcp.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jaydev.tossmcp.service.TradingService;
import dev.jaydev.tossmcp.trading.OrderResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 주문·계좌 MCP 도구. 판단은 담지 않고 TradingService 에 위임한다.
 *
 * 주문 도구는 기본이 미리보기다. 실제로 전송하려면 (1) 서버 설정에서 실매매가 켜져 있고
 * (2) execute=true 를 넘겨야 하며 (3) 설정된 금액·횟수 한도를 넘지 않아야 한다.
 */
@Component
public class TradingTools {

    private final TradingService trading;
    private final ObjectMapper json = new ObjectMapper();

    public TradingTools(TradingService trading) {
        this.trading = trading;
    }

    @Tool(description = "주식 주문을 낸다. 기본은 미리보기(dry-run)이며, 실제로 전송하려면 execute=true 가 필요하고 서버에서 실매매가 켜져 있어야 한다. 응답의 status 로 DRY_RUN·PLACED·REJECTED·UNKNOWN 을 구분한다.")
    public String placeOrder(
            @ToolParam(description = "종목 코드. 국내는 6자리 숫자(예: 005930), 미국은 티커(예: AAPL)") String symbol,
            @ToolParam(description = "매매 방향: BUY(매수) 또는 SELL(매도)") String side,
            @ToolParam(description = "주문 수량") String quantity,
            @ToolParam(description = "호가 유형: LIMIT(지정가) 또는 MARKET(시장가)") String orderType,
            @ToolParam(description = "주문 가격. LIMIT 일 때만 넣는다", required = false) String price,
            @ToolParam(description = "true 여야 실제로 전송한다. 기본은 false(미리보기)", required = false) Boolean execute) {
        return toJson(trading.placeOrder(symbol, side, quantity, orderType, price, execute));
    }

    @Tool(description = "접수된 주문을 취소한다. 기본은 미리보기이며 실제로 취소하려면 execute=true 가 필요하다.")
    public String cancelOrder(
            @ToolParam(description = "취소할 주문의 식별자") String orderId,
            @ToolParam(description = "true 여야 실제로 취소한다. 기본은 false(미리보기)", required = false) Boolean execute) {
        return toJson(trading.cancelOrder(orderId, execute));
    }

    @Tool(description = "아직 체결되지 않은 주문 목록을 조회한다.")
    public String getOpenOrders(
            @ToolParam(description = "특정 종목만 볼 때의 종목 코드", required = false) String symbol,
            @ToolParam(description = "조회 건수(최대 100, 기본 20)", required = false) Integer limit) {
        return trading.openOrders(symbol, limit);
    }

    @Tool(description = "계좌의 보유 주식과 평가 손익을 조회한다.")
    public String getHoldings(
            @ToolParam(description = "특정 종목만 볼 때의 종목 코드", required = false) String symbol) {
        return trading.holdings(symbol);
    }

    @Tool(description = "매수에 쓸 수 있는 금액을 조회한다. 통화별로 따로 관리된다.")
    public String getBuyingPower(
            @ToolParam(description = "통화: KRW 또는 USD") String currency) {
        return trading.buyingPower(currency);
    }

    private String toJson(OrderResult result) {
        try {
            return json.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            // 응답을 직렬화하지 못하면 무슨 일이 있었는지라도 알려야 한다.
            return "{\"status\":\"UNKNOWN\",\"reason\":\"응답을 직렬화하지 못했습니다.\"}";
        }
    }
}
```

- [ ] **Step 4: 도구를 등록한다**

`src/main/java/dev/jaydev/tossmcp/TossMcpApplication.java`의 `tossTools` 빈을 다음으로 바꾼다. import 에 `dev.jaydev.tossmcp.tools.TradingTools` 를 추가한다.

```java
    @Bean
    ToolCallbackProvider tossTools(MarketDataTools marketDataTools, TradingTools tradingTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(marketDataTools, tradingTools)
                .build();
    }
```

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

Run: `./gradlew test --tests '*TradingToolsTest'`
Expected: PASS — 3개 테스트 통과.

- [ ] **Step 6: 전체 빌드로 회귀를 확인한다**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — 기존 테스트 전부 통과하고, `VirtualThreadPinningTest` 의 피닝 수가 여전히 0이다.

- [ ] **Step 7: 커밋한다**

```bash
git add src/main/java/dev/jaydev/tossmcp/tools/TradingTools.java \
        src/main/java/dev/jaydev/tossmcp/TossMcpApplication.java \
        src/test/java/dev/jaydev/tossmcp/tools/TradingToolsTest.java
git commit -m "feat(tools): expose order and account tools over MCP"
```

---

### Task 7: 문서 갱신

**Files:**
- Create: `CLAUDE.md`
- Modify: `docs/tools.md`, `AGENTS.md`, `README.md`, `README.en.md`, `llms.txt`

**Interfaces:**
- Consumes: Task 1~6이 만든 도구 이름·파라미터·설정 키
- Produces: 없음 (문서)

- [ ] **Step 1: CLAUDE.md 를 만든다**

`CLAUDE.md`:

```markdown
# CLAUDE.md

이 저장소의 규약은 [AGENTS.md](AGENTS.md) 한 곳에만 둔다. Claude Code로 작업할 때도
그 문서를 따른다 — 빌드·테스트 명령, 프로젝트 구조, 커밋 규칙, 안전 규칙이 모두 거기 있다.

특히 다음 두 가지를 기억한다.

- 주문 도구는 기본이 미리보기다. `toss.trading.enabled` 와 `execute` 가 모두 참일 때만 실제로 전송된다.
- 자격증명(`TOSS_CLIENT_ID`, `TOSS_CLIENT_SECRET`, `TOSS_ACCOUNT`)은 환경변수로만 다루고
  코드·로그·커밋에 넣지 않는다.
```

- [ ] **Step 2: docs/tools.md 에 주문 도구를 추가한다**

`docs/tools.md` 맨 아래(캐시 동작 절 앞)에 다음 절을 넣는다.

````markdown
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
````

- [ ] **Step 3: AGENTS.md 의 범위 규칙을 실제 구현에 맞춘다**

`AGENTS.md`의 `## Scope` 절에서 마지막 항목을 다음으로 바꾼다.

```markdown
- **Scope:** official API only (no unofficial WTS scraping). Market-data tools are read-only.
  Order tools exist but are off by default: an order is transmitted only when
  `toss.trading.enabled=true`, the call passes `execute=true`, and the configured notional and
  daily-count limits allow it. Never weaken those gates, never send `confirmHighValueOrder`,
  and never add automatic retries to the write path — `clientOrderId` makes a caller-driven
  retry safe instead.
```

또한 `## Project layout` 의 트리에 두 줄을 더한다.

```
  trading/                     # OrderGuard (safety gates), DailyOrderCounter, OrderRateLimiter, OrderResult
  tools/TradingTools.java      # MCP @Tool definitions for orders and account reads
```

- [ ] **Step 4: README 두 개와 llms.txt 의 "읽기 전용" 표현을 정정한다**

`README.md` 의 도구 소개 부분에 다음 문단을 추가한다(도구 표 바로 아래).

```markdown
주문 도구(`placeOrder`·`cancelOrder`)와 계좌 조회 도구(`getOpenOrders`·`getHoldings`·`getBuyingPower`)도
제공합니다. **주문은 기본적으로 전송되지 않습니다** — 서버 설정 `toss.trading.enabled=true` 와
호출 인자 `execute=true` 가 모두 있어야 하고, 설정된 금액·횟수 한도 안이어야 합니다.
자세한 동작은 [도구 레퍼런스](docs/tools.md)를 보세요.
```

`README.en.md` 의 같은 위치에 다음을 추가한다.

```markdown
Order tools (`placeOrder`, `cancelOrder`) and account reads (`getOpenOrders`, `getHoldings`,
`getBuyingPower`) are also available. **Orders are not transmitted by default** — the server
switch `toss.trading.enabled=true`, the call argument `execute=true`, and the configured
notional and daily-count limits must all allow it. See the [tool reference](docs/tools.md).
```

`llms.txt` 의 도구 목록 줄과 그 아래에 다음을 반영한다.

```markdown
- 5 read-only market-data tools: `getPrices`, `getOrderbook`, `getTrades`, `getCandles`, `getStocks`. Same tools cover Korea (6-digit codes, KRW) and US (tickers, USD).
- 5 trading/account tools: `placeOrder`, `cancelOrder`, `getOpenOrders`, `getHoldings`, `getBuyingPower`. Orders are gated: nothing is transmitted unless `toss.trading.enabled=true` AND the call passes `execute=true` AND the configured notional/daily-count guardrails allow it. Otherwise the tool returns a `DRY_RUN` preview. Account reads are never cached. The write path never auto-retries; every order carries a `clientOrderId` idempotency key so the caller can retry safely within 10 minutes.
```

- [ ] **Step 5: 문서가 실제와 맞는지 확인한다**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

문서에 적은 도구 이름 다섯 개(`placeOrder`, `cancelOrder`, `getOpenOrders`, `getHoldings`,
`getBuyingPower`)가 `TradingTools` 의 `@Tool` 메서드 이름과 정확히 같은지, 설정 키 다섯 개가
`application.yml` 과 같은지 눈으로 대조한다.

- [ ] **Step 6: 커밋한다**

```bash
git add CLAUDE.md docs/tools.md AGENTS.md README.md README.en.md llms.txt
git commit -m "docs: document order tools and the safety gates that guard them"
```

---

## 라이브 검증 (선택, 수동)

토스는 모의투자·샌드박스를 제공하지 않으므로 CI에서는 실주문을 검증할 수 없다.
직접 확인하고 싶다면 다음 순서로 한다. **실계좌에 실제 주문이 들어가므로 선택 사항이다.**

1. `TOSS_ACCOUNT` 에 계좌 일련번호를 넣고 `toss.trading.enabled=true` 로 서버를 띄운다.
2. `getBuyingPower(currency="KRW")` 로 계좌 연결을 먼저 확인한다.
3. `placeOrder` 를 **체결되지 않을 만큼 시장가에서 먼 지정가**로 1주 넣는다.
   (매수라면 현재가보다 훨씬 낮은 가격. 호가 단위에 맞아야 한다.)
4. `getOpenOrders` 로 접수를 확인한 뒤 `cancelOrder(orderId, execute=true)` 로 즉시 취소한다.
5. 확인이 끝나면 `toss.trading.enabled` 를 다시 `false` 로 되돌린다.
