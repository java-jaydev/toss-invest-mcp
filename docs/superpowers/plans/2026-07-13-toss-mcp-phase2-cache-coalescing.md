# toss-invest-mcp Phase 2 (Cache + Coalescing) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put a two-tier cache (Caffeine L1 near-cache + Redis L2 shared-cache) with per-node single-flight request coalescing in front of the rate-limited Toss Open API, so bursts of identical read-only market-data calls collapse to at most one upstream request.

**Architecture:** A new `MarketDataService` sits between the MCP tools and `TossApiClient`. Each call becomes `cache.get(key, ttl, () -> api.callUpstream())`. `MarketDataCache` resolves L1 (Caffeine, `get(key, loader)` gives atomic per-key single-flight) → L2 (an `L2Cache` interface: `NoOpL2Cache` by default, `RedisL2Cache` when enabled) → upstream. L2 failures degrade to upstream so a tool call never breaks. Cache keys normalize comma-separated symbols (trim + sort) so `005930,000660` and `000660,005930` share one entry.

**Tech Stack:** Java 17, Spring Boot 3.5.16, Spring AI 1.1.8 (BOM), Gradle 8.14, Caffeine, Spring Data Redis (Lettuce), Testcontainers, JUnit 5 + Mockito.

## Global Constraints

- Java 17 toolchain; Spring Boot 3.5.16; Spring AI BOM 1.1.8; Gradle 8.14 — do not change these versions.
- Dependency versions come from the Spring Boot / Spring AI BOMs — **never pin explicit versions** for `caffeine`, `spring-boot-starter-data-redis`, or Testcontainers artifacts.
- Package root is `dev.jaydev.tossmcp`.
- Read-only market data only. Do **not** add order/account tools (that is Phase 4).
- Secrets come from env vars only (`TOSS_CLIENT_ID` / `TOSS_CLIENT_SECRET` / `TOSS_ACCOUNT`); never hardcode them.
- Tool methods return the upstream response as a **raw JSON `String`** (the LLM reads it directly). The cache stores and returns that exact string.
- L2 (Redis) must **degrade gracefully**: any Redis error is logged and treated as a miss / no-op — it must never propagate out of a tool call.
- This is a **public OSS repo**. Commit author is Jinkyu Lee only. **Never add an AI co-author trailer, "Generated with…" line, or any AI attribution to commit messages.**
- Redis integration tests must self-skip when Docker is unavailable (`@Testcontainers(disabledWithoutDocker = true)`).
- **Out of scope for this plan:** HTTP (Streamable) transport. It is deferred to a separate plan (prerequisite for Phase 3 k6 load testing). Cross-node request coalescing is also out of scope — L1 single-flight is per-node; the shared L2 narrows (not eliminates) the concurrent-miss window across nodes. Document this honestly, do not build it.

**Current interfaces (already on `main`, do not change their signatures):**
- `dev.jaydev.tossmcp.client.TossApiClient` (`@Component`): `String getPrices(String symbols)`, `String getOrderbook(String symbol)`, `String getTrades(String symbol, Integer count)`, `String getCandles(String symbol, String interval, Integer count, String before, Boolean adjusted)`, `String getStocks(String symbols)`.
- `dev.jaydev.tossmcp.tools.MarketDataTools` (`@Component`): `@Tool` methods that currently delegate to `TossApiClient`.
- `dev.jaydev.tossmcp.TossMcpApplication`: registers `MarketDataTools` via `MethodToolCallbackProvider`. **Unchanged by this plan.**

---

### Task 1: L1 near-cache with single-flight + L2Cache seam

**Files:**
- Modify: `build.gradle` (add Caffeine)
- Create: `src/main/java/dev/jaydev/tossmcp/cache/L2Cache.java`
- Create: `src/main/java/dev/jaydev/tossmcp/cache/NoOpL2Cache.java`
- Create: `src/main/java/dev/jaydev/tossmcp/cache/MarketDataCache.java`
- Test: `src/test/java/dev/jaydev/tossmcp/cache/MarketDataCacheTest.java`

