# 🔌 플러그인 설치 가이드 (Claude Code · Codex CLI)

[← README(한국어)](../README.md) · [← README (English)](../README.en.md) · [← 도구 레퍼런스](tools.md) · [← 비개발자 가이드](vibe-coding.md)

이 문서는 이 저장소를 **Claude Code**와 **Codex CLI**에 "플러그인"으로 설치하는 방법을 다룹니다.
직접 `.mcp.json`을 손으로 쓰는 기본 설치법은 [README 5분 시작](../README.md#-5분-시작)에 이미 있습니다 —
이 문서는 그 위에 마켓플레이스 설치, 자격증명 처리, 실매매 활성화까지 더한 상세판입니다.

> ⚠️ **먼저 알아야 할 것: 이 서버는 Java로 빌드해야 실행되는 프로그램입니다.**
> jar 파일(`build/libs/toss-invest-mcp-0.1.0.jar`)은 저장소에 들어있지 않습니다 — 빌드 결과물을
> 저장소에 커밋하지 않는 것이 정상이기 때문입니다. 그래서 **플러그인을 설치해도 "제로 설치"는
> 아닙니다.** 설치 직후 반드시 **`./gradlew build`를 한 번 실행**해야 서버가 실제로 뜹니다.
> 이 가이드의 모든 경로(Claude Code 마켓플레이스든, Codex든, 수동 설정이든)에 이 단계가 공통으로
> 들어갑니다. 이 사실을 건너뛰면 MCP 서버가 "연결 실패"로만 보이고 원인을 알기 어렵습니다.

## 0. 사전 준비 (양쪽 호스트 공통)

1. **JDK 21 이상** — 빌드와 실행 모두에 필요합니다(가상스레드, 클래스파일 버전 65).
   ```bash
   java -version   # 21 이상이어야 함
   ```
   없다면 [Adoptium](https://adoptium.net/temurin/releases/?version=21)에서 Temurin 21을 설치하세요.

2. **토스증권 Open API 키** — [openapi.tossinvest.com](https://openapi.tossinvest.com)에서 발급받습니다.
   클라이언트 ID·시크릿이 나옵니다. 주문·계좌 도구를 쓸 계획이면 계좌 일련번호(`TOSS_ACCOUNT`)도 필요합니다.

3. **IP 허용목록 등록 — 이 단계를 빼먹으면 인증이 원인 불명으로 실패합니다.**
   토스 Open API는 **호출하는 서버의 공인(public) IP를 콘솔에 미리 등록**해야만 요청을 받아줍니다.
   샌드박스가 없으므로 이 등록 없이는 첫 호출부터 인증 오류가 납니다. 로컬 PC에서 바로 이 MCP
   서버를 띄운다면 내 PC의 공인 IP(자택 회선이면 유동 IP일 수 있음)를, 원격 서버에 올린다면 그
   서버의 고정 공인 IP를 [openapi.tossinvest.com](https://openapi.tossinvest.com) 콘솔의 허용목록에
   추가하세요.

4. **어디서 빌드할지는 설치 경로에 따라 다릅니다 — 아래 표를 먼저 보세요.**

   | 설치 경로 | 빌드는 어디서? |
   |---|---|
   | Claude Code 마켓플레이스(`/plugin marketplace add java-jaydev/...`) | Claude Code가 **자기 캐시(`${CLAUDE_PLUGIN_ROOT}`)에 저장소를 따로 복사**합니다. 지금 클론해 둔 폴더가 아니라 **그 캐시 안에서** 빌드해야 합니다 — 1-1 참고. |
   | Claude Code 수동 설정(`.mcp.json` 직접 작성) | 지금 직접 클론해서 빌드한 폴더의 경로를 그대로 씁니다. |
   | Codex CLI (플러그인은 스킬만, MCP는 `codex mcp add`) | 지금 직접 클론해서 빌드한 폴더의 경로를 그대로 씁니다. |

   마켓플레이스가 아니라 **직접 클론해서 쓸 계획이면 지금 빌드해 두세요**:
   ```bash
   git clone https://github.com/java-jaydev/toss-invest-mcp.git
   cd toss-invest-mcp
   ./gradlew build   # 처음 한 번은 의존성 다운로드 때문에 수 분 걸릴 수 있음
   ```
   빌드가 끝나면 `build/libs/toss-invest-mcp-0.1.0.jar`가 생깁니다. 이 파일이 실제로 실행되는
   MCP 서버입니다. (Claude Code 마켓플레이스로만 설치할 계획이라면 이 클론은 지금 안 해도
   됩니다 — 1-1에서 다시 설명합니다.)

---

## 1. Claude Code에 설치

### 1-1. 마켓플레이스로 설치

이 저장소 자체가 마켓플레이스입니다(`.claude-plugin/marketplace.json`). Claude Code 안에서:

```
/plugin marketplace add java-jaydev/toss-invest-mcp
/plugin install toss-invest-mcp@toss-invest-mcp
```

설치 도중 **자격증명 입력 프롬프트**가 뜹니다(`toss_client_id`, `toss_client_secret`, 선택적으로
`toss_account`). 이 값들은 플러그인 매니페스트의 `userConfig`로 선언되어 있고, `sensitive: true`로
표시된 값은 Claude Code가 파일에 평문으로 남기지 않고 OS 키체인(또는 `~/.claude/.credentials.json`,
키체인이 없는 환경)에 저장합니다. 설치가 끝나면 세션에 반영하기 위해:

```
/reload-plugins
```

**이 경로는 위 0단계에서 클론한 폴더를 쓰지 않습니다.** Claude Code는 `java-jaydev/toss-invest-mcp`를
직접 GitHub에서 받아 자기 플러그인 캐시(`${CLAUDE_PLUGIN_ROOT}`, 보통 `~/.claude/plugins/cache/` 아래,
정확한 경로는 `claude plugin list`나 `/plugin` 상세 화면에서 확인)에 복사합니다. **그 복사본에는
아직 jar가 없으므로, 설치 직후 그 디렉터리로 가서 한 번 빌드해야 합니다:**

```bash
cd "<claude plugin list 등으로 확인한 ${CLAUDE_PLUGIN_ROOT} 경로>"
./gradlew build
```

이 단계를 건너뛰면 도구 목록에 아무것도 안 뜨거나 MCP 서버가 연결 실패로 표시됩니다 — 대부분
"설치했는데 왜 안 되지"의 원인이 이것입니다.

### 1-2. 마켓플레이스 경로가 안 될 때 — 수동 설정

마켓플레이스를 못 쓰거나 그냥 직접 설정하고 싶다면, 프로젝트의 `.mcp.json`(또는 사용자 설정)에
직접 추가하면 됩니다. **자격증명 값은 여기에 실제 값을 그대로 적게 됩니다 — 이 파일을 커밋하지
마세요** (`.gitignore`에 이미 있지 않다면 추가하세요):

```json
{
  "mcpServers": {
    "toss-invest": {
      "command": "java",
      "args": ["-jar", "/절대경로/toss-invest-mcp/build/libs/toss-invest-mcp-0.1.0.jar"],
      "env": {
        "TOSS_CLIENT_ID": "발급받은_클라이언트_ID",
        "TOSS_CLIENT_SECRET": "발급받은_시크릿",
        "TOSS_ACCOUNT": "계좌_일련번호"
      }
    }
  }
}
```

`TOSS_ACCOUNT`는 주문·계좌 도구를 쓸 때만 필요합니다. 시세 조회만 쓸 계획이면 비워도 됩니다.
스킬(`skills/split-buy-strategy/`)은 마켓플레이스를 거치지 않아도 이 저장소를 프로젝트로 열면
Claude Code가 `skills/` 디렉터리에서 자동으로 찾습니다.

---

## 2. Codex CLI에 설치

**먼저 정직하게 밝힙니다: Codex의 플러그인/마켓플레이스 기능은 Claude Code보다 훨씬 최근에
생겼고, 공식 문서로 확인 가능한 범위가 더 좁습니다.** 아래에서 확인된 것과 확인되지 않은 것을
구분해서 씁니다.

### 확인된 것 / 확인되지 않은 것

- ✅ `.codex-plugin/plugin.json` 매니페스트 형식, `skills` 필드(스킬 디렉터리 경로 지정)는
  공식 문서(developers.openai.com/plugins/build/plugins)로 확인했습니다.
- ✅ `codex plugin marketplace add <owner>/<repo>` 로 마켓플레이스를 등록하는 것까지는
  확인했습니다.
- ❌ **그 마켓플레이스에서 개별 플러그인을 실제로 설치·활성화하는 CLI 명령(`codex plugin add`
  같은 것)은 문서에서 찾지 못했습니다.** 문서는 `codex plugin marketplace add/list/upgrade/remove`
  까지만 다루고, 그다음 "설치" 단계는 다루지 않습니다. 여러분이 쓰는 Codex CLI 버전에 따라
  TUI(대화형 화면)에서 활성화하는 방식일 수도 있습니다 — `codex plugin marketplace list` 실행 후
  안내를 보거나 `codex --help` / `codex plugin --help`로 직접 확인하세요.
- ❌ 플러그인이 참조하는 MCP 서버 설정 파일 안에서 로컬 빌드 경로나 자격증명을 안전하게(예:
  `${CLAUDE_PLUGIN_ROOT}` 같은 경로치환이나 `env_vars` 같은 시크릿-패스스루) 넘기는 방법은 이
  경로(플러그인 매니페스트)에서 확인하지 못했습니다.

이 두 가지 확인 안 된 부분 때문에, **이 저장소의 Codex 플러그인(`.codex-plugin/`)은 스킬만
번들링하고, MCP 서버 자체는 번들링하지 않습니다.** MCP 서버는 아래 2-2의 `codex mcp add` 또는
`config.toml` 직접 편집으로 설정하는 것이 **확실히 동작하는 방법**입니다.

### 2-1. 플러그인 마켓플레이스로 스킬만 설치 (선택사항)

```
codex plugin marketplace add java-jaydev/toss-invest-mcp
codex plugin marketplace list
```

이 저장소의 `.agents/plugins/marketplace.json`이 `toss-invest-mcp` 플러그인(스킬만 포함)을
가리킵니다. 위에서 밝힌 대로 이후 "설치/활성화" 단계는 여러분의 Codex CLI 버전이 안내하는 방식을
따르세요. 이 단계를 건너뛰어도 아래 2-3처럼 스킬 폴더를 직접 복사하면 동일하게 동작합니다.

### 2-2. MCP 서버 설정 (권장 경로 — 확실하게 동작)

`codex mcp add` 명령으로 한 번에 등록합니다. `--env`는 반복해서 여러 개 줄 수 있습니다:

```bash
codex mcp add toss-invest \
  --env TOSS_CLIENT_ID=발급받은_클라이언트_ID \
  --env TOSS_CLIENT_SECRET=발급받은_시크릿 \
  --env TOSS_ACCOUNT=계좌_일련번호 \
  -- java -jar /절대경로/toss-invest-mcp/build/libs/toss-invest-mcp-0.1.0.jar
```

이 명령은 `~/.codex/config.toml`에 아래와 같은 항목을 씁니다(직접 편집해도 동일합니다):

```toml
[mcp_servers.toss-invest]
command = "java"
args = ["-jar", "/절대경로/toss-invest-mcp/build/libs/toss-invest-mcp-0.1.0.jar"]
env = { TOSS_CLIENT_ID = "발급받은_클라이언트_ID", TOSS_CLIENT_SECRET = "발급받은_시크릿", TOSS_ACCOUNT = "계좌_일련번호" }
```

**자격증명 값이 `~/.codex/config.toml`에 평문으로 남는 것이 꺼려진다면**, `env` 대신 `env_vars`로
바꿔서 값이 아니라 **변수 이름만** 적고, 실제 값은 여러분의 셸 환경변수로 계속 남겨두는 방법도
있습니다(Codex가 실행 시점에 셸 환경에서 값을 가져와 전달합니다):

```toml
[mcp_servers.toss-invest]
command = "java"
args = ["-jar", "/절대경로/toss-invest-mcp/build/libs/toss-invest-mcp-0.1.0.jar"]
env_vars = ["TOSS_CLIENT_ID", "TOSS_CLIENT_SECRET", "TOSS_ACCOUNT"]
```

이 경우 Codex를 실행하기 전에 셸에서 `export TOSS_CLIENT_ID=...` 등으로 값을 미리 내보내 두어야
합니다. 어느 쪽을 쓰든 **`~/.codex/config.toml`은 저장소 밖(사용자 홈 디렉터리)에 있는 파일이라
git으로 커밋되지 않습니다.**

### 2-3. 스킬만 수동으로 쓰고 싶다면

플러그인 경로를 아예 안 쓰고 싶다면, `skills/split-buy-strategy/` 디렉터리를 Codex가 스킬을
찾는 위치 중 하나로 복사(또는 심볼릭 링크)하면 됩니다. 예를 들어 사용자 범위:

```bash
mkdir -p ~/.agents/skills
cp -r skills/split-buy-strategy ~/.agents/skills/split-buy-strategy
```

---

## 3. 설치 확인 — 도구 10개가 보이는지 확인

MCP 클라이언트에서 도구 목록을 조회했을 때 아래 10개가 모두 보여야 합니다.

**시세 조회(항상 가능, 읽기 전용):** `getPrices`, `getOrderbook`, `getTrades`, `getCandles`, `getStocks`
**주문·계좌(기본은 미리보기):** `placeOrder`, `cancelOrder`, `getOpenOrders`, `getHoldings`, `getBuyingPower`

빠르게 확인하는 방법:

- Claude Code: `/mcp` 로 연결 상태와 도구 목록을 확인. 에이전트에게 "삼성전자(005930) 현재가
  알려줘"라고 물어봐서 실제 시세가 돌아오는지 확인.
- Codex CLI: `codex mcp list`로 서버가 연결됐는지 확인 후, 같은 방식으로 자연어 질문을 던져봅니다.

시세가 안 돌아오고 인증 오류가 난다면 0단계의 **IP 허용목록**을 다시 확인하세요 — 가장 흔한
원인입니다.

---

## 4. 실매매(주문 전송) 켜기 — 신중하게

**이 플러그인은 기본적으로 주문을 절대 전송하지 않습니다.** `placeOrder`·`cancelOrder`를 호출해도
아래 세 조건이 **모두** 참이어야만 실제 주문이 나갑니다. 하나라도 어긋나면 `DRY_RUN`(미리보기)
응답만 돌아옵니다.

1. 서버 설정 `toss.trading.enabled=true`
2. 도구 호출 인자 `execute=true`
3. 설정된 금액·횟수·종목 한도 이내

**켜는 방법** — 서버 프로세스의 환경변수로 `TOSS_TRADING_ENABLED=true`를 넘기면 됩니다
(`src/main/resources/application.yml`의 `toss.trading.enabled: ${TOSS_TRADING_ENABLED:false}`).
Claude Code plugin.json의 `userConfig`에는 이 스위치를 넣지 않았습니다 — 실매매 스위치를 설치
마법사의 입력값 하나로 만들면 무심코 켜기 쉬워지기 때문입니다. 켜고 싶다면 MCP 서버 설정의
`env`에 `"TOSS_TRADING_ENABLED": "true"`를 **직접, 의도적으로** 추가하세요.

**기본 가드레일 값** (`application.yml`, 켜져 있어도 이 한도를 넘으면 거부됩니다):

```yaml
toss:
  trading:
    enabled: false                 # 이 값이 true여야만 실매매가 켜짐
    max-order-notional-krw: 100000 # 주문 1건당 원화 한도
    max-order-notional-usd: 100    # 주문 1건당 달러 한도
    daily-order-count: 20          # 하루 주문 건수 한도
    symbol-allowlist: []           # 비우면 종목 제한 없음
```

**토스는 모의투자·샌드박스 환경을 제공하지 않습니다. 실매매를 켜면 모든 호출이 실제 계좌에
그대로 반영됩니다.** 이 플러그인은 안전하게, 자동으로, 수익성 있게 매매해준다고 약속하지
않습니다 — 그런 판단(언제·얼마나 살지)은 서버가 아니라 [`skills/split-buy-strategy/`](../skills/split-buy-strategy/SKILL.md)
같은 스킬이나 이 도구를 호출하는 에이전트·앱의 몫입니다. 자세한 안전 동작은
[도구 레퍼런스의 주문·계좌 도구 섹션](tools.md#주문계좌-도구--trading--account-tools)을 보세요.

---

## 5. 번들 스킬: 분할매수 전략

이 저장소는 `skills/split-buy-strategy/`에 분할매수(물타기 변형) 전략 스킬을 함께 담고 있습니다.
두 플러그인 매니페스트 모두 이 스킬을 자동으로 포함합니다(Claude Code는 `skills/` 디렉터리를
기본으로 스캔, Codex 플러그인은 `skills` 필드로 명시). 이 스킬은 **매매 판단 로직을 담고
있을 뿐, 서버 자체에는 어떤 전략도 없습니다** — MCP 서버는 시세를 읽고 주문을 내는 손이고,
전략은 스킬(또는 이 도구를 호출하는 다른 에이전트·앱)에 있습니다. 스킬은 지속적인 하락장에서
자본이 묶일 수 있다는 위험을 스스로 명시하고 있으니, 실계좌에 적용하기 전에
[SKILL.md](../skills/split-buy-strategy/SKILL.md)를 먼저 읽어보세요.

---

## 문제 해결

| 증상 | 원인 | 조치 |
|---|---|---|
| MCP 서버가 연결되지 않음/즉시 죽음 | 아직 빌드 안 함 | `./gradlew build` 실행, jar 존재 확인 |
| `UnsupportedClassVersionError` | Java 21 미만 | `java -version` 확인, PATH의 `java`가 21+ 인지 확인 |
| 시세 조회 시 인증 오류 | IP 허용목록 미등록 | 호출 서버의 공인 IP를 토스 콘솔에 등록 |
| `placeOrder`가 항상 `DRY_RUN` | 의도한 정상 동작(기본값) | §4 참고 — 세 조건을 모두 켜야 함 |
| Claude Code에서 자격증명을 다시 물어봄 | 키체인/자격증명 저장소 미가용 환경 | `~/.claude/.credentials.json` 권한 확인 또는 수동 설정(1-2) 사용 |
