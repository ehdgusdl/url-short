package com.example.urlshort.cache;

import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Redis Pub/Sub 무효화 채널을 구독해 로컬(L1) 캐시를 즉시 비우는 리스너.
 *
 * <p>메시지 본문이 {@link #INVALIDATE_ALL}이면 전체 무효화, 그 외에는 해당 shortCode만 무효화한다.
 * 발행 인스턴스 자신도 메시지를 수신하지만 evict는 멱등이므로 안전하다.
 */
@Component
public class CacheInvalidationListener implements MessageListener {

    public static final String INVALIDATE_ALL = "__ALL__";

    private final LayeredUrlCache cache;

    // RedisMessageListenerContainer ↔ LayeredUrlCache 간 순환 참조 방지를 위해 지연 주입.
    public CacheInvalidationListener(@Lazy LayeredUrlCache cache) {
        this.cache = cache;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        if (INVALIDATE_ALL.equals(body)) {
            cache.evictLocalAll();
        } else {
            cache.evictLocal(body);
        }
    }
}
