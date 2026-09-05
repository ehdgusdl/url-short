package com.example.urlshort.config;

import com.example.urlshort.cache.CacheChannels;
import com.example.urlshort.cache.CacheInvalidationListener;
import com.example.urlshort.cache.HotKeyListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * redirect 인스턴스가 구독하는 두 채널.
 * <ul>
 *   <li>무효화: url-api가 삭제·만료 시 발행 → 로컬 L1 즉시 제거(정확성)</li>
 *   <li>핫키: dashboard가 주기적으로 발행 → L1 적재 대상 갱신(메모리)</li>
 * </ul>
 */
@Configuration
public class RedisListenerConfig {

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            CacheInvalidationListener invalidationListener,
            HotKeyListener hotKeyListener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(invalidationListener, new ChannelTopic(CacheChannels.INVALIDATION_CHANNEL));
        container.addMessageListener(hotKeyListener, new ChannelTopic(CacheChannels.HOTKEY_CHANNEL));
        return container;
    }
}
