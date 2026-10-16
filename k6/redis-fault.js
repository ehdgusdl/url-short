import http from 'k6/http';
import { check } from 'k6';
import { Trend, Rate } from 'k6/metrics';

/**
 * Redis 장애 중 리다이렉트 지연·에러율 측정.
 *
 * 흐름:
 *  1) setup(): 두 redirect 인스턴스의 admission을 끄고(전량 L1 적재) HOT개 URL을 만들어 L1·L2에 데운다.
 *     그다음 toxiproxy `redis` 프록시에 FAULT 모드 장애를 건다.
 *  2) measure: nginx(:80)로 고정 도착률 요청. HOT_TRAFFIC 비율은 데운 키(L1 히트),
 *     나머지는 없는 랜덤 코드(L1 미스 → 음성 캐시 조회 → DB → 404)로 보낸다.
 *  3) teardown(): 장애를 걷고 admission을 되돌린다.
 *
 * FAULT:
 *  none    장애 없음(기준선)
 *  latency 응답마다 LATENCY_MS 지연(느린 Redis)
 *  timeout 응답 없이 붙잡기(블랙홀). toxiproxy timeout toxic, timeout=0
 *  down    프록시 비활성화(연결 끊김·거부)
 *
 * 실행: k6/run-redis-fault.sh 가 모드별로 돌린다.
 */

const NGINX = __ENV.NGINX_URL || 'http://localhost';
const WRITE_URL = __ENV.WRITE_URL || 'http://localhost:8082';
const REDIRECTS = (__ENV.REDIRECT_URLS || 'http://localhost:18080,http://localhost:8081').split(',');
const TOXI = __ENV.TOXIPROXY_API || 'http://localhost:8474';
const FAULT = (__ENV.FAULT || 'none').toLowerCase();
const LATENCY_MS = parseInt(__ENV.LATENCY_MS || '1000', 10);
const HOT = parseInt(__ENV.HOT || '200', 10);
const HOT_TRAFFIC = parseFloat(__ENV.HOT_TRAFFIC || '0.9');
const RATE = parseInt(__ENV.RATE || '300', 10);
const DURATION = __ENV.DURATION || '90s';
const MAXVUS = parseInt(__ENV.MAXVUS || '3000', 10);

const hotLatency = new Trend('hot_latency', true);
const coldLatency = new Trend('cold_latency', true);
const errors = new Rate('redirect_errors');

export const options = {
  setupTimeout: '120s',
  teardownTimeout: '120s',
  scenarios: {
    measure: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 200,
      maxVUs: MAXVUS,
      exec: 'measure',
    },
  },
  tags: { fault: FAULT },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

function json(body) {
  return { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) };
}

function setAdmission(on) {
  for (const base of REDIRECTS) {
    const p = json({ admission: on });
    http.post(`${base}/actuator/l1cache`, p.body, { headers: p.headers });
  }
}

function clearFault() {
  http.del(`${TOXI}/proxies/redis/toxics/fault_latency`);
  http.del(`${TOXI}/proxies/redis/toxics/fault_timeout`);
  const p = json({ enabled: true });
  http.post(`${TOXI}/proxies/redis`, p.body, { headers: p.headers });
}

function applyFault() {
  if (FAULT === 'latency') {
    const p = json({ name: 'fault_latency', type: 'latency', stream: 'downstream', attributes: { latency: LATENCY_MS } });
    http.post(`${TOXI}/proxies/redis/toxics`, p.body, { headers: p.headers });
  } else if (FAULT === 'timeout') {
    const p = json({ name: 'fault_timeout', type: 'timeout', stream: 'downstream', attributes: { timeout: 0 } });
    http.post(`${TOXI}/proxies/redis/toxics`, p.body, { headers: p.headers });
  } else if (FAULT === 'down') {
    const p = json({ enabled: false });
    http.post(`${TOXI}/proxies/redis`, p.body, { headers: p.headers });
  }
}

function randomCode() {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  let s = 'zz';
  for (let i = 0; i < 5; i++) s += chars[Math.floor(Math.random() * chars.length)];
  return s;
}

export function setup() {
  clearFault();
  setAdmission(false);

  // 핫키 생성 후 각 인스턴스에 직접 두 번씩 조회해 L1에 확실히 올린다.
  const codes = [];
  for (let i = 0; i < HOT; i++) {
    const p = json({ originalUrl: `https://example.com/fault/${Date.now()}/${i}` });
    const res = http.post(`${WRITE_URL}/api/urls`, p.body, { headers: p.headers });
    if (res.status === 200 || res.status === 201) codes.push(res.json('shortCode'));
  }
  for (let round = 0; round < 2; round++) {
    for (const base of REDIRECTS) {
      for (const c of codes) http.get(`${base}/${c}`, { redirects: 0 });
    }
  }

  applyFault();
  return { codes };
}

export function measure(data) {
  const hot = Math.random() < HOT_TRAFFIC;
  const code = hot ? data.codes[Math.floor(Math.random() * data.codes.length)] : randomCode();
  const res = http.get(`${NGINX}/${code}`, { redirects: 0, timeout: '70s' });
  const ok = hot ? res.status === 302 : res.status === 404;
  check(res, { 'expected status': () => ok });
  errors.add(!ok);
  (hot ? hotLatency : coldLatency).add(res.timings.duration);
}

export function teardown() {
  clearFault();
  setAdmission(true);
}
