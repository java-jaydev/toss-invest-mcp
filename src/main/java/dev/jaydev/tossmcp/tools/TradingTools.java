package dev.jaydev.tossmcp.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jaydev.tossmcp.service.TradingService;
import dev.jaydev.tossmcp.trading.OrderResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 주문·계좌 MCP 도구. 판단은 담지 않고 TradingService 에 위임한다.
 *
 * 주문 도구는 기본이 미리보기다. 실제로 전송하려면 (1) 서버 설정에서 실매매가 켜져 있고
 * (2) execute=true 를 넘겨야 하며 (3) 설정된 금액·횟수 한도를 넘지 않아야 한다.
 */
@Component
public class TradingTools {

    private final TradingService trading;
    private final ObjectMapper json = new ObjectMapper();

    public TradingTools(TradingService trading) {
        this.trading = trading;
    }

    @Tool(description = "주식 주문을 낸다. 기본은 미리보기(dry-run)이며, 실제로 전송하려면 execute=true 가 필요하고 서버에서 실매매가 켜져 있어야 한다. 응답의 status 로 DRY_RUN·PLACED·REJECTED·UNKNOWN 을 구분한다.")
    public String placeOrder(
            @ToolParam(description = "종목 코드. 국내는 6자리 숫자(예: 005930), 미국은 티커(예: AAPL)") String symbol,
            @ToolParam(description = "매매 방향: BUY(매수) 또는 SELL(매도)") String side,
            @ToolParam(description = "주문 수량") String quantity,
            @ToolParam(description = "호가 유형: LIMIT(지정가) 또는 MARKET(시장가)") String orderType,
            @ToolParam(description = "주문 가격. LIMIT 일 때만 넣는다", required = false) String price,
            @ToolParam(description = "true 여야 실제로 전송한다. 기본은 false(미리보기)", required = false) Boolean execute) {
        return toJson(trading.placeOrder(symbol, side, quantity, orderType, price, execute));
    }

    @Tool(description = "접수된 주문을 취소한다. 기본은 미리보기이며 실제로 취소하려면 execute=true 가 필요하다.")
    public String cancelOrder(
            @ToolParam(description = "취소할 주문의 식별자") String orderId,
            @ToolParam(description = "true 여야 실제로 취소한다. 기본은 false(미리보기)", required = false) Boolean execute) {
        return toJson(trading.cancelOrder(orderId, execute));
    }

    @Tool(description = "아직 체결되지 않은 주문 목록을 조회한다.")
    public String getOpenOrders(
            @ToolParam(description = "특정 종목만 볼 때의 종목 코드", required = false) String symbol,
            @ToolParam(description = "조회 건수(최대 100, 기본 20)", required = false) Integer limit) {
        return trading.openOrders(symbol, limit);
    }

    @Tool(description = "계좌의 보유 주식과 평가 손익을 조회한다.")
    public String getHoldings(
            @ToolParam(description = "특정 종목만 볼 때의 종목 코드", required = false) String symbol) {
        return trading.holdings(symbol);
    }

    @Tool(description = "매수에 쓸 수 있는 금액을 조회한다. 통화별로 따로 관리된다.")
    public String getBuyingPower(
            @ToolParam(description = "통화: KRW 또는 USD") String currency) {
        return trading.buyingPower(currency);
    }

    private String toJson(OrderResult result) {
        try {
            return json.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            // 응답을 직렬화하지 못하면 무슨 일이 있었는지라도 알려야 한다.
            return "{\"status\":\"UNKNOWN\",\"reason\":\"응답을 직렬화하지 못했습니다.\"}";
        }
    }
}
