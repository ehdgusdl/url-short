#!/usr/bin/env bash
#
# 무효화 구독이 끊긴 사이 삭제된 URL이 L1에서 얼마나 오래 302되는지 잰다.
#
# 흐름:
#  1) 두 redirect의 admission을 끄고(전량 L1 적재) URL 하나를 만들어 양쪽 L1에 올린다.
#  2) toxiproxy `redis` 프록시를 끈다. redirect↔Redis만 끊기고 url-api는 Redis에 직결이다.
#  3) url-api로 삭제한다. L2는 지워지고 무효화 메시지는 발행되지만 redirect는 못 받는다.
#  4) OUTAGE초 뒤 프록시를 다시 켠다.
#  5) 양쪽 인스턴스가 모두 404/410을 줄 때까지 0.5초마다 조회한다.
#
# 출력: 삭제 → 양쪽 404까지(stale 총 구간), 재연결 → 양쪽 404까지.
#
# 사용법: ./k6/run-stale-after-reconnect.sh [OUTAGE초=10] [최대대기초=420]
# 전제: 스택 기동, redirect-1이 REDIRECT_1(기본 :18080), redirect-2가 REDIRECT_2(기본 :8081).
set -euo pipefail
OUTAGE="${1:-10}"
MAX_WAIT="${2:-420}"
TOXI="${TOXIPROXY_API:-http://localhost:8474}"
WRITE="${WRITE_URL:-http://localhost:8082}"
R1="${REDIRECT_1:-http://localhost:18080}"
R2="${REDIRECT_2:-http://localhost:8081}"

status() { curl -s -o /dev/null -w '%{http_code}' --max-time 70 "$1"; }
now() { python3 -c 'import time; print(time.time())'; }
admission() { for r in "$R1" "$R2"; do curl -s -X POST "$r/actuator/l1cache" -H 'Content-Type: application/json' -d "{\"admission\":$1}" >/dev/null; done; }
proxy() { curl -s -X POST "$TOXI/proxies/redis" -H 'Content-Type: application/json' -d "{\"enabled\":$1}" >/dev/null; }

# 중간에 끊겨도 프록시와 admission은 원래대로 돌려 둔다.
trap 'proxy true; admission true' EXIT

admission false
CODE=$(curl -s -X POST "$WRITE/api/urls" -H 'Content-Type: application/json' \
  -d "{\"originalUrl\":\"https://example.com/stale/$(date +%s)\"}" | python3 -c 'import sys,json; print(json.load(sys.stdin)["shortCode"])')
for _ in 1 2; do status "$R1/$CODE" >/dev/null; status "$R2/$CODE" >/dev/null; done
echo "code=$CODE  r1=$(status "$R1/$CODE") r2=$(status "$R2/$CODE")  (302면 L1 적재 완료)"

proxy false
sleep 1
T_DEL=$(now)
echo "delete -> $(curl -s -o /dev/null -w '%{http_code}' -X DELETE "$WRITE/api/urls/$CODE")"
sleep "$OUTAGE"
proxy true
T_UP=$(now)
echo "proxy re-enabled after ${OUTAGE}s outage"

while :; do
  s1=$(status "$R1/$CODE"); s2=$(status "$R2/$CODE")
  T=$(now)
  if [[ "$s1" =~ ^(404|410)$ && "$s2" =~ ^(404|410)$ ]]; then break; fi
  if python3 -c "import sys; sys.exit(0 if $T - $T_DEL > $MAX_WAIT else 1)"; then
    echo "!! ${MAX_WAIT}s 안에 404가 안 됨 (r1=$s1 r2=$s2)"; exit 1
  fi
  sleep 0.5
done
python3 - "$T_DEL" "$T_UP" "$T" <<'EOF'
import sys
d, u, t = map(float, sys.argv[1:])
print(f"stale total (delete -> both 404): {t-d:.1f}s")
print(f"after reconnect (proxy up -> both 404): {t-u:.1f}s")
EOF
