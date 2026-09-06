package com.example.urlshort.cache;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;

/**
 * L1 적재량과 힙 여유를 주기적으로 로그로 남긴다.
 *
 * <p>지표만 보면 "힙이 찼다"는 알 수 있어도 "무엇이 채웠는지"는 알 수 없다.
 * 엔트리 수와 힙 사용률을 한 줄에 같이 찍어야 캐시 때문인지 아닌지가 로그만으로 판별된다.
 * 여유가 임계 아래로 떨어지면 WARN으로 올려 알림 대상이 되게 한다.
 */
@Component
public class CacheMetricsReporter {

    private static final Logger log = LoggerFactory.getLogger(CacheMetricsReporter.class);

    private final LayeredUrlCache cache;
    private final HotKeySet hotKeys;
    private final MeterRegistry registry;
    private final double warnThreshold;
    private final long bytesPerEntry;
    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();

    public CacheMetricsReporter(LayeredUrlCache cache, HotKeySet hotKeys, MeterRegistry registry,
                                @Value("${app.cache.headroom-warn-ratio:0.70}") double warnThreshold,
                                @Value("${app.cache.bytes-per-entry:576}") long bytesPerEntry) {
        this.cache = cache;
        this.hotKeys = hotKeys;
        this.registry = registry;
        this.warnThreshold = warnThreshold;
        this.bytesPerEntry = bytesPerEntry;
    }

    @Scheduled(fixedDelayString = "${app.cache.report-interval-ms:30000}")
    public void report() {
        long entries = cache.localSize();
        long used = memory.getHeapMemoryUsage().getUsed();
        long max = memory.getHeapMemoryUsage().getMax();
        double usage = max > 0 ? (double) used / max : 0;

        if (usage >= warnThreshold) {
            log.warn("localCache entries={} retained={}MB heap_usage={}% headroom={}MB admission={}",
                    entries, entries * bytesPerEntry / 1048576, Math.round(usage * 100),
                    (max - used) / 1048576, hotKeys.isEnabled() ? hotKeys.size() : "off");
        } else {
            log.info("localCache entries={} retained={}MB heap_usage={}% headroom={}MB admission={}",
                    entries, entries * bytesPerEntry / 1048576, Math.round(usage * 100),
                    (max - used) / 1048576, hotKeys.isEnabled() ? hotKeys.size() : "off");
        }
        log.info("l1_hit_ratio={} l2_lookup_total={} db_loads={}",
                String.format("%.3f", ratio("urlcache.hits", "urlcache.requests")),
                (long) counter("urlcache.requests", "l2"), (long) counterNoTag("urlcache.loads"));
        logResponse();
    }

    /** 응답 지연·에러율도 같이 남긴다. 힙이 차오르는 동안 응답이 멀쩡한지 로그만으로 보이게 하려는 것이다. */
    private void logResponse() {
        var timers = registry.find("http.server.requests").timers().stream()
                .filter(t -> !String.valueOf(t.getId().getTag("uri")).contains("actuator"))
                .toList();
        if (timers.isEmpty()) {
            return;
        }
        long count = 0, errors = 0;
        double p99 = 0;
        for (var t : timers) {
            count += t.count();
            if (String.valueOf(t.getId().getTag("status")).startsWith("5")) {
                errors += t.count();
            }
            for (var v : t.takeSnapshot().percentileValues()) {
                if (Math.abs(v.percentile() - 0.99) < 1e-6) {
                    p99 = Math.max(p99, v.value(java.util.concurrent.TimeUnit.MILLISECONDS));
                }
            }
        }
        log.info("GET /{} p99_ms={} error_rate={} total_requests={}",
                "{shortCode}", String.format("%.1f", p99),
                String.format("%.4f", count == 0 ? 0 : (double) errors / count), count);
    }

    private double ratio(String hits, String requests) {
        double r = counter(requests, "l1");
        return r == 0 ? 0 : counter(hits, "l1") / r;
    }

    private double counter(String name, String layer) {
        var c = registry.find(name).tag("layer", layer).counter();
        return c == null ? 0 : c.count();
    }

    private double counterNoTag(String name) {
        var c = registry.find(name).counter();
        return c == null ? 0 : c.count();
    }
}
