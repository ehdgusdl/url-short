#!/usr/bin/env bash
#
# Redis 장애 모드별로 k6/redis-fault.js 를 돌리고 요약을 남긴다.
#
# 사용법: ./k6/run-redis-fault.sh <라벨> [모드...]
#   ./k6/run-redis-fault.sh before                 # none latency timeout down 전부
#   ./k6/run-redis-fault.sh after timeout down     # 일부만
# 결과: ${OUT_DIR:-k6/results}/redis-fault-<라벨>-<모드>.json (k6 summary export)
# 전제: 스택 기동. 모드 사이에 20초 쉬어 Lettuce 재연결과 스레드 회복을 기다린다.
set -euo pipefail
LABEL="${1:?라벨(before|after)}"; shift
if [[ $# -eq 0 ]]; then MODES=(none latency timeout down); else MODES=("$@"); fi
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="${OUT_DIR:-$DIR/results}"; mkdir -p "$OUT"

for m in "${MODES[@]}"; do
  echo "=== $LABEL / $m ==="
  k6 run -q -e FAULT="$m" --summary-export "$OUT/redis-fault-$LABEL-$m.json" "$DIR/redis-fault.js" || true
  sleep 20
done
