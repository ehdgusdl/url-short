import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

/**
 * CQRS 읽기/쓰기 격리 시나리오.
 *
 * 목적: "쓰기 부하가 폭증해도 읽기 지연이 흔들리지 않는가"(자원 결합 해소)를 Grafana에서 실측한다.
 *
 * 구성:
 *  - read  (전 구간 지속): cold key(존재하지 않는 랜덤 코드)를 조회한다. 캐시를 무조건 미스하므로
 *    매 요청이 Replica로 readOnly SELECT를 내려보낸다. DB 연산은 실제 리다이렉트 조회와 동일한
 *    short_code 인덱스 룩업(결과 0행일 뿐)이라 Replica '읽기 경로'의 지연을 그대로 대표한다.
 *  - write_burst (중간 구간): BURST_START 시점부터 URL 생성(POST)을 몰아친다. 생성은 Primary로 고정된다.
 *
 * 기대 그림(대시보드 'URL Shortener — CQRS 라우팅 & 읽기/쓰기 격리'):
 *  - 패널②: write TPS가 치솟는 구간에도 read p99/p95가 평평하면 격리 성공.
 *  - 패널①: read-only 구간엔 게이지가 Replica ~100%, burst 구간엔 Primary가 섞여 오른다.
 *  - 패널③: replica-pool active는 꾸준, burst 때 primary-pool active만 솟는다.
 *
 * 환경변수:
 *  BASE_URL(=http://localhost:8080)
 *  READ_RATE(=1500 req/s), WRITE_RATE(=400 req/s)
 *  DURATION(=90s, 읽기 전체 구간), BURST_START(=30s), BURST_DUR(=25s)
 *  PREALLOC(=100), MAXVUS(=600)
 *
 * 실행:
 *  ./k6/run-cqrs.sh
 *  READ_RATE=2000 WRITE_RATE=600 ./k6/run-cqrs.sh
 */

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
// 서비스 분리 후 생성/삭제는 url-api(8082), 리다이렉트는 redirect(8080/8081)로 나뉜다.
const WRITE_URL = __ENV.WRITE_URL || 'http://localhost:8082';
const READ_RATE = parseInt(__ENV.READ_RATE || '1500', 10);
const WRITE_RATE = parseInt(__ENV.WRITE_RATE || '400', 10);
const DURATION = __ENV.DURATION || '90s';
const BURST_START = __ENV.BURST_START || '30s';
const BURST_DUR = __ENV.BURST_DUR || '25s';
const PREALLOC = parseInt(__ENV.PREALLOC || '100', 10);
const MAXVUS = parseInt(__ENV.MAXVUS || '600', 10);

// cold key 조회는 404가 정상이다. 404/302를 실패로 세지 않도록 기대 상태코드를 넓힌다.
http.setResponseCallback(http.expectedStatuses(200, 201, 204, 302, 404));

const readLatency = new Trend('cqrs_read_latency', true);    // Replica 읽기 경로 지연
const writeLatency = new Trend('cqrs_write_latency', true);   // Primary 쓰기 경로 지연

export const options = {
  scenarios: {
    // 읽기: 전 구간 지속. cold key라 매번 Replica SELECT.
    read: {
      executor: 'constant-arrival-rate',
      rate: READ_RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: PREALLOC,
      maxVUs: MAXVUS,
      exec: 'readColdKey',
      tags: { phase: 'read' },
    },
    // 쓰기: 중간 구간에만 몰아치는 burst. Primary로 고정된다.
    write_burst: {
      executor: 'constant-arrival-rate',
      rate: WRITE_RATE,
      timeUnit: '1s',
      startTime: BURST_START,
      duration: BURST_DUR,
      preAllocatedVUs: PREALLOC,
      maxVUs: MAXVUS,
      exec: 'writeCreate',
      tags: { phase: 'write_burst' },
    },
  },
  // 실패율만 느슨하게 검증(404는 위 responseCallback으로 정상 처리되어 여기 안 잡힌다).
  thresholds: {
    http_req_failed: ['rate<0.05'],
  },
};

// 존재하지 않을 8자리 코드를 만든다(형식은 유효: [A-Za-z0-9]{6,10}).
const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
function coldCode() {
  let s = '';
  for (let i = 0; i < 8; i++) s += ALPHABET[Math.floor(Math.random() * ALPHABET.length)];
  return s;
}

// 읽기: cold key 조회 → Replica readOnly SELECT.
export function readColdKey() {
  const res = http.get(`${BASE_URL}/${coldCode()}`, {
    redirects: 0,                       // 혹시 실제로 존재해도 외부로 안 나간다.
    tags: { name: 'read_redirect' },
  });
  readLatency.add(res.timings.duration);
  check(res, { 'read handled (302|404)': (r) => r.status === 302 || r.status === 404 });
}

// 쓰기: URL 생성 → Primary. existsByShortCode(유일성 검사)도 Primary에서 수행된다.
export function writeCreate() {
  const res = http.post(
    `${WRITE_URL}/api/urls`,
    JSON.stringify({ originalUrl: `https://example.com/cqrs/${Date.now()}/${Math.random()}` }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'create' } }
  );
  writeLatency.add(res.timings.duration);
  check(res, { 'create 201': (r) => r.status === 201 });
}
