package dev.jaydev.tossmcp.client;

import dev.jaydev.tossmcp.auth.TossAuthService;
import dev.jaydev.tossmcp.config.TossProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import java.util.function.Consumer;

/**
 * 토스 Open API 호출 래퍼. 모든 요청에 Bearer 토큰을 붙인다.
 * MVP 단계에서는 응답을 원본 JSON 문자열로 반환한다(LLM 이 직접 읽음).
 *
 * 시세(Market Data) read-only 엔드포인트만 다룬다. 파라미터는 공식 OpenAPI 스펙
 * (openapi.tossinvest.com/openapi-docs/latest/openapi.json)으로 검증했다.
 */
@Component
public class TossApiClient {

    private final RestClient http;
    private final TossAuthService auth;

    public TossApiClient(TossProperties props, TossAuthService auth) {
        this.http = RestClient.create(props.baseUrl());
        this.auth = auth;
    }

    /** GET /api/v1/prices — 현재가. symbols 는 콤마 구분(최대 200종목). */
    public String getPrices(String symbols) {
        return get("/api/v1/prices", uri -> uri.queryParam("symbols", symbols));
    }

    /** GET /api/v1/orderbook — 호가(매수/매도 잔량). 단일 종목. */
    public String getOrderbook(String symbol) {
        return get("/api/v1/orderbook", uri -> uri.queryParam("symbol", symbol));
    }

    /** GET /api/v1/trades — 최근 체결 내역. count 기본 50·최대 50. */
    public String getTrades(String symbol, Integer count) {
        return get("/api/v1/trades", uri -> {
            uri.queryParam("symbol", symbol);
            if (count != null) uri.queryParam("count", count);
        });
    }

    /** GET /api/v1/candles — 캔들(시고저종). interval 은 "1m" 또는 "1d". */
    public String getCandles(String symbol, String interval, Integer count, String before, Boolean adjusted) {
        return get("/api/v1/candles", uri -> {
            uri.queryParam("symbol", symbol);
            uri.queryParam("interval", interval);
            if (count != null) uri.queryParam("count", count);
            if (before != null) uri.queryParam("before", before);
            if (adjusted != null) uri.queryParam("adjusted", adjusted);
        });
    }

    /** GET /api/v1/stocks — 종목 기본 정보(이름·시장·종류 등). symbols 콤마 구분(최대 200). */
    public String getStocks(String symbols) {
        return get("/api/v1/stocks", uri -> uri.queryParam("symbols", symbols));
    }

    /** 공통 GET: path + 쿼리 빌더 + Bearer 토큰 → 원본 JSON 문자열. */
    private String get(String path, Consumer<UriBuilder> query) {
        return http.get()
                .uri(uriBuilder -> {
                    uriBuilder.path(path);
                    query.accept(uriBuilder);
                    return uriBuilder.build();
                })
                .header("Authorization", "Bearer " + auth.accessToken())
                .retrieve()
                .body(String.class);
    }
}
