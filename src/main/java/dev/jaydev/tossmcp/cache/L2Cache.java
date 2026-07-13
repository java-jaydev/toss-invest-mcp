package dev.jaydev.tossmcp.cache;

import java.time.Duration;
import java.util.Optional;

/** 공유(L2) 캐시 추상화. 기본은 NoOp, Redis 활성 시 RedisL2Cache 로 대체된다. */
public interface L2Cache {

    Optional<String> get(String key);

    void put(String key, String value, Duration ttl);
}
