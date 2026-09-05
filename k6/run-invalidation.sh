#!/usr/bin/env bash
#
# 캐시 무효화 정확성 검증을 순차로 돌린다. k6가 정답지가 되어 삭제 후 Stale 응답을 판정하고,
# 지표를 Prometheus로 remote-write 하여 Grafana 'URL Shortener — Cache Invalidation' 대시보드에서 본다.
#
#   1) 전파 ON  → B가 A와 함께 404로 수렴, stale_reads==0 이어야 합격
#   2) 전파 OFF → 음성대조군. B가 L1에서 Stale 302를 지속(stale_reads>0)해야 대조군 성립
#   3) (선택) 재적재 레이스 → 동일 인스턴스 읽기/삭제 경합에서 재적재(repopulation)==0 확인
#
# 사용법:
#   ./k6/run-invalidation.sh            # on → off 순차
#   RACE=true ./k6/run-invalidation.sh  # 위 + 재적재 레이스까지
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_A="${BASE_A:-http://localhost:8080}"
BASE_B="${BASE_B:-http://localhost:8081}"
RW_URL="${K6_PROMETHEUS_RW_SERVER_URL:-http://localhost:9090/api/v1/write}"

echo "==> 인스턴스 상태 확인 (A=${BASE_A}, B=${BASE_B})"
for base in "$BASE_A" "$BASE_B"; do
  if ! curl -sf "${base}/actuator/health" >/dev/null; then
    echo "!! ${base} 에 연결할 수 없습니다. 'docker compose up -d app app2' 로 두 인스턴스를 먼저 띄우세요." >&2
    exit 1
  fi
done

if [ "${RESET:-true}" = "true" ]; then
  "${SCRIPT_DIR}/reset-metrics.sh" || echo "==> (지표 초기화 건너뜀)"
fi

run_k6() {
  local label="$1"; shift
  echo ""
  echo "########################################################"
  echo "#  ${label}"
  echo "########################################################"
  K6_PROMETHEUS_RW_SERVER_URL="$RW_URL" \
  K6_PROMETHEUS_RW_TREND_STATS='p(50),p(95),p(99),avg,max' \
  BASE_A="$BASE_A" BASE_B="$BASE_B" \
  "$@" k6 run -o experimental-prometheus-rw "${SCRIPT_DIR}/invalidation-verify.js" || true
}

run_k6 "전파 ON — 즉시 무효화 수렴 검증 (합격 기대: stale_reads==0)" env PROP=on TEST=propagation
sleep 3
run_k6 "전파 OFF — 음성대조군, Stale 지속 확인 (기대: stale_reads>0)" env PROP=off TEST=propagation

if [ "${RACE:-false}" = "true" ]; then
  sleep 3
  run_k6 "재적재 레이스 사냥 (기대: repopulation==0)" env TEST=race
fi

echo ""
echo "==> 완료. Grafana(http://localhost:3000) → 'URL Shortener — Cache Invalidation' 에서 확인."
echo "    - stale_reads: 전파 ON 구간 0, OFF 구간 상승"
echo "    - 발행 vs 수신(app2): OFF 구간에서 수신이 발행을 못 따라가는 갭 = 미전파"
