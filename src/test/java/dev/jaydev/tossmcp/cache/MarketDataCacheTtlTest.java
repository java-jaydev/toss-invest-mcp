package dev.jaydev.tossmcp.cache;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketDataCacheTtlTest {

    /** 테스트에서 시간을 수동으로 전진시키는 Caffeine ticker. */
    static final class ManualTicker implements Ticker {
        private long nanos = 0L;
        @Override public long read() { return nanos; }
        void advance(Duration d) { nanos += d.toNanos(); }
    }

    @Test
    void shortTtlEntryExpiresAtItsOwnTtl() {
        ManualTicker ticker = new ManualTicker();
        AtomicInteger loads = new AtomicInteger();
        Supplier<String> loader = () -> { loads.incrementAndGet(); return "V"; };
        MarketDataCache cache = new MarketDataCache(new NoOpL2Cache(), ticker);

        cache.get("k", Duration.ofSeconds(2), loader);
        ticker.advance(Duration.ofSeconds(1));
        cache.get("k", Duration.ofSeconds(2), loader);   // 1s < 2s → 캐시 히트
        assertEquals(1, loads.get());

        ticker.advance(Duration.ofSeconds(2));           // 누적 3s > 2s → 만료
        cache.get("k", Duration.ofSeconds(2), loader);
        assertEquals(2, loads.get());
    }

    @Test
    void longTtlEntryIsCachedWithoutRedis() {
        ManualTicker ticker = new ManualTicker();
        AtomicInteger loads = new AtomicInteger();
        Supplier<String> loader = () -> { loads.incrementAndGet(); return "V"; };
        MarketDataCache cache = new MarketDataCache(new NoOpL2Cache(), ticker);

        cache.get("stocks:X", Duration.ofHours(6), loader);
        ticker.advance(Duration.ofMinutes(30));
        cache.get("stocks:X", Duration.ofHours(6), loader);  // 30m < 6h → L2 없이도 캐시
        assertEquals(1, loads.get(), "6h TTL 항목은 30분 뒤에도 L1 캐시 (Redis 불필요)");

        ticker.advance(Duration.ofHours(7));                 // > 6h → 만료
        cache.get("stocks:X", Duration.ofHours(6), loader);
        assertEquals(2, loads.get());
    }
}
