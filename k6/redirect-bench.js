import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

/**
 * 리다이렉트 경로 벤치마크 — Redis 단독 vs 레이어드 캐시 비교.
 *
 * 동작:
 *  1) setup(): MODE에 맞춰 앱의 L1(로컬 캐시)을 켜거나 끄고(/actuator/l1cache),
 *     KEYS개의 단축 URL을 미리 생성한 뒤 캐시를 워밍한다.
 *  2) default(): 핫키 편중(기본 상위 20% 키에 80% 트래픽)으로 /{shortCode}를 조회한다.
 *     리다이렉트(302)를 따라가지 않도록 redirects=0으로 두고 상태코드만 검증한다.
 *
 * 환경변수:
 *  BASE_URL(=http://localhost:8080), MODE(layered|redis), KEYS(=1000),
 *  HOT_PCT(=0.2), HOT_TRAFFIC(=0.8), VUS(=50), DURATION(=60s), WARMUP(=true)
 *
 * 실행 예:
 *  k6 run -e MODE=layered k6/redirect-bench.js
 *  k6 run -e MODE=redis   k6/redirect-bench.js
 */

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
// 서비스 분리 후 생성/삭제는 url-api(8082), 리다이렉트는 redirect(8080/8081)로 나뉜다.
const WRITE_URL = __ENV.WRITE_URL || 'http://localhost:8082';
const MODE = (__ENV.MODE || 'layered').toLowerCase();       // layered | redis
const KEYS = parseInt(__ENV.KEYS || '1000', 10);
const HOT_PCT = parseFloat(__ENV.HOT_PCT || '0.2');          // 핫키로 취급할 상위 비율
const HOT_TRAFFIC = parseFloat(__ENV.HOT_TRAFFIC || '0.8');  // 핫키로 향하는 트래픽 비율
const VUS = parseInt(__ENV.VUS || '50', 10);
const DURATION = __ENV.DURATION || '60s';
const WARMUP = (__ENV.WARMUP || 'true') === 'true';

const redirectLatency = new Trend('redirect_latency', true);

const WARMUP_DUR = __ENV.WARMUP_DURATION || '20s';   // JVM(JIT)/스레드풀/커넥션풀 예열 구간
// 고정 도착률(open model). 포화 아래로 두어야 지연이 큐잉이 아닌 '실제 캐시 비용'을 반영한다.
const RATE = parseInt(__ENV.RATE || '2000', 10);           // 목표 req/s
const PREALLOC = parseInt(__ENV.PREALLOC || '50', 10);     // 미리 확보할 VU
const MAXVUS = parseInt(__ENV.MAXVUS || '400', 10);        // 상한(도달하면 도착률 못 맞춤 = 포화 신호)

export const options = {
  scenarios: {
    // 1) 웜업: 측정 전 JVM을 데운다. 커스텀 지표(redirect_latency)에 기록하지 않는다.
    warmup: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: WARMUP_DUR,
      preAllocatedVUs: PREALLOC,
      maxVUs: MAXVUS,
      exec: 'warmupHit',
      tags: { phase: 'warmup' },
      gracefulStop: '0s',
    },
    // 2) 측정: 웜업이 끝난 뒤 시작. 이 구간만 지표로 남긴다. open model이라 앱이 느려져도
    //    부하를 더 몰지 않고 도착률을 고정 → 큐잉 폭주 없이 실제 지연을 잰다.
    measure: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: PREALLOC,
      maxVUs: MAXVUS,
      startTime: WARMUP_DUR,
      exec: 'measureHit',
      tags: { phase: 'measure' },
    },
  },
  // 모드별로 요약을 구분하기 위한 태그.
  tags: { mode: MODE },
  // 측정용 벤치라 지연 임계값은 두지 않는다(환경마다 달라 pass/fail이 무의미하고, 넘으면 k6가 non-zero로 끝나
  // wrapper 순차 실행을 끊는다). 실패율만 최소 검증한다.
  thresholds: {
    'http_req_failed{scenario:measure}': ['rate<0.05'],
  },
};

function randInt(n) {
  return Math.floor(Math.random() * n);
}

// 핫키 편중 분포로 코드 하나를 골라 리다이렉트를 친다.
function hit(data) {
  const codes = data.codes;
  const hotCount = Math.max(1, Math.floor(codes.length * HOT_PCT));
  let idx;
  if (Math.random() < HOT_TRAFFIC) {
    idx = randInt(hotCount);                       // 상위 핫키
  } else {
    idx = hotCount + randInt(codes.length - hotCount); // 나머지 콜드키
  }
  return http.get(`${BASE_URL}/${codes[idx]}`, {
    redirects: 0,                                  // 302를 따라가지 않는다(외부 요청 방지).
    tags: { name: 'redirect' },
  });
}

export function setup() {
  // 1) 벤치 모드에 맞춰 L1 on/off. layered=L1 사용, redis=Redis 단독.
  const enableL1 = MODE !== 'redis';
  const toggle = http.post(
    `${BASE_URL}/actuator/l1cache`,
    JSON.stringify({ enabled: enableL1 }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  check(toggle, { 'L1 toggle ok': (r) => r.status === 200 });
  console.log(`[setup] MODE=${MODE} -> L1 enabled=${enableL1} (status ${toggle.status})`);

  // 2) 단축 URL 시드 생성.
  const codes = [];
  for (let i = 0; i < KEYS; i++) {
    const res = http.post(
      `${WRITE_URL}/api/urls`,
      JSON.stringify({ originalUrl: `https://example.com/landing/${i}?ref=bench` }),
      { headers: { 'Content-Type': 'application/json' } }
    );
    if (res.status === 201) {
      codes.push(res.json('shortCode'));
    }
  }
  if (codes.length === 0) {
    throw new Error('시드 생성 실패: 단축 URL을 하나도 만들지 못했습니다. 앱이 떠 있는지 확인하세요.');
  }
  console.log(`[setup] seeded ${codes.length}/${KEYS} short codes`);

  // 3) 캐시 워밍 — 각 키를 한 번씩 조회해 L2(및 layered면 L1)에 적재.
  if (WARMUP) {
    for (const code of codes) {
      http.get(`${BASE_URL}/${code}`, { redirects: 0 });
    }
    console.log('[setup] cache warmed');
  }

  return { codes };
}

// 웜업 구간: JVM을 데우기만 하고 커스텀 지표에는 기록하지 않는다.
export function warmupHit(data) {
  hit(data);
}

// 측정 구간: 이 구간만 redirect_latency에 남긴다.
export function measureHit(data) {
  const res = hit(data);
  redirectLatency.add(res.timings.duration);
  check(res, { 'redirect 302': (r) => r.status === 302 });
}
