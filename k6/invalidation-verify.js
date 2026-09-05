import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend, Rate } from 'k6/metrics';

/**
 * 캐시 무효화 정확성 검증 — k6를 "정답지(oracle)"로 쓴다.
 *
 * k6는 자기가 방금 삭제한 키를 알기 때문에, 삭제 이후 그 키가 404가 아니면 Stale이라고 판정할 수 있다.
 * 앱 지표(발행/수신 카운트)만으로는 "기계가 돌았다"는 것만 알 뿐 "Stale을 안 줬다"는 증명이 못 되므로,
 * 실제 응답을 관측하는 이 스크립트가 합격/불합격을 threshold로 못박는다.
 *
 * ── 두 개의 인스턴스(app=A, app2=B)가 필요한 이유 ─────────────────────────────
 * 단일 인스턴스면 delete()가 자기 L1을 동기적으로 지워 Stale이 아예 안 생긴다(Pub/Sub 경로를 안 탐).
 * 문서의 핵심 주장("한 서버에서 지워도 다른 서버 L1엔 남는다")은 A에서 삭제하고 B를 보는 구조라야 검증된다.
 *
 * ── replica-lag 혼입 제거(판별식) ────────────────────────────────────────────
 * 삭제는 Primary에서 일어나고 조회는 Replica로 갈 수 있어, 복제 지연 동안 A·B 둘 다 302를 줄 수 있다.
 * 이건 캐시가 아니라 DB 지연이다. 그래서 "B가 302인데 A는 이미 404"일 때만 진짜 L1 분기(stale_reads)로 센다.
 * 둘 다 302면 복제 지연(db_lag_reads)으로 따로 집계해 숨기지 않는다.
 *   - 전파 ON  : B의 L1이 Pub/Sub로 비워짐 → B는 A와 함께 404로 수렴 → stale_reads ≈ 0
 *   - 전파 OFF : B의 L1이 안 비워짐 → B는 L1 히트로 302를 계속(최대 local-ttl 5분) → stale_reads 누적
 *     (이때 L2는 발행자가 이미 지웠으므로 B의 302는 반드시 L1에서 온 것 = L1 잔존의 증거)
 *
 * 실행 예:
 *   PROP=on  k6 run k6/invalidation-verify.js     # 전파 ON  → 수렴, stale 0 (합격)
 *   PROP=off k6 run k6/invalidation-verify.js     # 전파 OFF → Stale 지속 (음성대조군 성립 확인)
 *   TEST=race k6 run k6/invalidation-verify.js     # 동일 인스턴스 읽기/삭제 재적재 레이스 사냥
 */

const BASE_A = __ENV.BASE_A || 'http://localhost:8080';   // 발행(삭제) 인스턴스
const BASE_B = __ENV.BASE_B || 'http://localhost:8081';   // 수신(검증 대상) 인스턴스
const BASE_WRITE = __ENV.BASE_WRITE || 'http://localhost:8082'; // 생성/삭제는 url-api
const PROP = (__ENV.PROP || 'on').toLowerCase();          // on | off (B의 무효화 전파)
const TEST = (__ENV.TEST || 'propagation').toLowerCase(); // propagation | race
const ITER = parseInt(__ENV.ITER || '200', 10);
const VUS = parseInt(__ENV.VUS || '10', 10);
const POLL_TRIES = parseInt(__ENV.POLL_TRIES || '100', 10);       // 삭제 후 폴 횟수
const POLL_INTERVAL_MS = parseInt(__ENV.POLL_INTERVAL_MS || '20', 10); // 폴 간격(ms)
const RACE_READERS = parseInt(__ENV.RACE_READERS || '8', 10);     // 레이스: 동시 읽기 수

const tags = { prop: PROP, test: TEST };

