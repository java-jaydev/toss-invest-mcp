package dev.jaydev.tossmcp.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * L2 공유 캐시(Redis). toss.cache.l2.enabled=true 일 때만 활성.
 * Redis 오류는 로그만 남기고 미스/no-op 으로 degrade — 도구 호출을 절대 깨지 않는다.
 */
@Component
@ConditionalOnProperty(name = "toss.cache.l2.enabled", havingValue = "true")
public class RedisL2Cache implements L2Cache {

    private static final Logger log = LoggerFactory.getLogger(RedisL2Cache.class);

    private final StringRedisTemplate redis;

    public RedisL2Cache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<String> get(String key) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(key));
        } catch (RuntimeException e) {
            log.warn("L2 get 실패 → 미스로 degrade: {}", e.toString());
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (RuntimeException e) {
            log.warn("L2 put 실패 → 무시: {}", e.toString());
        }
    }
}
