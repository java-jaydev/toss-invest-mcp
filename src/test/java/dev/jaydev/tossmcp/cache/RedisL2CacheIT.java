package dev.jaydev.tossmcp.cache;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "toss.cache.l2.enabled=true",
        "spring.ai.mcp.server.enabled=false",
        "spring.ai.mcp.server.stdio=false",
        "spring.main.web-application-type=none"
})
@Testcontainers(disabledWithoutDocker = true)
class RedisL2CacheIT {

    @Container
    @ServiceConnection
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    L2Cache l2;

    @Test
    void beanIsRedisImplementation() {
        assertTrue(l2 instanceof RedisL2Cache, "L2 활성 시 RedisL2Cache 가 주입돼야 한다");
    }

    @Test
    void putThenGetRoundtrips() {
        l2.put("it:k", "v", Duration.ofMinutes(1));
        assertEquals(Optional.of("v"), l2.get("it:k"));
    }

    @Test
    void entryExpiresAfterTtl() throws Exception {
        l2.put("it:ttl", "v", Duration.ofMillis(300));
        Thread.sleep(600);
        assertEquals(Optional.empty(), l2.get("it:ttl"));
    }
}
