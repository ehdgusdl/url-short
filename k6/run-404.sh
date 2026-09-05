#!/usr/bin/env bash
#
# 생성 직후 404 검증을 'Before(음성대조군) → After(적용군)' 순으로 한 타임라인에 실행한다.
#   Before: 선입력·백스톱 모두 off → 생성 직후 404 재현(높은 고원)
#   After : 선입력만 on(백스톱 off) → 404가 0으로 수렴(L2 선입력이 흡수)
# 두 구간은 Prometheus를 초기화하지 않고 이어 붙여, writeguard 게이지 전환점을 기준으로 before/after를 대조한다.
#
# 전제: 지연 복제가 켜져 있어야 Before의 404가 재현된다(이 스크립트가 자동 주입).
#
# 사용법:
#   ./k6/run-404.sh                 # LAG=1s, before(60s) → after(60s)
#   LAG=2 RATE=300 DURATION=45s ./k6/run-404.sh
#   MODES="after" ./k6/run-404.sh   # after만 실행(선입력 유지 상태 확인 등)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"
LAG="${LAG:-1}"
MODES="${MODES:-before after}"

echo "==> 앱 상태 확인: ${BASE_URL}/actuator/health"
if ! curl -sf "${BASE_URL}/actuator/health" >/dev/null; then
  echo "!! 앱에 연결할 수 없습니다. 스택을 먼저 띄우세요 (docker compose up -d)." >&2
  exit 1
fi

# 1) 지연 복제 주입 → Replica가 LAG초 늦게 적용 → 생성 직후 조회 시 행 부재로 404 재현 가능.
"${SCRIPT_DIR}/set-replica-lag.sh" "${LAG}"

# 2) 워밍업(측정 제외): JVM JIT + HikariCP 커넥션 풀을 예열한다.
#    1 vCPU 앱에 constant-arrival-rate를 Cold 상태로 꽂으면 풀 고갈(pending)로 생성 p99가 초 단위까지
#    튀어 패널③(쓰기 지연)을 오염시킨다. 예열 후 측정해야 before/after 생성 지연이 '겹치는 수평선'으로
#    나와 'Write-Through가 쓰기 경로에 지연을 더하지 않는다'가 정직하게 증명된다. WARMUP=0 으로 끌 수 있음.
if [ "${WARMUP:-20s}" != "0" ]; then
  echo "==> 워밍업 ${WARMUP:-20s} (JVM/커넥션풀 예열, 측정 제외)"
  MODE=after BASE_URL="${BASE_URL}" RATE="${RATE:-200}" DURATION="${WARMUP:-20s}" \
    k6 run "${SCRIPT_DIR}/immediate-read-404.js" >/dev/null 2>&1 || true
fi

# 3) 이전 벤치 + 워밍업 잔상 제거(한 판만 남긴다). RESET=false 로 끌 수 있음.
if [ "${RESET:-true}" = "true" ]; then
  "${SCRIPT_DIR}/reset-metrics.sh" || echo "==> (지표 초기화 건너뜀 — Prometheus admin API 확인)"
fi

for MODE in ${MODES}; do
  echo ""
  echo "########################################################"
  echo "#  생성 직후 404 검증 — MODE=${MODE} (LAG=${LAG}s)"
  echo "########################################################"
  MODE="${MODE}" \
  BASE_URL="${BASE_URL}" \
  RATE="${RATE:-200}" \
  DURATION="${DURATION:-60s}" \
  K6_PROMETHEUS_RW_SERVER_URL="${K6_PROMETHEUS_RW_SERVER_URL:-http://localhost:9090/api/v1/write}" \
  K6_PROMETHEUS_RW_TREND_STATS='p(50),p(95),p(99),avg,max' \
  k6 run -o experimental-prometheus-rw "${SCRIPT_DIR}/immediate-read-404.js" || true
done

# 4) 선입력을 운영 기본값(on)으로 복구해 스택을 정상 상태로 남긴다.
echo ""
echo "==> writeguard를 운영 기본값(prime=on)으로 복구"
curl -sf -X POST "${BASE_URL}/actuator/writeguard" \
  -H 'Content-Type: application/json' \
  -d '{"prime":true}' >/dev/null && echo "    복구 완료" || echo "    복구 실패(수동 확인 필요)"

echo ""
echo "==> 완료. Grafana(http://localhost:3000)에서 확인하세요:"
echo "    대시보드: 'URL Shortener — 생성 직후 404 (Write-Through)'"
echo "    - 패널①: before 구간 404율 높은 고원 → after 구간 0 일직선"
echo "    - 패널②: after 진입 시 L2 히트 ↑ / Replica 조회 ↓ 의 X교차"
echo "    - 패널③: POST /api/urls p99가 before/after 겹치는 수평선(쓰기 지연 무증가)"
echo ""
echo "==> 마무리로 지연 복제를 제거하려면: ./k6/set-replica-lag.sh 0"
