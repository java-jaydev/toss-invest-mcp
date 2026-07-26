import http from 'k6/http';
import { check } from 'k6';

// 캐시 오프로드 부하: 단일 핫키를 여러 VU 가 반복 조회한다.
// stocks 는 6h TTL 이라 부하 동안 재적재가 없어, 순수 오프로드/병합을 본다.
//
// 정직성: 이 수치는 "캐시 + 고정지연 스텁 업스트림" 기준이다. 토스 실제 지연이 아니고,
// 절대 처리량은 실행 머신 사양에 의존한다. 방어 가능한 결론은 "요청 대비 upstream 호출이
// 극소"라는 오프로드 비율이다 — 실행 후 /actuator/prometheus 의
// marketdata_upstream_calls_total 을 k6 의 총 요청수와 비교해 확인한다.
//
// 실행: k6 run loadtest/k6/cache-offload.js   (BASE_URL 로 대상 지정 가능)

const BASE = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    hot_key: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 100 },
        { duration: '30s', target: 100 },
        { duration: '5s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  const res = http.get(`${BASE}/loadtest/stocks?symbol=HOT`);
  check(res, { 'status is 200': (r) => r.status === 200 });
}
