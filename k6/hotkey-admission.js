import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';

/**
 * 핫키 admission 대조 벤치마크 — L1을 "전량 적재"할 때와 "상위 핫키만 적재"할 때
 * 히트율/메모리를 한 번의 실행 단위로 비교한다.
 *
 * 동작:
 *  1) setup(): MODE에 맞춰 /actuator/l1cache 의 admission을 켜거나 끄고,
 *     KEYS개의 단축 URL을 생성한 뒤 전체를 한 번씩 조회해 L2를 채운다(워밍업).
 *     MODE=admission이면 대시보드가 핫키 스냅샷을 발행해 hotKeySize>0이 될 때까지 기다린다.
 *     이 대기를 건너뛰면 측정 구간 중간에 핫키 집합이 바뀌어 히트율에 계단이 생긴다.
 *  2) main(): 핫키 편중(상위 HOT_RATIO 비율에 HOT_SHARE 트래픽) 분포로 /{shortCode}를 조회한다.
 *
 * 환경변수:
 *  BASE_URL(=http://localhost:8080), WRITE_URL(=http://localhost:8082)
 *  KEYS(=1000), MODE(admission|all, =admission), HOT_RATIO(=0.1), HOT_SHARE(=0.9)
 *  DURATION(=3m), RPS(=500)
 *
 * 실행 예 (대조 비교):
 *  k6 run -e MODE=all        k6/hotkey-admission.js   # 대조군: 전량 적재
 *  k6 run -e MODE=admission  k6/hotkey-admission.js   # 실험군: 상위 10%만 적재
 *
 * 메모리 차이는 Grafana의 jvm_memory_used_bytes{application="redirect", area="heap"} 로 확인한다.
 */

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
// 생성은 url-api(8082), 리다이렉트는 redirect(8080)로 서비스가 분리되어 있다.
const WRITE_URL = __ENV.WRITE_URL || 'http://localhost:8082';
const KEYS = parseInt(__ENV.KEYS || '1000', 10);
const MODE = (__ENV.MODE || 'admission').toLowerCase();     // admission | all
const HOT_RATIO = parseFloat(__ENV.HOT_RATIO || '0.1');      // 핫키로 취급할 상위 비율
const HOT_SHARE = parseFloat(__ENV.HOT_SHARE || '0.9');      // 핫키로 향하는 트래픽 비율
const DURATION = __ENV.DURATION || '3m';
const RPS = parseInt(__ENV.RPS || '500', 10);

const HOT_WAIT_TIMEOUT_S = 180;  // hotKeySize>0 대기 상한

const redirectLatency = new Trend('redirect_latency', true);
const redirect302 = new Rate('redirect_302');
const hotKeyHits = new Counter('hot_key_hits');
const coldKeyHits = new Counter('cold_key_hits');

export const options = {
  scenarios: {
    main: {
      executor: 'constant-arrival-rate',
      rate: RPS,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.min(RPS, 200),
      maxVUs: Math.max(RPS, 400),
      exec: 'measureHit',
    },
  },
  tags: { mode: MODE },
  thresholds: {
    redirect_302: ['rate>0.99'],
    http_req_failed: ['rate<0.01'],
    // p99는 합격/불합격 기준이 아니라 대조용 요약 지표로만 남긴다.
  },
};

function getL1Status() {
  const res = http.get(`${BASE_URL}/actuator/l1cache`);
  return res.json();
}

function randInt(n) {
  return Math.floor(Math.random() * n);
}

// 핫키 편중 분포로 코드 인덱스 하나를 고른다(누적 가중치 없이 hot/cold 두 구간만으로 충분).
function pickIndex(hotCount, total) {
  if (Math.random() < HOT_SHARE) {
    return randInt(hotCount);
  }
  return hotCount + randInt(total - hotCount);
}

export function setup() {
  // 1) MODE에 맞춰 admission on/off.
  const admissionEnabled = MODE === 'admission';
  const toggle = http.post(
    `${BASE_URL}/actuator/l1cache`,
    JSON.stringify({ admission: admissionEnabled }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  check(toggle, { 'admission toggle ok': (r) => r.status === 200 });
  console.log(`[setup] MODE=${MODE} -> admission=${admissionEnabled} (status ${toggle.status})`);

  // 2) 단축 URL 시드 생성.
  const codes = [];
  for (let i = 0; i < KEYS; i++) {
    const res = http.post(
      `${WRITE_URL}/api/urls`,
      JSON.stringify({ originalUrl: `https://example.com/hotkey/${i}?ref=bench` }),
      { headers: { 'Content-Type': 'application/json' } }
    );
    if (res.status === 201) {
      codes.push(res.json('shortCode'));
    }
  }
  if (codes.length === 0) {
    throw new Error('시드 생성 실패: 단축 URL을 하나도 만들지 못했습니다. url-api가 떠 있는지 확인하세요.');
  }
  console.log(`[setup] seeded ${codes.length}/${KEYS} short codes`);

  const hotCount = Math.max(1, Math.floor(codes.length * HOT_RATIO));
  const hotSet = new Set(codes.slice(0, hotCount));

  // 3) 워밍업: 전체 키를 한 번씩 조회해 L2를 채운다.
  for (const code of codes) {
    http.get(`${BASE_URL}/${code}`, { redirects: 0 });
  }
  console.log('[setup] cache warmed (L2)');

  // 4) admission 모드면 대시보드가 핫키 스냅샷을 발행할 때까지 기다린다.
  //    측정 구간이 스냅샷 도착 시점을 걸치면 히트율에 계단이 생겨 대조를 못 믿는다.
  if (admissionEnabled) {
    const deadline = Date.now() + HOT_WAIT_TIMEOUT_S * 1000;
    let status = getL1Status();
    while ((status.hotKeySize || 0) <= 0 && Date.now() < deadline) {
      const remaining = Math.round((deadline - Date.now()) / 1000);
      console.log(`[setup] waiting for hot key snapshot... hotKeySize=${status.hotKeySize || 0}, ${remaining}s left`);
      sleep(2);
      status = getL1Status();
    }
    if ((status.hotKeySize || 0) <= 0) {
      throw new Error(`hotKeySize가 ${HOT_WAIT_TIMEOUT_S}초 안에 0보다 커지지 않았습니다. 핫키 발행 파이프라인을 확인하세요.`);
    }
    console.log(`[setup] hot key snapshot ready: hotKeySize=${status.hotKeySize}, hotKeyVersion=${status.hotKeyVersion}`);
  }

  return { codes, hotCount };
}

// 측정 구간: 핫키 편중 분포로 리다이렉트를 조회하고 hot/cold 히트를 분류한다.
export function measureHit(data) {
  const { codes, hotCount } = data;
  const idx = pickIndex(hotCount, codes.length);
  const code = codes[idx];

  const res = http.get(`${BASE_URL}/${code}`, {
    redirects: 0,
    tags: { name: 'redirect' },
  });
  redirectLatency.add(res.timings.duration);
  redirect302.add(res.status === 302);
  check(res, { 'redirect 302': (r) => r.status === 302 });

  if (idx < hotCount) {
    hotKeyHits.add(1);
  } else {
    coldKeyHits.add(1);
  }
}

export function teardown() {
  const status = getL1Status();
  console.log(
    `[teardown] entries=${status.entries}, hotKeySize=${status.hotKeySize}, admissionEnabled=${status.admissionEnabled}`
  );
}
