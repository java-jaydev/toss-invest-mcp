# CLI 어댑터 구현 계획 (서브프로젝트 B)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans.

**Goal:** 같은 코어 위에 명령줄 어댑터를 얹어, AI 없이도 터미널에서 시세를 조회하고 (게이트를 통과한 경우) 주문할 수 있게 한다.

**Architecture:** 하나의 jar가 세 가지 모드로 뜬다 — stdio MCP(기본), Streamable HTTP MCP(`http` 프로파일), 그리고 CLI(`cli` 프로파일, 1회 실행 후 종료). 세 모드는 같은 `MarketDataService`·`TradingService`를 쓰며, CLI는 얇은 껍데기다. 안전게이트는 어댑터가 아니라 코어에 있으므로 CLI에도 그대로 적용된다.

**Tech Stack:** Java 21, Spring Boot 3.5, picocli, Gradle.

## Global Constraints

- **전략 로직을 넣지 않는다.** CLI 명령은 도구와 1:1로 대응하는 원시 명령이다.
- **안전게이트를 우회하지 않는다.** 주문은 반드시 `TradingService`를 거친다. CLI가 `OrderGuard`를 직접 호출하거나 `TossApiClient`를 직접 부르는 일은 없다.
- **stdout 오염 금지.** stdio MCP 모드의 stdout은 프로토콜 채널이다. CLI 모드에서만 콘솔 출력을 켜며, 그 설정은 `cli` 프로파일 안에서만 한다. 기본(무프로파일) 동작은 조금도 바뀌면 안 된다.
- 시크릿은 환경변수로만. 출력·로그·문서에 노출 금지.
- 블로킹 호출을 `synchronized` 안에 넣지 않는다(가상스레드 피닝 0 유지).
- Conventional Commits, 명령형. **AI 생성 표기·Co-Authored-By 금지.**

## 설계 결정

**모드 선택은 프로파일로 한다.** `java -jar toss-invest-mcp.jar --spring.profiles.active=cli <명령>`. 인자를 보고 모드를 추측하지 않는다 — 추측은 "시세를 조회하려던 명령이 MCP 서버를 띄우는" 식의 놀라움을 만든다. 대신 저장소 루트에 `toss` 래퍼 스크립트를 두어 사용자가 `./toss price 005930` 으로 쓰게 한다.

**`cli` 프로파일이 하는 일** (`application-cli.yml`):
- `spring.ai.mcp.server.stdio: false` — CLI 모드에서 MCP 서버를 띄우지 않는다.
- `logging.threshold.console: "OFF"` 유지 — 로그는 파일로만. 명령 결과만 stdout에 쓴다.
- 웹 서버 없음(기본값 유지).

**종료 코드**로 결과를 알린다. 스크립트에서 쓸 수 있어야 한다.

| 코드 | 의미 |
|---|---|
| 0 | 성공(조회 성공, 또는 주문이 `PLACED`·`DRY_RUN`) |
| 1 | `REJECTED` — 안전게이트나 토스가 거부 |
| 2 | `UNKNOWN` — 접수 여부 불명 |
| 3 | 사용법 오류(인자 누락·잘못된 값) |
| 4 | 그 밖의 실패(인증·네트워크) |

## File Structure

**생성**
- `src/main/java/dev/jaydev/tossmcp/cli/TossCli.java` — picocli 최상위 명령(`@Command(name="toss", subcommands={...})`).
- `src/main/java/dev/jaydev/tossmcp/cli/MarketCommands.java` — `price`, `orderbook`, `trades`, `candles`, `stocks`.
- `src/main/java/dev/jaydev/tossmcp/cli/AccountCommands.java` — `holdings`, `buying-power`, `orders`.
- `src/main/java/dev/jaydev/tossmcp/cli/OrderCommands.java` — `order`(buy/sell), `cancel`.
- `src/main/java/dev/jaydev/tossmcp/cli/CliRunner.java` — `@Profile("cli")` `CommandLineRunner`. picocli를 실행하고 종료 코드를 `System.exit`으로 넘긴다.
- `src/main/resources/application-cli.yml`
- `toss` — 래퍼 스크립트(실행 권한).
- 테스트: `src/test/java/dev/jaydev/tossmcp/cli/CliCommandTest.java`

**수정**
- `build.gradle` — `implementation 'info.picocli:picocli:4.7.6'`
- `docs/cli.md`(신규) + `README.md`·`README.en.md`에 포인터

## 명령 표면

```
toss price <symbols>                    # 콤마 구분, 최대 200
toss orderbook <symbol>
toss trades <symbol> [--count N]
toss candles <symbol> --interval 1m|1d [--count N] [--before ISO] [--adjusted]
toss stocks <symbols>

toss holdings [--symbol S]
toss buying-power <KRW|USD>
toss orders [--symbol S] [--limit N]

toss order buy|sell <symbol> --qty Q --type limit|market [--price P] [--execute]
toss cancel <orderId> [--execute]
```

