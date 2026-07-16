package dev.jaydev.tossmcp.cache;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/** L2 비활성(기본) 상태의 no-op 구현. toss.cache.l2.enabled 가 없거나 false 일 때 활성. */
@Component
@ConditionalOnProperty(name = "toss.cache.l2.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpL2Cache implements L2Cache {

    @Override
    public Optional<String> get(String key) {
        return Optional.empty();
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        // no-op
    }
}