**Interfaces:**
- Produces:
  - `interface L2Cache { Optional<String> get(String key); void put(String key, String value, Duration ttl); }`
  - `class MarketDataCache` with `String get(String key, Duration ttl, Supplier<String> upstream)` and constructor `MarketDataCache(L2Cache l2)`.
  - `class NoOpL2Cache implements L2Cache` (returns empty / does nothing).

- [ ] **Step 1: Add Caffeine dependency**

In `build.gradle`, inside the `dependencies { }` block, after the `spring-web` line add:

```gradle
    // L1 near-cache (single-flight via Caffeine's atomic get(key, loader))
    implementation 'com.github.ben-manes.caffeine:caffeine'
```

- [ ] **Step 2: Write the failing test**

Create `src/test/java/dev/jaydev/tossmcp/cache/MarketDataCacheTest.java`:

```java
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
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests "dev.jaydev.tossmcp.cache.MarketDataCacheTest"`
Expected: FAIL — compilation error, `cannot find symbol: class MarketDataCache` / `class NoOpL2Cache`.

- [ ] **Step 4: Create the `L2Cache` interface**

Create `src/main/java/dev/jaydev/tossmcp/cache/L2Cache.java`:

```java
package dev.jaydev.tossmcp.cache;

import java.time.Duration;
import java.util.Optional;

/** 공유(L2) 캐시 추상화. 기본은 NoOp, Redis 활성 시 RedisL2Cache 로 대체된다. */
public interface L2Cache {

    Optional<String> get(String key);

    void put(String key, String value, Duration ttl);
}
```

- [ ] **Step 5: Create `NoOpL2Cache`**

Create `src/main/java/dev/jaydev/tossmcp/cache/NoOpL2Cache.java`:

```java
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
```

- [ ] **Step 6: Create `MarketDataCache`**

Create `src/main/java/dev/jaydev/tossmcp/cache/MarketDataCache.java`:

```java
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
```

- [ ] **Step 7: Run test to verify it passes**

Run: `./gradlew test --tests "dev.jaydev.tossmcp.cache.MarketDataCacheTest"`
Expected: PASS (2 tests).

- [ ] **Step 8: Commit**

```bash
git add build.gradle src/main/java/dev/jaydev/tossmcp/cache src/test/java/dev/jaydev/tossmcp/cache
git commit -m "feat(cache): L1 Caffeine near-cache with single-flight and L2 seam"
```

---

### Task 2: Route market-data tools through the cache

**Files:**
- Create: `src/main/java/dev/jaydev/tossmcp/service/MarketDataService.java`
- Modify: `src/main/java/dev/jaydev/tossmcp/tools/MarketDataTools.java`
- Test: `src/test/java/dev/jaydev/tossmcp/service/MarketDataServiceTest.java`

**Interfaces:**
- Consumes: `TossApiClient` (existing, on `main`), `MarketDataCache.get(String, Duration, Supplier<String>)` (Task 1).
- Produces: `class MarketDataService` (`@Service`) with `String prices(String)`, `String orderbook(String)`, `String trades(String, Integer)`, `String candles(String, String, Integer, String, Boolean)`, `String stocks(String)`, and package-visible `static String normalize(String symbols)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/jaydev/tossmcp/service/MarketDataServiceTest.java`:

