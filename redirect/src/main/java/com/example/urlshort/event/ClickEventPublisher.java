package com.example.urlshort.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 클릭 이벤트를 Kafka로 발행한다.
 *
 * <p>파티션 키를 shortCode로 고정해 같은 키의 이벤트가 같은 파티션으로 모이게 한다(집계 순서 안정).
 *
 * <p>{@code KafkaProducer.send()}는 메타데이터 미확보·버퍼 포화 시 {@code max.block.ms} 만큼 호출 스레드를
 * 붙잡는다. 브로커가 죽었을 때 그 지연이 리다이렉트 302에 실리면 안 되므로 발행을 경계 있는 큐로 넘긴다.
 * 큐가 차면 이벤트를 버린다 — 집계는 부가 기능이고 응답 지연보다 덜 중요하다.
 */
@Component
public class ClickEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ClickEventPublisher.class);

    // ClickHouse DateTime이 그대로 파싱하는 포맷.
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String topic;
    private final ThreadPoolExecutor worker;
    private final Counter published;
    private final Counter failed;
    private final Counter dropped;

    public ClickEventPublisher(KafkaTemplate<String, String> kafka,
                               @Value("${app.kafka.click-topic:events}") String topic,
                               @Value("${app.kafka.publish-queue-size:1000}") int queueSize,
                               MeterRegistry registry) {
        this.kafka = kafka;
        this.topic = topic;
        this.published = Counter.builder("click.events").tag("result", "published").register(registry);
        this.failed = Counter.builder("click.events").tag("result", "failed").register(registry);
        this.dropped = Counter.builder("click.events").tag("result", "dropped").register(registry);
        this.worker = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize),
                r -> {
                    Thread t = new Thread(r, "click-event-publisher");
                    t.setDaemon(true);
                    return t;
                },
                // 큐가 차면 버리되 버린 사실은 남긴다. 조용한 유실은 디버깅을 불가능하게 만든다.
                (r, executor) -> this.dropped.increment());
        registry.gauge("click.events.queue.size", worker, e -> e.getQueue().size());
    }

    public void publish(String shortCode) {
        // 이벤트 시각은 요청 시점으로 고정한다. 워커에서 만들면 큐 대기 시간만큼 어긋난다.
        ClickEvent event = new ClickEvent(UUID.randomUUID().toString(), shortCode, TS.format(Instant.now()));
        worker.execute(() -> send(event));
    }

    private void send(ClickEvent event) {
        try {
            String payload = mapper.writeValueAsString(event);
            kafka.send(topic, event.shortCode(), payload).whenComplete((result, ex) -> {
                if (ex != null) {
                    failed.increment();
                    log.warn("click event publish failed for {} (ignored)", event.shortCode());
                } else {
                    published.increment();
                }
            });
        } catch (Exception e) {
            // 집계는 부가 기능이다. 리다이렉트 응답보다 우선할 수 없다.
            failed.increment();
            log.warn("click event serialization failed for {} (ignored)", event.shortCode(), e);
        }
    }

    /** 큐에 남은 이벤트를 짧게 비우고 워커를 닫는다. */
    @PreDestroy
    void shutdown() {
        worker.shutdown();
        try {
            worker.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
