package com.example.urlshort.event;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ClickEventPublisher 비동기 발행 테스트")
class ClickEventPublisherTest {

    @Test
    @DisplayName("발행은 요청 스레드가 아니라 전용 워커에서 일어난다")
    @SuppressWarnings("unchecked")
    void publishes_off_the_calling_thread() throws Exception {
        KafkaTemplate<String, String> kafka = Mockito.mock(KafkaTemplate.class);
        CountDownLatch sent = new CountDownLatch(1);
        AtomicReference<String> sendingThread = new AtomicReference<>();
        Mockito.when(kafka.send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(inv -> {
                    sendingThread.set(Thread.currentThread().getName());
                    sent.countDown();
                    return CompletableFuture.completedFuture(null);
                });

        ClickEventPublisher publisher =
                new ClickEventPublisher(kafka, "events", 10, new SimpleMeterRegistry());
        String callerThread = Thread.currentThread().getName();

        publisher.publish("aB3xK9p");

        assertThat(sent.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(sendingThread.get())
                .isEqualTo("click-event-publisher")
                .isNotEqualTo(callerThread);
    }

    @Test
    @DisplayName("큐가 가득 차면 응답을 막는 대신 버린다")
    @SuppressWarnings("unchecked")
    void drops_instead_of_blocking_when_queue_is_full() throws Exception {
        KafkaTemplate<String, String> kafka = Mockito.mock(KafkaTemplate.class);
        CountDownLatch release = new CountDownLatch(1);
        // 워커를 붙잡아 큐가 쌓이게 만든다.
        Mockito.when(kafka.send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(inv -> {
                    release.await(3, TimeUnit.SECONDS);
                    return CompletableFuture.completedFuture(null);
                });

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ClickEventPublisher publisher = new ClickEventPublisher(kafka, "events", 1, registry);

        long start = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            publisher.publish("code" + i);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        release.countDown();

        // 호출 스레드는 어떤 경우에도 막히지 않는다.
        assertThat(elapsedMs).isLessThan(1000);
        assertThat(registry.get("click.events").tag("result", "dropped").counter().count())
                .isGreaterThan(0);
    }
}