```java
package dev.jaydev.tossmcp.service;

import dev.jaydev.tossmcp.cache.MarketDataCache;
import dev.jaydev.tossmcp.cache.NoOpL2Cache;
import dev.jaydev.tossmcp.client.TossApiClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketDataServiceTest {

    private MarketDataService withRealCache(TossApiClient api) {
        return new MarketDataService(api, new MarketDataCache(new NoOpL2Cache()));
    }

    @Test
    void repeatedPricesCallUpstreamOnce() {
        TossApiClient api = mock(TossApiClient.class);
        when(api.getPrices("005930")).thenReturn("R");
        MarketDataService svc = withRealCache(api);

        assertEquals("R", svc.prices("005930"));
        assertEquals("R", svc.prices("005930"));

        verify(api, times(1)).getPrices("005930");
    }

    @Test
    void symbolOrderNormalizedSharesOneCacheEntry() {
        TossApiClient api = mock(TossApiClient.class);
        when(api.getPrices("000660,005930")).thenReturn("R");
        MarketDataService svc = withRealCache(api);

        svc.prices("005930,000660");
        svc.prices("  000660 , 005930 ");

        verify(api, times(1)).getPrices("000660,005930");
    }

    @Test
    void differentParamsCallUpstreamSeparately() {
        TossApiClient api = mock(TossApiClient.class);
        when(api.getTrades("005930", 10)).thenReturn("A");
        when(api.getTrades("005930", 20)).thenReturn("B");
        MarketDataService svc = withRealCache(api);

        assertEquals("A", svc.trades("005930", 10));
        assertEquals("B", svc.trades("005930", 20));

        verify(api, times(1)).getTrades("005930", 10);
        verify(api, times(1)).getTrades("005930", 20);
    }

    @Test
    void normalizeSortsTrimsAndDropsBlanks() {
        assertEquals("000660,005930", MarketDataService.normalize(" 005930, 000660 ,"));
        assertEquals("", MarketDataService.normalize(null));
    }

    @Test
    void candlesUseDailyVsIntradayTtl() {
        TossApiClient api = mock(TossApiClient.class);
        MarketDataCache cache = mock(MarketDataCache.class);
        when(cache.get(anyString(), any(Duration.class), any())).thenReturn("X");
        MarketDataService svc = new MarketDataService(api, cache);

        svc.candles("005930", "1d", null, null, null);
        svc.candles("005930", "1m", null, null, null);

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(cache, times(2)).get(anyString(), ttl.capture(), any());
        assertEquals(Duration.ofHours(1), ttl.getAllValues().get(0), "1d 캔들은 장시간 TTL");
        assertEquals(Duration.ofSeconds(10), ttl.getAllValues().get(1), "분봉은 단시간 TTL");
    }

    @Test
    void stocksUseLongTtl() {
        TossApiClient api = mock(TossApiClient.class);
        MarketDataCache cache = mock(MarketDataCache.class);
        when(cache.get(eq("stocks:005930"), any(Duration.class), any())).thenReturn("X");
        MarketDataService svc = new MarketDataService(api, cache);

        svc.stocks("005930");

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(cache).get(eq("stocks:005930"), ttl.capture(), any());
        assertEquals(Duration.ofHours(6), ttl.getValue());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "dev.jaydev.tossmcp.service.MarketDataServiceTest"`
Expected: FAIL — compilation error, `cannot find symbol: class MarketDataService`.

- [ ] **Step 3: Create `MarketDataService`**

Create `src/main/java/dev/jaydev/tossmcp/service/MarketDataService.java`:

```java
package dev.jaydev.tossmcp.service;

import dev.jaydev.tossmcp.cache.MarketDataCache;
import dev.jaydev.tossmcp.client.TossApiClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 시세 조회에 2계층 캐시를 입힌다. 데이터 종류별 TTL 을 정하고,
 * 콤마 구분 심볼을 정규화(trim·정렬)해 순서 무관 캐시 히트를 만든다.
 */
@Service
public class MarketDataService {

    private static final Duration TTL_QUOTE = Duration.ofSeconds(2);   // prices, orderbook
    private static final Duration TTL_TRADES = Duration.ofSeconds(3);
    private static final Duration TTL_CANDLE_INTRADAY = Duration.ofSeconds(10);
    private static final Duration TTL_CANDLE_DAILY = Duration.ofHours(1);
    private static final Duration TTL_STOCKS = Duration.ofHours(6);

    private final TossApiClient api;
    private final MarketDataCache cache;

    public MarketDataService(TossApiClient api, MarketDataCache cache) {
        this.api = api;
        this.cache = cache;
    }

    public String prices(String symbols) {
        String norm = normalize(symbols);
        return cache.get("prices:" + norm, TTL_QUOTE, () -> api.getPrices(norm));
    }

    public String orderbook(String symbol) {
        return cache.get("orderbook:" + symbol, TTL_QUOTE, () -> api.getOrderbook(symbol));
    }

    public String trades(String symbol, Integer count) {
        return cache.get("trades:" + symbol + ":" + count, TTL_TRADES, () -> api.getTrades(symbol, count));
    }

    public String candles(String symbol, String interval, Integer count, String before, Boolean adjusted) {
        Duration ttl = "1d".equals(interval) ? TTL_CANDLE_DAILY : TTL_CANDLE_INTRADAY;
        String key = String.join(":", "candles", symbol, interval,
                String.valueOf(count), String.valueOf(before), String.valueOf(adjusted));
        return cache.get(key, ttl, () -> api.getCandles(symbol, interval, count, before, adjusted));
    }

    public String stocks(String symbols) {
        String norm = normalize(symbols);
        return cache.get("stocks:" + norm, TTL_STOCKS, () -> api.getStocks(norm));
    }

    /** 콤마 구분 심볼을 trim·빈값제거·정렬 후 재결합. null → "". */
    static String normalize(String symbols) {
        if (symbols == null) {
            return "";
        }
        return Arrays.stream(symbols.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .sorted()
                .collect(Collectors.joining(","));
    }
}
```

