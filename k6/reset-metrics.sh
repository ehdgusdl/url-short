#!/usr/bin/env bash
#
# Prometheus에 쌓인 이전 벤치 데이터를 지운다(그래프에서 옛 봉우리 잔상 제거).
# Prometheus가 --web.enable-admin-api 로 떠 있어야 한다(docker-compose에 설정됨).
#
# 사용법: ./k6/reset-metrics.sh
#
set -euo pipefail
PROM="${PROMETHEUS_URL:-http://localhost:9090}"

if ! curl -sf -o /dev/null -X POST "$PROM/api/v1/admin/tsdb/delete_series" \
      --data-urlencode 'match[]={__name__=~"http_server_requests_seconds.*|urlcache_.*|redis_.*|jvm_.*|process_cpu_usage|system_cpu_usage|k6_.*"}'; then
  echo "!! 지표 초기화 실패. Prometheus admin API가 켜져 있는지 확인하세요 (docker compose up -d --force-recreate prometheus)." >&2
  exit 1
fi
curl -s -X POST "$PROM/api/v1/admin/tsdb/clean_tombstones" >/dev/null 2>&1 || true
echo "==> 이전 벤치 지표 초기화 완료"
