package dev.jaydev.tossmcp.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketDataCacheTest {

    private MarketDataCache newCache() {
        return new MarketDataCache(new NoOpL2Cache());
    }

    @Test
    void secondCallWithinTtlUsesCachedValue() {
        AtomicInteger loads = new AtomicInteger();
        Supplier<String> loader = () -> { loads.incrementAndGet(); return "V"; };
        MarketDataCache cache = newCache();

        assertEquals("V", cache.get("k", Duration.ofSeconds(60), loader));
        assertEquals("V", cache.get("k", Duration.ofSeconds(60), loader));

        assertEquals(1, loads.get(), "두 번째 호출은 L1 캐시에서 나와야 한다");
    }

    @Test
    void concurrentSameKeyCollapsesToOneLoad() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        Supplier<String> slowLoader = () -> {
            loads.incrementAndGet();
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "V";
        };
        MarketDataCache cache = newCache();

        int n = 20;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return cache.get("hot", Duration.ofSeconds(60), slowLoader);
            }));
        }
        start.countDown();
        for (Future<String> f : futures) {
            assertEquals("V", f.get());
        }
        pool.shutdownNow();

        assertEquals(1, loads.get(), "동일 키 동시요청은 single-flight로 loader 1회만 실행돼야 한다");
    }
}