- [ ] **Step 4: Point `MarketDataTools` at `MarketDataService`**

Replace the whole body of `src/main/java/dev/jaydev/tossmcp/tools/MarketDataTools.java` with:

```java
package dev.jaydev.tossmcp.tools;

import dev.jaydev.tossmcp.service.MarketDataService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 시세(Market Data) MCP 도구. 전부 공식 Open API 기반, read-only.
 * 캐시·요청병합은 MarketDataService 가 담당한다.
 */
@Component
public class MarketDataTools {

    private final MarketDataService market;

    public MarketDataTools(MarketDataService market) {
        this.market = market;
    }

    @Tool(description = "토스증권 종목의 현재가를 조회한다. symbols 는 종목 코드이며 콤마로 여러 개(최대 200) 넣을 수 있다. 예: 삼성전자 005930, SK하이닉스 000660.")
    public String getPrices(@ToolParam(description = "종목 코드(콤마 구분, 최대 200개)") String symbols) {
        return market.prices(symbols);
    }

    @Tool(description = "토스증권 종목의 호가(매수·매도 잔량)를 조회한다. symbol 은 단일 종목 코드.")
    public String getOrderbook(@ToolParam(description = "종목 코드(단일)") String symbol) {
        return market.orderbook(symbol);
    }

    @Tool(description = "토스증권 종목의 최근 체결 내역을 조회한다. symbol 은 단일 종목 코드.")
    public String getTrades(
            @ToolParam(description = "종목 코드(단일)") String symbol,
            @ToolParam(description = "조회 건수(기본 50, 최대 50)", required = false) Integer count) {
        return market.trades(symbol, count);
    }

    @Tool(description = "토스증권 종목의 캔들(시가·고가·저가·종가) 차트를 조회한다.")
    public String getCandles(
            @ToolParam(description = "종목 코드(단일)") String symbol,
            @ToolParam(description = "간격: 1m(1분) 또는 1d(1일)") String interval,
            @ToolParam(description = "봉 개수(기본 100, 최대 200)", required = false) Integer count,
            @ToolParam(description = "페이지네이션 상한(ISO 8601 시각)", required = false) String before,
            @ToolParam(description = "수정주가 적용 여부(기본 true)", required = false) Boolean adjusted) {
        return market.candles(symbol, interval, count, before, adjusted);
    }

    @Tool(description = "토스증권 종목의 기본 정보(이름·시장·종류·상장일 등)를 조회한다. symbols 는 콤마로 여러 개(최대 200).")
    public String getStocks(@ToolParam(description = "종목 코드(콤마 구분, 최대 200개)") String symbols) {
        return market.stocks(symbols);
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "dev.jaydev.tossmcp.service.MarketDataServiceTest"`
Expected: PASS (6 tests). Then run the full suite to confirm no regression:
Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/jaydev/tossmcp/service src/main/java/dev/jaydev/tossmcp/tools/MarketDataTools.java src/test/java/dev/jaydev/tossmcp/service
git commit -m "feat(cache): route market-data tools through cached MarketDataService with per-type TTLs"
```

---

### Task 3: Redis L2 shared cache with graceful degradation

**Files:**
- Modify: `build.gradle` (add Spring Data Redis + Testcontainers)
- Create: `src/main/java/dev/jaydev/tossmcp/cache/RedisL2Cache.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/dev/jaydev/tossmcp/cache/RedisL2CacheIT.java`
- Test: `src/test/java/dev/jaydev/tossmcp/cache/RedisL2CacheDegradeTest.java`

**Interfaces:**
- Consumes: `L2Cache` (Task 1), `StringRedisTemplate` (Spring Data Redis auto-config).
- Produces: `class RedisL2Cache implements L2Cache` (`@Component`, active only when `toss.cache.l2.enabled=true`).

- [ ] **Step 1: Add Redis + Testcontainers dependencies**

In `build.gradle`, inside `dependencies { }`, add after the Caffeine line:

```gradle
    // L2 shared cache
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
```

and after the existing `testImplementation 'org.springframework.boot:spring-boot-starter-test'` line add:

```gradle
    testImplementation 'org.springframework.boot:spring-boot-testcontainers'
    testImplementation 'org.testcontainers:junit-jupiter'
