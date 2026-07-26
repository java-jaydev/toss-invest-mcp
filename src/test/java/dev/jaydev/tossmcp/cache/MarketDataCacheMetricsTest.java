package dev.jaydev.tossmcp.cache;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계측이 실제 캐시 동작을 반영하는지 검증한다. 핵심 지표는
 * marketdata.upstream.calls — 캐시·병합으로 요청 수보다 적어야 하며, 이것이
 * "캐시 오프로드"를 정직하게 보여주는 (하드웨어 독립적인) 수치다.
 */
class MarketDataCacheMetricsTest {

    private final Duration ttl = Duration.ofSeconds(60);

    @Test
    void repeatedKeyCallsUpstreamOnceAndCountsIt() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MarketDataCache cache = new MarketDataCache(new NoOpL2Cache(), registry);
        AtomicInteger loads = new AtomicInteger();
        Supplier<String> loader = () -> {
            loads.incrementAndGet();
            return "V";
        };

        cache.get("k", ttl, loader);
        cache.get("k", ttl, loader); // L1 히트 → upstream 호출 안 함

        assertThat(registry.get("marketdata.upstream.calls").counter().count())
                .as("두 요청 중 upstream 은 1회만 호출돼야 한다")
                .isEqualTo(1.0);
        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    void l2HitAvoidsUpstreamAndIsCounted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        L2Cache alwaysHit = new L2Cache() {
            @Override
            public Optional<String> get(String key) {
                return Optional.of("FROM_L2");
            }

            @Override
            public void put(String key, String value, Duration ttl) {
            }
        };
        MarketDataCache cache = new MarketDataCache(alwaysHit, registry);

        String value = cache.get("k", ttl, () -> {
            throw new AssertionError("L2 히트 시 upstream 을 호출하면 안 된다");
        });

        assertThat(value).isEqualTo("FROM_L2");
        assertThat(registry.get("marketdata.l2.hits").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("marketdata.upstream.calls").counter().count()).isZero();
    }

    @Test
    void caffeineHitMissMetersAreRegistered() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MarketDataCache cache = new MarketDataCache(new NoOpL2Cache(), registry);

        cache.get("k", ttl, () -> "V"); // miss → load
        cache.get("k", ttl, () -> "V"); // hit

        // Caffeine 통계가 Micrometer 로 바인딩됐는지(관측성 노출 여부) 확인.
        // CaffeineCacheMetrics 는 cache.* 미터를 FunctionCounter/Gauge 로 등록하므로
        // 타입에 의존하지 않고 이름 접두사로 존재를 확인한다.
        assertThat(registry.getMeters().stream()
                .map(m -> m.getId().getName())
                .filter(name -> name.startsWith("cache.")))
                .as("CaffeineCacheMetrics 가 cache.* 미터를 등록해야 한다")
                .isNotEmpty();
    }
}
