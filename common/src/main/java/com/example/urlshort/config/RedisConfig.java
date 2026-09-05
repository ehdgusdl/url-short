package com.example.urlshort.config;

import com.example.urlshort.dto.UrlView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * L2(Redis) 캐시 템플릿 구성. url-api(쓰기)와 redirect(읽기)가 같은 직렬화 포맷을 써야 하므로 common에 둔다.
 */
@Configuration
public class RedisConfig {

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
}
