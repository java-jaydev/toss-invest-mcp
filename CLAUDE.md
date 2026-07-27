# CLAUDE.md

이 저장소의 규약은 [AGENTS.md](AGENTS.md) 한 곳에만 둔다. Claude Code로 작업할 때도
그 문서를 따른다 — 빌드·테스트 명령, 프로젝트 구조, 커밋 규칙, 안전 규칙이 모두 거기 있다.

특히 다음 두 가지를 기억한다.

- 주문 도구는 기본이 미리보기다. `toss.trading.enabled` 와 `execute` 가 모두 참일 때만 실제로 전송된다.
- 자격증명(`TOSS_CLIENT_ID`, `TOSS_CLIENT_SECRET`, `TOSS_ACCOUNT`)은 환경변수로만 다루고
  코드·로그·커밋에 넣지 않는다.
