#!/usr/bin/env bash
# 전체 스택 E2E: URL 생성 → 리다이렉트 → Kafka 적재 → ClickHouse 집계 → 핫키 발행 → L1 반영
# 사용법: docker compose up -d --build 후 ./scripts/e2e.sh
set -uo pipefail

NGINX=${NGINX:-http://localhost}
URL_API=${URL_API:-http://localhost:8082}
REDIRECT1=${REDIRECT1:-http://localhost:8080}
REDIRECT2=${REDIRECT2:-http://localhost:8081}
DASHBOARD=${DASHBOARD:-http://localhost:8083}
CLICKHOUSE=${CLICKHOUSE:-http://localhost:8123}
CH_AUTH=${CH_AUTH:-urlshort:urlshort}

pass=0; fail=0
ok()   { echo "  ✓ $1"; pass=$((pass+1)); }
bad()  { echo "  ✗ $1"; fail=$((fail+1)); }
step() { echo; echo "── $1"; }

wait_for() { # url, name, timeout_sec
  local end=$((SECONDS + ${3:-120}))
  until curl -fsS "$1" >/dev/null 2>&1; do
    [ $SECONDS -ge $end ] && { bad "$2 준비 실패 (timeout)"; return 1; }
    sleep 3
  done
  ok "$2 준비됨"
}

step "0. 서비스 헬스 대기"
wait_for "$URL_API/actuator/health"   "url-api"   180
wait_for "$REDIRECT1/actuator/health" "redirect-1" 180
wait_for "$REDIRECT2/actuator/health" "redirect-2" 180
wait_for "$DASHBOARD/actuator/health" "dashboard"  180
wait_for "$CLICKHOUSE/ping"           "clickhouse" 180
wait_for "$NGINX/"                    "nginx"      120

step "1. URL 생성 (Nginx → url-api)"
CODES=()
for i in $(seq 1 3); do
  body=$(curl -fsS -X POST "$NGINX/api/urls" -H 'Content-Type: application/json' \
        -d "{\"originalUrl\":\"https://example.com/e2e-$i\"}")
  code=$(echo "$body" | sed -n 's/.*"shortCode":"\([^"]*\)".*/\1/p')
  [ -n "$code" ] && { CODES+=("$code"); ok "생성됨 $code"; } || bad "생성 실패: $body"
done
[ ${#CODES[@]} -eq 0 ] && { echo "생성된 코드가 없어 중단"; exit 1; }

step "2. 목록 조회"
listed=$(curl -fsS "$NGINX/api/urls" | grep -o '"shortCode"' | wc -l | tr -d ' ')
[ "$listed" -ge 3 ] && ok "목록 $listed 건" || bad "목록이 비었거나 부족함 ($listed)"

step "3. 리다이렉트 (Nginx → redirect 풀). 상위 키를 만들기 위해 첫 코드에 부하를 몰아준다"
HOT=${CODES[0]}
status=$(curl -s -o /dev/null -w '%{http_code}' "$NGINX/$HOT")
loc=$(curl -s -o /dev/null -D - "$NGINX/$HOT" | tr -d '\r' | sed -n 's/^[Ll]ocation: //p')
[ "$status" = "302" ] && ok "302 응답" || bad "302 아님 ($status)"
[ "$loc" = "https://example.com/e2e-1" ] && ok "Location 정확" || bad "Location 불일치 ($loc)"

for _ in $(seq 1 60); do curl -s -o /dev/null "$NGINX/$HOT"; done
for c in "${CODES[@]:1}"; do curl -s -o /dev/null "$NGINX/$c"; done
ok "클릭 60+ 건 발생"

status=$(curl -s -o /dev/null -w '%{http_code}' "$NGINX/zzzzzzz")
[ "$status" = "404" ] && ok "없는 코드 404" || bad "없는 코드가 404가 아님 ($status)"

step "4. Kafka → ClickHouse 적재 (이번 회차 클릭이 집계될 때까지 최대 90초 대기)"
# ClickHouse Kafka Engine은 flush 주기가 있어 즉시 반영되지 않는다.
# 이전 회차 데이터가 남아 있을 수 있으므로 '행 존재'가 아니라 '이번 회차 핫키의 클릭수'를 기다린다.
q="SELECT%20uniqMerge(clicks)%20FROM%20click_daily%20WHERE%20short_code%3D%27$HOT%27"
clicks=0
for _ in $(seq 1 30); do
  clicks=$(curl -fsS -u "$CH_AUTH" "$CLICKHOUSE/?query=$q" 2>/dev/null | tr -d '[:space:]')
  [[ "$clicks" =~ ^[0-9]+$ ]] && [ "$clicks" -ge 50 ] && break
  sleep 3
done
[ "${clicks:-0}" -ge 50 ] && ok "핫키 클릭수 $clicks (event_id 중복 제거 후)" || bad "핫키 클릭수가 예상보다 적음 ($clicks)"

rows=$(curl -fsS -u "$CH_AUTH" "$CLICKHOUSE/?query=SELECT%20count()%20FROM%20click_daily" 2>/dev/null | tr -d '[:space:]')
[ "${rows:-0}" -gt 0 ] && ok "click_daily 행 $rows 건" || bad "ClickHouse에 집계 행이 없음"

step "5. 대시보드 통계 API"
top=$(curl -fsS "$NGINX/api/stats/top?limit=10")
echo "$top" | grep -q "$HOT" && ok "상위 목록에 핫키 포함" || bad "상위 목록에 핫키 없음: $top"
one=$(curl -fsS "$NGINX/api/stats/$HOT")
echo "$one" | grep -q '"clicks"' && ok "단일 조회 응답 정상" || bad "단일 조회 실패: $one"

step "6. 핫키 발행 → redirect L1 반영 (최대 120초 대기)"
applied=0
for _ in $(seq 1 40); do
  v1=$(curl -fsS "$REDIRECT1/actuator/l1cache" | sed -n 's/.*"hotKeyVersion":\([0-9-]*\).*/\1/p')
  v2=$(curl -fsS "$REDIRECT2/actuator/l1cache" | sed -n 's/.*"hotKeyVersion":\([0-9-]*\).*/\1/p')
  if [ "${v1:--1}" -ge 1 ] && [ "${v2:--1}" -ge 1 ]; then applied=1; break; fi
  sleep 3
done
if [ "$applied" = "1" ]; then
  ok "두 redirect 인스턴스 모두 핫키 수신 (version=$v1 / $v2)"
  s1=$(curl -fsS "$REDIRECT1/actuator/l1cache" | sed -n 's/.*"hotKeySize":\([0-9]*\).*/\1/p')
  [ "${s1:-0}" -ge 1 ] && ok "핫키 목록 크기 $s1" || bad "핫키 목록이 비어 있음"
  pub=$(curl -fsS "$DASHBOARD/api/stats/hotkeys")
  ok "dashboard 발행 상태: $pub"
else
  bad "핫키가 redirect에 반영되지 않음 (v1=$v1 v2=$v2). dashboard 스케줄러 주기를 확인"
fi

step "7. 삭제 → 무효화 전파"
del=${CODES[${#CODES[@]}-1]}
curl -s -o /dev/null -X DELETE "$NGINX/api/urls/$del"
sleep 2
s1=$(curl -s -o /dev/null -w '%{http_code}' "$REDIRECT1/$del")
s2=$(curl -s -o /dev/null -w '%{http_code}' "$REDIRECT2/$del")
{ [ "$s1" = "404" ] && [ "$s2" = "404" ]; } && ok "두 인스턴스 모두 즉시 404" || bad "Stale 응답 (r1=$s1 r2=$s2)"

echo
echo "════════════════════════════════"
echo " 통과 $pass · 실패 $fail"
echo "════════════════════════════════"
[ "$fail" -eq 0 ]
