-- Primary 초기화: 복제용 계정 생성.
-- 이 스크립트는 Primary 최초 부팅 시 한 번 실행되며, 여기서 생성한 계정/DB(urlshort)는
-- GTID 기반 복제를 통해 Replica로 전파된다.
CREATE USER IF NOT EXISTS 'repl'@'%' IDENTIFIED WITH mysql_native_password BY 'replpw';
GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%';
FLUSH PRIVILEGES;
