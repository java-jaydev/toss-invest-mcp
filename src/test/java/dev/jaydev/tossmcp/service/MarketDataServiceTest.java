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
