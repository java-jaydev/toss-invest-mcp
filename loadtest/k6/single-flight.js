import http from 'k6/http';
import { check } from 'k6';

// single-flight 부하: 다수 VU 가 콜드 스타트 순간 같은 키를 동시에 친다.
// 서버를 새로 띄운 뒤(또는 새 KEY 로) 실행하고, 직후 /actuator/prometheus 의
// marketdata_upstream_calls_total 델타가 1 인지 확인한다 — N 동시요청이 upstream
// 1회로 병합됨을 뜻한다.
//
// 실행: k6 run -e KEY=COLD1 loadtest/k6/single-flight.js

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const KEY = __ENV.KEY || 'COLD';

export const options = {
  scenarios: {
    burst: {
      executor: 'shared-iterations',
      vus: 200,
      iterations: 200,
      maxDuration: '30s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  const res = http.get(`${BASE}/loadtest/stocks?symbol=${KEY}`);
  check(res, { 'status is 200': (r) => r.status === 200 });
}
