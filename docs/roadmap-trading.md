# 트레이딩 확장 백로그 (toss-invest-mcp)

> 이 문서는 "토스 자동매매" 방향의 큰 그림과 결정 사항을 잊지 않기 위한 백로그입니다.
> 각 서브프로젝트는 자기 spec/plan을 따로 가집니다.

## 한 줄 비전

**MCP = 손**(시세 읽기 + 주문 실행), **규칙 = 두뇌**(kiwoom 분할매수 규칙을 소비 프로그램/AI에 구현).
하나의 코어 위에 얇은 어댑터·포장을 얹어 **MCP · CLI · Claude Code 플러그인 · Codex 플러그인** 네 형태로 제공.

## 확정된 원칙

- **MCP에는 규칙을 넣지 않는다.** MCP 도구는 원시적(예: `placeOrder(symbol, qty, price, side)`)이고, 전략 판단은 두뇌(앱 or AI)에만 둔다.
- **실행 사고 방지 안전장치는 MCP에 최소한만** (dry-run 기본 + 실행 게이트). 전략이 아니라 실행 안전.
- 공식 API only, 구현 전 파라미터는 토스 공식 문서(Context7)로 재검증 — 추측 금지.
- 만들 실제 자산은 딱 둘: **MCP jar**(주문 추가) + **규칙 스킬 `SKILL.md`**. 나머지는 그 위 얇은 포장.

## 서브프로젝트

| | 서브프로젝트 | 의존성 |
|---|---|---|
| **A** | MCP 주문/거래 도구 + 안전게이트 (toss-invest-mcp 쓰기 확장) | 없음 — **토대** |
| B | CLI 어댑터 (같은 코어 위 picocli) | A의 코어 |
| C | 규칙 스킬 `SKILL.md` (크로스툴 규칙 안내) | 도구가 존재해야 참조 가능 |
| D | 배포 포장 (Claude Code + Codex 플러그인/마켓플레이스) | A (+ B·C 번들) |

**순서:** A → (B·C 병렬) → D. 현재 **A 설계 중**.

## 아키텍처 (한 장)

```
[배포 포장]  Claude Code 플러그인 · Codex 플러그인 · CLI(npx/brew)
                        │ 얇은 매니페스트
[공유 자산]  ┌ 규칙 스킬 SKILL.md (agentskills 표준, 어디서나 재사용) ← 두뇌 안내
             └ MCP jar (손: 시세+주문+안전게이트)  +  CLI 어댑터(picocli)
                        │ 얇은 껍데기
[코어]       인증 · 캐시 · 주문실행 · 안전게이트
                        │
                   토스 Open API
```

## 검증된 사실 (2026-07-27, 공식 문서 확인)

### Claude Code 플러그인
- 한 플러그인이 MCP 서버 + 슬래시 명령 + 서브에이전트 + 스킬 + 훅을 전부 번들.
- MCP 선언: `.mcp.json` 또는 `plugin.json` 인라인. 로컬 jar 실행 지원(`command:"java", args:["-jar","${CLAUDE_PLUGIN_ROOT}/lib/x.jar"]`, env 주입). stdio/http.
- 경로 변수: `${CLAUDE_PLUGIN_ROOT}`, `${CLAUDE_PLUGIN_DATA}`(업데이트에도 보존).
- 배포: `.claude-plugin/marketplace.json` → 공개 git 레포가 곧 마켓플레이스. `/plugin marketplace add owner/repo` → `/plugin install`.
- 규칙 안내는 스킬(SKILL.md)이 정답(자동 적용). 출처: code.claude.com/docs (plugins-reference, plugin-marketplaces, mcp).

### Codex CLI
- MCP 지원: `~/.codex/config.toml`의 `[mcp_servers.<name>]`(snake_case). `command` 있으면 stdio, `url` 있으면 streamable HTTP(별도 transport 키 없음). `java -jar`+env 확인됨. `codex mcp add ...` CLI로도 등록.
- 커스텀 프롬프트(`~/.codex/prompts/*.md`)는 **deprecated** → "스킬을 써라".
- 정식 **플러그인 + 마켓플레이스 존재**: `.codex-plugin/plugin.json`, `.agents/plugins/marketplace.json`, `codex plugin add/list/remove`, `codex plugin marketplace add owner/repo`.
- 스킬은 `.agents/skills`의 `SKILL.md`. **크로스툴 표준 agentskills.io** — 같은 스킬이 Codex·Claude Code·Cursor·Copilot에서 동작.
- 출처: learn.chatgpt.com/docs (extend/mcp, config-reference, custom-prompts, build-skills), developers.openai.com/plugins/build/plugins, github.com/openai/codex.

### 불확실(정직)
- Codex `AGENTS.md` 정확한 병합 순서·`--print-instructions` = 2차 출처.
- 스킬 출시 시점(≈2025-12) 대략치. 플러그인 http/websocket 세부는 stdio보다 덜 검증됨.
