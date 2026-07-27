package dev.jaydev.tossmcp.tools;

import dev.jaydev.tossmcp.service.TradingService;
import dev.jaydev.tossmcp.trading.OrderResult;
import dev.jaydev.tossmcp.trading.OrderResult.Status;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradingToolsTest {

    private final TradingService trading = mock(TradingService.class);
    private final TradingTools tools = new TradingTools(trading);

    @Test
    void allFiveToolsAreRegistered() {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build()
                .getToolCallbacks();

        assertThat(callbacks).extracting(callback -> callback.getToolDefinition().name())
                .containsExactlyInAnyOrder(
                        "placeOrder", "cancelOrder", "getOpenOrders", "getHoldings", "getBuyingPower");
    }

    @Test
    void placeOrderSerializesTheResultAsJson() {
        when(trading.placeOrder(any(), any(), any(), any(), any(), any()))
                .thenReturn(new OrderResult(Status.DRY_RUN, "미리보기입니다.", null, List.of("검사 통과"), null, null));

        String json = tools.placeOrder("005930", "BUY", "1", "LIMIT", "50000", null);

        assertThat(json).contains("\"status\":\"DRY_RUN\"");
        assertThat(json).contains("미리보기입니다.");
    }

    @Test
    void readToolsDelegateStraightToTheService() {
        when(trading.holdings(isNull())).thenReturn("{\"result\":{}}");
        when(trading.buyingPower(eq("KRW"))).thenReturn("{\"result\":{}}");

        tools.getHoldings(null);
        tools.getBuyingPower("KRW");

        verify(trading).holdings(null);
        verify(trading).buyingPower("KRW");
    }
}
