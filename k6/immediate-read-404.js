import http from 'k6/http';
import { check } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';

/**
 * 생성 직후 리다이렉트 404 검증 시나리오 (Write-Through 선입력 효과 실측).
 *
 * 목적: "URL 생성 응답을 받자마자(0~300ms 내) 그 짧은 링크를 조회하면 404가 나는가"를 지표로 본다.
 *   - Before(음성대조군): 선입력을 꺼 Replication Lag 구간의 Replica 조회로 404를 재현한다.
 *   - After(적용군): Write-Through 선입력을 켜 생성 직후 조회가 L2 히트로 흡수되어 404가 0으로 수렴하는지 본다.
 *
 * 전제(반드시 선행): 실제 복제 지연이 있어야 Before의 404가 재현된다.
 *   ./k6/set-replica-lag.sh 1     # 지연 복제(SOURCE_DELAY=1s) 주입 → 생성 직후 1s간 Replica에 행 부재
 * 이 스크립트 setup()은 /actuator/writeguard로 방어 토글만 맞춘다(복제 지연은 위 스크립트가 담당).
 *
 * 검증 대시보드 'URL Shortener — 생성 직후 404 (Write-Through)':
 *   패널①: k6_immediate_read_404_rate — Before는 높은 고원, After는 바닥(0)에 붙은 일직선.
 *   패널②: urlcache_hits{layer=l2} vs urlcache_db_reads{datasource=replica} — 선입력 켠 순간 X교차.
 *   패널③: http_server_requests(POST /api/urls) p99 — Before/After가 겹치는 수평선(쓰기 경로 지연 무증가).
 *
 * 환경변수:
 *   BASE_URL(=http://localhost:8080)
 *   MODE(before|after|prod, 기본 after) — writeguard 토글 프리셋
 *   RATE(=200 creates/s), DURATION(=60s), PREALLOC(=50), MAXVUS(=300)
 *
 * 실행: ./k6/run-404.sh  (before→after 연속 실행), 또는
 *   MODE=before k6 run k6/immediate-read-404.js
 */

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
// 서비스 분리 후 생성/삭제는 url-api(8082), 리다이렉트는 redirect(8080/8081)로 나뉜다.
const WRITE_URL = __ENV.WRITE_URL || 'http://localhost:8082';
const MODE = (__ENV.MODE || 'after').toLowerCase();
const RATE = parseInt(__ENV.RATE || '200', 10);
const DURATION = __ENV.DURATION || '60s';
const PREALLOC = parseInt(__ENV.PREALLOC || '50', 10);
const MAXVUS = parseInt(__ENV.MAXVUS || '300', 10);

// writeguard 프리셋: Write-Through 선입력만 토글한다.
//  before : prime off → 404 재현(음성대조군)
//  after  : prime on  → 생성 직후 조회를 L2가 흡수 → 404 0
const PRESETS = {
  before: { prime: false },
  after: { prime: true },
};

// 404/302 모두 정상 흐름이므로 실패로 세지 않는다.
http.setResponseCallback(http.expectedStatuses(200, 201, 204, 302, 404));

const createLatency = new Trend('create_latency', true);        // POST /api/urls 지연(쓰기 경로 비용)
const immediateReadLatency = new Trend('immediate_read_latency', true);
const immediate404 = new Rate('immediate_read_404');            // 생성 직후 조회의 404 비율(핵심 지표)
const immediateReadTotal = new Counter('immediate_read_total');
const immediate404Total = new Counter('immediate_read_404_total');

export const options = {
  scenarios: {
    create_then_read: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: PREALLOC,
      maxVUs: MAXVUS,
      exec: 'createThenRead',
      tags: { mode: MODE },
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    // after/prod에서는 생성 직후 404가 0이어야 한다(선입력이 흡수). before는 재현이 목적이라 검증하지 않는다.
    ...(MODE === 'before' ? {} : { 'immediate_read_404': ['rate==0'] }),
  },
};

export function setup() {
  const preset = PRESETS[MODE] || PRESETS.after;
  const res = http.post(
    `${WRITE_URL}/actuator/writeguard`,
    JSON.stringify(preset),
    { headers: { 'Content-Type': 'application/json' } }
  );
  check(res, { 'writeguard set ok': (r) => r.status === 200 });
  console.log(`[setup] MODE=${MODE} -> writeguard=${JSON.stringify(preset)} (status ${res.status}, body ${res.body})`);
  return preset;
}

export function createThenRead() {
  // 1) 생성 → Primary 고정
  const created = http.post(
    `${WRITE_URL}/api/urls`,
    JSON.stringify({ originalUrl: `https://example.com/i404/${__VU}/${__ITER}/${Date.now()}` }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'create' } }
  );
  createLatency.add(created.timings.duration);
  if (created.status !== 201) {
    check(created, { 'create 201': false });
    return;
  }

  // 2) 생성 응답에서 받은 짧은 코드를 '즉시' 조회 → 정상 시나리오(생성 직후 클릭/공유)
  const shortCode = created.json('shortCode');
  const read = http.get(`${BASE_URL}/${shortCode}`, {
    redirects: 0,                       // 302여도 외부로 나가지 않는다.
    tags: { name: 'immediate_read' },
  });
  immediateReadLatency.add(read.timings.duration);

  const is404 = read.status === 404;
  immediate404.add(is404);
  immediateReadTotal.add(1);
  if (is404) immediate404Total.add(1);

  check(read, { 'immediate read served (302)': (r) => r.status === 302 });
}
