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
