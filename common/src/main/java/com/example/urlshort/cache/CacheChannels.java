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

    /** 캐시 무효화 메시지 채널. 본문은 shortCode 또는 {@link #INVALIDATE_ALL}. */
    public static final String INVALIDATION_CHANNEL = "url-cache:invalidation";

    /** 전체 무효화를 뜻하는 메시지 본문. */
    public static final String INVALIDATE_ALL = "__ALL__";

    /** 핫키 목록 발행 채널. 본문은 {"version":n,"keys":[...]} JSON 스냅샷. */
    public static final String HOTKEY_CHANNEL = "url-cache:hotkeys";

    private CacheChannels() {
    }
}