// 삭제 후 B가 A와 달리(=A는 404인데 B는 302) 준 Stale 리다이렉트 수. 정상(전파 ON)이면 0.
const staleReads = new Counter('stale_reads');
// A·B 둘 다 302 = DB 복제 지연. 캐시 문제가 아니므로 분리 집계(숨기지 않는다).
const dbLagReads = new Counter('db_lag_reads');
// 404를 본 뒤 다시 302가 나온 횟수 = 재적재 스모킹건. 어떤 모드에서도 0이어야 한다.
const repopulation = new Counter('repopulation');
// 폴 예산 안에서 B가 404로 수렴했는지 비율.
const converged = new Rate('converged');
// 삭제 완료 → B 첫 404 까지 시간(ms). 수렴 시간의 상한(폴 간격이 하한임에 유의).
const staleWindowMs = new Trend('stale_window_ms', true);

export const options = buildOptions();

function buildOptions() {
  const scenarios = {};
  if (TEST === 'race') {
    scenarios.race = {
      executor: 'shared-iterations',
      vus: Math.max(2, VUS),
      iterations: ITER,
      exec: 'raceCheck',
      tags,
    };
  } else {
    scenarios.propagation = {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: ITER,
      exec: 'propagationCheck',
      tags,
    };
  }

  // threshold로 합격/불합격을 못박는다. 재적재(404 뒤 302 = 굳는 Stale)는 어떤 모드든 버그라 항상 0.
  const thresholds = {
    'repopulation': ['count==0'],
  };
  if (TEST !== 'race') {
    if (PROP === 'on') {
      // Pub/Sub는 비동기라 발행~수신 사이 짧은 전파 윈도우 동안 B가 잠깐 Stale일 수 있다(정상, 문서의 한계와 일치).
      // 따라서 정답은 '즉시 0'이 아니라 '유한 시간 내 수렴 + 윈도우가 TTL이 아니라 유계'다.
      thresholds['converged{prop:on}'] = ['rate>0.99'];         // 모든 키가 결국 404로 수렴
      thresholds['stale_window_ms{prop:on}'] = ['p(95)<2000'];  // 전파 윈도우가 초 미만으로 유계(=TTL 지속과 대비)
    } else {
      // 음성대조군: 전파가 없으면 수렴하지 않고 Stale이 지속돼야(=B의 L1 히트) 대조군이 성립한다.
      thresholds['stale_reads{prop:off}'] = ['count>0'];
      thresholds['converged{prop:off}'] = ['rate<0.01'];
    }
  }
  return { scenarios, thresholds };
}

function createUrl(i) {
  const res = http.post(
    `${BASE_WRITE}/api/urls`,
    JSON.stringify({ originalUrl: `https://example.com/inval/${__VU}-${i}-${Date.now()}` }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'create' } }
  );
  return res.status === 201 ? res.json('shortCode') : null;
}

function get(base, code, name) {
  return http.get(`${base}/${code}`, { redirects: 0, tags: { name } });
}

export function setup() {
  // 양 인스턴스 모두 레이어드(L1 on)로 맞춘다.
  for (const base of [BASE_A, BASE_B]) {
    http.post(`${base}/actuator/l1cache`, JSON.stringify({ enabled: true }),
      { headers: { 'Content-Type': 'application/json' } });
  }
  // A(발행자)는 전파 ON 고정. B만 시나리오에 맞춰 전파 on/off → 음성대조군.
  http.post(`${BASE_A}/actuator/invalidation`, JSON.stringify({ enabled: true }),
    { headers: { 'Content-Type': 'application/json' } });
  const bProp = http.post(`${BASE_B}/actuator/invalidation`, JSON.stringify({ enabled: PROP === 'on' }),
    { headers: { 'Content-Type': 'application/json' } });
  check(bProp, { 'B 전파 토글 200': (r) => r.status === 200 });
  console.log(`[setup] TEST=${TEST} PROP=${PROP} A=${BASE_A} B=${BASE_B} (B propagation=${PROP === 'on'})`);
  return {};
}

