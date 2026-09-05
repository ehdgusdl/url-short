#!/usr/bin/env bash
#
# MySQL Replica에 '지연 복제(delayed replication)'를 주입해 Replication Lag를 결정적으로 재현한다.
# SOURCE_DELAY=N 이면 Replica가 Primary의 트랜잭션을 N초 늦게 적용한다 → 그 구간 동안 방금 생성된
# 행이 Replica에는 '실제로 부재'하므로, 생성 직후 Replica 조회가 진짜 404를 낸다.
#
# (toxiproxy 네트워크 지연과의 차이: 네트워크 지연은 조회를 '느리게' 만들 뿐 행은 결국 반환한다.
#  404는 '느린 조회'가 아니라 '행 부재'에서 나오므로, 지연 복제만이 이 문제를 정확히 재현한다.)
#
# 사용법:
#   ./k6/set-replica-lag.sh 1      # 1초 지연 복제 주입(생성 직후 ~1s간 Replica에 행 부재)
#   ./k6/set-replica-lag.sh 0      # 지연 제거(복제 즉시 따라잡기)
#
# 인자: $1 = delay(초, 정수). SOURCE_DELAY는 초 단위이므로 최소 1s.
# 환경변수: REPLICA_SVC(=mysql-replica), MYSQL_ROOT_PW(=rootpw)
set -euo pipefail

DELAY="${1:-1}"
REPLICA_SVC="${REPLICA_SVC:-mysql-replica}"
ROOT_PW="${MYSQL_ROOT_PW:-rootpw}"

if ! [[ "$DELAY" =~ ^[0-9]+$ ]]; then
  echo "!! delay는 0 이상의 정수(초)여야 합니다. 예: ./k6/set-replica-lag.sh 1" >&2
  exit 1
fi

# 지연 복제 재설정에는 SQL 스레드 정지가 필요하다. 전체 STOP/START로 단순화한다.
SQL="STOP REPLICA; CHANGE REPLICATION SOURCE TO SOURCE_DELAY=${DELAY}; START REPLICA;"

echo "==> ${REPLICA_SVC}에 SOURCE_DELAY=${DELAY}s 적용"
if ! docker compose exec -T "${REPLICA_SVC}" mysql -uroot -p"${ROOT_PW}" -e "${SQL}" 2>/dev/null; then
  echo "!! Replica에 접속할 수 없습니다. 스택이 떠 있는지 확인하세요 (docker compose up -d ${REPLICA_SVC})." >&2
  exit 1
fi

# 적용 결과 확인(SQL_Delay, 초).
docker compose exec -T "${REPLICA_SVC}" mysql -uroot -p"${ROOT_PW}" \
  -e "SHOW REPLICA STATUS\G" 2>/dev/null \
  | grep -E "SQL_Delay|Replica_SQL_Running:|Seconds_Behind_Source" || true

if [ "$DELAY" = "0" ]; then
  echo "==> 지연 복제 제거됨(즉시 복제)."
else
  echo "==> 지연 복제 ${DELAY}s 주입됨. 이제 생성 직후 Replica 조회는 ~${DELAY}s간 404가 난다."
fi
