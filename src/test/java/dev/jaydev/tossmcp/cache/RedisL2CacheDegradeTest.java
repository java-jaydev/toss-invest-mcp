package dev.jaydev.tossmcp.cache;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisL2CacheDegradeTest {

    @Test
    void getReturnsEmptyWhenRedisThrows() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenThrow(new RuntimeException("redis down"));

        RedisL2Cache l2 = new RedisL2Cache(redis);

        assertEquals(Optional.empty(), l2.get("k"), "Redis 장애는 미스로 degrade 돼야 한다");
    }

    @Test
    void putSwallowsRedisFailure() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        doThrow(new RuntimeException("redis down")).when(ops).set(anyString(), anyString(), any(Duration.class));

        RedisL2Cache l2 = new RedisL2Cache(redis);

        assertDoesNotThrow(() -> l2.put("k", "v", Duration.ofSeconds(30)));
    }
}
