#!/usr/bin/env bash
#
# app↔Redis 경로에 네트워크 지연(RTT)을 주입해 '원격 Redis'를 흉내낸다.
# toxiproxy 프록시(redis)에 downstream latency toxic을 건다 → Redis 응답마다 지연이 붙는다.
#
# 사용법:
#   ./k6/set-redis-latency.sh 1        # 1ms 지연 (same-AZ 원격 Redis 수준)
#   ./k6/set-redis-latency.sh 3 1      # 3ms 지연 + 1ms 지터 (cross-AZ + 부하 수준)
#   ./k6/set-redis-latency.sh 0        # 지연 제거
#
# 인자: $1 = latency(ms), $2 = jitter(ms, 기본 0)
set -euo pipefail
API="${TOXIPROXY_API:-http://localhost:8474}"
MS="${1:-1}"
JITTER="${2:-0}"

if ! curl -sf "$API/proxies/redis" >/dev/null 2>&1; then
  echo "!! toxiproxy 프록시 'redis'를 찾을 수 없습니다. 'docker compose up -d toxiproxy' 먼저 실행하세요." >&2
  exit 1
fi

# 기존 지연 toxic 제거(멱등)
curl -s -X DELETE "$API/proxies/redis/toxics/latency_down" >/dev/null 2>&1 || true

if [ "$MS" = "0" ]; then
  echo "Redis 지연 제거됨 (직결 수준)"
  exit 0
fi

curl -s -X POST "$API/proxies/redis/toxics" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"latency_down\",\"type\":\"latency\",\"stream\":\"downstream\",\"attributes\":{\"latency\":${MS},\"jitter\":${JITTER}}}" >/dev/null

echo "Redis 응답 지연 ${MS}ms (지터 ${JITTER}ms) 주입됨 (app↔Redis, downstream)"
