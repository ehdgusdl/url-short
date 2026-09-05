package com.example.urlshort.dashboard;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "Stats", description = "클릭 집계 조회 API")
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final ClickStatsRepository repository;
    private final HotKeyPublisher publisher;
    private final HotKeyProperties props;

    public StatsController(ClickStatsRepository repository, HotKeyPublisher publisher, HotKeyProperties props) {
        this.repository = repository;
        this.publisher = publisher;
        this.props = props;
    }

    @Operation(summary = "클릭수 상위 목록", description = "집계 기간 내 조회수 상위 단축 코드를 반환합니다.")
    @GetMapping("/top")
    public List<ClickStats> top(@RequestParam(defaultValue = "20") int limit) {
        // 음수가 그대로 내려가면 LIMIT -1 이 되어 ClickHouse가 에러를 던진다.
        return repository.topStats(props.lookbackDays(), Math.clamp(limit, 1, 500));
    }

    @Operation(summary = "단일 코드 클릭수")
    @GetMapping("/{shortCode:[A-Za-z0-9]{6,10}}")
    public ClickStats one(@PathVariable String shortCode) {
        return new ClickStats(shortCode, repository.clicksOf(shortCode, props.lookbackDays()));
    }

    @Operation(summary = "현재 발행된 핫키 상태", description = "마지막으로 발행한 핫키 목록의 버전과 크기를 반환합니다.")
    @GetMapping("/hotkeys")
    public Map<String, Object> hotKeys() {
        return Map.of("version", publisher.version(), "size", publisher.lastSize());
    }
}
