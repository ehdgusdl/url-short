#!/usr/bin/env bash
#
# Redis 단독 vs 레이어드 캐시 벤치마크를 순차 실행한다.
# 두 구간 사이에 간격을 둬서 Grafana에서 두 시계열 창으로 명확히 구분되게 한다.
#
# 사용법:
#   ./k6/run-benchmark.sh
#   BASE_URL=http://localhost:8080 VUS=100 DURATION=90s ./k6/run-benchmark.sh
#
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GAP="${GAP:-20}"   # 두 벤치 사이 간격(초)

echo "==> 앱 상태 확인: ${BASE_URL}/actuator/health"
if ! curl -sf "${BASE_URL}/actuator/health" >/dev/null; then
  echo "!! 앱에 연결할 수 없습니다. gradle bootRun 등으로 앱을 먼저 띄우세요." >&2
  exit 1
fi

# 이전 벤치 잔상 제거 → Grafana에 이번 실행 한 판만 남는다. (RESET=false 로 끌 수 있음)
if [ "${RESET:-true}" = "true" ]; then
  "${SCRIPT_DIR}/reset-metrics.sh" || echo "==> (지표 초기화 건너뜀 — Prometheus admin API 확인)"
fi

echo ""
echo "########################################################"
echo "# 1/2  LAYERED (L1 + L2) 모드"
echo "########################################################"
MODE=layered k6 run "${SCRIPT_DIR}/redirect-bench.js" || true

echo ""
echo "==> ${GAP}s 대기 (Grafana에서 두 구간 구분용)"
sleep "${GAP}"

echo ""
echo "########################################################"
echo "# 2/2  REDIS-ONLY (L2 만) 모드"
echo "########################################################"
MODE=redis k6 run "${SCRIPT_DIR}/redirect-bench.js" || true

echo ""
echo "==> 완료. Grafana에서 두 구간을 비교하세요: http://localhost:3000"
echo "    (대시보드: 'URL Shortener — Redis vs Layered Cache')"
echo "==> 벤치 후 기본 모드(레이어드)로 되돌립니다."
curl -sf -X POST "${BASE_URL}/actuator/l1cache" \
  -H 'Content-Type: application/json' -d '{"enabled": true}' >/dev/null && echo "    L1 재활성화 완료."
