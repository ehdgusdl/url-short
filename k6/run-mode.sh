#!/usr/bin/env bash
#
# 한 모드만 단독 측정한다(레이어드 또는 Redis 단독). 각 실행은 지표를 초기화하고 시작하므로
# Grafana에 그 모드 한 판만 깔끔하게 남는다. k6 지연 지표를 Prometheus로 remote-write 한다.
#
# 사용법:
#   ./k6/run-mode.sh layered      # 레이어드 단독 → 스샷
#   ./k6/run-mode.sh redis        # Redis 단독 → 스샷
#   DURATION=90s ./k6/run-mode.sh layered
#
set -euo pipefail

MODE="${1:-}"
if [ "$MODE" != "layered" ] && [ "$MODE" != "redis" ]; then
  echo "사용법: $0 <layered|redis>" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"

echo "==> 앱 상태 확인"
if ! curl -sf "${BASE_URL}/actuator/health" >/dev/null; then
  echo "!! 앱에 연결할 수 없습니다." >&2
  exit 1
fi

# 이전 데이터 초기화 → 이 모드 한 판만 남는다.
if [ "${RESET:-true}" = "true" ]; then
  "${SCRIPT_DIR}/reset-metrics.sh" || echo "==> (지표 초기화 건너뜀)"
fi

echo ""
echo "########################################################"
echo "#  ${MODE} 단독 측정"
echo "########################################################"
MODE="$MODE" \
DURATION="${DURATION:-120s}" \
K6_PROMETHEUS_RW_SERVER_URL="${K6_PROMETHEUS_RW_SERVER_URL:-http://localhost:9090/api/v1/write}" \
K6_PROMETHEUS_RW_TREND_STATS='p(50),p(90),p(95),p(99),avg,max' \
k6 run -o experimental-prometheus-rw "${SCRIPT_DIR}/redirect-bench.js" || true

echo ""
echo "==> 완료. Grafana(http://localhost:3000)에서 '${MODE}' 한 판을 스샷하세요."
