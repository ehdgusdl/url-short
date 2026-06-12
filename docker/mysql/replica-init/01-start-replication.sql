-- Replica 초기화: Primary를 복제 소스로 지정하고 복제를 시작한다.
-- depends_on(service_healthy)으로 Primary가 준비된 뒤 실행되며, GTID auto-position으로
-- urlshort DB/계정 생성을 포함한 모든 트랜잭션을 자동으로 따라잡는다.
CHANGE REPLICATION SOURCE TO
    SOURCE_HOST = 'mysql-primary',
    SOURCE_PORT = 3306,
    SOURCE_USER = 'repl',
    SOURCE_PASSWORD = 'replpw',
    SOURCE_AUTO_POSITION = 1,
    GET_SOURCE_PUBLIC_KEY = 1;
START REPLICA;
