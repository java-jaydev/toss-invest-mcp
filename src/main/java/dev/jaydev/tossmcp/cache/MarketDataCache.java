package dev.jaydev.tossmcp.cache;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 2계층 캐시 + 요청병합.
 * L1(Caffeine AsyncCache): 키 단위로 하나의 진행 중 future 를 공유해 동일 키
 * 동시요청을 single-flight 로 병합한다. 동기 Cache.get(key, loader) 와 달리 loader 가
 * ConcurrentHashMap 모니터 밖(별도 가상스레드)에서 실행되므로, 로딩 중 블로킹 I/O 가
 * JDK 21 캐리어를 핀하지 않는다. 각 항목은 호출자가 지정한 타입별 TTL 로 만료되므로,
 * Redis 없이도 종목정보 6h·분봉 10s 등 의도대로 캐시된다.
 * L2(공유 캐시, opt-in): L1 미스 시 조회, 미스면 upstream 호출 후 L2 적재.
 * 여러 인스턴스로 확장하면 L2 가 노드 간 캐시를 공유한다.
 */
@Component
public class MarketDataCache {

    private static final int L1_MAX_SIZE = 10_000;

    private final AsyncCache<String, Entry> l1;
    private final L2Cache l2;
    private final Counter upstreamCalls;
    private final Counter l2Hits;

    @Autowired
    public MarketDataCache(L2Cache l2, MeterRegistry registry) {
        this(l2, Ticker.systemTicker(), registry);
    }

    // 테스트 편의: 지표를 버리는 레지스트리로 만든다(계측 검증이 목적이 아닌 테스트용).
    public MarketDataCache(L2Cache l2) {
        this(l2, Ticker.systemTicker(), new SimpleMeterRegistry());
    }

    // 테스트 편의: 수동 ticker + 버려지는 레지스트리(TTL 테스트용).
    MarketDataCache(L2Cache l2, Ticker ticker) {
        this(l2, ticker, new SimpleMeterRegistry());
    }

    MarketDataCache(L2Cache l2, Ticker ticker, MeterRegistry registry) {
        this.l2 = l2;
        this.upstreamCalls = Counter.builder("marketdata.upstream.calls")
                .description("upstream(토스 API) 실제 호출 횟수 — 캐시·병합으로 요청수보다 적다")
                .register(registry);
        this.l2Hits = Counter.builder("marketdata.l2.hits")
                .description("L2(공유 캐시)에서 값을 가져온 횟수")
                .register(registry);
        this.l1 = Caffeine.newBuilder()
                .maximumSize(L1_MAX_SIZE)
                .ticker(ticker)
                // 로딩을 가상스레드에서 돌려 loader 의 블로킹 I/O 가 캐리어를 핀하지 않게 한다.
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .expireAfter(new TtlExpiry())
                .recordStats()
                .buildAsync();
        // Caffeine 내부 통계(히트/미스/적재/축출)를 Micrometer 로 노출.
        CaffeineCacheMetrics.monitor(registry, l1.synchronous(), "marketdata.l1");
    }

    /** L1(타입별 TTL, single-flight) → L2(공유) → upstream 순으로 해석. */
    public String get(String key, Duration ttl, Supplier<String> upstream) {
        // mappingFunction 은 진행 중 future 가 없을 때만 호출된다. supplyAsync 가 즉시
        // 반환하므로 Caffeine 의 compute 모니터는 곧바로 풀리고, 실제 로딩은 executor 의
        // 가상스레드에서 일어난다. 동일 키 동시요청은 같은 future 를 공유(single-flight).
        CompletableFuture<Entry> future = l1.get(key, (k, executor) ->
                CompletableFuture.supplyAsync(() -> {
                    String value = l2GetOrLoad(k, ttl, upstream);
                    return value == null ? null : new Entry(value, ttl);
                }, executor));
        Entry entry = future.join();
        return entry == null ? null : entry.value();
    }

    private String l2GetOrLoad(String key, Duration ttl, Supplier<String> upstream) {
        Optional<String> hit = l2.get(key);
        if (hit.isPresent()) {
            l2Hits.increment();
            return hit.get();
        }
        upstreamCalls.increment();
        String value = upstream.get();
        if (value != null) {
            l2.put(key, value, ttl);
        }
        return value;
    }

    /** L1 캐시 항목: 값 + 그 값의 만료 기준 TTL. */
    private record Entry(String value, Duration ttl) {
    }

    /** 항목별 TTL 을 그대로 적용하는 가변 만료(쓰기 기준, 읽기로 연장하지 않음). */
    private static final class TtlExpiry implements Expiry<String, Entry> {
        @Override
        public long expireAfterCreate(String key, Entry entry, long currentTime) {
            return entry.ttl().toNanos();
        }

        @Override
        public long expireAfterUpdate(String key, Entry entry, long currentTime, long currentDuration) {
            return entry.ttl().toNanos();
        }

        @Override
        public long expireAfterRead(String key, Entry entry, long currentTime, long currentDuration) {
            return currentDuration;
        }
    }
}