/**
 * 전파 검증: 생성 → A·B 워밍(둘 다 L1 적재) → A에서 삭제 → B를 폴링하며 수렴/Stale/재적재 관측.
 */
export function propagationCheck() {
  const code = createUrl(__ITER);
  if (!code) return;

  // 워밍: A가 L2+L1a 적재 → B가 L2 히트로 L1b 적재. B의 302로 잔존을 확인.
  get(BASE_A, code, 'warmA');
  const warmB = get(BASE_B, code, 'warmB');
  if (warmB.status !== 302) {
    // B가 워밍 안 됐으면 검증이 무의미 → 건너뛴다(예: 복제 전 조회 실패 등).
    return;
  }

  // 삭제(발행). 이 시점 이후 B의 302는 "삭제된 값" = Stale 후보.
  const del = http.del(`${BASE_WRITE}/api/urls/${code}`, null, { tags: { name: 'delete' } });
  check(del, { 'delete 204': (r) => r.status === 204 });
  const t0 = Date.now();

  let seen404OnB = false;
  let convergedThisIter = false;
  for (let i = 0; i < POLL_TRIES; i++) {
    const a = get(BASE_A, code, 'pollA');   // 참조: A는 L1a가 지워졌으므로 복제만 따라오면 404
    const b = get(BASE_B, code, 'pollB');   // 검증 대상

    if (b.status === 404) {
      if (!seen404OnB) {
        seen404OnB = true;
        convergedThisIter = true;
        staleWindowMs.add(Date.now() - t0, tags);
      }
    } else if (b.status === 302) {
      if (seen404OnB) {
        repopulation.add(1, tags);          // 404 뒤 302 재출현 = 재적재 버그
      } else if (a.status === 404) {
        staleReads.add(1, tags);            // A는 이미 404인데 B만 302 = 진짜 L1 분기
      } else {
        dbLagReads.add(1, tags);            // 둘 다 302 = 복제 지연(캐시 아님)
      }
    }

    // 전파 ON이면 수렴 직후 몇 번 더 폴해 재적재만 확인하고 종료. OFF면 예산을 다 써 Stale 지속을 관측.
    if (convergedThisIter && PROP === 'on' && i > 2) break;
    sleep(POLL_INTERVAL_MS / 1000);
  }
  converged.add(seen404OnB ? 1 : 0, tags);
}

/**
 * 재적재 레이스 사냥(단일 인스턴스 A): 동일 키를 동시에 읽는 와중에 삭제를 던진다.
 * get()이 DB 로드 후 뒤늦게 캐시에 쓰는 경로와 invalidate()가 엇갈리면, 삭제된 값이 캐시에 재적재돼
 * 어떤 무효화도 못 지우는 Stale이 남을 수 있다(고전 read/delete 레이스). 삭제 후 A가 404로 유지되는지 본다.
 */
export function raceCheck() {
  const code = createUrl(__ITER);
  if (!code) return;
  get(BASE_A, code, 'warmA');   // 일단 캐시에 올려둔다

  // 삭제와 동시 읽기를 한 배치로 병렬 발사 → invalidate()와 get() 재적재 경로를 겹친다.
  const batch = [['DELETE', `${BASE_A}/api/urls/${code}`, null, { tags: { name: 'raceDelete' } }]];
  for (let r = 0; r < RACE_READERS; r++) {
    batch.push(['GET', `${BASE_A}/${code}`, null, { redirects: 0, tags: { name: 'raceRead' } }]);
  }
  http.batch(batch);

  // 삭제가 확정된 뒤 A는 계속 404여야 한다. 302가 나오면 재적재로 Stale이 굳은 것.
  for (let i = 0; i < 20; i++) {
    const a = get(BASE_A, code, 'raceVerify');
    if (a.status === 302) repopulation.add(1, tags);
    sleep(0.02);
  }
}
