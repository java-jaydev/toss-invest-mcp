package dev.jaydev.tossmcp.cache;

import dev.jaydev.tossmcp.client.TossApiClient;
import dev.jaydev.tossmcp.config.TossProperties;
import dev.jaydev.tossmcp.service.MarketDataService;
import dev.jaydev.tossmcp.tools.MarketDataTools;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class CacheWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(TossProperties.class, () -> new TossProperties("https://example.test", "id", "secret", "acct"))
            .withBean(SimpleMeterRegistry.class)
            .withUserConfiguration(
                    dev.jaydev.tossmcp.auth.TossAuthService.class,
                    TossApiClient.class,
                    MarketDataCache.class,
                    NoOpL2Cache.class,
                    RedisL2Cache.class,
                    MarketDataService.class,
                    MarketDataTools.class);

    @Test
    void defaultProfileWiresNoOpL2AndFullToolChain() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(MarketDataTools.class);
            assertThat(ctx).hasSingleBean(MarketDataService.class);
            assertThat(ctx).hasSingleBean(MarketDataCache.class);
            assertThat(ctx).hasSingleBean(L2Cache.class);
            assertThat(ctx.getBean(L2Cache.class)).isInstanceOf(NoOpL2Cache.class);
        });
    }

    @Test
    void l2EnabledFlagIsHonoredAtConditionLevel() {
        runner.withPropertyValues("toss.cache.l2.enabled=false").run(ctx ->
                assertThat(ctx.getBean(L2Cache.class)).isInstanceOf(NoOpL2Cache.class));
    }

    @Test
    void l2EnabledWiresRedisL2Cache() {
        runner
                .withBean(StringRedisTemplate.class, () -> Mockito.mock(StringRedisTemplate.class))
                .withPropertyValues("toss.cache.l2.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(L2Cache.class);
                    assertThat(ctx.getBean(L2Cache.class)).isInstanceOf(RedisL2Cache.class);
                });
    }
}
