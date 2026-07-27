package dev.jaydev.tossmcp.client;

import dev.jaydev.tossmcp.auth.TossAuthService;
import dev.jaydev.tossmcp.config.TossProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 토스 Open API 호출 래퍼. 모든 요청에 Bearer 토큰을 붙인다.
 * MVP 단계에서는 응답을 원본 JSON 문자열로 반환한다(LLM 이 직접 읽음).
 *
 * 시세(Market Data) read-only 엔드포인트와 계좌에 영향을 주는 주문/잔고 엔드포인트를 다룬다.
 * 후자는 {@value #ACCOUNT_HEADER} 헤더로 계좌를 명시해야 한다. 파라미터는 공식 OpenAPI 스펙
 * (openapi.tossinvest.com/openapi-docs/latest/openapi.json)으로 검증했다.
 */
@Component
public class TossApiClient {

    /** 계좌에 영향을 주는 요청에 반드시 실어야 하는 헤더. */
    private static final String ACCOUNT_HEADER = "X-Tossinvest-Account";

    private final RestClient http;
    private final TossAuthService auth;
    private final String account;

    public TossApiClient(TossProperties props, TossAuthService auth) {
        this.http = RestClient.create(props.baseUrl());
        this.auth = auth;
        this.account = props.account();
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

    /** POST /api/v1/orders — 주문 생성. 재시도하지 않는다(이중 주문 방지). */
    public String placeOrder(Map<String, Object> body) {
        return http.post()
                .uri("/api/v1/orders")
                .header("Authorization", "Bearer " + auth.accessToken())
                .header(ACCOUNT_HEADER, requireAccount())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
    }

    /**
     * POST /api/v1/orders/{orderId}/cancel — 주문 취소.
     * 응답의 orderId 는 취소로 새로 발급된 식별자라 원주문 번호와 다르다.
     */
    public String cancelOrder(String orderId) {
        return http.post()
                .uri(uriBuilder -> uriBuilder.path("/api/v1/orders/{orderId}/cancel").build(orderId))
                .header("Authorization", "Bearer " + auth.accessToken())
                .header(ACCOUNT_HEADER, requireAccount())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of())
                .retrieve()
                .body(String.class);
    }

    /** GET /api/v1/orders — 주문 목록. status 는 OPEN 또는 CLOSED 이며 필수다. */
    public String getOrders(String status, String symbol, Integer limit) {
        return accountGet("/api/v1/orders", uri -> {
            uri.queryParam("status", status);
            if (symbol != null) uri.queryParam("symbol", symbol);
            if (limit != null) uri.queryParam("limit", limit);
        });
    }

    /** GET /api/v1/holdings — 보유 주식. symbol 을 주면 해당 종목만. */
    public String getHoldings(String symbol) {
        return accountGet("/api/v1/holdings", uri -> {
            if (symbol != null) uri.queryParam("symbol", symbol);
        });
    }

    /** GET /api/v1/buying-power — 매수 가능 금액. currency 는 필수다. */
    public String getBuyingPower(String currency) {
        return accountGet("/api/v1/buying-power", uri -> uri.queryParam("currency", currency));
    }

    /** 계좌 헤더가 필요한 GET 공통 처리. */
    private String accountGet(String path, Consumer<UriBuilder> query) {
        return http.get()
                .uri(uriBuilder -> {
                    uriBuilder.path(path);
                    query.accept(uriBuilder);
                    return uriBuilder.build();
                })
                .header("Authorization", "Bearer " + auth.accessToken())
                .header(ACCOUNT_HEADER, requireAccount())
                .retrieve()
                .body(String.class);
    }

    /** 계좌가 없으면 조용히 실패하지 않고 무엇을 설정해야 하는지 알려준다. */
    private String requireAccount() {
        if (account == null || account.isBlank()) {
            throw new IllegalStateException(
                    "계좌가 설정되지 않았습니다. 환경변수 TOSS_ACCOUNT 에 계좌 일련번호를 넣어주세요.");
        }
        return account;
    }
}