`--execute` 가 없으면 미리보기다. 이는 도구 계층의 `execute` 기본값 false와 같은 의미이며, CLI가 따로 판단하지 않고 그대로 `TradingService`에 넘긴다.

---

### Task 1: CLI 골격과 시세 명령

**Files:**
- Create: `cli/TossCli.java`, `cli/MarketCommands.java`, `cli/CliRunner.java`, `src/main/resources/application-cli.yml`, `toss`
- Modify: `build.gradle`
- Test: `src/test/java/dev/jaydev/tossmcp/cli/CliCommandTest.java`

**Interfaces:**
- Consumes: `MarketDataService.prices/orderbook/trades/candles/stocks`
- Produces: picocli 명령 트리, 종료 코드 규약(위 표)

- [ ] **Step 1: 의존성 추가** — `build.gradle`에 `implementation 'info.picocli:picocli:4.7.6'`
- [ ] **Step 2: 실패하는 테스트** — 각 시세 명령이 해당 서비스 메서드를 정확한 인자로 호출하고, 결과를 stdout에 쓰고, 종료 코드 0을 돌려주는지. 서비스는 목으로. picocli `CommandLine.execute(...)`를 직접 호출해 검증한다(스프링 컨텍스트 불필요).
- [ ] **Step 3: RED 확인** — `./gradlew test --tests '*CliCommandTest'`
- [ ] **Step 4: 구현** — `TossCli`(최상위), `MarketCommands`(서브명령), `CliRunner`(`@Profile("cli")`), `application-cli.yml`, `toss` 래퍼
- [ ] **Step 5: GREEN 확인**
- [ ] **Step 6: 기본 모드 회귀 확인** — `./gradlew build`. **`StdioNoWebServerTest`와 `StdoutSafetyConfigTest`가 여전히 통과해야 한다** — 무프로파일 동작이 바뀌지 않았다는 증거다.
- [ ] **Step 7: 커밋**

### Task 2: 계좌·주문 명령과 종료 코드

**Files:**
- Create: `cli/AccountCommands.java`, `cli/OrderCommands.java`
- Modify: `cli/TossCli.java`(서브명령 등록), `CliCommandTest.java`

**Interfaces:**
- Consumes: `TradingService.holdings/buyingPower/openOrders/placeOrder/cancelOrder`, `OrderResult`

- [ ] **Step 1: 실패하는 테스트** — 아래를 전부 단언한다.
  - `order buy 005930 --qty 1 --type limit --price 50000` 가 `--execute` 없이 호출되면 `TradingService.placeOrder(..., execute=false)` 로 전달되고 종료 코드 0
  - `--execute` 를 주면 `execute=true` 로 전달된다
  - `OrderResult.status` 가 `REJECTED`면 종료 코드 1, `UNKNOWN`이면 2
  - `--type limit` 인데 `--price` 가 없으면 종료 코드 3이고 **서비스가 호출되지 않는다**
  - `cancel <id>` 가 `--execute` 없이 미리보기로 전달된다
  - 출력에 `status`와 `reason`이 사람이 읽을 수 있게 포함된다
- [ ] **Step 2: RED 확인**
- [ ] **Step 3: 구현**
- [ ] **Step 4: GREEN 확인**
- [ ] **Step 5: 뮤턴트 검증** — `--execute` 를 무시하고 항상 `true`를 넘기도록 바꿔 테스트가 실패하는지 확인하고 원복한다. 이 프로젝트에서 실제 돈이 움직이는 분기이므로 반드시 확인한다.
- [ ] **Step 6: 전체 빌드 후 커밋**

### Task 3: 문서

**Files:** Create `docs/cli.md`; Modify `README.md`, `README.en.md`, `llms.txt`, `AGENTS.md`

- [ ] **Step 1:** `docs/cli.md` — 설치(빌드), `toss` 래퍼 사용법, 명령 표, 종료 코드 표, 실매매를 켜는 방법과 그 위험(샌드박스 없음), 예시.
- [ ] **Step 2:** README 두 곳과 `llms.txt`·`AGENTS.md`에 CLI 모드 존재를 반영. **AGENTS.md의 실행 명령 목록에 CLI 모드를 추가한다.**
- [ ] **Step 3:** 문서의 모든 명령·플래그·종료 코드가 실제 구현과 일치하는지 grep으로 대조하고 그 출력을 남긴다.
- [ ] **Step 4:** 커밋

## 검증 기준 (Definition of Done)

1. `./gradlew build` 초록불, 기존 테스트 회귀 없음.
2. **기본(무프로파일) 동작 불변** — `StdioNoWebServerTest`·`StdoutSafetyConfigTest` 통과가 그 증거.
3. 가상스레드 피닝 0 유지.
4. `--execute` 가 없으면 실제 주문이 전송되지 않음이 테스트로 단언된다(뮤턴트로 확인).
5. 종료 코드 0/1/2/3 각각이 테스트로 고정된다.
6. CI가 실 토스 API를 호출하지 않는다.
7. 문서의 명령·플래그가 구현과 일치한다.
