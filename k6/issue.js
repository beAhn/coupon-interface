// 다중 서버 테스트: nginx(:80) 경유 발급 부하 테스트
// 실행: K6_WEB_DASHBOARD=true k6 run -e VERSION=v5-1 k6/issue.js
import http from 'k6/http';
import { Counter } from 'k6/metrics';

const VERSION = __ENV.VERSION || 'v5-1';
const BASE_URL = __ENV.BASE_URL || 'http://localhost';

// 서버별, 결과별 집계
const app1 = new Counter('server_app1');
const app2 = new Counter('server_app2');
const issued = new Counter('result_issued');      // 200
const soldOut = new Counter('result_sold_out');   // 409
const error = new Counter('result_error');        // 그 외 (500 충돌 등)

export const options = {
  scenarios: {
    issue: {
      executor: 'shared-iterations', // 전체 반복을 VU들이 나눠 처리
      vus: 500,                      // 동시 사용자 = 동시 연결 수
      iterations: 10000,             // 총 요청 수
    },
  },
};

// 1인 1매(v7-2): USERS=150이면 user-1~150 중 랜덤 userId로 요청
const USERS = Number(__ENV.USERS || 0);
const duplicate = new Counter('result_duplicate'); // 409 + DUPLICATE

export default function () {
  const url = USERS > 0
    ? `${BASE_URL}/${VERSION}/api/issue-user?userId=user-${Math.floor(Math.random() * USERS) + 1}`
    : `${BASE_URL}/${VERSION}/api/issue`;
  const res = http.post(url);

  if (res.status === 200) issued.add(1);
  else if (res.status === 409 && res.body && res.body.includes('DUPLICATE')) duplicate.add(1);
  else if (res.status === 409) soldOut.add(1);
  else error.add(1);

  // 500 등은 JSON이 아닐 수 있음
  try {
    const server = res.json('serverName');
    if (server === 'app1') app1.add(1);
    else if (server === 'app2') app2.add(1);
  } catch (e) {}
}