```

- [ ] **Step 2: Write the failing degrade unit test (no Docker needed)**

Create `src/test/java/dev/jaydev/tossmcp/cache/RedisL2CacheDegradeTest.java`:

```java
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
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests "dev.jaydev.tossmcp.cache.RedisL2CacheDegradeTest"`
Expected: FAIL — compilation error, `cannot find symbol: class RedisL2Cache`.

- [ ] **Step 4: Create `RedisL2Cache`**

Create `src/main/java/dev/jaydev/tossmcp/cache/RedisL2Cache.java`:

```java
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
```

- [ ] **Step 5: Run the degrade test to verify it passes**

Run: `./gradlew test --tests "dev.jaydev.tossmcp.cache.RedisL2CacheDegradeTest"`
Expected: PASS (2 tests).

- [ ] **Step 6: Add Redis + L2 config to `application.yml`**

Edit `src/main/resources/application.yml`. Under the existing top-level `spring:` block (which currently has `main:` and `ai:`), add a `data:` child:

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
```

And under the existing top-level `toss:` block (which currently has `base-url`, `client-id`, `client-secret`, `account`), add:

```yaml
toss:
  cache:
    l2:
      enabled: ${TOSS_CACHE_L2:false}
```

(Keep all existing keys; only add the new `spring.data.redis` and `toss.cache.l2` subtrees.)

- [ ] **Step 7: Write the Testcontainers integration test**

Create `src/test/java/dev/jaydev/tossmcp/cache/RedisL2CacheIT.java`:

```java
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
```

- [ ] **Step 8: Run the integration test**

Run: `./gradlew test --tests "dev.jaydev.tossmcp.cache.RedisL2CacheIT"`
Expected: PASS (3 tests) if Docker is available; the class is **skipped** (not failed) if Docker is unavailable.

- [ ] **Step 9: Run the full suite**

Run: `./gradlew test`
Expected: PASS (all prior tests still green; the Redis IT passes or is skipped).

- [ ] **Step 10: Commit**

```bash
git add build.gradle src/main/java/dev/jaydev/tossmcp/cache/RedisL2Cache.java src/main/resources/application.yml src/test/java/dev/jaydev/tossmcp/cache
git commit -m "feat(cache): Redis L2 shared cache with graceful degradation (opt-in via TOSS_CACHE_L2)"
```

---

### Task 4: DI wiring verification + docs

**Files:**
- Test: `src/test/java/dev/jaydev/tossmcp/cache/CacheWiringTest.java`
- Modify: `README.md` (Roadmap table + a short "Caching" section)

**Interfaces:**
- Consumes: all beans from Tasks 1–3.

