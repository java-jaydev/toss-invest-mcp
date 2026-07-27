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
        // 인자 순서 고정: quantity 와 orderType 은 둘 다 String 이라 뒤바뀌어도 컴파일·기존 단언이 통과한다.
        // 실계좌로 나가는 유일한 쓰기 경로이므로 위치까지 정확히 전달됐는지 직접 검증한다.
        verify(trading).placeOrder(eq("005930"), eq("BUY"), eq("1"), eq("LIMIT"), eq("50000"), isNull());
    }

    @Test
    void readToolsDelegateStraightToTheService() {
        when(trading.holdings(isNull())).thenReturn("{\"result\":{}}");
        when(trading.buyingPower(eq("KRW"))).thenReturn("{\"result\":{}}");

        String holdings = tools.getHoldings(null);
        String buyingPower = tools.getBuyingPower("KRW");

        assertThat(holdings).isEqualTo("{\"result\":{}}");
        assertThat(buyingPower).isEqualTo("{\"result\":{}}");
        verify(trading).holdings(null);
        verify(trading).buyingPower("KRW");
    }

    @Test
    void getOpenOrdersDelegatesStraightToTheService() {
        when(trading.openOrders(eq("005930"), eq(10))).thenReturn("{\"result\":[]}");

        String openOrders = tools.getOpenOrders("005930", 10);

        assertThat(openOrders).isEqualTo("{\"result\":[]}");
        verify(trading).openOrders("005930", 10);
    }
}
