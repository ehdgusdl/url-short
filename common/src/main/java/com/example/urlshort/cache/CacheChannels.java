package com.example.urlshort.cache;

/**
 * 서비스 경계를 넘어 공유되는 캐시 키 접두사와 Redis Pub/Sub 채널 이름.
 *
 * <p>url-api(발행) ↔ redirect(구독), dashboard(발행) ↔ redirect(구독)가 같은 문자열을 써야 하므로
 * 문자열을 각 모듈에 흩뿌리지 않고 여기 한 곳에서만 정의한다.
 */
public final class CacheChannels {

    /** L2(Redis)에 UrlView를 저장할 때 쓰는 키 접두사. */
    public static final String KEY_PREFIX = "url:";

    /**
     * 삭제 직후 복제 지연 구간을 표시하는 묘비(tombstone) 키 접두사.
     *
     * <p>삭제는 Primary에 반영되지만 Replica는 잠깐 옛 행을 갖고 있다. 그 사이 조회가 미스로 내려가
     * Replica의 옛 행을 읽어 L2에 다시 채우면, 무효화 메시지는 이미 지나갔으므로 L2 TTL(기본 1h) 내내
     * 삭제된 URL이 계속 302를 반환한다. 묘비가 그 구간을 덮는다.
     *
     * <p>{@link #KEY_PREFIX} 아래에 두면 안 된다. 전체 무효화가 {@code url:*} 를 훑어 지울 때
     * 살아 있는 묘비까지 같이 지워, 묘비가 막으려던 되살아남이 그대로 일어난다.
     */
    public static final String TOMBSTONE_PREFIX = "urlgone:";

    /**
     * 없는 코드로 확인된 구간을 표시하는 음성 캐시 키 접두사.
     *
     * <p>없는 코드는 핫키 목록에 없으니 L1을 건너뛰고, L2에도 값이 없으니 매 요청이 그대로 Replica로
     * 내려간다. 무작위 코드를 훑는 트래픽이 캐시를 하나도 거치지 않고 DB에 도달한다는 뜻이다.
     * 미스도 짧게 기억해 두 계층 중 하나에서 흡수한다. 생성 시 {@link #KEY_PREFIX} 선입력과 함께 지운다.
     *
     * <p>{@link #KEY_PREFIX} 아래에 두면 안 되는 이유는 {@link #TOMBSTONE_PREFIX}와 같다.
     */
    public static final String MISS_PREFIX = "urlmiss:";

    /** 캐시 무효화 메시지 채널. 본문은 shortCode 또는 {@link #INVALIDATE_ALL}. */
    public static final String INVALIDATION_CHANNEL = "url-cache:invalidation";

    /** 전체 무효화를 뜻하는 메시지 본문. */
    public static final String INVALIDATE_ALL = "__ALL__";

    /** 핫키 목록 발행 채널. 본문은 {"version":n,"keys":[...]} JSON 스냅샷. */
    public static final String HOTKEY_CHANNEL = "url-cache:hotkeys";

    private CacheChannels() {
    }
}