- [ ] **Step 1: Write the wiring test**

Create `src/test/java/dev/jaydev/tossmcp/cache/CacheWiringTest.java`. This uses `ApplicationContextRunner` so it does not boot the MCP server or need Docker — it just proves the DI graph and the default `L2Cache` selection.

```java
package dev.jaydev.tossmcp.cache;

import dev.jaydev.tossmcp.client.TossApiClient;
import dev.jaydev.tossmcp.config.TossProperties;
import dev.jaydev.tossmcp.service.MarketDataService;
import dev.jaydev.tossmcp.tools.MarketDataTools;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class CacheWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(TossProperties.class, () -> new TossProperties("https://example.test", "id", "secret", "acct"))
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
}
```

Note: `RedisL2Cache` is listed in `withUserConfiguration` but its `@ConditionalOnProperty(havingValue="true")` keeps it out of the context unless the flag is `true`; with the flag absent/false only `NoOpL2Cache` is created, so `L2Cache` stays a single bean. (Enabling it requires a `StringRedisTemplate`, which this lightweight runner does not provide — hence we assert the disabled path here and cover the enabled path in `RedisL2CacheIT`.)

- [ ] **Step 2: Run the wiring test**

Run: `./gradlew test --tests "dev.jaydev.tossmcp.cache.CacheWiringTest"`
Expected: PASS (2 tests).

- [ ] **Step 3: Update the README**

In `README.md`, change the Roadmap table row for Phase 2 from its current "not done" state to done, and adjust the split:

```markdown
| 2 ✅ | Two-tier cache (Caffeine L1 + Redis L2) + per-node single-flight coalescing in front of the rate-limited upstream — **done** |
| 2.5 | HTTP (Streamable) transport (WebMVC) alongside stdio — enables load testing |
| 3 | Load testing (k6) + observability (Micrometer / Prometheus / Grafana) with published throughput & latency numbers |
| 4 | Account & order tools behind explicit opt-in safety gates (dry-run → confirm) |
```

Then add a new `## Caching` section immediately before the `## Contributing` section:

```markdown
## Caching

Read-only market-data calls pass through a two-tier cache so bursts of identical
requests collapse to at most one upstream call:

- **L1 — Caffeine near-cache** (in-process, 2s): `get(key, loader)` is atomic per
  key, so concurrent identical requests on a node are single-flighted to one load.
- **L2 — Redis shared cache** (opt-in): per-data-type TTLs — quotes/orderbook 2s,
  trades 3s, intraday candles 10s, daily candles 1h, stock info 6h. Any Redis
  error degrades to a cache miss; it never breaks a tool call.

Cache keys normalize comma-separated symbols (trim + sort), so `005930,000660`
and `000660,005930` share one entry.

Enable L2 with env vars:

​```bash
export TOSS_CACHE_L2=true
export REDIS_HOST=localhost   # default
export REDIS_PORT=6379        # default
​```

Scope note: L1 single-flight is per-node. Cross-node request coalescing is not
implemented; the shared L2 narrows (but does not eliminate) the concurrent-miss
window when running multiple instances.
```

(Remove the zero-width `​` characters shown around the inner code fence — they are only here to keep this plan's own fencing intact. Use a normal triple-backtick `bash` block in the README.)

- [ ] **Step 4: Commit**

```bash
git add src/test/java/dev/jaydev/tossmcp/cache/CacheWiringTest.java README.md
git commit -m "test(cache): verify DI wiring; docs: mark Phase 2 done and document cache design"
```

---

## Notes for the executor

- Run everything from the repo root `/home/ubuntu/projects/toss-invest-mcp`.
- `./gradlew test` is the full suite; per-task commands filter with `--tests`.
- The Redis IT (`RedisL2CacheIT`) needs Docker. Without Docker it self-skips (`disabledWithoutDocker = true`) — a skip is not a failure.
- Do not touch `TossMcpApplication`, `TossAuthService`, or `TossProperties` signatures.
- Commit messages: plain, no AI attribution (public OSS repo, author = Jinkyu Lee).
