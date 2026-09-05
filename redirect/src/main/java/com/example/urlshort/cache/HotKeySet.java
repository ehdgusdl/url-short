package com.example.urlshort.cache;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * dashboard가 발행한 상위 조회 키 목록. L1 적재를 이 목록으로 제한(admission)한다.
 *
 * <p>로컬 eviction(LRU·TinyLFU)은 그 인스턴스가 본 요청만 알기 때문에 인스턴스마다 캐시가 갈리고
 * 새로 뜬 인스턴스는 콜드 스타트가 된다. 전 인스턴스가 같은 실측 랭킹을 공유하도록 목록을 밖에서 받는다.
 *
 * <p>목록을 아직 한 번도 받지 못했으면 전부 허용한다. 그렇지 않으면 dashboard가 뜨기 전까지
 * L1이 아무것도 담지 못해 캐시가 통째로 무용지물이 된다.
 */
@Component
public class HotKeySet {

    private volatile Set<String> keys = Set.of();

    /**
     * admission 사용 여부. 벤치마크에서 "전량 적재 vs 상위 키만" 을 재시작 없이 대조하기 위한 스위치다.
     * 끄면 핫키 목록과 무관하게 전부 허용한다.
     */
    private final java.util.concurrent.atomic.AtomicBoolean enabled =
            new java.util.concurrent.atomic.AtomicBoolean(true);
    private final AtomicLong version = new AtomicLong(-1);
    private final AtomicInteger size = new AtomicInteger();

    public HotKeySet(MeterRegistry registry) {
        registry.gauge("urlcache.hotkeys.size", size);
        registry.gauge("urlcache.hotkeys.version", version);
        registry.gauge("urlcache.hotkeys.admission.enabled", enabled, b -> b.get() ? 1.0 : 0.0);
    }

    /** 이 키를 L1에 담아도 되는가. 목록이 비어 있으면(=아직 미수신) 전부 허용. */
    public boolean admits(String shortCode) {
        if (!enabled.get()) {
            return true;
        }
        Set<String> snapshot = keys;
        return snapshot.isEmpty() || snapshot.contains(shortCode);
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    /** admission 런타임 토글(벤치 대조군). */
    public void setEnabled(boolean value) {
        enabled.set(value);
    }

    /** 발행된 스냅샷으로 통째 교체. 오래된(작거나 같은) 버전은 무시한다. */
    public synchronized boolean replace(long newVersion, Set<String> newKeys) {
        if (newVersion <= version.get()) {
            return false;
        }
        this.keys = Set.copyOf(newKeys);
        this.version.set(newVersion);
        this.size.set(newKeys.size());
        return true;
    }

    /**
     * 바뀐 것만 적용한다. 목록의 대부분은 주기마다 그대로이므로 통째 교체는 낭비다.
     *
     * <p>{@code baseVersion}이 지금 갖고 있는 버전과 다르면 중간 메시지를 놓친 것이다.
     * 그 상태에서 적용하면 목록이 조용히 어긋나므로 버리고 다음 전체 스냅샷을 기다린다.
     */
    public synchronized boolean applyDelta(long newVersion, long baseVersion,
                                           Set<String> add, Set<String> remove) {
        if (baseVersion != version.get() || newVersion <= version.get()) {
            return false;
        }
        Set<String> next = new java.util.HashSet<>(keys);
        next.addAll(add);
        next.removeAll(remove);
        this.keys = Set.copyOf(next);
        this.version.set(newVersion);
        this.size.set(next.size());
        return true;
    }

    public long version() {
        return version.get();
    }

    public int size() {
        return size.get();
    }
}
