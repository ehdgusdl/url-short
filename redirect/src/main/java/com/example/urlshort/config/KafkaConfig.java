package com.example.urlshort.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** 클릭 이벤트 토픽. 파티션은 병렬 소비 단위이자 순서 단위라 shortCode 키 해시로 분배된다. */
@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic clickTopic(@Value("${app.kafka.click-topic:events}") String topic,
                               @Value("${app.kafka.click-partitions:3}") int partitions) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(1).build();
    }
}
