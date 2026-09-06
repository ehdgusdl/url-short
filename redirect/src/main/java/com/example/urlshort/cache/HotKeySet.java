package com.example.urlshort.cache;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * dashboard가 발행한 상위 조회 키 목록. L1 적재를 이 목록으로 제한(admission)한다.
 *
 * <p>로컬 eviction(LRU·TinyLFU)은 그 인스턴스가 본 요청만 알기 때문에 인스턴스마다 캐시가 갈리고
 * 새로 뜬 인스턴스는 콜드 스타트가 된다. 전 인스턴스가 같은 실측 랭킹을 공유하도록 목록을 밖에서 받는다.
 *
 * <p>목록을 아직 한 번도 받지 못했으면 기동 후 유예 시간 동안만 전부 허용한다. 그렇지 않으면
 * dashboard가 뜨기 전까지 L1이 아무것도 담지 못해 캐시가 통째로 무용지물이 된다.
 *
 * <p>유예를 끝없이 두면 반대쪽으로 터진다. dashboard가 죽거나 ClickHouse가 아직 집계를 못 하면
 * 목록이 영영 비어 있고, 그 동안 L1은 조건 없는 적재로 되돌아가 이 구조가 막으려던 힙 증가를
 * 그대로 다시 만든다. 게다가 지표상으로는 "아직 시작 안 함"과 구분되지 않는다.
 * 유예가 지나면 닫는다. L1을 못 쓰는 건 느려지는 것이고, 무한히 담는 건 죽는 것이다.
 */
@Component
public class HotKeySet {

    private static final Logger log = LoggerFactory.getLogger(HotKeySet.class);

    private volatile Set<String> keys = Set.of();

    /** 목록을 한 번도 못 받았을 때 전부 허용할 시간. 지나면 닫는다. */
    private final Duration grace;
    private final long startedAtNanos = System.nanoTime();
    private final java.util.concurrent.atomic.AtomicBoolean graceExpiryLogged =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * admission 사용 여부. 벤치마크에서 "전량 적재 vs 상위 키만" 을 재시작 없이 대조하기 위한 스위치다.
     * 끄면 핫키 목록과 무관하게 전부 허용한다.
     */
    private final java.util.concurrent.atomic.AtomicBoolean enabled =
            new java.util.concurrent.atomic.AtomicBoolean(true);
    private final AtomicLong version = new AtomicLong(-1);
    private final AtomicInteger size = new AtomicInteger();

    public HotKeySet(@Value("${app.cache.hotkey-grace:2m}") Duration grace, MeterRegistry registry) {
        this.grace = grace;
        registry.gauge("urlcache.hotkeys.size", size);
        // 목록 없이 전부 허용 중인지. "아직 시작 안 함"과 "dashboard 가 죽었음"을 구분하기 위한 것이다.
        registry.gauge("urlcache.hotkeys.grace.active", this, s -> s.inGrace() ? 1.0 : 0.0);
        registry.gauge("urlcache.hotkeys.version", version);
        registry.gauge("urlcache.hotkeys.admission.enabled", enabled, b -> b.get() ? 1.0 : 0.0);
    }

    /** 이 키를 L1에 담아도 되는가. 목록이 비어 있으면 유예 시간 안에서만 전부 허용한다. */
    public boolean admits(String shortCode) {
        if (!enabled.get()) {
            return true;
        }
        Set<String> snapshot = keys;
        if (!snapshot.isEmpty()) {
            return snapshot.contains(shortCode);
        }
        if (inGrace()) {
            return true;
        }
        if (graceExpiryLogged.compareAndSet(false, true)) {
            log.warn("hot key list still empty after {} — closing L1 admission until a list arrives", grace);
        }
        return false;
    }

    /** 목록을 아직 못 받았고 유예 시간 안인가. */
    private boolean inGrace() {
        return keys.isEmpty() && System.nanoTime() - startedAtNanos < grace.toNanos();
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
