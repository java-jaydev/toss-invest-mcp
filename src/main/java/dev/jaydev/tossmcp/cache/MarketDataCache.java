package dev.jaydev.tossmcp.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 2계층 캐시 + 요청병합.
 * L1(Caffeine): get(key, loader) 가 키 단위 원자 실행이라 동일 키 동시요청을
 * single-flight 로 병합한다. 각 항목은 호출자가 지정한 타입별 TTL 로 만료되므로,
 * Redis 없이도 종목정보 6h·분봉 10s 등 의도대로 캐시된다.
 * L2(공유 캐시, opt-in): L1 미스 시 조회, 미스면 upstream 호출 후 L2 적재.
 * 여러 인스턴스로 확장하면 L2 가 노드 간 캐시를 공유한다.
 */
@Component
public class MarketDataCache {

    private static final int L1_MAX_SIZE = 10_000;

    private final Cache<String, Entry> l1;
    private final L2Cache l2;

    @Autowired
    public MarketDataCache(L2Cache l2) {
        this(l2, Ticker.systemTicker());
    }

    MarketDataCache(L2Cache l2, Ticker ticker) {
        this.l2 = l2;
        this.l1 = Caffeine.newBuilder()
                .maximumSize(L1_MAX_SIZE)
                .ticker(ticker)
                .expireAfter(new TtlExpiry())
                .build();
    }

    /** L1(타입별 TTL, single-flight) → L2(공유) → upstream 순으로 해석. */
    public String get(String key, Duration ttl, Supplier<String> upstream) {
        Entry entry = l1.get(key, k -> {
            String value = l2GetOrLoad(k, ttl, upstream);
            return value == null ? null : new Entry(value, ttl);
        });
        return entry == null ? null : entry.value();
    }

    private String l2GetOrLoad(String key, Duration ttl, Supplier<String> upstream) {
        Optional<String> hit = l2.get(key);
        if (hit.isPresent()) {
            return hit.get();
        }
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
