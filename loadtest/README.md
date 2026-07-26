# 부하테스트 & 관측성

이 서버의 **캐시 오프로드 · 요청병합(single-flight) · 가상스레드 처리량**을 관측 가능하게
만들고 부하로 확인하기 위한 자산이다.

## 먼저: 정직성 (무엇을 재고, 무엇을 안 재는가)

실제 토스 Open API 를 부하로 두들길 수 없다(레이트리밋·약관·자격증명). 그래서 `loadtest`
프로파일은 **고정 지연 스텁 upstream**(`StubTossApiClient`)으로 상단을 대체한다. 따라서:

- ❌ **절대 지연·처리량을 "토스 서비스 성능"으로 제시하지 않는다.** 스텁 지연은 임의값이고,
  숫자는 실행 머신 사양에 의존한다.
- ❌ MCP JSON-RPC 프레이밍 오버헤드는 측정 범위가 아니다(부하는 얇은 HTTP shim 경유).
- ✅ **방어 가능한 결론은 하드웨어 독립적인 "비율"이다:**
  - **캐시 오프로드**: 요청 수 대비 upstream 실제 호출 수(`marketdata_upstream_calls_total`).
  - **single-flight**: 동일 키 동시요청이 upstream 1회로 병합됨.
  - **가상스레드**: 요청이 플랫폼 스레드가 아니라 가상스레드에서 처리됨(`VirtualThreadProbeIT` 로 증명).

공개 그래프·수치에는 항상 **"캐시 + 고정지연 스텁 업스트림, <머신> 기준"** 을 캡션한다.

## CI 로 재현되는 결정론적 증거 (k6/Docker 불필요)

수치를 지어낼 필요가 없다. `LoadOffloadIT` 가 HTTP 로 부하를 주고 지표 델타를 단언한다:

- 동일 키 **200 동시요청 → upstream 정확히 1회** (single-flight + 캐시)
- **500 반복요청 → upstream 정확히 1회** (오프로드)

이건 매 CI 실행에서 검증된다. 아래 k6/Grafana 는 "규모를 키워 눈으로 보는" 재현 도구다.

## 실측 결과 (예시 실행)

> **환경(캡션 필수):** WSL2, 4 vCPU / 11GiB, JDK 21, k6 v0.50.0, **캐시 + 고정지연
> 40ms 스텁 업스트림**. 절대 처리량·지연은 이 머신·스텁 기준이며 토스 실제 성능이 아니다.
> 방어 가능한 결론은 아래의 **오프로드 비율**이다.

| 시나리오 | 요청 수 | upstream 실제 호출 | 관측 |
|---|---:|---:|---|
| single-flight (콜드 키 200 동시) | 200 | **1** | 200 동시요청이 upstream 1회로 병합 |
| cache-offload (핫키, ~45s 램프 100 VU) | **157,476** (~3,500 req/s) | **1** | 6h TTL 핫키 → 전체 부하가 upstream 1회 |

- cache-offload 실행의 L1 히트율 ≈ **99.998%** (`cache_gets_total`: hit 157,674 / miss 3).
- http_req_duration p95 ≈ 55ms(위 스텁·머신 기준). **지연 절대값은 강조하지 않는다** — 핵심은
  "요청 대비 upstream 호출 수"다.

이 숫자는 아래 절차로 재현할 수 있으며, 병합·오프로드 성질은 `LoadOffloadIT` 가 CI 에서
매번 결정론적으로 담보한다.

## 로컬 실행 (k6)

1. 앱을 `http,loadtest` 프로파일로 띄운다(실제 자격증명 불필요):

   ```bash
   ./gradlew bootRun --args='--spring.profiles.active=http,loadtest'
   # 스텁 지연 조정: LOADTEST_UPSTREAM_LATENCY_MS=40
   ```

2. 부하를 준다:

   ```bash
   k6 run loadtest/k6/cache-offload.js      # 핫키 반복 → 오프로드
   k6 run -e KEY=COLD1 loadtest/k6/single-flight.js  # 콜드 버스트 → 병합
   ```

3. 오프로드를 확인한다 — k6 총 요청수와 서버 카운터를 비교:

   ```bash
   curl -s localhost:8080/actuator/prometheus | grep -E 'marketdata_upstream_calls_total|cache_gets_total'
   ```

   예: k6 가 수만 요청을 보냈는데 `marketdata_upstream_calls_total` 은 한 자릿수 →
   오프로드가 그 비율만큼 일어났다는 뜻(single-flight 스크립트는 델타가 1 이어야 한다).

## 관측성 스택 (Prometheus + Grafana)

> ⚠️ 이 저장소의 개발 환경(WSL)에는 Docker 데몬이 없어 **이 스택은 여기서 실행하지 않았다.**
> Docker 가 있는 머신에서 재현한다.

```bash
cd loadtest/observability
docker compose up -d          # Prometheus :9090, Grafana :3000 (익명 Admin)
# 앱은 컴포즈 밖에서 http,loadtest 로 띄운다(위 참고).
# Grafana > Dashboards > Import > grafana-dashboard.json, Prometheus 데이터소스 선택.
```

대시보드 패널: upstream calls/s, L1 히트율, `/loadtest` 요청률, 요청 지연 p95
(히스토그램 버킷은 `http` 프로파일에서 발행하도록 설정됨).

## 구성요소

| 파일 | 역할 |
|---|---|
| `k6/cache-offload.js` | 핫키 반복 부하(오프로드) |
| `k6/single-flight.js` | 콜드 버스트(요청병합) |
| `observability/docker-compose.yml` | Prometheus + Grafana |
| `observability/prometheus.yml` | `/actuator/prometheus` 스크레이프 설정 |
| `observability/grafana-dashboard.json` | 대시보드(임포트용) |

스텁 upstream·HTTP shim 은 `loadtest` 프로파일에서만 활성화되며 프로덕션엔 존재하지 않는다
(`src/main/java/dev/jaydev/tossmcp/loadtest/`).
