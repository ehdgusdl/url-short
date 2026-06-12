package com.example.urlshort.config;

import com.example.urlshort.cache.CacheInvalidationListener;
import com.example.urlshort.dto.UrlView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * L2(Redis) 캐시 및 Pub/Sub 기반 캐시 무효화 채널 구성.
 */
@Configuration
public class RedisConfig {

    /** 캐시 무효화 메시지가 오가는 Pub/Sub 채널. */
    public static final String INVALIDATION_CHANNEL = "url-cache:invalidation";

    /** L2 캐시에 {@link UrlView}를 JSON으로 직렬화해 저장하는 템플릿. */
    @Bean
    public RedisTemplate<String, UrlView> urlRedisTemplate(RedisConnectionFactory connectionFactory) {
        ObjectMapper objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();

        Jackson2JsonRedisSerializer<UrlView> valueSerializer =
                new Jackson2JsonRedisSerializer<>(objectMapper, UrlView.class);

        RedisTemplate<String, UrlView> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(valueSerializer);
        template.afterPropertiesSet();
        return template;
    }

    /** 모든 인스턴스가 무효화 채널을 구독해 로컬(L1) 캐시를 즉시 비우도록 하는 리스너 컨테이너. */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            CacheInvalidationListener listener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listener, new ChannelTopic(INVALIDATION_CHANNEL));
        return container;
    }
}
