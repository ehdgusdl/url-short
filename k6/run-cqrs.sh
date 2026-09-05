#!/usr/bin/env bash
#
# CQRS 읽기/쓰기 격리 시나리오를 실행한다.
# 전 구간 cold-key 읽기(Replica) + 중간 구간 URL 생성 burst(Primary)를 겹쳐
# "쓰기 폭증에도 읽기 지연이 흔들리지 않는가"를 Grafana에서 확인한다.
#
# 사용법:
#   ./k6/run-cqrs.sh
#   READ_RATE=2000 WRITE_RATE=600 DURATION=120s ./k6/run-cqrs.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"

echo "==> 앱 상태 확인: ${BASE_URL}/actuator/health"
if ! curl -sf "${BASE_URL}/actuator/health" >/dev/null; then
  echo "!! 앱에 연결할 수 없습니다. 스택을 먼저 띄우세요 (docker compose up -d)." >&2
  exit 1
fi

# 이전 벤치 잔상 제거 → Grafana에 이번 한 판만 남는다. (RESET=false 로 끌 수 있음)
if [ "${RESET:-true}" = "true" ]; then
  "${SCRIPT_DIR}/reset-metrics.sh" || echo "==> (지표 초기화 건너뜀 — Prometheus admin API 확인)"
fi

echo ""
echo "########################################################"
echo "#  CQRS 격리 시나리오"
echo "#  read(cold-key, Replica) 전 구간 + write burst(Primary) 중간"
echo "########################################################"
READ_RATE="${READ_RATE:-1500}" \
WRITE_RATE="${WRITE_RATE:-400}" \
DURATION="${DURATION:-90s}" \
BURST_START="${BURST_START:-30s}" \
BURST_DUR="${BURST_DUR:-25s}" \
K6_PROMETHEUS_RW_SERVER_URL="${K6_PROMETHEUS_RW_SERVER_URL:-http://localhost:9090/api/v1/write}" \
K6_PROMETHEUS_RW_TREND_STATS='p(50),p(90),p(95),p(99),avg,max' \
k6 run -o experimental-prometheus-rw "${SCRIPT_DIR}/cqrs-isolation.js" || true

echo ""
echo "==> 완료. Grafana(http://localhost:3000)에서 확인하세요:"
echo "    대시보드: 'URL Shortener — CQRS 라우팅 & 읽기/쓰기 격리'"
echo "    - 패널②: write TPS 봉우리 구간에도 read p99가 평평한가 (격리성)"
echo "    - 패널①: read 구간 Replica ~100%, burst 구간 Primary 혼입"
echo "    - 패널③: burst 때 primary-pool active만 솟고 replica는 꾸준한가"
