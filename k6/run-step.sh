#!/usr/bin/env bash
#
# 계단식 대조 실험: 동일 TPS를 유지한 채 측정 중간에 L1을 꺼서
# 레이어드(L1+L2) → Redis 단독(L2) 전환을 한 그래프에 담는다.
#
#   [앞 구간] L1 ON  : L1이 흡수, 지연 낮음, Redis I/O 바닥
#   [전환]    L1 OFF : /actuator/l1cache
#   [뒤 구간] L1 OFF : 전량 Redis, 지연·Redis I/O 계단식 상승
#
# 통제 변인: 도착률(RATE)을 포화 아래로 고정 → 지연 변화가 오직 캐시 구조 차이.
#
# 사용법:  RATE=3000 HALF=120 ./k6/run-step.sh
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"
RATE="${RATE:-3000}"
HALF="${HALF:-120}"                       # 각 구간(레이어드/Redis단독) 지속(초)
WARMUP_DURATION="${WARMUP_DURATION:-20s}"
LOG="${LOG:-/tmp/k6step.log}"

echo "==> 앱 확인"; curl -sf "$BASE_URL/actuator/health" >/dev/null || { echo "!! 앱 다운" >&2; exit 1; }

# 이전 데이터 초기화 + 레이어드로 시작(L1 ON)
"${SCRIPT_DIR}/reset-metrics.sh" || true
curl -s -X POST "$BASE_URL/actuator/l1cache" -H 'Content-Type: application/json' -d '{"enabled":true}' >/dev/null
echo "==> L1 ON (레이어드로 시작), RATE=${RATE} req/s, 각 구간 ${HALF}s"

DUR="$((HALF*2))s"
MODE=layered RATE="$RATE" DURATION="$DUR" WARMUP_DURATION="$WARMUP_DURATION" \
K6_PROMETHEUS_RW_SERVER_URL="http://localhost:9090/api/v1/write" \
K6_PROMETHEUS_RW_TREND_STATS='p(50),p(90),p(95),p(99),avg,max' \
k6 run -o experimental-prometheus-rw "${SCRIPT_DIR}/redirect-bench.js" > "$LOG" 2>&1 &
K6PID=$!

# setup(시드+워밍) 끝날 때까지 대기 → 그 뒤 warmup 스테이지 + 앞 구간(HALF) 지나면 전환
until grep -q "cache warmed" "$LOG" 2>/dev/null; do
  kill -0 "$K6PID" 2>/dev/null || { echo "!! k6 조기 종료"; cat "$LOG"; exit 1; }
  sleep 1
done
WU="$(echo "$WARMUP_DURATION" | sed 's/s//')"
echo "==> setup 완료. warmup ${WU}s + 앞 구간 ${HALF}s 후 L1 OFF 전환"
sleep "$((WU + HALF))"

# ── 전환: L1 OFF → Redis 단독 (계단!) ──
curl -s -X POST "$BASE_URL/actuator/l1cache" -H 'Content-Type: application/json' -d '{"enabled":false}' >/dev/null
echo "==> [전환] $(date '+%H:%M:%S')  L1 OFF → Redis 단독"

wait "$K6PID"
# 실험 후 L1 복구
curl -s -X POST "$BASE_URL/actuator/l1cache" -H 'Content-Type: application/json' -d '{"enabled":true}' >/dev/null
echo "==> 완료. Grafana에서 전환 시점 계단 확인(L1→L2 교차, 지연·Redis I/O 급상승). L1 복구됨."
