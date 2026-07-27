package dev.jaydev.tossmcp.service;

import dev.jaydev.tossmcp.client.TossApiClient;
import dev.jaydev.tossmcp.config.TossTradingProperties;
import dev.jaydev.tossmcp.trading.DailyOrderCounter;
import dev.jaydev.tossmcp.trading.OrderGuard;
import dev.jaydev.tossmcp.trading.OrderRateLimiter;
import dev.jaydev.tossmcp.trading.OrderResult;
import dev.jaydev.tossmcp.trading.OrderResult.Status;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradingServiceTest {

    // 한국시간 14:00 — 개장 직후 혼잡 구간이 아니라 레이트리밋 여유가 있다.
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-07-27T05:00:00Z"), ZoneOffset.UTC);

    private final TossApiClient api = mock(TossApiClient.class);
    private final MarketDataService market = mock(MarketDataService.class);

    private TradingService service(boolean tradingEnabled) {
        TossTradingProperties props = new TossTradingProperties(
                tradingEnabled, new BigDecimal("100000"), new BigDecimal("100"), 20, List.of());
        return new TradingService(api, market, new OrderGuard(props),
                new DailyOrderCounter(FIXED), new OrderRateLimiter(FIXED));
    }

    @Test
    void disabledTradingNeverSendsTheOrder() {
        OrderResult result = service(false)
                .placeOrder("005930", "BUY", "1", "LIMIT", "50000", true);

        assertThat(result.status()).isEqualTo(Status.DRY_RUN);
        verify(api, never()).placeOrder(any());
    }

    @Test
    void enabledWithoutExecuteIsPreviewOnly() {
        OrderResult result = service(true)
                .placeOrder("005930", "BUY", "1", "LIMIT", "50000", null);

        assertThat(result.status()).isEqualTo(Status.DRY_RUN);
        assertThat(result.wouldPlace().estimatedNotional()).isEqualTo("50000");
        verify(api, never()).placeOrder(any());
    }

    @Test
    void enabledWithExecuteSendsIdempotencyKeyAndNoHighValueConfirm() {
        when(api.placeOrder(any())).thenReturn("{\"result\":{\"orderId\":\"order-1\"}}");

        OrderResult result = service(true)
                .placeOrder("005930", "BUY", "1", "LIMIT", "50000", true);

        assertThat(result.status()).isEqualTo(Status.PLACED);
        assertThat(result.orderId()).isEqualTo("order-1");
        assertThat(result.clientOrderId()).isNotBlank();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(api).placeOrder(body.capture());
        assertThat(body.getValue()).containsEntry("symbol", "005930");
        assertThat(body.getValue()).containsEntry("side", "BUY");
        assertThat(body.getValue()).containsEntry("orderType", "LIMIT");
        assertThat(body.getValue()).containsEntry("price", "50000");
        assertThat(body.getValue()).containsKey("clientOrderId");
        // 1억원 이상 주문에 대한 토스 자체 방어막을 우리가 켜면 안 된다.
        assertThat(body.getValue()).doesNotContainKey("confirmHighValueOrder");
    }

    @Test
    void marketOrderEstimatesNotionalFromLastPrice() {
        when(market.prices("005930")).thenReturn("{\"result\":[{\"lastPrice\":\"60000\"}]}");

        OrderResult result = service(true)
                .placeOrder("005930", "BUY", "1", "MARKET", null, false);

        assertThat(result.status()).isEqualTo(Status.DRY_RUN);
        assertThat(result.wouldPlace().estimatedNotional()).isEqualTo("60000");
    }

    @Test
    void marketOrderIsRejectedWhenPriceLookupFails() {
        when(market.prices("005930")).thenThrow(new RuntimeException("upstream down"));

        OrderResult result = service(true)
                .placeOrder("005930", "BUY", "1", "MARKET", null, true);

        assertThat(result.status()).isEqualTo(Status.REJECTED);
        verify(api, never()).placeOrder(any());
    }

    @Test
    void limitOrderWithoutPriceIsRejected() {
        OrderResult result = service(true)
                .placeOrder("005930", "BUY", "1", "LIMIT", null, true);

        assertThat(result.status()).isEqualTo(Status.REJECTED);
        assertThat(result.reason()).contains("price");
        verify(api, never()).placeOrder(any());
    }

    @Test
    void marketOrderWithPriceIsRejected() {
        OrderResult result = service(true)
                .placeOrder("005930", "BUY", "1", "MARKET", "50000", true);

        assertThat(result.status()).isEqualTo(Status.REJECTED);
        verify(api, never()).placeOrder(any());
    }

    @Test
    void timeoutReturnsUnknownAndDoesNotRetry() {
        when(api.placeOrder(any()))
                .thenThrow(new ResourceAccessException("timeout", new SocketTimeoutException()));

        OrderResult result = service(true)
                .placeOrder("005930", "BUY", "1", "LIMIT", "50000", true);

        assertThat(result.status()).isEqualTo(Status.UNKNOWN);
        assertThat(result.clientOrderId()).isNotBlank();
        assertThat(result.reason()).contains("clientOrderId");
        verify(api, times(1)).placeOrder(any());
    }

    @Test
    void cancelIsPreviewedWhenTradingDisabled() {
        OrderResult result = service(false).cancelOrder("abc-123", true);

        assertThat(result.status()).isEqualTo(Status.DRY_RUN);
        verify(api, never()).cancelOrder(anyString());
    }

    @Test
    void cancelIsSentWhenEnabledAndExecuted() {
        when(api.cancelOrder("abc-123")).thenReturn("{\"result\":{\"orderId\":\"cancel-1\"}}");

        OrderResult result = service(true).cancelOrder("abc-123", true);

        assertThat(result.status()).isEqualTo(Status.PLACED);
        verify(api).cancelOrder("abc-123");
    }

    @Test
    void accountStateReadsAreNotCached() {
        // 주문 직후 상태가 바뀌므로 매 호출이 그대로 상위로 나가야 한다.
        when(api.getHoldings(null)).thenReturn("{}");
        TradingService service = service(true);

        service.holdings(null);
        service.holdings(null);

        verify(api, times(2)).getHoldings(null);
    }

    @Test
    void openOrdersAsksForOpenStatus() {
        when(api.getOrders("OPEN", null, null)).thenReturn("{}");

        service(true).openOrders(null, null);

        verify(api).getOrders("OPEN", null, null);
    }
}
