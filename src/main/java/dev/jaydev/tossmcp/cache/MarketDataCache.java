package dev.jaydev.tossmcp.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 2계층 캐시 + 요청병합.
 * L1(Caffeine 근캐시, 고정 2s): get(key, loader) 가 키 단위 원자 실행이라
 * 동일 키 동시요청을 자동으로 single-flight 로 병합한다.
 * L2(공유 캐시, 데이터별 ttl): L1 미스 시 조회, 미스면 upstream 호출 후 L2 적재.
 */
@Component
public class MarketDataCache {

    private static final Duration L1_TTL = Duration.ofSeconds(2);
    private static final int L1_MAX_SIZE = 10_000;

    private final Cache<String, String> l1 = Caffeine.newBuilder()
            .maximumSize(L1_MAX_SIZE)
            .expireAfterWrite(L1_TTL)
            .build();

    private final L2Cache l2;

    public MarketDataCache(L2Cache l2) {
        this.l2 = l2;
    }

    /** L1 → L2 → upstream 순으로 해석하고, 값을 각 계층에 채운다. */
    public String get(String key, Duration ttl, Supplier<String> upstream) {
        return l1.get(key, k -> l2GetOrLoad(k, ttl, upstream));
    }

    private String l2GetOrLoad(String key, Duration ttl, Supplier<String> upstream) {
        Optional<String> hit = l2.get(key);
        if (hit.isPresent()) {
            return hit.get();
        }
        String value = upstream.get();
        l2.put(key, value, ttl);
        return value;
    }
}
